package com.core.storagetracking.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.storagetracking.R;
import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeInfo;
import com.core.storagetracking.storage.StorageVolumeProvider;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Native read-only folder picker dialog allowing selection of a storage volume root
 * (which Android 11+ SAF restricts) or any accessible subdirectory.
 */
public final class FolderPickerDialog {

    private FolderPickerDialog() {
    }

    public static void show(
            Context context,
            StorageDiscovery discovery,
            String initialFolder,
            Consumer<String> onFolderSelected) {
        show(
                context,
                discovery,
                initialFolder,
                context.getString(R.string.action_select_folder),
                context.getString(R.string.dialog_select_target_confirm),
                onFolderSelected);
    }

    public static void show(
            Context context,
            StorageDiscovery discovery,
            String initialFolder,
            String dialogTitle,
            String confirmLabel,
            Consumer<String> onFolderSelected) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_folder_picker, null, false);
        ChipGroup volumeChipGroup = view.findViewById(R.id.chip_group_volumes);
        EditText editPath = view.findViewById(R.id.edit_current_folder_path);
        Button buttonUp = view.findViewById(R.id.button_folder_up);
        TextView subfoldersLabel = view.findViewById(R.id.text_subfolders_label);
        RecyclerView recyclerSubfolders = view.findViewById(R.id.recycler_subfolders);
        TextView textEmpty = view.findViewById(R.id.text_picker_empty);

        List<File> currentSubdirs = new ArrayList<>();
        SubfolderAdapter adapter = new SubfolderAdapter(currentSubdirs, chosen -> {
            editPath.setText(PathPolicy.pathKey(chosen.getAbsolutePath()));
            editPath.setSelection(editPath.getText().length());
            // Trigger refresh via tag/callback below
            Object tag = recyclerSubfolders.getTag();
            if (tag instanceof Runnable) {
                ((Runnable) tag).run();
            }
        });
        recyclerSubfolders.setLayoutManager(new LinearLayoutManager(context));
        recyclerSubfolders.setAdapter(adapter);

        Runnable refreshSubdirs = () -> {
            String pathText = editPath.getText().toString().trim();
            currentSubdirs.clear();
            if (pathText.isEmpty()) {
                subfoldersLabel.setText(R.string.picker_select_volume);
                textEmpty.setVisibility(View.VISIBLE);
                textEmpty.setText(R.string.picker_select_volume);
                adapter.notifyDataSetChanged();
                return;
            }
            File dir = new File(pathText);
            if (!dir.exists() || !dir.isDirectory()) {
                subfoldersLabel.setText(R.string.picker_folder_inaccessible);
                textEmpty.setVisibility(View.VISIBLE);
                textEmpty.setText(R.string.picker_folder_inaccessible);
                adapter.notifyDataSetChanged();
                return;
            }
            File[] children = dir.listFiles();
            if (children == null) {
                subfoldersLabel.setText(R.string.picker_subfolders_restricted);
                textEmpty.setVisibility(View.VISIBLE);
                textEmpty.setText(R.string.picker_access_restricted);
                adapter.notifyDataSetChanged();
                return;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                try {
                    if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                        currentSubdirs.add(child);
                    }
                } catch (Throwable ignored) {
                }
            }
            subfoldersLabel.setText(context.getString(R.string.picker_subfolders_count, currentSubdirs.size()));
            textEmpty.setText(R.string.picker_empty_subfolders);
            textEmpty.setVisibility(currentSubdirs.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
        };
        recyclerSubfolders.setTag(refreshSubdirs);

        List<StorageVolumeInfo> volumes = discovery != null
                ? discovery.getVolumes()
                : Collections.emptyList();
        for (StorageVolumeInfo vol : volumes) {
            Chip chip = new Chip(context);
            chip.setText(vol.getLabel());
            chip.setChipIconResource(R.drawable.ic_storage);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setOnClickListener(v -> {
                editPath.setText(vol.getPath());
                editPath.setSelection(editPath.getText().length());
                refreshSubdirs.run();
            });
            volumeChipGroup.addView(chip);
        }

        String startPath = initialFolder;
        if (startPath == null || startPath.isEmpty()) {
            startPath = StorageVolumeProvider.resolveDefaultFolder(discovery);
        }
        if (startPath != null) {
            editPath.setText(startPath);
            editPath.setSelection(editPath.getText().length());
        }
        refreshSubdirs.run();

        buttonUp.setOnClickListener(v -> {
            String cur = editPath.getText().toString().trim();
            if (!cur.isEmpty()) {
                String parent = PathPolicy.parentKey(cur);
                if (parent != null) {
                    editPath.setText(parent);
                    editPath.setSelection(editPath.getText().length());
                    refreshSubdirs.run();
                }
            }
        });

        new MaterialAlertDialogBuilder(context)
                .setTitle(dialogTitle)
                .setView(view)
                .setPositiveButton(confirmLabel, (dialog, which) -> {
                    String chosenPath = editPath.getText().toString().trim();
                    if (!chosenPath.isEmpty() && onFolderSelected != null) {
                        onFolderSelected.accept(chosenPath);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static final class SubfolderAdapter extends RecyclerView.Adapter<SubfolderAdapter.Holder> {
        private final List<File> items;
        private final Consumer<File> onItemClick;

        SubfolderAdapter(List<File> items, Consumer<File> onItemClick) {
            this.items = items;
            this.onItemClick = onItemClick;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_picker_folder, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            File dir = items.get(position);
            holder.name.setText(dir.getName());
            holder.itemView.setOnClickListener(v -> onItemClick.accept(dir));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView name;

            Holder(@NonNull View itemView) {
                super(itemView);
                this.name = itemView.findViewById(R.id.text_picker_folder_name);
            }
        }
    }
}
