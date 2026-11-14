package com.core.storagetracking.scan.folder;

/**
 * Progress snapshot for FolderScanner.
 */
public final class FolderProgress {
    private final long filesScanned;
    private final long directoriesScanned;
    private final long bytesScanned;
    private final long errors;
    private final String currentDirectory;

    public FolderProgress() {
        this(0L, 0L, 0L, 0L, "");
    }

    public FolderProgress(
            long filesScanned,
            long directoriesScanned,
            long bytesScanned,
            long errors,
            String currentDirectory) {
        this.filesScanned = filesScanned;
        this.directoriesScanned = directoriesScanned;
        this.bytesScanned = bytesScanned;
        this.errors = errors;
        this.currentDirectory = currentDirectory == null ? "" : currentDirectory;
    }

    public long getFilesScanned() {
        return filesScanned;
    }

    public long getDirectoriesScanned() {
        return directoriesScanned;
    }

    public long getBytesScanned() {
        return bytesScanned;
    }

    public long getErrors() {
        return errors;
    }

    public String getCurrentDirectory() {
        return currentDirectory;
    }
}
