package com.core.storagetracking.scan.folder;

import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.util.SizeFormatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Folder-only RAM index. Every direct-byte delta reaches each ancestor once.
 * Stores O(result folders) only, never individual file objects.
 */
public final class FolderResultStore {
    public static final String STATE_SCANNING = "scanning";
    public static final String STATE_COMPLETE = "complete";
    public static final String STATE_PARTIAL = "partial";

    public static final class Page {
        private final int totalCount;
        private final List<FolderNode> nodes;

        public Page(int totalCount, List<FolderNode> nodes) {
            this.totalCount = totalCount;
            this.nodes = nodes;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public List<FolderNode> getNodes() {
            return nodes;
        }
    }

    private final List<String> roots;
    private final Map<String, FolderNode> nodes = new HashMap<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Set<String> changes = new LinkedHashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private String state = STATE_SCANNING;
    private boolean finalized = false;

    public FolderResultStore(Collection<String> configuredRoots) {
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
        this.children.put(null, new ArrayList<>());
        for (String root : unique) {
            ensure(root, root);
        }
    }

    public int getFolderCount() {
        lock.lock();
        try {
            return nodes.size();
        } finally {
            lock.unlock();
        }
    }

    public String getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public boolean isFinalized() {
        lock.lock();
        try {
            return finalized;
        } finally {
            lock.unlock();
        }
    }

    private void ensure(String path, String root) {
        List<String> missing = new ArrayList<>();
        String current = path;
        while (!nodes.containsKey(current)) {
            missing.add(current);
            if (current.equals(root)) {
                break;
            }
            String parent = PathPolicy.parentKey(current);
            if (parent == null) {
                break;
            }
            current = parent;
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            String folder = missing.get(i);
            String parent = folder.equals(root) ? null : PathPolicy.parentKey(folder);
            String displayName = parent == null ? folder : PathPolicy.baseName(folder);
            nodes.put(folder, new FolderNode(folder, displayName, 0L, 0L));
            children.put(folder, new ArrayList<>());
            parents.put(folder, parent);
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(folder);
            changes.add(parent);
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

    public void addBatch(List<DirectoryDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        for (DirectoryDelta delta : deltas) {
            if (delta == null) {
                continue;
            }
            String key = PathPolicy.pathKey(delta.getPath());
            String root = findOwningRoot(key);
            if (root == null || delta.getDirectBytes() < 0L) {
                throw new IllegalArgumentException("Invalid directory delta: " + delta.getPath());
            }
            if (delta.getDirectBytes() == 0L) {
                continue;
            }
            lock.lock();
            try {
                if (finalized) {
                    throw new IllegalStateException("Folder results are finalized");
                }
                ensure(key, root);
                FolderNode target = nodes.get(key);
                nodes.put(key, target.withDirectBytes(
                        SizeFormatter.safeAdd(target.getDirectBytes(), delta.getDirectBytes())));
                String cursor = key;
                while (cursor != null) {
                    FolderNode current = nodes.get(cursor);
                    nodes.put(cursor, current.withSizeBytes(
                            SizeFormatter.safeAdd(current.getSizeBytes(), delta.getDirectBytes())));
                    changes.add(cursor);
                    cursor = parents.get(cursor);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public void finish(boolean complete) {
        List<String> parentKeys;
        lock.lock();
        try {
            parentKeys = new ArrayList<>(children.keySet());
        } finally {
            lock.unlock();
        }

        for (String parent : parentKeys) {
            List<String> copy;
            Map<String, Long> sizeSnapshot = new HashMap<>();
            lock.lock();
            try {
                List<String> existing = children.get(parent);
                if (existing == null || existing.size() <= 1) {
                    if (existing != null) {
                        changes.add(parent);
                    }
                    continue;
                }
                copy = new ArrayList<>(existing);
                for (String childKey : copy) {
                    FolderNode node = nodes.get(childKey);
                    sizeSnapshot.put(childKey, node != null ? node.getSizeBytes() : 0L);
                }
            } finally {
                lock.unlock();
            }

            copy.sort((a, b) -> {
                long sizeA = sizeSnapshot.getOrDefault(a, 0L);
                long sizeB = sizeSnapshot.getOrDefault(b, 0L);
                int cmp = Long.compare(sizeB, sizeA);
                return cmp != 0 ? cmp : a.compareTo(b);
            });

            lock.lock();
            try {
                children.put(parent, copy);
                changes.add(parent);
            } finally {
                lock.unlock();
            }
        }

        lock.lock();
        try {
            this.state = complete ? STATE_COMPLETE : STATE_PARTIAL;
            this.finalized = true;
        } finally {
            lock.unlock();
        }
    }

    public FolderNode node(String path) {
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
            List<FolderNode> pageNodes = new ArrayList<>(end - offset);
            for (int i = offset; i < end; i++) {
                FolderNode n = nodes.get(keys.get(i));
                if (n != null) {
                    pageNodes.add(n);
                }
            }
            return new Page(total, Collections.unmodifiableList(pageNodes));
        } finally {
            lock.unlock();
        }
    }

    public List<FolderNode> children(String parent) {
        return childrenPage(parent, 0, 200).getNodes();
    }

    public List<FolderNode> children(String parent, int offset, int limit) {
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
