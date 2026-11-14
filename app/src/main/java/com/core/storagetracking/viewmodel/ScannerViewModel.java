package com.core.storagetracking.viewmodel;

import androidx.lifecycle.ViewModel;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.scan.common.SizeConverter;
import com.core.storagetracking.scan.file.FileScanConfig;
import com.core.storagetracking.scan.file.FileScanController;
import com.core.storagetracking.scan.file.FileScanProgress;
import com.core.storagetracking.scan.file.FileScanSummary;
import com.core.storagetracking.scan.folder.FolderConfig;
import com.core.storagetracking.scan.folder.FolderProgress;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.scan.folder.FolderScanController;
import com.core.storagetracking.scan.folder.FolderSummary;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeProvider;
import com.core.storagetracking.ui.FileTreeState;
import com.core.storagetracking.ui.FolderTreeState;
import com.core.storagetracking.ui.TreeRowItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates scan mode, scope configuration, independent controllers, and 1,000ms UI updates.
 * Survives Activity configuration changes (e.g. screen rotation) without restarting scans.
 */
public final class ScannerViewModel extends ViewModel {
    public static final String MODE_FOLDER = "folder";
    public static final String MODE_FILE = "file";
    public static final int DEFAULT_UPDATE_MS = 1000;

    public static final class CompletionNotice {
        private final int runId;
        private final Object storeIdentity;
        private final boolean error;
        private final String title;
        private final String message;

        public CompletionNotice(int runId, Object storeIdentity, boolean error, String title, String message) {
            this.runId = runId;
            this.storeIdentity = storeIdentity;
            this.error = error;
            this.title = title;
            this.message = message;
        }

        public int getRunId() {
            return runId;
        }

        public Object getStoreIdentity() {
            return storeIdentity;
        }

        public boolean isError() {
            return error;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }
    }

    private final ReentrantLock scanLock = new ReentrantLock();
    private final FolderTreeState folderTreeState = new FolderTreeState();
    private final FileTreeState fileTreeState = new FileTreeState();

    private String scanMode = MODE_FOLDER;
    private FolderScanController folderController = new FolderScanController(scanLock);
    private FileScanController fileController = null;

    private StorageDiscovery discovery = new StorageDiscovery(
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList());
    private boolean discovering = false;
    private String selectedFolder = null;
    private String minSizeMibText = SizeConverter.bytesToMib(SizeConverter.DEFAULT_MIN_SIZE_BYTES);
    private String excludedPathsText = "";

    private Integer runId = null;
    private Integer presentedRunId = null;
    private Integer notifiedRunId = null;
    private CompletionNotice pendingNotice = null;

    private boolean cancelEnabled = false;
    private String statusText = "Select a folder to scan.";
    private String currentPathText = "";

    public String getScanMode() {
        return scanMode;
    }

    public boolean isFolderMode() {
        return MODE_FOLDER.equals(scanMode);
    }

    public boolean isFileMode() {
        return MODE_FILE.equals(scanMode);
    }

    public String getModeDescription() {
        if (MODE_FOLDER.equals(scanMode)) {
            return "All regular files; logical sizes. Aggregating until complete; cancelled/error totals are partial.";
        }
        return "Folder sizes include matching files only.";
    }

    public String getSizeHeadingText() {
        return MODE_FOLDER.equals(scanMode)
                ? folderTreeState.getSizeHeadingText()
                : fileTreeState.getSizeHeadingText();
    }

    public void changeMode(String mode) {
        if (!MODE_FOLDER.equals(mode) && !MODE_FILE.equals(mode)) {
            throw new IllegalArgumentException("scan_mode must be folder or file");
        }
        if (mode.equals(scanMode)) {
            return;
        }
        closeActiveController();
        folderTreeState.reset(null, null);
        fileTreeState.reset(null, null);
        this.scanMode = mode;
        if (MODE_FOLDER.equals(mode)) {
            folderController = new FolderScanController(scanLock);
            fileController = null;
        } else {
            fileController = new FileScanController(scanLock);
            folderController = null;
        }
        this.runId = null;
        this.presentedRunId = null;
        this.notifiedRunId = null;
        this.pendingNotice = null;
        this.currentPathText = "";
        this.cancelEnabled = false;
        this.statusText = "Ready - press Scan / Restart.";
    }

