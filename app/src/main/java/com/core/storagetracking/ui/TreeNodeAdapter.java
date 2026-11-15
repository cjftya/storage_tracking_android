package com.core.storagetracking.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.core.storagetracking.R;

/**
 * RecyclerView adapter for rendering visible tree nodes, usage proportion bars,
 * and "Load next 200…" pagination rows.
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
        private final ImageView chevron;
        private final ImageView icon;
        private final TextView name;
        private final ProgressBar proportionBar;
        private final TextView size;
        private final TextView bytes;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.rowRoot = itemView.findViewById(R.id.row_root);
            this.chevron = itemView.findViewById(R.id.image_row_chevron);
            this.icon = itemView.findViewById(R.id.image_row_icon);
            this.name = itemView.findViewById(R.id.text_row_name);
            this.proportionBar = itemView.findViewById(R.id.progress_row_share);
            this.size = itemView.findViewById(R.id.text_row_size);
            this.bytes = itemView.findViewById(R.id.text_row_bytes);
        }

        void bind(
                TreeRowItem item,
                int position,
                OnRowClickListener clickListener,
                OnFolderLongClickListener longClickListener) {
            Context context = itemView.getContext();
            float density = itemView.getResources().getDisplayMetrics().density;
            int depth = item.getDepth();
            int indentDp = Math.min(depth, 5) * 12 + Math.max(0, Math.min(depth - 5, 5)) * 6 + 8;
            int indentPx = (int) (indentDp * density);
            int rightPx = (int) (12 * density);
            int vertPx = (int) (6 * density);
            rowRoot.setPadding(indentPx, vertPx, rightPx, vertPx);

            int bgColorRes;
            if (item.isSelected()) {
                bgColorRes = R.color.row_selected;
            } else {
                bgColorRes = (position % 2 == 0) ? R.color.row_light : R.color.row_dark;
            }
            rowRoot.setBackgroundColor(ContextCompat.getColor(context, bgColorRes));

            if (item.isLoadMoreRow()) {
                chevron.setVisibility(View.INVISIBLE);
                icon.setImageResource(R.drawable.ic_add);
                ImageViewCompat.setImageTintList(
                        icon, ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary)));
                name.setText(context.getString(R.string.load_next_200));
                name.setTypeface(null, Typeface.BOLD);
                name.setTextColor(ContextCompat.getColor(context, R.color.primary));
                proportionBar.setVisibility(View.GONE);
                size.setText("");
                bytes.setText("");
            } else if (item.isDirectory()) {
                chevron.setVisibility(View.VISIBLE);
                chevron.setImageResource(item.isExpanded() ? R.drawable.ic_expand_more : R.drawable.ic_chevron_right);
                icon.setImageResource(item.isExpanded() ? R.drawable.ic_folder_open : R.drawable.ic_folder);
                ImageViewCompat.setImageTintList(
                        icon, ColorStateList.valueOf(ContextCompat.getColor(context, R.color.folder_icon_tint)));
                name.setText(item.getName());
                name.setTypeface(null, Typeface.BOLD);
                name.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                bindProportionBar(item.getParentRatio());
                size.setText(item.getFormattedSize());
                bytes.setText(item.getFormattedBytes());
            } else {
                chevron.setVisibility(View.INVISIBLE);
                icon.setImageResource(R.drawable.ic_file);
                ImageViewCompat.setImageTintList(
                        icon, ColorStateList.valueOf(ContextCompat.getColor(context, R.color.file_icon_tint)));
                name.setText(item.getName());
                name.setTypeface(null, Typeface.NORMAL);
                name.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                bindProportionBar(item.getParentRatio());
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

        private void bindProportionBar(float ratio) {
            if (ratio < 0f) {
                proportionBar.setVisibility(View.GONE);
                return;
            }
            proportionBar.setVisibility(View.VISIBLE);
            int progress = Math.max(0, Math.min(1000, Math.round(ratio * 1000f)));
            proportionBar.setProgress(progress);
        }
    }
}
