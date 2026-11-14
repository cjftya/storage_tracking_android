package com.core.storagetracking.scan.file;

/**
 * Immutable result node in FileResultStore (either a matched file or an ancestor directory).
 * For directories, sizeBytes represents the sum of matched descendant files only.
 */
public final class FileResultNode {
    private final String path;
    private final String name;
    private final boolean directory;
    private final long sizeBytes;

    public FileResultNode(String path, String name, boolean directory, long sizeBytes) {
        this.path = path;
        this.name = name;
        this.directory = directory;
        this.sizeBytes = sizeBytes;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public FileResultNode withSizeBytes(long newSizeBytes) {
        return new FileResultNode(this.path, this.name, this.directory, newSizeBytes);
    }
}