    public StorageDiscovery getDiscovery() {
        return discovery;
    }

    public void setDiscovery(StorageDiscovery discovery, boolean initial) {
        this.discovery = discovery;
        this.discovering = false;
        if (initial && excludedPathsText.isEmpty() && discovery != null && !discovery.getDefaultExclusions().isEmpty()) {
            this.excludedPathsText = String.join("\n", discovery.getDefaultExclusions()) + "\n";
        }
        if (runId == null) {
            this.statusText = hasSelectedFolder() ? "Ready" : "Select a folder to scan.";
        }
    }

    public void setDiscoveryFailed(String reason) {
        this.discovery = null;
        this.discovering = false;
        this.statusText = "Drives unavailable: " + reason;
    }

    public void markDiscovering() {
        this.discovering = true;
        if (runId == null) {
            this.statusText = "Checking drives…";
        }
    }

    public boolean isDiscovering() {
        return discovering;
    }

    public String getSelectedFolder() {
        return selectedFolder;
    }

    public boolean hasSelectedFolder() {
        return selectedFolder != null && !selectedFolder.trim().isEmpty();
    }

    public void selectFolder(String folderPath) {
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return;
        }
        this.selectedFolder = PathPolicy.pathKey(folderPath);
        if (runId == null && !discovering && discovery != null) {
            this.statusText = "Ready";
        }
    }

    public void clearSelectedFolder() {
        this.selectedFolder = null;
        if (runId == null && !discovering && discovery != null) {
            this.statusText = "Select a folder to scan.";
        }
    }

    public boolean isStartEnabled() {
        return discovery != null && !discovering && hasSelectedFolder();
    }

    public boolean isCancelEnabled() {
        return cancelEnabled;
    }

    public String getMinSizeMibText() {
        return minSizeMibText;
    }

    public void setMinSizeMibText(String minSizeMibText) {
        this.minSizeMibText = minSizeMibText != null ? minSizeMibText : "";
    }

    public String getExcludedPathsText() {
        return excludedPathsText;
    }

    public void setExcludedPathsText(String excludedPathsText) {
        this.excludedPathsText = excludedPathsText != null ? excludedPathsText : "";
    }

    public String getStatusText() {
        return statusText;
    }

    public String getCurrentPathText() {
        return currentPathText;
    }

    public Integer getRunId() {
        return runId;
    }

    public FolderTreeState getFolderTreeState() {
        return folderTreeState;
    }

    public FileTreeState getFileTreeState() {
        return fileTreeState;
    }

    public void startScan() {
        if (discovery == null) {
            throw new IllegalStateException("Drives unavailable");
        }
        if (!hasSelectedFolder()) {
            throw new IllegalArgumentException("Select a folder first.");
        }
        List<String> roots = Collections.singletonList(selectedFolder);
        StorageVolumeProvider.validateRoots(discovery, roots);
        List<String> parsedExclusions = parseExcludedLines(excludedPathsText);
        List<String> effectiveExclusions = StorageVolumeProvider.mergeExclusions(discovery, parsedExclusions);
        List<String> boundaries = discovery.getBoundaryPaths();

        if (MODE_FOLDER.equals(scanMode)) {
            FolderConfig config = new FolderConfig(roots, effectiveExclusions, boundaries);
            FolderScanController.RunSnapshot snapshot = folderController.start(config);
            this.runId = snapshot.getRunId();
            this.presentedRunId = null;
            this.notifiedRunId = null;
            this.pendingNotice = null;
            this.folderTreeState.reset(snapshot.getStore(), snapshot.getRunId());
        } else {
            long minSizeBytes = SizeConverter.mibToBytes(minSizeMibText);
            FileScanConfig config = new FileScanConfig(roots, minSizeBytes, effectiveExclusions, boundaries);
            FileScanController.RunSnapshot snapshot = fileController.start(config);
            this.runId = snapshot.getRunId();
            this.presentedRunId = null;
            this.notifiedRunId = null;
            this.pendingNotice = null;
            this.fileTreeState.reset(snapshot.getStore(), snapshot.getRunId());
        }

        this.cancelEnabled = true;
        this.statusText = "Scanning…";
        this.currentPathText = "";
    }

    public void cancelScan() {
        if (MODE_FOLDER.equals(scanMode) && folderController != null) {
            folderController.cancel();
        } else if (MODE_FILE.equals(scanMode) && fileController != null) {
            fileController.cancel();
        }
        this.cancelEnabled = false;
        this.statusText = "Cancelling…";
    }

    public void tick() {
        if (MODE_FOLDER.equals(scanMode)) {
            tickFolderMode();
        } else {
            tickFileMode();
        }
    }

    private void tickFolderMode() {
        if (folderController == null || runId == null) {
            return;
        }
        FolderScanController.RunSnapshot snapshot = folderController.snapshot();
        if (snapshot == null || snapshot.getRunId() != runId) {
            return;
        }
        FolderProgress progress = snapshot.getProgress();
        String state = "Scanning";
        if (!snapshot.isFinished() || !Integer.valueOf(snapshot.getRunId()).equals(presentedRunId)) {
            folderTreeState.pollChanges();
            folderTreeState.refreshAllPending();
        }
        if (snapshot.isFinished()) {
            cancelEnabled = false;
            if (snapshot.getError() != null) {
                state = "Failed";
            } else if (snapshot.getSummary() != null && snapshot.getSummary().isCancelled()) {
                state = "Cancelled";
            } else {
                state = "Completed";
            }
            if (!Integer.valueOf(snapshot.getRunId()).equals(presentedRunId)) {
                folderTreeState.pollChanges();
                folderTreeState.refreshAllPending();
                presentedRunId = snapshot.getRunId();
            }
            if (FolderResultStore.STATE_PARTIAL.equals(snapshot.getStore().getState())) {
                state += " (partial totals)";
            }
        }
        statusText = String.format(
                Locale.US,
                "%s | Folder scan | Files %,d | Folders %,d | Folders stored %,d | Bytes %,d | Errors %,d | %.1fs",
                state,
                progress.getFilesScanned(),
                progress.getDirectoriesScanned(),
                snapshot.getStore().getFolderCount(),
                progress.getBytesScanned(),
                progress.getErrors(),
                snapshot.getElapsedSeconds());
        currentPathText = snapshot.getError() != null ? snapshot.getError() : progress.getCurrentDirectory();

        if (snapshot.isFinished() && !Integer.valueOf(snapshot.getRunId()).equals(notifiedRunId)) {
            notifiedRunId = snapshot.getRunId();
            if (snapshot.getError() != null) {
                pendingNotice = new CompletionNotice(
                        snapshot.getRunId(),
                        snapshot.getStore(),
                        true,
                        "Scan failed",
                        snapshot.getError());
            } else if (snapshot.getSummary() != null && !snapshot.getSummary().isCancelled()) {
                FolderSummary summary = snapshot.getSummary();
                String msg = String.format(
                        Locale.US,
                        "Folders stored: %,d\nLogical bytes: %,d\nTotals: %s\nFiles: %,d\nErrors: %,d\nTime: %.1fs",
                        snapshot.getStore().getFolderCount(),
                        summary.getBytesScanned(),
                        snapshot.getStore().getState(),
                        summary.getFilesScanned(),
                        summary.getErrors(),
                        summary.getElapsedSeconds());
                pendingNotice = new CompletionNotice(
                        snapshot.getRunId(),
                        snapshot.getStore(),
                        false,
                        "Scan complete",
                        msg);
            }
        }
    }

    private void tickFileMode() {
        if (fileController == null || runId == null) {
            return;
        }
        FileScanController.RunSnapshot snapshot = fileController.snapshot();
        if (snapshot == null || snapshot.getRunId() != runId) {
            return;
        }
        FileScanProgress progress = snapshot.getProgress();
        String state = "Scanning";
        if (!snapshot.isFinished() || !Integer.valueOf(snapshot.getRunId()).equals(presentedRunId)) {
            fileTreeState.pollChanges();
            fileTreeState.refreshAllPending();
        }
        if (snapshot.isFinished()) {
            cancelEnabled = false;
            if (snapshot.getError() != null) {
                state = "Failed";
            } else if (snapshot.getSummary() != null && snapshot.getSummary().isCancelled()) {
                state = "Cancelled";
            } else {
                state = "Completed";
            }
            if (!Integer.valueOf(snapshot.getRunId()).equals(presentedRunId)) {
                fileTreeState.pollChanges();
                fileTreeState.refreshAllPending();
                presentedRunId = snapshot.getRunId();
            }
        }
        statusText = String.format(
                Locale.US,
                "%s | File scan | Files %,d | Folders %,d | Matches %,d | Errors %,d | %.1fs",
                state,
                progress.getFilesScanned(),
                progress.getDirectoriesScanned(),
                progress.getFilesMatched(),
                progress.getErrors(),
                snapshot.getElapsedSeconds());
        currentPathText = snapshot.getError() != null ? snapshot.getError() : progress.getCurrentDirectory();

        if (snapshot.isFinished() && !Integer.valueOf(snapshot.getRunId()).equals(notifiedRunId)) {
            notifiedRunId = snapshot.getRunId();
            if (snapshot.getError() != null) {
                pendingNotice = new CompletionNotice(
                        snapshot.getRunId(),
                        snapshot.getStore(),
                        true,
                        "Scan failed",
                        snapshot.getError());
            } else if (snapshot.getSummary() != null && !snapshot.getSummary().isCancelled()) {
                FileScanSummary summary = snapshot.getSummary();
                String msg = String.format(
                        Locale.US,
                        "Matches: %,d\nFiles: %,d\nErrors: %,d\nTime: %.1fs",
                        summary.getFilesMatched(),
                        summary.getFilesScanned(),
                        summary.getErrors(),
                        summary.getElapsedSeconds());
                pendingNotice = new CompletionNotice(
                        snapshot.getRunId(),
                        snapshot.getStore(),
                        false,
                        "Scan complete",
                        msg);
            }
        }
    }

    public CompletionNotice consumeCompletionNotice() {
        CompletionNotice notice = this.pendingNotice;
        this.pendingNotice = null;
        if (notice == null || runId == null || notice.getRunId() != runId) {
            return null;
        }
        Object activeStore = MODE_FOLDER.equals(scanMode)
                ? (folderController != null && folderController.snapshot() != null
                        ? folderController.snapshot().getStore() : null)
                : (fileController != null && fileController.snapshot() != null
                        ? fileController.snapshot().getStore() : null);
        if (activeStore != notice.getStoreIdentity()) {
            return null;
        }
        return notice;
    }

    public List<TreeRowItem> getVisibleRows() {
        if (MODE_FOLDER.equals(scanMode)) {
            folderTreeState.refreshAllPending();
            return folderTreeState.buildVisibleRows();
        } else {
            fileTreeState.refreshAllPending();
            return fileTreeState.buildVisibleRows();
        }
    }

    public void onRowClicked(TreeRowItem item) {
        if (item == null) {
            return;
        }
        if (item.isLoadMoreRow()) {
            if (MODE_FOLDER.equals(scanMode)) {
                folderTreeState.loadMore(item.getPath());
            } else {
                fileTreeState.loadMore(item.getPath());
            }
        } else if (item.isDirectory()) {
            if (MODE_FOLDER.equals(scanMode)) {
                folderTreeState.toggleExpand(item.getPath());
            } else {
                fileTreeState.toggleExpand(item.getPath());
            }
        } else {
            if (MODE_FOLDER.equals(scanMode)) {
                folderTreeState.selectPath(item.getPath());
            } else {
                fileTreeState.selectPath(item.getPath());
            }
        }
    }

    public static List<String> parseExcludedLines(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = rawText.split("\\r?\\n");
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(PathPolicy.pathKey(trimmed));
            }
        }
        return result;
    }

    private void closeActiveController() {
        if (folderController != null) {
            folderController.close();
        }
        if (fileController != null) {
            fileController.close();
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        closeActiveController();
    }
}
