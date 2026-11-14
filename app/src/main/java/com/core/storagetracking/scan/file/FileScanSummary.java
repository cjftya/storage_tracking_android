package com.core.storagetracking.scan.file;

/**
 * Final summary returned by FileScanner.
 */
public final class FileScanSummary {
    private final long filesScanned;
    private final long directoriesScanned;
    private final long filesMatched;
    private final long errors;
    private final double elapsedSeconds;
    private final boolean cancelled;

    public FileScanSummary(
            long filesScanned,
            long directoriesScanned,
            long filesMatched,
            long errors,
            double elapsedSeconds,
            boolean cancelled) {
        this.filesScanned = filesScanned;
        this.directoriesScanned = directoriesScanned;
        this.filesMatched = filesMatched;
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

    public long getFilesMatched() {
        return filesMatched;
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
