package com.core.storagetracking.scan.folder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for FolderScanner.
 * Does not include any minimum file size filter (Folder Scan always aggregates all regular files).
 */
public final class FolderConfig {
    private final List<String> roots;
    private final List<String> excludedPaths;
    private final List<String> boundaryPaths;
    private final int batchSize;
    private final double flushIntervalSeconds;
    private final double progressIntervalSeconds;
    private final int cancellationCheckInterval;

    public FolderConfig(Collection<String> roots) {
        this(roots, Collections.emptyList(), Collections.emptyList(), 512, 0.25, 0.20, 128);
    }

    public FolderConfig(
            Collection<String> roots,
            Collection<String> excludedPaths,
            Collection<String> boundaryPaths) {
        this(roots, excludedPaths, boundaryPaths, 512, 0.25, 0.20, 128);
    }

    public FolderConfig(
            Collection<String> roots,
            Collection<String> excludedPaths,
            Collection<String> boundaryPaths,
            int batchSize,
            double flushIntervalSeconds,
            double progressIntervalSeconds,
            int cancellationCheckInterval) {
        if (roots == null) {
            throw new IllegalArgumentException("Roots must not be null");
        }
        if (batchSize < 256 || batchSize > 1024) {
            throw new IllegalArgumentException("Batch size must be between 256 and 1024");
        }
        if (flushIntervalSeconds <= 0.0 || progressIntervalSeconds <= 0.0) {
            throw new IllegalArgumentException("Update intervals must be positive");
        }
        if (cancellationCheckInterval < 1) {
            throw new IllegalArgumentException("Cancellation interval must be positive");
        }
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
        this.excludedPaths = excludedPaths == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(excludedPaths));
        this.boundaryPaths = boundaryPaths == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(boundaryPaths));
        this.batchSize = batchSize;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.progressIntervalSeconds = progressIntervalSeconds;
        this.cancellationCheckInterval = cancellationCheckInterval;
    }

    public List<String> getRoots() {
        return roots;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public List<String> getBoundaryPaths() {
        return boundaryPaths;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public double getFlushIntervalSeconds() {
        return flushIntervalSeconds;
    }

    public double getProgressIntervalSeconds() {
        return progressIntervalSeconds;
    }

    public int getCancellationCheckInterval() {
        return cancellationCheckInterval;
    }
}
