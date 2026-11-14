package com.core.storagetracking.scan.folder;

/**
 * Final summary returned by FolderScanner.
 */
public final class FolderSummary {
    private final long filesScanned;
    private final long directoriesScanned;
    private final long bytesScanned;
    private final long errors;
    private final double elapsedSeconds;
    private final boolean cancelled;

    public FolderSummary(
            long filesScanned,
            long directoriesScanned,
            long bytesScanned,
            long errors,
            double elapsedSeconds,
            boolean cancelled) {
        this.filesScanned = filesScanned;
        this.directoriesScanned = directoriesScanned;
        this.bytesScanned = bytesScanned;
        this.errors = errors;
        this.elapsedSeconds = elapsedSeconds;
        this.cancelled = cancelled;
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

    public double getElapsedSeconds() {
        return elapsedSeconds;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
