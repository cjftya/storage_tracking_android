package com.core.storagetracking.scan.file;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.util.SizeFormatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe append-only result index for File Scan mode.
 * Never builds unmatched folders; folder sizes reflect matched descendant files only.
 */
public final class FileResultStore {

    public static final class Page {
        private final int totalCount;
        private final List<FileResultNode> nodes;

        public Page(int totalCount, List<FileResultNode> nodes) {
            this.totalCount = totalCount;
            this.nodes = nodes;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public List<FileResultNode> getNodes() {
            return nodes;
        }
    }

    private final List<String> roots;
    private final Set<String> rootSet;
    private final Map<String, FileResultNode> nodes = new HashMap<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Set<String> changes = new LinkedHashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int matchCount = 0;

    public FileResultStore(Collection<String> configuredRoots) {
        if (configuredRoots == null) {
            throw new IllegalArgumentException("Roots must not be null");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String r : configuredRoots) {
            if (r != null && !r.trim().isEmpty()) {
                unique.add(PathPolicy.pathKey(r));
            }
        }
        List<String> sortedRoots = new ArrayList<>(unique);
        sortedRoots.sort((a, b) -> Integer.compare(b.length(), a.length()));
        this.roots = Collections.unmodifiableList(sortedRoots);
        this.rootSet = Collections.unmodifiableSet(new LinkedHashSet<>(sortedRoots));
        this.children.put(null, new ArrayList<>());
    }

    public int getMatchCount() {
        lock.lock();
        try {
            return matchCount;
        } finally {
            lock.unlock();
        }
    }

    private String findOwningRoot(String normalizedKey) {
        for (String r : roots) {
            if (PathPolicy.containsPath(r, normalizedKey)) {
                return r;
            }
        }
        return null;
    }

    public void addBatch(List<FileMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        // Release the lock every 64 items so UI page reads never wait on a large batch.
        for (int offset = 0; offset < matches.size(); offset += 64) {
            int end = Math.min(matches.size(), offset + 64);
            lock.lock();
            try {
                Map<String, Long> directSizes = new LinkedHashMap<>();
                for (int i = offset; i < end; i++) {
                    FileMatch match = matches.get(i);
                    if (match == null) {
                        continue;
                    }
                    String key = PathPolicy.pathKey(match.getPath());
                    if (nodes.containsKey(key)) {
                        continue;
                    }
                    if (rootSet.contains(key)) {
                        nodes.put(key, new FileResultNode(key, match.getPath(), false, match.getSizeBytes()));
                        children.computeIfAbsent(null, k -> new ArrayList<>()).add(key);
                        changes.add(null);
                        matchCount++;
                        continue;
                    }
                    String parent = PathPolicy.parentKey(key);
                    if (parent == null) {
                        throw new IllegalArgumentException("Result has no parent directory: " + match.getPath());
                    }
                    if (!nodes.containsKey(parent)) {
                        String root = findOwningRoot(key);
                        if (root == null) {
                            throw new IllegalArgumentException("Result outside configured roots: " + match.getPath());
                        }
                        List<String> missing = new ArrayList<>();
                        String folder = parent;
                        while (!nodes.containsKey(folder)) {
                            missing.add(folder);
                            if (folder.equals(root)) {
                                break;
                            }
                            String nextParent = PathPolicy.parentKey(folder);
                            if (nextParent == null) {
                                break;
                            }
                            folder = nextParent;
                        }
                        for (int m = missing.size() - 1; m >= 0; m--) {
                            String missingFolder = missing.get(m);
                            String ancestor = missingFolder.equals(root) ? null : PathPolicy.parentKey(missingFolder);
                            String displayName = ancestor == null ? missingFolder : PathPolicy.baseName(missingFolder);
                            nodes.put(missingFolder, new FileResultNode(missingFolder, displayName, true, 0L));
                            children.put(missingFolder, new ArrayList<>());
                            parents.put(missingFolder, ancestor);
                            children.computeIfAbsent(ancestor, k -> new ArrayList<>()).add(missingFolder);
                            changes.add(ancestor);
                        }
                    }
                    String fileName = PathPolicy.baseName(match.getPath());
                    nodes.put(key, new FileResultNode(key, fileName, false, match.getSizeBytes()));
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(key);
                    changes.add(parent);
                    matchCount++;
                    long prevDirect = directSizes.getOrDefault(parent, 0L);
                    directSizes.put(parent, SizeFormatter.safeAdd(prevDirect, match.getSizeBytes()));
                }

                Map<String, Long> totals = new LinkedHashMap<>();
                for (Map.Entry<String, Long> entry : directSizes.entrySet()) {
                    String folder = entry.getKey();
                    long delta = entry.getValue();
                    while (folder != null) {
                        long prevTotal = totals.getOrDefault(folder, 0L);
                        totals.put(folder, SizeFormatter.safeAdd(prevTotal, delta));
                        folder = parents.get(folder);
                    }
                }
                for (Map.Entry<String, Long> entry : totals.entrySet()) {
                    String folder = entry.getKey();
                    long delta = entry.getValue();
                    FileResultNode node = nodes.get(folder);
                    if (node != null) {
                        nodes.put(folder, node.withSizeBytes(SizeFormatter.safeAdd(node.getSizeBytes(), delta)));
                        changes.add(folder);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public FileResultNode node(String path) {
        if (path == null) {
            return null;
        }
        String key = PathPolicy.pathKey(path);
        lock.lock();
        try {
            return nodes.get(key);
        } finally {
            lock.unlock();
        }
    }

    public Page childrenPage(String parent, int offset, int limit) {
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("Page offset and limit must be nonnegative");
        }
        String parentKey = parent == null ? null : PathPolicy.pathKey(parent);
        lock.lock();
        try {
            List<String> keys = children.get(parentKey);
            if (keys == null || keys.isEmpty()) {
                return new Page(0, Collections.emptyList());
            }
            int total = keys.size();
            if (offset >= total || limit == 0) {
                return new Page(total, Collections.emptyList());
            }
            int end = Math.min(total, offset + limit);
            List<FileResultNode> pageNodes = new ArrayList<>(end - offset);
            for (int i = offset; i < end; i++) {
                FileResultNode n = nodes.get(keys.get(i));
                if (n != null) {
                    pageNodes.add(n);
                }
            }
            return new Page(total, Collections.unmodifiableList(pageNodes));
        } finally {
            lock.unlock();
        }
    }

    public List<FileResultNode> children(String parent) {
        return childrenPage(parent, 0, 200).getNodes();
    }

    public List<FileResultNode> children(String parent, int offset, int limit) {
        return childrenPage(parent, offset, limit).getNodes();
    }

    public int childCount(String parent) {
        String parentKey = parent == null ? null : PathPolicy.pathKey(parent);
        lock.lock();
        try {
            List<String> keys = children.get(parentKey);
            return keys == null ? 0 : keys.size();
        } finally {
            lock.unlock();
        }
    }

    public List<String> takeChanges(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        lock.lock();
        try {
            if (changes.isEmpty()) {
                return Collections.emptyList();
            }
            int count = Math.min(limit, changes.size());
            List<String> taken = new ArrayList<>(count);
            Iterator<String> it = changes.iterator();
            for (int i = 0; i < count && it.hasNext(); i++) {
                taken.add(it.next());
                it.remove();
            }
            return Collections.unmodifiableList(taken);
        } finally {
            lock.unlock();
        }
    }
}
