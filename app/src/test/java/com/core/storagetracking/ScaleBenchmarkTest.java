package com.core.storagetracking;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.file.FileResultStore;
import com.core.storagetracking.scan.file.FileScanConfig;
import com.core.storagetracking.scan.file.FileScanSummary;
import com.core.storagetracking.scan.file.FileScanner;
import com.core.storagetracking.scan.folder.FolderConfig;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.scan.folder.FolderScanner;
import com.core.storagetracking.scan.folder.FolderSummary;
import com.core.storagetracking.ui.FileTreeState;
import com.core.storagetracking.ui.FolderTreeState;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies accuracy, bounded object retention, and performance across 1,000 / 10,000 / 100,000 files.
 */
public class ScaleBenchmarkTest {

    private static final class SyntheticAttrs implements BasicFileAttributes {
        private final boolean directory;
        private final long size;

        SyntheticAttrs(boolean directory, long size) {
            this.directory = directory;
            this.size = size;
        }

        @Override
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(0L);
        }

        @Override
        public FileTime lastAccessTime() {
            return FileTime.fromMillis(0L);
        }

        @Override
        public FileTime creationTime() {
            return FileTime.fromMillis(0L);
        }

        @Override
        public boolean isRegularFile() {
            return !directory;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public Object fileKey() {
            return null;
        }
    }

