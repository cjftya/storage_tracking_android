package com.core.storagetracking.ui;

import com.core.storagetracking.scan.file.FileResultNode;
import com.core.storagetracking.scan.file.FileResultStore;
import com.core.storagetracking.util.SizeFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent UI tree state for File Scan mode.
 * Preserves discovery order, supports lazy folder expansion and 200-item paging ("Load next 200…"),
 * and never shares state or models with FolderTreeState.
 */
public final class FileTreeState {
    public static final int PAGE_SIZE = 200;
    public static final int INSERT_BUDGET = 150;
    private static final long TIME_BUDGET_NANOS = 12_000_000L;

    private FileResultStore store;
    private Integer runId;
    private int generation = 0;

    private final Set<String> expandedFolders = new HashSet<>();
    private final Set<String> directories = new HashSet<>();
    private final Map<String, Integer> offsets = new HashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();
    private final Map<String, LinkedHashSet<String>> loadedChildKeys = new HashMap<>();
    private final Map<String, Integer> totalChildCounts = new HashMap<>();
    private final LinkedList<String> pending = new LinkedList<>();
    private final Set<String> queued = new HashSet<>();
    private final LinkedList<String> dirty = new LinkedList<>();
    private boolean pollChangesRequested = false;
    private String selectedPath = null;

    public FileTreeState() {
        reset(null, null);
    }

    public void reset(FileResultStore newStore, Integer newRunId) {
        generation++;
        this.store = newStore;
        this.runId = newRunId;
        this.expandedFolders.clear();
        this.directories.clear();
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

    public FileResultStore getStore() {
        return store;
    }

    public String getSizeHeadingText() {
        return "Matched size";
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
        if (path == null || store == null || !directories.contains(path)) {
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
        if (path == null || store == null || !directories.contains(path)) {
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
        return store != null && (!pending.isEmpty() || !dirty.isEmpty() || pollChangesRequested);
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
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        int inserted = 0;
        int work = 0;

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
            FileResultStore.Page page = store.childrenPage(path, offset, Math.max(0, amount));
            int total = page.getTotalCount();
            totalChildCounts.put(path, total);
            List<FileResultNode> nodes = page.getNodes();

            if (!nodes.isEmpty()) {
                LinkedHashSet<String> existingSet = loadedChildKeys.computeIfAbsent(path, k -> new LinkedHashSet<>());
                for (FileResultNode node : nodes) {
                    if (work >= INSERT_BUDGET || System.nanoTime() >= deadline) {
                        break;
                    }
                    work++;
                    offset++;
                    offsets.put(path, offset);
                    boolean wasNew = existingSet.add(node.getPath());
                    if (node.isDirectory()) {
                        directories.add(node.getPath());
                        offsets.putIfAbsent(node.getPath(), 0);
                        limits.putIfAbsent(node.getPath(), PAGE_SIZE);
                    }
                    if (wasNew) {
                        inserted++;
                    }
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
                FileResultNode node = store.node(childKey);
                if (node == null) {
                    continue;
                }
                boolean isDir = node.isDirectory();
                boolean expanded = isDir && expandedFolders.contains(childKey);
                boolean selected = childKey.equals(selectedPath);
                out.add(new TreeRowItem(
                        childKey,
                        childKey,
                        node.getName(),
                        depth,
                        isDir,
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
