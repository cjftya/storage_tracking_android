package com.core.storagetracking.ui;

import com.core.storagetracking.scan.folder.FolderNode;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.util.SizeFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent UI tree state for Folder Scan mode.
 * Implements lazy folder expansion, 200-item paging ("Load next 200…"),
 * bounded change processing, and post-finalization descending size ordering
 * while preserving selection and expanded folders.
 */
public final class FolderTreeState {
    public static final int PAGE_SIZE = 200;
    public static final int INSERT_BUDGET = 150;
    private static final long TIME_BUDGET_NANOS = 12_000_000L;

    private FolderResultStore store;
    private Integer runId;
    private int generation = 0;
    private boolean sorted = false;
    private Iterator<String> sortPathsIterator = null;

    private final Set<String> expandedFolders = new HashSet<>();
    private final Map<String, Integer> offsets = new HashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();
    private final Map<String, LinkedHashSet<String>> loadedChildKeys = new HashMap<>();
    private final Map<String, Integer> totalChildCounts = new HashMap<>();
    private final LinkedList<String> pending = new LinkedList<>();
    private final Set<String> queued = new HashSet<>();
    private final LinkedList<String> dirty = new LinkedList<>();
    private boolean pollChangesRequested = false;
    private String selectedPath = null;

    public FolderTreeState() {
        reset(null, null);
    }

    public void reset(FolderResultStore newStore, Integer newRunId) {
        generation++;
        this.store = newStore;
        this.runId = newRunId;
        this.sorted = false;
        this.sortPathsIterator = null;
        this.expandedFolders.clear();
        this.offsets.clear();
        this.limits.clear();
        this.loadedChildKeys.clear();
        this.totalChildCounts.clear();
        this.pending.clear();
        this.queued.clear();
        this.dirty.clear();
        this.pollChangesRequested = false;
        this.selectedPath = null;

        this.offsets.put(null, 0);
        this.limits.put(null, PAGE_SIZE);
        this.loadedChildKeys.put(null, new LinkedHashSet<>());
        if (newStore != null) {
            enqueue(null);
        }
    }

    public int getGeneration() {
        return generation;
    }

    public Integer getRunId() {
        return runId;
    }

    public FolderResultStore getStore() {
        return store;
    }

    public boolean isSorted() {
        return sorted;
    }

    public String getSizeHeadingText() {
        if (store == null || !sorted) {
            return "Logical size (aggregating)";
        }
        return FolderResultStore.STATE_COMPLETE.equals(store.getState())
                ? "Logical size"
                : "Logical size (partial)";
    }

    public String getSelectedPath() {
        return selectedPath;
    }

    public void selectPath(String path) {
        this.selectedPath = path;
    }

    public boolean isExpanded(String path) {
        return expandedFolders.contains(path);
    }

    public void toggleExpand(String path) {
        if (path == null || store == null) {
            return;
        }
        selectedPath = path;
        if (expandedFolders.contains(path)) {
            expandedFolders.remove(path);
        } else {
            expandedFolders.add(path);
            offsets.putIfAbsent(path, 0);
            limits.putIfAbsent(path, PAGE_SIZE);
            loadedChildKeys.putIfAbsent(path, new LinkedHashSet<>());
            enqueue(path);
            refreshAllPending();
        }
    }

    public void expand(String path) {
        if (path == null || store == null) {
            return;
        }
        if (expandedFolders.add(path)) {
            offsets.putIfAbsent(path, 0);
            limits.putIfAbsent(path, PAGE_SIZE);
            loadedChildKeys.putIfAbsent(path, new LinkedHashSet<>());
            enqueue(path);
            refreshAllPending();
        }
    }

    public void loadMore(String parentPath) {
        if (store == null) {
            return;
        }
        int currentLimit = limits.getOrDefault(parentPath, PAGE_SIZE);
        limits.put(parentPath, currentLimit + PAGE_SIZE);
        enqueue(parentPath);
        refreshAllPending();
    }

    public void pollChanges() {
        if (store != null) {
            pollChangesRequested = true;
            refresh();
        }
    }

    public boolean hasPendingWork() {
        return store != null && (sortPathsIterator != null || !pending.isEmpty() || !dirty.isEmpty() || pollChangesRequested);
    }

    public void refreshAllPending() {
        int guard = 0;
        while (hasPendingWork() && guard < 200) {
            refresh();
            guard++;
        }
    }

