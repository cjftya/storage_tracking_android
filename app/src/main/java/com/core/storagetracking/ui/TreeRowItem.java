package com.core.storagetracking.ui;

import java.util.Objects;

/**
 * Immutable visible row model rendered by the result RecyclerView.
 */
public final class TreeRowItem {
    private final String rowId;
    private final String path;
    private final String name;
    private final int depth;
    private final boolean directory;
    private final boolean expanded;
    private final boolean loadMoreRow;
    private final boolean selected;
    private final String formattedSize;
    private final String formattedBytes;
    private final float parentRatio;

    public TreeRowItem(
            String rowId,
            String path,
            String name,
            int depth,
            boolean directory,
            boolean expanded,
            boolean loadMoreRow,
            boolean selected,
            String formattedSize,
            String formattedBytes) {
        this(rowId, path, name, depth, directory, expanded, loadMoreRow, selected,
                formattedSize, formattedBytes, -1f);
    }

    public TreeRowItem(
            String rowId,
            String path,
            String name,
            int depth,
            boolean directory,
            boolean expanded,
            boolean loadMoreRow,
            boolean selected,
            String formattedSize,
            String formattedBytes,
            float parentRatio) {
        this.rowId = rowId;
        this.path = path;
        this.name = name;
        this.depth = depth;
        this.directory = directory;
        this.expanded = expanded;
        this.loadMoreRow = loadMoreRow;
        this.selected = selected;
        this.formattedSize = formattedSize == null ? "" : formattedSize;
        this.formattedBytes = formattedBytes == null ? "" : formattedBytes;
        this.parentRatio = parentRatio;
    }

    public String getRowId() {
        return rowId;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public int getDepth() {
        return depth;
    }

    public boolean isDirectory() {
        return directory;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public boolean isLoadMoreRow() {
        return loadMoreRow;
    }

    public boolean isSelected() {
        return selected;
    }

    public String getFormattedSize() {
        return formattedSize;
    }

    public String getFormattedBytes() {
        return formattedBytes;
    }

    public float getParentRatio() {
        return parentRatio;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TreeRowItem)) return false;
        TreeRowItem that = (TreeRowItem) o;
        return depth == that.depth
                && directory == that.directory
                && expanded == that.expanded
                && loadMoreRow == that.loadMoreRow
                && selected == that.selected
                && Float.compare(that.parentRatio, parentRatio) == 0
                && Objects.equals(rowId, that.rowId)
                && Objects.equals(path, that.path)
                && Objects.equals(name, that.name)
                && Objects.equals(formattedSize, that.formattedSize)
                && Objects.equals(formattedBytes, that.formattedBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                rowId, path, name, depth, directory, expanded, loadMoreRow, selected, formattedSize, formattedBytes, parentRatio);
    }
}