    private static final class SyntheticFileSystem
            implements FolderScanner.DirectoryEntryProvider, FileScanner.DirectoryEntryProvider {
        private final Map<String, List<Path>> childrenMap = new HashMap<>();
        private final Map<String, SyntheticAttrs> attrsMap = new HashMap<>();

        SyntheticFileSystem(Path root, int folderCount, int totalFiles, long fileSizeBytes, int largeFileEvery) {
            String rootKey = PathPolicy.pathKey(root.toString());
            attrsMap.put(rootKey, new SyntheticAttrs(true, 0L));
            List<Path> rootChildren = new ArrayList<>(folderCount);
            List<Path> folders = new ArrayList<>(folderCount);
            for (int d = 0; d < folderCount; d++) {
                Path sub = root.resolve("dir_" + d);
                String subKey = PathPolicy.pathKey(sub.toString());
                attrsMap.put(subKey, new SyntheticAttrs(true, 0L));
                rootChildren.add(sub);
                folders.add(sub);
                childrenMap.put(subKey, new ArrayList<>());
            }
            childrenMap.put(rootKey, rootChildren);

            for (int f = 0; f < totalFiles; f++) {
                Path parent = folders.get(f % folderCount);
                String parentKey = PathPolicy.pathKey(parent.toString());
                Path file = parent.resolve("f_" + f + ".bin");
                String fileKey = PathPolicy.pathKey(file.toString());
                long size = (largeFileEvery > 0 && f % largeFileEvery == 0)
                        ? 100L * 1024L * 1024L
                        : fileSizeBytes;
                attrsMap.put(fileKey, new SyntheticAttrs(false, size));
                childrenMap.get(parentKey).add(file);
            }
        }

        @Override
        public BasicFileAttributes inspectRoot(Path root) {
            return attrsMap.get(PathPolicy.pathKey(root.toString()));
        }

        @Override
        public DirectoryStream<Path> openDirectory(Path dir) {
            List<Path> list = childrenMap.getOrDefault(
                    PathPolicy.pathKey(dir.toString()), Collections.emptyList());
            return new DirectoryStream<Path>() {
                @Override
                public Iterator<Path> iterator() {
                    return list.iterator();
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public BasicFileAttributes readAttributes(Path entry) {
            return attrsMap.get(PathPolicy.pathKey(entry.toString()));
        }

        @Override
        public boolean isLinkOrReparse(Path entry, BasicFileAttributes attrs) {
            return false;
        }
    }

    @Test
    public void testRealDisk1000FilesFolderAndFileModes() throws IOException {
        Path tempDir = Files.createTempDirectory("real_disk_1k_");
        try {
            int folderCount = 20;
            int totalFiles = 1000;
            for (int d = 0; d < folderCount; d++) {
                Files.createDirectories(tempDir.resolve("dir_" + d));
            }
            long expectedTotalBytes = 0L;
            long threshold = 1024L * 1024L;
            int expectedMatches = 0;
            for (int f = 0; f < totalFiles; f++) {
                Path file = tempDir.resolve("dir_" + (f % folderCount)).resolve("file_" + f + ".bin");
                long size = (f % 100 == 0) ? threshold : 512L;
                Files.write(file, new byte[1]);
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
                    raf.setLength(size);
                }
                expectedTotalBytes += size;
                if (size >= threshold) {
                    expectedMatches++;
                }
            }

            long startNs = System.nanoTime();
            FolderConfig folderCfg = new FolderConfig(Collections.singletonList(tempDir.toString()));
            FolderResultStore folderStore = new FolderResultStore(folderCfg.getRoots());
            FolderSummary folderSum = FolderScanner.scan(folderCfg, folderStore::addBatch, p -> {}, () -> false);
            folderStore.finish(true);
            double folderMs = (System.nanoTime() - startNs) / 1_000_000.0;

            assertEquals(totalFiles, folderSum.getFilesScanned());
            assertEquals(expectedTotalBytes, folderSum.getBytesScanned());
            assertEquals(0L, folderSum.getErrors());
            assertEquals(folderCount + 1, folderStore.getFolderCount());

            long startFileNs = System.nanoTime();
            FileScanConfig fileCfg = new FileScanConfig(Collections.singletonList(tempDir.toString()), threshold);
            FileResultStore fileStore = new FileResultStore(fileCfg.getRoots());
            FileScanSummary fileSum = FileScanner.scan(fileCfg, fileStore::addBatch, p -> {}, () -> false);
            double fileMs = (System.nanoTime() - startFileNs) / 1_000_000.0;

            assertEquals(totalFiles, fileSum.getFilesScanned());
            assertEquals(expectedMatches, fileSum.getFilesMatched());
            assertEquals(expectedMatches, fileStore.getMatchCount());
            assertEquals(0L, fileSum.getErrors());

            System.out.printf(Locale.US,
                    "[BENCHMARK-REAL-DISK-1K] folderMs=%.2f fileMs=%.2f foldersStored=%d matches=%d%n",
                    folderMs, fileMs, folderStore.getFolderCount(), fileStore.getMatchCount());
        } finally {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    public void testScale1k10k100kFilesFolderAndFileScanners() throws IOException {
        Path root = Files.createTempDirectory("scale_bench_root_");
        try {
            int[][] scenarios = {
                    {1_000, 10},
                    {10_000, 50},
                    {100_000, 100},
                    {100_000, 2_000}
            };

            for (int[] sc : scenarios) {
                int totalFiles = sc[0];
                int folderCount = sc[1];
                SyntheticFileSystem fs = new SyntheticFileSystem(root, folderCount, totalFiles, 1024L, 1000);

                System.gc();
                long memBefore = usedMemoryMiB();
                long t0 = System.nanoTime();

                FolderConfig folderCfg = new FolderConfig(Collections.singletonList(root.toString()));
                FolderResultStore folderStore = new FolderResultStore(folderCfg.getRoots());
                FolderSummary folderSummary = FolderScanner.scan(
                        folderCfg, folderStore::addBatch, p -> {}, () -> false, fs);
                folderStore.finish(true);
                FolderTreeState folderTree = new FolderTreeState();
                folderTree.reset(folderStore, 1);
                folderTree.toggleExpand(PathPolicy.pathKey(root.toString()));

                double folderMs = (System.nanoTime() - t0) / 1_000_000.0;
                long folderPeakMiB = Math.max(memBefore, usedMemoryMiB());

                assertEquals(totalFiles, folderSummary.getFilesScanned());
                assertEquals(0L, folderSummary.getErrors());
                // Only folders are stored, never individual files!
                assertEquals(folderCount + 1, folderStore.getFolderCount());
                // Visible UI rows are capped by PAGE_SIZE (200) + root + "Load next 200…"
                assertTrue(folderTree.buildVisibleRows().size() <= 202);

                long t1 = System.nanoTime();
                FileScanConfig fileCfg = new FileScanConfig(
                        Collections.singletonList(root.toString()),
                        100L * 1024L * 1024L);
                FileResultStore fileStore = new FileResultStore(fileCfg.getRoots());
                FileScanSummary fileSummary = FileScanner.scan(
                        fileCfg, fileStore::addBatch, p -> {}, () -> false, fs);
                FileTreeState fileTree = new FileTreeState();
                fileTree.reset(fileStore, 1);
                fileTree.toggleExpand(PathPolicy.pathKey(root.toString()));

                double fileMs = (System.nanoTime() - t1) / 1_000_000.0;
                long filePeakMiB = Math.max(folderPeakMiB, usedMemoryMiB());

                int expectedMatches = totalFiles / 1000;
                assertEquals(totalFiles, fileSummary.getFilesScanned());
                assertEquals(expectedMatches, fileSummary.getFilesMatched());
                assertEquals(expectedMatches, fileStore.getMatchCount());
                assertEquals(0L, fileSummary.getErrors());

                System.out.printf(Locale.US,
                        "[BENCHMARK-SCALE] files=%,d folders=%,d | folderMs=%.2f storedFolders=%d | fileMs=%.2f matches=%d | heapMiB=%d%n",
                        totalFiles, folderCount, folderMs, folderStore.getFolderCount(),
                        fileMs, fileStore.getMatchCount(), filePeakMiB);
            }
        } finally {
            Files.deleteIfExists(root);
        }
    }

    private static long usedMemoryMiB() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
    }
}
