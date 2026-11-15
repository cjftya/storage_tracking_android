package com.core.storagetracking;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.file.FileScanConfig;
import com.core.storagetracking.scan.file.FileScanController;
import com.core.storagetracking.scan.file.FileScanSummary;
import com.core.storagetracking.scan.folder.DirectoryDelta;
import com.core.storagetracking.scan.folder.FolderConfig;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.scan.folder.FolderScanController;
import com.core.storagetracking.scan.folder.FolderSummary;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeInfo;
import com.core.storagetracking.ui.FolderTreeState;
import com.core.storagetracking.ui.TreeRowItem;
import com.core.storagetracking.viewmodel.ScannerViewModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScannerIndependenceAndLifecycleTest {

    private Path tempRoot;

    @Before
    public void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("independence_test_");
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
    public void testZeroCrossImportsBetweenFolderAndFilePackages() throws IOException {
        Path base = Paths.get("src/main/java/com/core/storagetracking/scan");
        if (!Files.exists(base)) {
            base = Paths.get("app/src/main/java/com/core/storagetracking/scan");
        }
        assertTrue("Scan source directory should exist", Files.exists(base));

        Path folderPkg = base.resolve("folder");
        Path filePkg = base.resolve("file");

        for (Path javaFile : Files.newDirectoryStream(folderPkg, "*.java")) {
            String content = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
            assertFalse("Folder package must not import file package: " + javaFile,
                    content.contains("com.core.storagetracking.scan.file"));
            assertFalse("Folder package must not import UI package: " + javaFile,
                    content.contains("com.core.storagetracking.ui"));
        }

        for (Path javaFile : Files.newDirectoryStream(filePkg, "*.java")) {
            String content = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
            assertFalse("File package must not import folder package: " + javaFile,
                    content.contains("com.core.storagetracking.scan.folder"));
            assertFalse("File package must not import UI package: " + javaFile,
                    content.contains("com.core.storagetracking.ui"));
        }
    }

    @Test
    public void testFirstScanFileAndFirstScanFolderAndAlternatingOrders() throws IOException {
        long mib = 1024L * 1024L;
        createFile("small.bin", 500L);
        createFile("sub/large.bin", 2L * mib);

        StorageDiscovery discovery = new StorageDiscovery(
                Collections.singletonList(new StorageVolumeInfo(
                        PathPolicy.pathKey(tempRoot.toString()), "TestRoot", true, false, "mounted")),
                Collections.singletonList(PathPolicy.pathKey(tempRoot.toString())),
                Collections.singletonList(PathPolicy.pathKey(tempRoot.toString())),
                Collections.emptyList(),
                Collections.emptyList());

        // 1. Run File Scan FIRST right after startup, then Folder -> File
        ScannerViewModel vmFileFirst = new ScannerViewModel();
        vmFileFirst.setDiscovery(discovery, true);
        vmFileFirst.selectFolder(tempRoot.toString());
        vmFileFirst.setMinSizeMibText("1");

        for (String mode : Arrays.asList(
                ScannerViewModel.MODE_FILE,
                ScannerViewModel.MODE_FOLDER,
                ScannerViewModel.MODE_FILE,
                ScannerViewModel.MODE_FOLDER)) {
            vmFileFirst.changeMode(mode);
            vmFileFirst.startScan();
            waitUntilFinished(vmFileFirst);
            vmFileFirst.tick();
            List<TreeRowItem> rows = vmFileFirst.getVisibleRows();
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).isExpanded());
            assertEquals(0, rows.get(0).getDepth());
            assertFalse(rows.get(1).isExpanded());
            assertEquals(1, rows.get(1).getDepth());
            if (ScannerViewModel.MODE_FOLDER.equals(mode)) {
                assertTrue(vmFileFirst.getStatusText().contains("Folder scan"));
                assertFalse(vmFileFirst.getStatusText().contains("Matches"));
                assertEquals("2,097,652", rows.get(0).getFormattedBytes());
            } else {
                assertTrue(vmFileFirst.getStatusText().contains("File scan"));
                assertTrue(vmFileFirst.getStatusText().contains("Matches 1"));
                assertEquals("2,097,152", rows.get(0).getFormattedBytes());
            }
        }
    }

    @Test
    public void testFolderModeIgnoresInvalidMinSizeAndStaleNotifications() throws IOException {
        createFile("a.bin", 10L);
        StorageDiscovery discovery = new StorageDiscovery(
                Collections.singletonList(new StorageVolumeInfo(
                        PathPolicy.pathKey(tempRoot.toString()), "TestRoot", true, false, "mounted")),
                Collections.singletonList(PathPolicy.pathKey(tempRoot.toString())),
                Collections.singletonList(PathPolicy.pathKey(tempRoot.toString())),
                Collections.emptyList(),
                Collections.emptyList());

        ScannerViewModel vm = new ScannerViewModel();
        vm.setDiscovery(discovery, true);
        vm.selectFolder(tempRoot.toString());
        vm.changeMode(ScannerViewModel.MODE_FILE);
        vm.setMinSizeMibText("100");
        vm.startScan();
        waitUntilFinished(vm);
        vm.tick();
        assertNotNull(vm.consumeCompletionNotice());
        assertNull("Completion notice must only be consumed once", vm.consumeCompletionNotice());

        // Switch to Folder mode and put invalid text into minSizeMibText
        vm.changeMode(ScannerViewModel.MODE_FOLDER);
        vm.setMinSizeMibText("invalid_threshold");
        vm.startScan();
        waitUntilFinished(vm);
        vm.tick();
        ScannerViewModel.CompletionNotice folderNotice = vm.consumeCompletionNotice();
        assertNotNull(folderNotice);
        assertFalse(folderNotice.isError());
        assertTrue(folderNotice.getMessage().contains("Logical bytes: 10"));
    }

    @Test
    public void testWorkersShareGateAcrossModesAndRestartDropsLateBatches() throws Exception {
        ReentrantLock sharedGate = new ReentrantLock();
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicBoolean firstExited = new AtomicBoolean(false);
        String root = PathPolicy.pathKey(tempRoot.toString());

        FolderScanController folderCtrl = new FolderScanController(sharedGate, (cfg, onBatch, onProg, cancel) -> {
            firstStarted.countDown();
            while (!cancel.getAsBoolean()) {
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException ignored) {
                }
            }
            // Emit late batch after cancellation/superseding
            onBatch.accept(Collections.singletonList(new DirectoryDelta(root, 999L)));
            firstExited.set(true);
            return new FolderSummary(1L, 1L, 999L, 0L, 0.05, true);
        });

        FileScanController fileCtrl = new FileScanController(sharedGate, (cfg, onBatch, onProg, cancel) -> {
            assertTrue("Second worker must not overlap with first worker", firstExited.get());
            return new FileScanSummary(1L, 1L, 0L, 0L, 0.01, false);
        });

        FolderScanController.RunSnapshot snap1 = folderCtrl.start(new FolderConfig(Collections.singletonList(root)));
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        folderCtrl.close();

        FileScanController.RunSnapshot snap2 = fileCtrl.start(new FileScanConfig(Collections.singletonList(root)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && !fileCtrl.snapshot().isFinished()) {
            Thread.sleep(5L);
        }
        assertTrue(fileCtrl.snapshot().isFinished());
        assertNull(fileCtrl.snapshot().getError());
        assertEquals("Superseded run must drop late batch", 0L, snap1.getStore().node(root).getSizeBytes());
        fileCtrl.close();
    }

    @Test
    public void testFolderTreeStatePagingFinalSortAndSelectionPreservation() {
        String root = PathPolicy.pathKey(tempRoot.toString());
        FolderResultStore store = new FolderResultStore(Collections.singletonList(root));
        List<DirectoryDelta> deltas = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            deltas.add(new DirectoryDelta(new File(root, String.valueOf(i)).getPath(), i + 1L));
        }
        store.addBatch(deltas);

        FolderTreeState tree = new FolderTreeState();
        tree.reset(store, 1);
        tree.refreshAllPending();

        // Initially only root is visible (1 row)
        List<TreeRowItem> initialRows = tree.buildVisibleRows();
        assertEquals(1, initialRows.size());
        assertEquals(root, initialRows.get(0).getPath());

        // Expand root -> loads first 200 children + "Load next 200…" row
        tree.toggleExpand(root);
        List<TreeRowItem> expandedRows = tree.buildVisibleRows();
        assertEquals(1 + 200 + 1, expandedRows.size());
        assertTrue(expandedRows.get(expandedRows.size() - 1).isLoadMoreRow());

        // Select and expand child "0" (which has 1 byte, so it will fall outside top 200 after sorting)
        String child0 = PathPolicy.pathKey(new File(root, "0").getPath());
        tree.toggleExpand(child0);
        assertEquals(child0, tree.getSelectedPath());
        assertTrue(tree.isExpanded(child0));

        // Finalize store -> sorts descending by sizeBytes
        store.finish(true);
        tree.pollChanges();
        tree.refreshAllPending();

        List<TreeRowItem> sortedRows = tree.buildVisibleRows();
        // First child under root must now be "599" (600 bytes)
        String child599 = PathPolicy.pathKey(new File(root, "599").getPath());
        assertEquals(child599, sortedRows.get(1).getPath());
        // Selection and expansion of child0 must be preserved
        assertEquals(child0, tree.getSelectedPath());
        assertTrue(tree.isExpanded(child0));
        assertEquals("Logical size", tree.getSizeHeadingText());

        // Load next 200
        tree.loadMore(root);
        assertEquals(400, tree.getOffset(root));
    }

    @Test
    public void testMultiExclusionManagementAndParentRatio() {
        ScannerViewModel vm = new ScannerViewModel();
        assertTrue(vm.getExcludedPaths().isEmpty());

        String ex1 = PathPolicy.pathKey(new File(tempRoot.toFile(), "cache").getPath());
        String ex2 = PathPolicy.pathKey(new File(tempRoot.toFile(), "tmp").getPath());

        assertTrue(vm.addExcludedPath(ex1));
        assertFalse("Duplicate exclusion should not be added twice", vm.addExcludedPath(ex1));
        assertTrue(vm.addExcludedPath(ex2));
        assertEquals(2, vm.getExcludedPaths().size());
        assertTrue(vm.getExcludedPaths().contains(ex1));
        assertTrue(vm.getExcludedPaths().contains(ex2));

        assertTrue(vm.removeExcludedPath(ex1));
        assertEquals(1, vm.getExcludedPaths().size());
        assertEquals(ex2, vm.getExcludedPaths().get(0));

        vm.clearExcludedPaths();
        assertTrue(vm.getExcludedPaths().isEmpty());
        assertEquals("", vm.getExcludedPathsText());

        String root = PathPolicy.pathKey(tempRoot.toString());
        String subA = PathPolicy.pathKey(new File(root, "subA").getPath());
        String subB = PathPolicy.pathKey(new File(root, "subB").getPath());
        FolderResultStore store = new FolderResultStore(Collections.singletonList(root));
        store.addBatch(Arrays.asList(
                new DirectoryDelta(subA, 750L),
                new DirectoryDelta(subB, 250L)));
        store.finish(true);

        FolderTreeState tree = new FolderTreeState();
        tree.reset(store, 1);
        tree.refreshAllPending();
        tree.toggleExpand(root);
        List<TreeRowItem> rows = tree.buildVisibleRows();
        assertEquals(3, rows.size());
        assertEquals(1.0f, rows.get(0).getParentRatio(), 0.001f);
        assertEquals(0.75f, rows.get(1).getParentRatio(), 0.001f);
        assertEquals(0.25f, rows.get(2).getParentRatio(), 0.001f);
    }

    @Test
    public void testScanCompletionExpandsOnlyRootLeavingInnerFoldersCollapsed() throws IOException {
        long mib = 1024L * 1024L;
        createFile("dirA/nested/deep.bin", 2L * mib);
        createFile("dirB/other.bin", mib);

        String rootKey = PathPolicy.pathKey(tempRoot.toString());
        String dirAKey = PathPolicy.pathKey(tempRoot.resolve("dirA").toString());
        String dirBKey = PathPolicy.pathKey(tempRoot.resolve("dirB").toString());
        String nestedKey = PathPolicy.pathKey(tempRoot.resolve("dirA/nested").toString());

        StorageDiscovery discovery = new StorageDiscovery(
                Collections.singletonList(new StorageVolumeInfo(
                        rootKey, "TestRoot", true, false, "mounted")),
                Collections.singletonList(rootKey),
                Collections.singletonList(rootKey),
                Collections.emptyList(),
                Collections.emptyList());

        ScannerViewModel vm = new ScannerViewModel();
        vm.setDiscovery(discovery, true);
        vm.selectFolder(rootKey);
        assertTrue(vm.isScopeExpanded());

        // 1. Folder mode completion expands only root, keeping inner folders collapsed, and collapses scope
        vm.startScan();
        waitUntilFinished(vm);
        assertFalse(vm.isScopeExpanded());
        List<TreeRowItem> folderRows = vm.getVisibleRows();
        assertEquals(3, folderRows.size());
        assertEquals(rootKey, folderRows.get(0).getPath());
        assertTrue(folderRows.get(0).isExpanded());
        assertEquals(dirAKey, folderRows.get(1).getPath());
        assertFalse(folderRows.get(1).isExpanded());
        assertEquals(dirBKey, folderRows.get(2).getPath());
        assertFalse(folderRows.get(2).isExpanded());
        assertFalse(vm.getFolderTreeState().isExpanded(nestedKey));

        // User collapsing root and re-expanding scope after completion must persist across subsequent ticks
        vm.onRowClicked(folderRows.get(0));
        vm.setScopeExpanded(true);
        assertFalse(vm.getFolderTreeState().isExpanded(rootKey));
        assertTrue(vm.isScopeExpanded());
        vm.tick();
        assertFalse(vm.getFolderTreeState().isExpanded(rootKey));
        assertTrue(vm.isScopeExpanded());
        assertEquals(1, vm.getVisibleRows().size());

        // 2. File mode completion expands only root, keeping inner folders collapsed, and collapses scope
        vm.changeMode(ScannerViewModel.MODE_FILE);
        vm.setMinSizeMibText("1");
        vm.startScan();
        waitUntilFinished(vm);
        assertFalse(vm.isScopeExpanded());
        List<TreeRowItem> fileRows = vm.getVisibleRows();
        assertEquals(3, fileRows.size());
        assertEquals(rootKey, fileRows.get(0).getPath());
        assertTrue(fileRows.get(0).isExpanded());
        assertFalse(fileRows.get(1).isExpanded());
        assertEquals(1, fileRows.get(1).getDepth());
        assertFalse(fileRows.get(2).isExpanded());
        assertEquals(1, fileRows.get(2).getDepth());
        List<String> fileChildPaths = Arrays.asList(fileRows.get(1).getPath(), fileRows.get(2).getPath());
        assertTrue(fileChildPaths.contains(dirAKey));
        assertTrue(fileChildPaths.contains(dirBKey));
        assertFalse(vm.getFileTreeState().isExpanded(nestedKey));

        // User collapsing root after completion must persist across subsequent ticks
        vm.onRowClicked(fileRows.get(0));
        assertFalse(vm.getFileTreeState().isExpanded(rootKey));
        vm.tick();
        assertFalse(vm.getFileTreeState().isExpanded(rootKey));
        assertEquals(1, vm.getVisibleRows().size());
    }

    private static void waitUntilFinished(ScannerViewModel vm) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            vm.tick();
            if (!vm.isCancelEnabled()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Scan did not finish within timeout");
    }
}
