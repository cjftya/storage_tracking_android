package com.core.storagetracking.ui;

import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.storagetracking.R;
import com.core.storagetracking.storage.FolderOpener;
import com.core.storagetracking.storage.StorageAccessManager;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeProvider;
import com.core.storagetracking.viewmodel.ScannerViewModel;

/**
 * Main Activity for Storage Scanner Android v1.0.
 * Renders Folder Scan (default) and Large File Scan (optional) modes with 1,000ms periodic UI updates.
 */
public class MainActivity extends AppCompatActivity {

    private ScannerViewModel viewModel;
    private TreeNodeAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean tickScheduled = false;
    private boolean openingFolder = false;
    private boolean suppressInputListeners = false;

    private LinearLayout permissionBanner;
    private TextView textPermissionStatus;
    private Button buttonGrantAccess;
    private RadioGroup radioGroupMode;
    private RadioButton radioFolderScan;
    private RadioButton radioFileScan;
    private Button buttonRefreshDrives;
    private EditText editMinSizeMib;
    private Button buttonScanRestart;
    private Button buttonCancel;
    private TextView textSelectedFolder;
    private Button buttonSelectFolder;
    private Button buttonClearFolder;
    private Button buttonToggleExclusions;
    private LinearLayout layoutExclusionsContainer;
    private EditText editExcludedPaths;
    private TextView textModeMeaning;
    private TextView textHeadingSize;
    private RecyclerView recyclerResults;
    private TextView textStatus;
    private TextView textCurrentPath;

