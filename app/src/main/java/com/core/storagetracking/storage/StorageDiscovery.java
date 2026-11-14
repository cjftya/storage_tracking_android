package com.core.storagetracking.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of discovered storage volumes and mount/exclusion boundaries.
 */
public final class StorageDiscovery {
    private final List<StorageVolumeInfo> volumes;
    private final List<String> roots;
    private final List<String> boundaryPaths;
    private final List<String> defaultExclusions;
    private final List<String> mandatoryExclusions;

    public StorageDiscovery(
            Collection<StorageVolumeInfo> volumes,
            Collection<String> roots,
            Collection<String> boundaryPaths,
            Collection<String> defaultExclusions,
            Collection<String> mandatoryExclusions) {
        this.volumes = volumes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(volumes));
        this.roots = roots == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(roots));
        this.boundaryPaths = boundaryPaths == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(boundaryPaths));
        this.defaultExclusions = defaultExclusions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(defaultExclusions));
        this.mandatoryExclusions = mandatoryExclusions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(mandatoryExclusions));
    }

    public List<StorageVolumeInfo> getVolumes() {
        return volumes;
    }

    public List<String> getRoots() {
        return roots;
    }

    public List<String> getBoundaryPaths() {
        return boundaryPaths;
    }

    public List<String> getDefaultExclusions() {
        return defaultExclusions;
    }

    public List<String> getMandatoryExclusions() {
        return mandatoryExclusions;
    }
}
