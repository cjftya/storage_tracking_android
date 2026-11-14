package com.core.storagetracking.ui;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.core.storagetracking.R;

/**
 * RecyclerView adapter for rendering visible tree nodes and "Load next 200…" pagination rows.
 */
public final class TreeNodeAdapter extends ListAdapter<TreeRowItem, TreeNodeAdapter.ViewHolder> {

    public interface OnRowClickListener {
        void onRowClick(TreeRowItem item);
    }

    public interface OnFolderLongClickListener {
        void onFolderLongClick(View anchor, TreeRowItem item);
    }

    private static final DiffUtil.ItemCallback<TreeRowItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<TreeRowItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull TreeRowItem oldItem, @NonNull TreeRowItem newItem) {
                    return oldItem.getRowId().equals(newItem.getRowId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull TreeRowItem oldItem, @NonNull TreeRowItem newItem) {
                    return oldItem.equals(newItem);
                }
            };

    private final OnRowClickListener clickListener;
    private final OnFolderLongClickListener longClickListener;

    public TreeNodeAdapter(
            OnRowClickListener clickListener,
            OnFolderLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tree_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TreeRowItem item = getItem(position);
        holder.bind(item, position, clickListener, longClickListener);
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout rowRoot;
        private final TextView indicator;
        private final TextView name;
        private final TextView size;
        private final TextView bytes;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.rowRoot = itemView.findViewById(R.id.row_root);
            this.indicator = itemView.findViewById(R.id.text_row_indicator);
            this.name = itemView.findViewById(R.id.text_row_name);
            this.size = itemView.findViewById(R.id.text_row_size);
            this.bytes = itemView.findViewById(R.id.text_row_bytes);
        }

        void bind(
                TreeRowItem item,
                int position,
                OnRowClickListener clickListener,
                OnFolderLongClickListener longClickListener) {
            float density = itemView.getResources().getDisplayMetrics().density;
            int depth = item.getDepth();
            int indentDp = Math.min(depth, 5) * 10 + Math.max(0, Math.min(depth - 5, 5)) * 6 + 4;
            int indentPx = (int) (indentDp * density);
            int rightPx = (int) (8 * density);
            int vertPx = (int) (4 * density);
            rowRoot.setPadding(indentPx, vertPx, rightPx, vertPx);

            int bgColorRes;
            if (item.isSelected()) {
                bgColorRes = R.color.row_selected;
            } else {
                bgColorRes = (position % 2 == 0) ? R.color.row_light : R.color.row_dark;
            }
            rowRoot.setBackgroundColor(ContextCompat.getColor(itemView.getContext(), bgColorRes));

            if (item.isLoadMoreRow()) {
                indicator.setText("＋");
                name.setText(item.getName());
                name.setTypeface(null, Typeface.BOLD);
                name.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.primary));
                size.setText("");
                bytes.setText("");
            } else if (item.isDirectory()) {
                indicator.setText(item.isExpanded() ? "▼" : "▶");
                name.setText(item.getName());
                name.setTypeface(null, Typeface.BOLD);
                name.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.text_primary));
                size.setText(item.getFormattedSize());
                bytes.setText(item.getFormattedBytes());
            } else {
                indicator.setText("•");
                name.setText(item.getName());
                name.setTypeface(null, Typeface.NORMAL);
                name.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.text_primary));
                size.setText(item.getFormattedSize());
                bytes.setText(item.getFormattedBytes());
            }

            rowRoot.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onRowClick(item);
                }
            });

            if (item.isDirectory() && !item.isLoadMoreRow()) {
                rowRoot.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onFolderLongClick(v, item);
                        return true;
                    }
                    return false;
                });
            } else {
                rowRoot.setOnLongClickListener(null);
            }
        }
    }
}
