package com.core.storagetracking.scan.folder;

/**
 * Immutable directory node stored in FolderResultStore.
 */
public final class FolderNode {
    private final String path;
    private final String name;
    private final long sizeBytes;
    private final long directBytes;

    public FolderNode(String path, String name, long sizeBytes, long directBytes) {
        this.path = path;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.directBytes = directBytes;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getDirectBytes() {
        return directBytes;
    }

    public boolean isDirectory() {
        return true;
    }

    public FolderNode withDirectBytes(long newDirectBytes) {
        return new FolderNode(this.path, this.name, this.sizeBytes, newDirectBytes);
    }

    public FolderNode withSizeBytes(long newSizeBytes) {
        return new FolderNode(this.path, this.name, newSizeBytes, this.directBytes);
    }
}
