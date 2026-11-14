package com.core.storagetracking.scan.file;

/**
 * Progress snapshot for FileScanner.
 */
public final class FileScanProgress {
    private final long filesScanned;
    private final long directoriesScanned;
    private final long filesMatched;
    private final long errors;
    private final String currentDirectory;

    public FileScanProgress() {
        this(0L, 0L, 0L, 0L, "");
    }

    public FileScanProgress(
            long filesScanned,
            long directoriesScanned,
            long filesMatched,
            long errors,
            String currentDirectory) {
        this.filesScanned = filesScanned;
        this.directoriesScanned = directoriesScanned;
        this.filesMatched = filesMatched;
        this.errors = errors;
        this.currentDirectory = currentDirectory == null ? "" : currentDirectory;
    }

    public long getFilesScanned() {
        return filesScanned;
    }

    public long getDirectoriesScanned() {
        return directoriesScanned;
    }

    public long getFilesMatched() {
        return filesMatched;
    }

    public long getErrors() {
        return errors;
    }

    public String getCurrentDirectory() {
        return currentDirectory;
    }
}
