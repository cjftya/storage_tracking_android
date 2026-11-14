package com.core.storagetracking.scan.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared exclusion boundary policy for metadata traversals.
 * Ensures excluding "/storage/emulated/0/A" never excludes "/storage/emulated/0/AB".
 */
public final class PathFilter {
    private final boolean windows;
    private final List<String> excluded;
    private final Set<String> exact;

    public PathFilter(Collection<String> excludedPaths) {
        this(excludedPaths, PathPolicy.isWindowsHost());
    }

    public PathFilter(Collection<String> excludedPaths, boolean windows) {
        this.windows = windows;
        if (excludedPaths == null || excludedPaths.isEmpty()) {
            this.excluded = Collections.emptyList();
            this.exact = Collections.emptySet();
        } else {
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String p : excludedPaths) {
                if (p != null && !p.trim().isEmpty()) {
                    normalized.add(PathPolicy.pathKey(p, windows));
                }
            }
            this.excluded = Collections.unmodifiableList(new ArrayList<>(normalized));
            this.exact = Collections.unmodifiableSet(new HashSet<>(normalized));
        }
    }

    public List<String> getExcluded() {
        return excluded;
    }

    public boolean excludes(String path) {
        if (excluded.isEmpty() || path == null || path.trim().isEmpty()) {
            return false;
        }
        String candidate = PathPolicy.pathKey(path, windows);
        return excludesKey(candidate);
    }

    public boolean excludesKey(String candidateKey) {
        if (excluded.isEmpty() || candidateKey == null) {
            return false;
        }
        for (String ex : excluded) {
            if (PathPolicy.containsPath(ex, candidateKey, windows)) {
                return true;
            }
        }
        return false;
    }

    public boolean excludesFileEntry(String entryPathKey) {
        if (exact.isEmpty() || entryPathKey == null) {
            return false;
        }
        return exact.contains(entryPathKey);
    }
}
