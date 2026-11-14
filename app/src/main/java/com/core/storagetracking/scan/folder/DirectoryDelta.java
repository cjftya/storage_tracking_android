package com.core.storagetracking.scan.folder;

/**
 * Direct-byte increment emitted by FolderScanner for a single directory.
 */
public final class DirectoryDelta {
    private final String path;
    private final long directBytes;

    public DirectoryDelta(String path, long directBytes) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        this.path = path;
        this.directBytes = directBytes;
    }

    public String getPath() {
        return path;
    }

    public long getDirectBytes() {
        return directBytes;
    }
}
