package com.core.storagetracking.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.core.storagetracking.R;
import com.core.storagetracking.scan.common.PathPolicy;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeInfo;
import com.core.storagetracking.storage.StorageVolumeProvider;

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
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_folder_picker, null, false);
        LinearLayout volumeButtonsLayout = view.findViewById(R.id.layout_volume_buttons);
        EditText editPath = view.findViewById(R.id.edit_current_folder_path);
        Button buttonUp = view.findViewById(R.id.button_folder_up);
        TextView subfoldersLabel = view.findViewById(R.id.text_subfolders_label);
        ListView listSubfolders = view.findViewById(R.id.list_subfolders);

        List<File> currentSubdirs = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_list_item_1,
                new ArrayList<>());
        listSubfolders.setAdapter(adapter);

        Runnable refreshSubdirs = () -> {
            String pathText = editPath.getText().toString().trim();
            currentSubdirs.clear();
            adapter.clear();
            if (pathText.isEmpty()) {
                subfoldersLabel.setText("Select a storage volume above");
                adapter.notifyDataSetChanged();
                return;
            }
            File dir = new File(pathText);
            if (!dir.exists() || !dir.isDirectory()) {
                subfoldersLabel.setText("Folder does not exist or is inaccessible");
                adapter.notifyDataSetChanged();
                return;
            }
            File[] children = dir.listFiles();
            if (children == null) {
                subfoldersLabel.setText("Subfolders (access restricted or permission needed)");
                adapter.notifyDataSetChanged();
                return;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            List<String> names = new ArrayList<>();
            for (File child : children) {
                try {
                    if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                        currentSubdirs.add(child);
                        names.add("📁 " + child.getName());
                    }
                } catch (Throwable ignored) {
                }
            }
            subfoldersLabel.setText("Subfolders (" + names.size() + ")");
            adapter.addAll(names);
            adapter.notifyDataSetChanged();
        };

        List<StorageVolumeInfo> volumes = discovery != null
                ? discovery.getVolumes()
                : Collections.emptyList();
        for (StorageVolumeInfo vol : volumes) {
            Button volBtn = new Button(context);
            volBtn.setAllCaps(false);
            volBtn.setText(vol.getLabel() + " (" + vol.getPath() + ")");
            volBtn.setTextSize(12f);
            volBtn.setOnClickListener(v -> {
                editPath.setText(vol.getPath());
                refreshSubdirs.run();
            });
            volumeButtonsLayout.addView(volBtn);
        }

        String startPath = initialFolder;
        if (startPath == null || startPath.isEmpty()) {
            startPath = StorageVolumeProvider.resolveDefaultFolder(discovery);
        }
        if (startPath != null) {
            editPath.setText(startPath);
        }
        refreshSubdirs.run();

        buttonUp.setOnClickListener(v -> {
            String cur = editPath.getText().toString().trim();
            if (!cur.isEmpty()) {
                String parent = PathPolicy.parentKey(cur);
                if (parent != null) {
                    editPath.setText(parent);
                    refreshSubdirs.run();
                }
            }
        });

        listSubfolders.setOnItemClickListener((parent, itemView, position, id) -> {
            if (position >= 0 && position < currentSubdirs.size()) {
                File chosen = currentSubdirs.get(position);
                editPath.setText(PathPolicy.pathKey(chosen.getAbsolutePath()));
                refreshSubdirs.run();
            }
        });

        new AlertDialog.Builder(context)
                .setTitle(R.string.action_select_folder)
                .setView(view)
                .setPositiveButton("Select this folder", (dialog, which) -> {
                    String chosenPath = editPath.getText().toString().trim();
                    if (!chosenPath.isEmpty() && onFolderSelected != null) {
                        onFolderSelected.accept(chosenPath);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
