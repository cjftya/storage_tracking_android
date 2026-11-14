package com.core.storagetracking.storage;

/**
 * Metadata for a discovered Android storage volume.
 */
public final class StorageVolumeInfo {
    private final String path;
    private final String label;
    private final boolean primary;
    private final boolean removable;
    private final String state;

    public StorageVolumeInfo(
            String path,
            String label,
            boolean primary,
            boolean removable,
            String state) {
        this.path = path;
        this.label = label;
        this.primary = primary;
        this.removable = removable;
        this.state = state;
    }

    public String getPath() {
        return path;
    }

    public String getLabel() {
        return label;
    }

    public boolean isPrimary() {
        return primary;
    }

    public boolean isRemovable() {
        return removable;
    }

    public String getState() {
        return state;
    }
}
