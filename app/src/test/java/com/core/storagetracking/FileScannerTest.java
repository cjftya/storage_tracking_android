package com.core.storagetracking;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.common.SizeConverter;
import com.core.storagetracking.scan.file.FileMatch;
import com.core.storagetracking.scan.file.FileResultNode;
import com.core.storagetracking.scan.file.FileResultStore;
import com.core.storagetracking.scan.file.FileScanConfig;
import com.core.storagetracking.scan.file.FileScanSummary;
import com.core.storagetracking.scan.file.FileScanner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileScannerTest {

    private Path tempRoot;

    @Before
    public void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("file_scanner_test_");
    }

    @After
    public void tearDown() throws IOException {
        if (tempRoot != null && Files.exists(tempRoot)) {
            Files.walk(tempRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
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

    @Test
    public void testInclusiveMinimumBoundaryAndMatchedFolderSums() throws IOException {
        long threshold = 1024L * 1024L; // 1 MiB
        createFile("sub1/below.bin", threshold - 1L);
        createFile("sub1/exact.bin", threshold);
        createFile("sub2/nested/above.bin", threshold + 500L);
        createFile("unmatched_dir/small.bin", 512L);

        FileScanConfig config = new FileScanConfig(
                Collections.singletonList(tempRoot.toString()),
                threshold);
        FileResultStore store = new FileResultStore(config.getRoots());
        FileScanSummary summary = FileScanner.scan(config, store::addBatch, p -> {}, () -> false);

        assertEquals(4L, summary.getFilesScanned());
        assertEquals(2L, summary.getFilesMatched());
        assertEquals(2, store.getMatchCount());

        String rootKey = PathPolicy.pathKey(tempRoot.toString());
        FileResultNode rootNode = store.node(rootKey);
        assertNotNull(rootNode);
        // Folder size in File Scan mode MUST equal matched files sum only (never includes below.bin or small.bin)
        assertEquals(threshold + (threshold + 500L), rootNode.getSizeBytes());

        // Unmatched directory must not exist in FileResultStore
        assertNull(store.node(PathPolicy.pathKey(tempRoot.resolve("unmatched_dir").toString())));
    }

    @Test
    public void testLinksSkippedAndExclusionsHonored() throws IOException {
        createFile("keep/match.bin", 2048L);
        Path excludedSub = createFile("ex/match.bin", 4096L).getParent();
        Path linkedSub = createFile("linked_dir/match.bin", 8192L).getParent();
        String linkedKey = PathPolicy.pathKey(linkedSub.toString());

        FileScanner.DirectoryEntryProvider provider = new FileScanner.DirectoryEntryProvider() {
            @Override
            public BasicFileAttributes inspectRoot(Path root) throws IOException {
                return PathPolicy.inspectRoot(root);
            }

            @Override
            public java.nio.file.DirectoryStream<Path> openDirectory(Path dir) throws IOException {
                return Files.newDirectoryStream(dir);
            }

            @Override
            public BasicFileAttributes readAttributes(Path entry) throws IOException {
                return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }

            @Override
            public boolean isLinkOrReparse(Path entry, BasicFileAttributes attrs) {
                return PathPolicy.pathKey(entry.toString()).equals(linkedKey);
            }
        };

        FileScanConfig config = new FileScanConfig(
                Collections.singletonList(tempRoot.toString()),
                1024L,
                Collections.singletonList(excludedSub.toString()),
                Collections.emptyList());
        List<FileMatch> matches = new ArrayList<>();
        FileScanSummary summary = FileScanner.scan(config, matches::addAll, p -> {}, () -> false, provider);

        assertEquals(1L, summary.getFilesMatched());
        assertEquals(1, matches.size());
        assertEquals(2048L, matches.get(0).getSizeBytes());
    }

    @Test
    public void testSizeConverterExactBytesAndValidation() {
        assertEquals(0L, SizeConverter.mibToBytes("0"));
        assertEquals(104_857_600L, SizeConverter.mibToBytes("100"));
        assertEquals(524_288L, SizeConverter.mibToBytes("0.5"));
        assertEquals("100", SizeConverter.bytesToMib(104_857_600L));
        assertEquals("0.5", SizeConverter.bytesToMib(524_288L));

        for (String invalid : Arrays.asList("", "  ", "-1", "abc", "0.1")) {
            try {
                SizeConverter.mibToBytes(invalid);
                throw new AssertionError("Expected IllegalArgumentException for: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("nonnegative MiB"));
            }
        }
    }
}