    private final ActivityResultLauncher<Intent> manageStorageLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        updatePermissionBanner();
                        triggerVolumeDiscovery(false);
                    });

    private final ActivityResultLauncher<String> readPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        updatePermissionBanner();
                        triggerVolumeDiscovery(false);
                    });

    private final Runnable periodicTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                tickScheduled = false;
                return;
            }
            viewModel.tick();
            renderStateFromViewModel();
            ScannerViewModel.CompletionNotice notice = viewModel.consumeCompletionNotice();
            if (notice != null) {
                showCompletionDialog(notice);
            }
            uiHandler.postDelayed(this, ScannerViewModel.DEFAULT_UPDATE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View rootLayout = findViewById(R.id.root_layout);
        applySystemBarAppearance(rootLayout);
        int basePaddingPx = (int) (10 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    safeInsets.left + basePaddingPx,
                    safeInsets.top + basePaddingPx,
                    safeInsets.right + basePaddingPx,
                    safeInsets.bottom + basePaddingPx);
            return insets;
        });

        viewModel = new ViewModelProvider(this).get(ScannerViewModel.class);
        bindViews();
        setupListeners();

        updatePermissionBanner();
        if (savedInstanceState == null && viewModel.getDiscovery().getVolumes().isEmpty()) {
            triggerVolumeDiscovery(true);
        } else {
            renderStateFromViewModel();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        updatePermissionBanner();
        startPeriodicTimer();
    }

    @Override
    protected void onStop() {
        stopPeriodicTimer();
        super.onStop();
    }

    private void bindViews() {
        permissionBanner = findViewById(R.id.permission_banner);
        textPermissionStatus = findViewById(R.id.text_permission_status);
        buttonGrantAccess = findViewById(R.id.button_grant_access);
        radioGroupMode = findViewById(R.id.radio_group_mode);
        radioFolderScan = findViewById(R.id.radio_folder_scan);
        radioFileScan = findViewById(R.id.radio_file_scan);
        buttonRefreshDrives = findViewById(R.id.button_refresh_drives);
        editMinSizeMib = findViewById(R.id.edit_min_size_mib);
        buttonScanRestart = findViewById(R.id.button_scan_restart);
        buttonCancel = findViewById(R.id.button_cancel);
        textSelectedFolder = findViewById(R.id.text_selected_folder);
        buttonSelectFolder = findViewById(R.id.button_select_folder);
        buttonClearFolder = findViewById(R.id.button_clear_folder);
        buttonToggleExclusions = findViewById(R.id.button_toggle_exclusions);
        layoutExclusionsContainer = findViewById(R.id.layout_exclusions_container);
        editExcludedPaths = findViewById(R.id.edit_excluded_paths);
        textModeMeaning = findViewById(R.id.text_mode_meaning);
        textHeadingSize = findViewById(R.id.text_heading_size);
        recyclerResults = findViewById(R.id.recycler_results);
        textStatus = findViewById(R.id.text_status);
        textCurrentPath = findViewById(R.id.text_current_path);

        adapter = new TreeNodeAdapter(
                item -> {
                    viewModel.onRowClicked(item);
                    renderStateFromViewModel();
                },
                this::showFolderContextMenu);
        recyclerResults.setLayoutManager(new LinearLayoutManager(this));
        recyclerResults.setItemAnimator(null);
        recyclerResults.setAdapter(adapter);
    }

    private void setupListeners() {
        buttonGrantAccess.setOnClickListener(v -> requestStorageAccess());

        radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressInputListeners) {
                return;
            }
            String mode = (checkedId == R.id.radio_file_scan)
                    ? ScannerViewModel.MODE_FILE
                    : ScannerViewModel.MODE_FOLDER;
            viewModel.changeMode(mode);
            renderStateFromViewModel();
        });

        editMinSizeMib.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressInputListeners) {
                    viewModel.setMinSizeMibText(s.toString());
                }
            }
        });

        editExcludedPaths.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressInputListeners) {
                    viewModel.setExcludedPathsText(s.toString());
                }
            }
        });

        buttonRefreshDrives.setOnClickListener(v -> triggerVolumeDiscovery(false));

        buttonSelectFolder.setOnClickListener(v -> FolderPickerDialog.show(
                this,
                viewModel.getDiscovery(),
                viewModel.getSelectedFolder(),
                folder -> {
                    viewModel.selectFolder(folder);
                    renderStateFromViewModel();
                }));

        buttonClearFolder.setOnClickListener(v -> {
            viewModel.clearSelectedFolder();
            renderStateFromViewModel();
        });

        buttonToggleExclusions.setOnClickListener(v -> {
            boolean currentlyVisible = layoutExclusionsContainer.getVisibility() == View.VISIBLE;
            layoutExclusionsContainer.setVisibility(currentlyVisible ? View.GONE : View.VISIBLE);
        });

        buttonScanRestart.setOnClickListener(v -> {
            try {
                viewModel.startScan();
                renderStateFromViewModel();
            } catch (IllegalArgumentException | IllegalStateException e) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_invalid_settings)
                        .setMessage(e.getMessage())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        buttonCancel.setOnClickListener(v -> {
            viewModel.cancelScan();
            renderStateFromViewModel();
        });
    }

    private void updatePermissionBanner() {
        boolean granted = StorageAccessManager.hasStorageAccess(this);
        if (granted) {
            permissionBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.ok_bg));
            textPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.ok_text));
            textPermissionStatus.setText(R.string.permission_granted);
            buttonGrantAccess.setVisibility(View.GONE);
        } else {
            permissionBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.warning_bg));
            textPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_text));
            textPermissionStatus.setText(R.string.permission_required);
            buttonGrantAccess.setVisibility(View.VISIBLE);
        }
    }

    private void requestStorageAccess() {
        if (StorageAccessManager.requiresSpecialAllFilesAccess()) {
            try {
                manageStorageLauncher.launch(StorageAccessManager.createManageAppAllFilesIntent(this));
            } catch (Exception e) {
                try {
                    manageStorageLauncher.launch(StorageAccessManager.createFallbackSettingsIntent(this));
                } catch (Exception ex) {
                    new AlertDialog.Builder(this)
                            .setTitle("Permission settings")
                            .setMessage("Please enable All files access for Storage Scanner in Android Settings.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void triggerVolumeDiscovery(boolean initial) {
        viewModel.markDiscovering();
        renderStateFromViewModel();

        Thread discoveryThread = new Thread(() -> {
            try {
                StorageDiscovery disc = StorageVolumeProvider.discoverVolumes(getApplicationContext());
                uiHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        viewModel.setDiscovery(disc, initial);
                        renderStateFromViewModel();
                    }
                });
            } catch (Throwable t) {
                uiHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        viewModel.setDiscoveryFailed(t.getMessage());
                        renderStateFromViewModel();
                    }
                });
            }
        }, "volume-discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private void showFolderContextMenu(View anchor, TreeRowItem item) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.action_open_folder));
        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == 1) {
                openFolderAsync(item.getPath());
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void openFolderAsync(String path) {
        if (openingFolder) {
            return;
        }
        openingFolder = true;
        Thread thread = new Thread(() -> {
            String error = null;
            try {
                FolderOpener.openFolder(getApplicationContext(), path);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String finalError = error;
            uiHandler.post(() -> {
                openingFolder = false;
                if (finalError != null && !isFinishing() && !isDestroyed()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.dialog_cannot_open_folder)
                            .setMessage(finalError)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        }, "open-folder");
        thread.setDaemon(true);
        thread.start();
    }

    private void showCompletionDialog(ScannerViewModel.CompletionNotice notice) {
        new AlertDialog.Builder(this)
                .setTitle(notice.getTitle())
                .setMessage(notice.getMessage())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void renderStateFromViewModel() {
        suppressInputListeners = true;
        try {
            if (viewModel.isFolderMode() && !radioFolderScan.isChecked()) {
                radioFolderScan.setChecked(true);
            } else if (viewModel.isFileMode() && !radioFileScan.isChecked()) {
                radioFileScan.setChecked(true);
            }

            editMinSizeMib.setEnabled(viewModel.isFileMode());
            if (!editMinSizeMib.getText().toString().equals(viewModel.getMinSizeMibText())) {
                editMinSizeMib.setText(viewModel.getMinSizeMibText());
            }

            if (!editExcludedPaths.getText().toString().equals(viewModel.getExcludedPathsText())) {
                editExcludedPaths.setText(viewModel.getExcludedPathsText());
            }
        } finally {
            suppressInputListeners = false;
        }

        buttonRefreshDrives.setEnabled(!viewModel.isDiscovering());
        buttonScanRestart.setEnabled(viewModel.isStartEnabled());
        buttonCancel.setEnabled(viewModel.isCancelEnabled());
        buttonClearFolder.setEnabled(viewModel.hasSelectedFolder());

        textSelectedFolder.setText(
                viewModel.hasSelectedFolder()
                        ? viewModel.getSelectedFolder()
                        : getString(R.string.no_folder_selected));
        textModeMeaning.setText(viewModel.getModeDescription());
        textHeadingSize.setText(viewModel.getSizeHeadingText());
        textStatus.setText(viewModel.getStatusText());
        textCurrentPath.setText(viewModel.getCurrentPathText());

        adapter.submitList(viewModel.getVisibleRows());
    }

    private void startPeriodicTimer() {
        if (!tickScheduled) {
            tickScheduled = true;
            uiHandler.postDelayed(periodicTickRunnable, ScannerViewModel.DEFAULT_UPDATE_MS);
        }
    }

    private void stopPeriodicTimer() {
        tickScheduled = false;
        uiHandler.removeCallbacks(periodicTickRunnable);
    }

    private void applySystemBarAppearance(View rootView) {
        boolean isNightMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), rootView);
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isNightMode);
            controller.setAppearanceLightNavigationBars(!isNightMode);
        }
    }
}
