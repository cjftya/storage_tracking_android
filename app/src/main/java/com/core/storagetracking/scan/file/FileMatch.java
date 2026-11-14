package com.core.storagetracking.scan.file;

/**
 * Immutable matched file record emitted by FileScanner.
 */
public final class FileMatch {
    private final String path;
    private final long sizeBytes;

    public FileMatch(String path, long sizeBytes) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        this.path = path;
        this.sizeBytes = sizeBytes;
    }

    public String getPath() {
        return path;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
