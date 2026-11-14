package com.core.storagetracking;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.file.FileMatch;
import com.core.storagetracking.scan.file.FileScanConfig;
import com.core.storagetracking.scan.file.FileScanner;
import com.core.storagetracking.scan.folder.DirectoryDelta;
import com.core.storagetracking.scan.folder.FolderConfig;
import com.core.storagetracking.scan.folder.FolderNode;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.scan.folder.FolderScanner;
import com.core.storagetracking.scan.folder.FolderSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FolderScannerTest {

    private Path tempRoot;

    @Before
    public void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("folder_scanner_test_");
    }

    @After
    public void tearDown() throws IOException {
        if (tempRoot != null && Files.exists(tempRoot)) {
            deleteRecursively(tempRoot);
        }
    }

    private Path createFile(String relativePath, long logicalSizeBytes) throws IOException {
        Path file = tempRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(logicalSizeBytes);
        }
        return file;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
    }

    @Test
    public void testAllSizesDirectAndAncestorTotalsOnce() throws IOException {
        long mib = 1024L * 1024L;
        createFile("root_file.bin", 3L);
        createFile("한글 space !/a.bin", mib);
        createFile("한글 space !/nested/b.bin", mib);
        createFile("large/big.bin", 100L * mib);
        createFile("zero.bin", 0L);
        Files.createDirectories(tempRoot.resolve("empty_dir"));

        FolderConfig config = new FolderConfig(Collections.singletonList(tempRoot.toString()));
        FolderResultStore store = new FolderResultStore(config.getRoots());
        FolderSummary summary = FolderScanner.scan(config, store::addBatch, p -> {}, () -> false);
        store.finish(!summary.isCancelled() && summary.getErrors() == 0L);

        String rootKey = PathPolicy.pathKey(tempRoot.toString());
        FolderNode rootNode = store.node(rootKey);
        assertNotNull(rootNode);
        assertEquals(3L, rootNode.getDirectBytes());
        assertEquals(102L * mib + 3L, rootNode.getSizeBytes());
        assertEquals(rootNode.getSizeBytes(), summary.getBytesScanned());
        assertEquals(4, store.getFolderCount());
        assertEquals(FolderResultStore.STATE_COMPLETE, store.getState());

        // Compare with FileScanner (default 100 MiB threshold)
        List<FileMatch> matches = new ArrayList<>();
        FileScanner.scan(
                new FileScanConfig(Collections.singletonList(tempRoot.toString())),
                matches::addAll,
                p -> {},
                () -> false);
        assertEquals(1, matches.size());
        assertEquals(100L * mib, matches.get(0).getSizeBytes());
    }

    @Test
    public void testExclusionsPathBoundaryAndNestedBoundaries() throws IOException {
        createFile("A/excluded_file.bin", 9L);
        createFile("AB/kept_prefix_sibling.bin", 15L);
        createFile("keep/a.bin", 7L);
        Path boundaryDir = createFile("mount/a.bin", 11L).getParent();
        Path hiddenFile = createFile("hidden.bin", 13L);

        Path excludedDir = tempRoot.resolve("A");
        FolderConfig config = new FolderConfig(
                Collections.singletonList(tempRoot.toString()),
                Arrays.asList(excludedDir.toString(), hiddenFile.toString()),
                Collections.singletonList(boundaryDir.toString()));
        FolderResultStore store = new FolderResultStore(config.getRoots());
        FolderSummary summary = FolderScanner.scan(config, store::addBatch, p -> {}, () -> false);
        store.finish(true);

        // "AB" must NOT be excluded when "A" is excluded; "keep" (7) + "AB" (15) = 22 bytes
        assertEquals(22L, summary.getBytesScanned());
        assertNull(store.node(PathPolicy.pathKey(excludedDir.toString())));
        assertNotNull(store.node(PathPolicy.pathKey(tempRoot.resolve("AB").toString())));

        // Excluding the root itself results in 0 scanned files and a 0B root node
        FolderConfig excludedRootCfg = new FolderConfig(
                Collections.singletonList(tempRoot.toString()),
                Collections.singletonList(tempRoot.toString()),
                Collections.emptyList());
        FolderResultStore rootStore = new FolderResultStore(excludedRootCfg.getRoots());
        FolderSummary rootSummary = FolderScanner.scan(excludedRootCfg, rootStore::addBatch, p -> {}, () -> false);
        assertEquals(0L, rootSummary.getFilesScanned());
        assertEquals(0L, rootStore.children(null).get(0).getSizeBytes());
    }

    @Test
    public void testManyFilesStoreOnlyFoldersAndBoundedBatches() throws IOException {
        for (int i = 0; i < 1200; i++) {
            createFile("data/file_" + i + ".bin", 1L);
        }
        FolderConfig config = new FolderConfig(Collections.singletonList(tempRoot.toString()));
        FolderResultStore store = new FolderResultStore(config.getRoots());
        List<Integer> batchSizes = new ArrayList<>();

        FolderSummary summary = FolderScanner.scan(config, deltas -> {
            batchSizes.add(deltas.size());
            for (DirectoryDelta d : deltas) {
                assertTrue(new File(d.getPath()).isDirectory());
            }
            store.addBatch(deltas);
        }, p -> {}, () -> false);

        assertEquals(1200L, summary.getBytesScanned());
        assertEquals(2, store.getFolderCount());
        for (int size : batchSizes) {
            assertTrue(size <= 512);
        }
    }

    @Test
    public void testCancelFlushesPartialAndRestartIsFresh() throws IOException {
        for (int i = 0; i < 300; i++) {
            createFile("f_" + i + ".bin", 1L);
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        FolderConfig config = new FolderConfig(
                Collections.singletonList(tempRoot.toString()),
                Collections.emptyList(),
                Collections.emptyList(),
                512,
                0.25,
                1e-9,
                32);
        FolderResultStore partialStore = new FolderResultStore(config.getRoots());
        FolderSummary cancelledSummary = FolderScanner.scan(
                config,
                partialStore::addBatch,
                progress -> {
                    if (progress.getFilesScanned() > 0L) {
                        cancel.set(true);
                    }
                },
                cancel::get);
        partialStore.finish(false);

        assertTrue(cancelledSummary.isCancelled());
        assertEquals(FolderResultStore.STATE_PARTIAL, partialStore.getState());
        assertEquals(cancelledSummary.getBytesScanned(), partialStore.children(null).get(0).getSizeBytes());

        // Fresh restart scans all 300 bytes cleanly
        FolderResultStore freshStore = new FolderResultStore(config.getRoots());
        FolderSummary fullSummary = FolderScanner.scan(config, freshStore::addBatch, p -> {}, () -> false);
        freshStore.finish(!fullSummary.isCancelled() && fullSummary.getErrors() == 0L);
        assertEquals(300L, fullSummary.getBytesScanned());
        assertEquals(FolderResultStore.STATE_COMPLETE, freshStore.getState());
    }

    @Test
    public void testDeepIterativeWalkDuplicateAndNestedRoots() throws IOException {
        StringBuilder deepRel = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            if (i > 0) deepRel.append('/');
            deepRel.append("d");
        }
        deepRel.append("/leaf.bin");
        createFile(deepRel.toString(), 17L);

        Path child = tempRoot.resolve("d");
        List<String> roots = Arrays.asList(tempRoot.toString(), child.toString(), tempRoot.toString());
        FolderConfig config = new FolderConfig(roots);
        FolderResultStore store = new FolderResultStore(config.getRoots());
        FolderSummary summary = FolderScanner.scan(config, store::addBatch, p -> {}, () -> false);
        store.finish(true);

        assertEquals(17L, summary.getBytesScanned());
        long rootSum = 0L;
        for (FolderNode n : store.children(null)) {
            rootSum += n.getSizeBytes();
        }
        assertEquals(17L, rootSum);
    }

    @Test
    public void testPermissionAndVanishedEntryErrorsKeepPartialTotals() throws IOException {
        createFile("good/a.bin", 5L);
        Path badDir = tempRoot.resolve("bad");
        Files.createDirectories(badDir);
        String badKey = PathPolicy.pathKey(badDir.toString());

        FolderScanner.DirectoryEntryProvider provider = new FolderScanner.DirectoryEntryProvider() {
            @Override
            public BasicFileAttributes inspectRoot(Path root) throws IOException {
                return PathPolicy.inspectRoot(root);
            }

            @Override
            public DirectoryStream<Path> openDirectory(Path dir) throws IOException {
                if (PathPolicy.pathKey(dir.toString()).equals(badKey)) {
                    throw new AccessDeniedException(dir.toString());
                }
                DirectoryStream<Path> real = Files.newDirectoryStream(dir);
                if (PathPolicy.pathKey(dir.toString()).equals(PathPolicy.pathKey(tempRoot.toString()))) {
                    return new DirectoryStream<Path>() {
                        @Override
                        public Iterator<Path> iterator() {
                            List<Path> items = new ArrayList<>();
                            items.add(tempRoot.resolve("vanished_during_scan.bin"));
                            for (Path p : real) {
                                items.add(p);
                            }
                            return items.iterator();
                        }

                        @Override
                        public void close() throws IOException {
                            real.close();
                        }
                    };
                }
                return real;
            }

            @Override
            public BasicFileAttributes readAttributes(Path entry) throws IOException {
                if (entry.getFileName().toString().equals("vanished_during_scan.bin")) {
                    throw new NoSuchFileException(entry.toString());
                }
                return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }

            @Override
            public boolean isLinkOrReparse(Path entry, BasicFileAttributes attrs) {
                return PathPolicy.isLinkOrReparseDirectory(entry, attrs);
            }
        };

        FolderConfig config = new FolderConfig(Collections.singletonList(tempRoot.toString()));
        FolderResultStore store = new FolderResultStore(config.getRoots());
        FolderSummary summary = FolderScanner.scan(config, store::addBatch, p -> {}, () -> false, provider);
        store.finish(!summary.isCancelled() && summary.getErrors() == 0L);

        assertEquals(2L, summary.getErrors());
        assertEquals(5L, summary.getBytesScanned());
        assertEquals(FolderResultStore.STATE_PARTIAL, store.getState());
    }

    @Test
    public void testFileRootReportsErrorInsteadOfCompletedZeroTotal() throws IOException {
        Path file = createFile("single_file.bin", 10L);
        FolderConfig config = new FolderConfig(Collections.singletonList(file.toString()));
        FolderSummary summary = FolderScanner.scan(config, deltas -> {}, p -> {}, () -> false);
        assertEquals(1L, summary.getErrors());
        assertEquals(0L, summary.getBytesScanned());
    }

    @Test
    public void testSortedPagesOnlyAfterFinalization() {
        FolderResultStore store = new FolderResultStore(Collections.singletonList(tempRoot.toString()));
        for (int i = 0; i < 450; i++) {
            Path sub = tempRoot.resolve(String.valueOf(i));
            store.addBatch(Collections.singletonList(new DirectoryDelta(sub.toString(), i + 1L)));
        }
        String rootKey = PathPolicy.pathKey(tempRoot.toString());
        assertEquals(1L, store.children(rootKey).get(0).getSizeBytes());

        store.finish(true);
        List<FolderNode> secondPage = store.children(rootKey, 200, 200);
        assertEquals(200, secondPage.size());
        assertEquals(250L, secondPage.get(0).getSizeBytes());
        assertEquals(450L * 451L / 2L, store.node(rootKey).getSizeBytes());
    }
}