    public int refresh() {
        if (store == null) {
            return 0;
        }
        if (store.isFinalized() && !sorted) {
            sorted = true;
            List<String> keysToResort = new ArrayList<>(offsets.keySet());
            sortPathsIterator = keysToResort.iterator();
        }

        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        int inserted = 0;
        int work = 0;

        while (sortPathsIterator != null && work < INSERT_BUDGET && System.nanoTime() < deadline) {
            if (sortPathsIterator.hasNext()) {
                String path = sortPathsIterator.next();
                offsets.put(path, 0);
                enqueue(path);
                work++;
            } else {
                sortPathsIterator = null;
                break;
            }
        }
        if (sortPathsIterator != null) {
            return 0;
        }

        while (work < INSERT_BUDGET && System.nanoTime() < deadline) {
            if (pollChangesRequested && dirty.isEmpty()) {
                List<String> changes = store.takeChanges(32);
                dirty.addAll(changes);
                if (dirty.isEmpty()) {
                    pollChangesRequested = false;
                }
            }
            if (!dirty.isEmpty()) {
                String path = dirty.pollFirst();
                if (path == null || expandedFolders.contains(path)) {
                    enqueue(path);
                }
                work++;
            }
            if (work >= INSERT_BUDGET || System.nanoTime() >= deadline) {
                break;
            }
            if (pending.isEmpty()) {
                if (!dirty.isEmpty() || pollChangesRequested) {
                    continue;
                }
                break;
            }

            String path = pending.pollFirst();
            queued.remove(path);
            work++;
            if (path != null && !expandedFolders.contains(path)) {
                continue;
            }
            int offset = offsets.getOrDefault(path, 0);
            int limit = limits.getOrDefault(path, PAGE_SIZE);
            int amount = Math.min(INSERT_BUDGET - work, Math.min(limit - offset, 32));
            FolderResultStore.Page page = store.childrenPage(path, offset, Math.max(0, amount));
            int total = page.getTotalCount();
            totalChildCounts.put(path, total);
            List<FolderNode> nodes = page.getNodes();

            if (!nodes.isEmpty()) {
                LinkedHashSet<String> existingSet = loadedChildKeys.computeIfAbsent(path, k -> new LinkedHashSet<>());
                List<String> existingOrder = sorted && offset == 0 ? new ArrayList<>(existingSet) : Collections.emptyList();
                if (sorted && offset == 0) {
                    existingSet.clear();
                }

                for (FolderNode node : nodes) {
                    if (work >= INSERT_BUDGET || System.nanoTime() >= deadline) {
                        break;
                    }
                    work++;
                    offset++;
                    offsets.put(path, offset);
                    boolean wasNew = existingSet.add(node.getPath());
                    offsets.putIfAbsent(node.getPath(), 0);
                    limits.putIfAbsent(node.getPath(), PAGE_SIZE);
                    if (wasNew) {
                        inserted++;
                    }
                }

                if (sorted && !existingOrder.isEmpty()) {
                    // Retain previously loaded items that fell outside the new top sorted slice.
                    existingSet.addAll(existingOrder);
                }
            }

            int updatedOffset = offsets.getOrDefault(path, 0);
            if (updatedOffset < Math.min(total, limit)) {
                enqueue(path);
            }
        }
        return inserted;
    }

    public List<TreeRowItem> buildVisibleRows() {
        if (store == null) {
            return Collections.emptyList();
        }
        List<TreeRowItem> rows = new ArrayList<>();
        appendChildrenRows(null, 0, rows);
        return Collections.unmodifiableList(rows);
    }

    private void appendChildrenRows(String parentPath, int depth, List<TreeRowItem> out) {
        LinkedHashSet<String> childKeys = loadedChildKeys.get(parentPath);
        if (childKeys != null) {
            for (String childKey : childKeys) {
                FolderNode node = store.node(childKey);
                if (node == null) {
                    continue;
                }
                boolean expanded = expandedFolders.contains(childKey);
                boolean selected = childKey.equals(selectedPath);
                out.add(new TreeRowItem(
                        childKey,
                        childKey,
                        node.getName(),
                        depth,
                        true,
                        expanded,
                        false,
                        selected,
                        SizeFormatter.formatDisplaySize(node.getSizeBytes()),
                        SizeFormatter.formatBytes(node.getSizeBytes())));
                if (expanded) {
                    appendChildrenRows(childKey, depth + 1, out);
                }
            }
        }
        int offset = offsets.getOrDefault(parentPath, 0);
        int total = totalChildCounts.getOrDefault(parentPath, 0);
        int limit = limits.getOrDefault(parentPath, PAGE_SIZE);
        if (offset >= Math.min(total, limit) && total > offset) {
            String moreId = (parentPath == null ? "" : parentPath) + ":more";
            out.add(new TreeRowItem(
                    moreId,
                    parentPath,
                    "Load next 200…",
                    depth,
                    false,
                    false,
                    true,
                    false,
                    "",
                    ""));
        }
    }

    public int getOffset(String parentPath) {
        return offsets.getOrDefault(parentPath, 0);
    }

    public int getLimit(String parentPath) {
        return limits.getOrDefault(parentPath, PAGE_SIZE);
    }

    private void enqueue(String path) {
        if (!queued.contains(path)) {
            pending.addLast(path);
            queued.add(path);
        }
    }
}
