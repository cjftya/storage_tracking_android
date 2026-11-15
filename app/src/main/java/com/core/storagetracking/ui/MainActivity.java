package com.core.storagetracking.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.core.storagetracking.R;
import com.core.storagetracking.report.MarkdownReportGenerator;
import com.core.storagetracking.scan.file.FileResultStore;
import com.core.storagetracking.scan.folder.FolderResultStore;
import com.core.storagetracking.storage.FolderOpener;
import com.core.storagetracking.storage.StorageAccessManager;
import com.core.storagetracking.storage.StorageDiscovery;
import com.core.storagetracking.storage.StorageVolumeProvider;
import com.core.storagetracking.viewmodel.ScannerViewModel;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    private List<String> renderedExclusions = Collections.emptyList();

    private LinearLayout badgePermissionOk;
    private MaterialCardView permissionBanner;
    private Button buttonGrantAccess;
    private MaterialButtonToggleGroup toggleGroupMode;
    private EditText editMinSizeMib;
    private Button buttonScanRestart;
    private Button buttonCancel;
    private LinearLayout layoutScopeHeader;
    private TextView textScopeSummary;
    private View spacerScopeHeader;
    private ImageView imageScopeChevron;
    private LinearLayout layoutScopeBody;
    private Button buttonRefreshDrives;
    private TextView textSelectedFolder;
    private Button buttonSelectFolder;
    private Button buttonClearFolder;
    private TextView textExclusionsTitle;
    private Button buttonAddExclusion;
    private Button buttonClearExclusions;
    private Button buttonToggleExclusions;
    private TextView textExclusionsEmpty;
    private HorizontalScrollView scrollExclusionsChips;
    private ChipGroup chipGroupExclusions;
    private LinearLayout layoutExclusionsContainer;
    private EditText editExcludedPaths;
    private TextView textModeMeaning;
    private TextView textHeadingSize;
    private LinearProgressIndicator progressScanning;
    private RecyclerView recyclerResults;
    private LinearLayout layoutEmptyState;
    private TextView textEmptyTitle;
    private TextView textEmptySubtitle;
    private TextView textStatus;
    private TextView textCurrentPath;
    private Button buttonExportReport;

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

    private final ActivityResultLauncher<String> createReportLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("text/markdown"),
                    uri -> {
                        if (uri != null) {
                            writeMarkdownReportToUri(uri);
                        } else {
                            viewModel.setPendingReportFocusPath(null);
                        }
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
        int basePaddingPx = (int) (12 * getResources().getDisplayMetrics().density);
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
        badgePermissionOk = findViewById(R.id.badge_permission_ok);
        permissionBanner = findViewById(R.id.permission_banner);
        buttonGrantAccess = findViewById(R.id.button_grant_access);
        toggleGroupMode = findViewById(R.id.toggle_group_mode);
        editMinSizeMib = findViewById(R.id.edit_min_size_mib);
        buttonScanRestart = findViewById(R.id.button_scan_restart);
        buttonCancel = findViewById(R.id.button_cancel);
        layoutScopeHeader = findViewById(R.id.layout_scope_header);
        textScopeSummary = findViewById(R.id.text_scope_summary);
        spacerScopeHeader = findViewById(R.id.spacer_scope_header);
        imageScopeChevron = findViewById(R.id.image_scope_chevron);
        layoutScopeBody = findViewById(R.id.layout_scope_body);
        buttonRefreshDrives = findViewById(R.id.button_refresh_drives);
        textSelectedFolder = findViewById(R.id.text_selected_folder);
        buttonSelectFolder = findViewById(R.id.button_select_folder);
        buttonClearFolder = findViewById(R.id.button_clear_folder);
        textExclusionsTitle = findViewById(R.id.text_exclusions_title);
        buttonAddExclusion = findViewById(R.id.button_add_exclusion);
        buttonClearExclusions = findViewById(R.id.button_clear_exclusions);
        buttonToggleExclusions = findViewById(R.id.button_toggle_exclusions);
        textExclusionsEmpty = findViewById(R.id.text_exclusions_empty);
        scrollExclusionsChips = findViewById(R.id.scroll_exclusions_chips);
        chipGroupExclusions = findViewById(R.id.chip_group_exclusions);
        layoutExclusionsContainer = findViewById(R.id.layout_exclusions_container);
        editExcludedPaths = findViewById(R.id.edit_excluded_paths);
        textModeMeaning = findViewById(R.id.text_mode_meaning);
        textHeadingSize = findViewById(R.id.text_heading_size);
        progressScanning = findViewById(R.id.progress_scanning);
        recyclerResults = findViewById(R.id.recycler_results);
        layoutEmptyState = findViewById(R.id.layout_empty_state);
        textEmptyTitle = findViewById(R.id.text_empty_title);
        textEmptySubtitle = findViewById(R.id.text_empty_subtitle);
        textStatus = findViewById(R.id.text_status);
        textCurrentPath = findViewById(R.id.text_current_path);
        buttonExportReport = findViewById(R.id.button_export_report);

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

        toggleGroupMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (suppressInputListeners || !isChecked) {
                return;
            }
            String mode = (checkedId == R.id.button_mode_file)
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
                    updateExclusionsUi();
                }
            }
        });

        layoutScopeHeader.setOnClickListener(v -> {
            viewModel.setScopeExpanded(!viewModel.isScopeExpanded());
            renderStateFromViewModel();
        });

        buttonRefreshDrives.setOnClickListener(v -> triggerVolumeDiscovery(false));

        buttonSelectFolder.setOnClickListener(v -> FolderPickerDialog.show(
                this,
                viewModel.getDiscovery(),
                viewModel.getSelectedFolder(),
                getString(R.string.action_select_folder),
                getString(R.string.dialog_select_target_confirm),
                folder -> {
                    viewModel.selectFolder(folder);
                    renderStateFromViewModel();
                }));

        buttonClearFolder.setOnClickListener(v -> {
            viewModel.clearSelectedFolder();
            renderStateFromViewModel();
        });

        buttonAddExclusion.setOnClickListener(v -> FolderPickerDialog.show(
                this,
                viewModel.getDiscovery(),
                viewModel.getSelectedFolder(),
                getString(R.string.dialog_add_exclusion_title),
                getString(R.string.dialog_add_exclusion_confirm),
                folder -> {
                    viewModel.addExcludedPath(folder);
                    renderStateFromViewModel();
                }));

        buttonClearExclusions.setOnClickListener(v -> {
            viewModel.clearExcludedPaths();
            renderStateFromViewModel();
        });

        buttonToggleExclusions.setOnClickListener(v -> {
            viewModel.setEditExclusionsRaw(!viewModel.isEditExclusionsRaw());
            renderStateFromViewModel();
        });

        buttonScanRestart.setOnClickListener(v -> {
            try {
                viewModel.startScan();
                renderStateFromViewModel();
            } catch (IllegalArgumentException | IllegalStateException e) {
                new MaterialAlertDialogBuilder(this)
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

        buttonExportReport.setOnClickListener(v -> requestExportMarkdownReport(null));
    }

    private void updatePermissionBanner() {
        boolean granted = StorageAccessManager.hasStorageAccess(this);
        if (granted) {
            badgePermissionOk.setVisibility(View.VISIBLE);
            permissionBanner.setVisibility(View.GONE);
        } else {
            badgePermissionOk.setVisibility(View.GONE);
            permissionBanner.setVisibility(View.VISIBLE);
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
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.dialog_permission_settings_title)
                            .setMessage(R.string.dialog_permission_settings_message)
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
        popup.getMenu().add(0, 2, 1, getString(R.string.action_export_folder_md));
        popup.getMenu().add(0, 3, 2, getString(R.string.action_add_to_exclusions));
        popup.getMenu().add(0, 4, 3, getString(R.string.action_copy_path));
        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == 1) {
                openFolderAsync(item.getPath());
                return true;
            } else if (id == 2) {
                requestExportMarkdownReport(item.getPath());
                return true;
            } else if (id == 3) {
                boolean added = viewModel.addExcludedPath(item.getPath());
                renderStateFromViewModel();
                Toast.makeText(
                        this,
                        added ? R.string.toast_exclusion_added : R.string.toast_exclusion_exists,
                        Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == 4) {
                copyPathToClipboard(item.getPath());
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
            List<FolderOpener.FolderAppTarget> targets = null;
            String error = null;
            try {
                targets = FolderOpener.resolveOpenFolderTargets(this, path);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final List<FolderOpener.FolderAppTarget> finalTargets = targets;
            final String finalError = error;
            uiHandler.post(() -> {
                openingFolder = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (finalError != null) {
                    showOpenFolderErrorDialog(path, finalError);
                    return;
                }
                if (finalTargets == null || finalTargets.isEmpty()) {
                    try {
                        FolderOpener.tryDirectFallbackOpen(this, path);
                    } catch (Throwable t) {
                        showOpenFolderErrorDialog(path, t.getMessage() != null ? t.getMessage() : path);
                    }
                    return;
                }
                if (finalTargets.size() == 1) {
                    launchFolderTarget(path, finalTargets.get(0));
                    return;
                }
                showFolderAppChooserDialog(path, finalTargets);
            });
        }, "open-folder");
        thread.setDaemon(true);
        thread.start();
    }

    private void launchFolderTarget(String path, FolderOpener.FolderAppTarget target) {
        try {
            startActivity(target.getIntent());
        } catch (Throwable t) {
            showOpenFolderErrorDialog(path, t.getMessage() != null ? t.getMessage() : path);
        }
    }

    private void showFolderAppChooserDialog(String path, List<FolderOpener.FolderAppTarget> targets) {
        ArrayAdapter<FolderOpener.FolderAppTarget> appAdapter =
                new ArrayAdapter<FolderOpener.FolderAppTarget>(this, R.layout.item_folder_app_target, targets) {
                    @NonNull
                    @Override
                    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                        View row = convertView != null
                                ? convertView
                                : LayoutInflater.from(getContext())
                                        .inflate(R.layout.item_folder_app_target, parent, false);
                        FolderOpener.FolderAppTarget target = getItem(position);
                        ImageView iconView = row.findViewById(R.id.image_app_icon);
                        TextView labelView = row.findViewById(R.id.text_app_label);
                        if (target != null) {
                            if (target.getIcon() != null) {
                                iconView.setImageDrawable(target.getIcon());
                            } else {
                                iconView.setImageResource(R.drawable.ic_folder);
                            }
                            labelView.setText(target.getLabel());
                        }
                        return row;
                    }
                };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_open_folder_chooser)
                .setAdapter(appAdapter, (dialog, which) -> {
                    if (which >= 0 && which < targets.size()) {
                        launchFolderTarget(path, targets.get(which));
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showOpenFolderErrorDialog(String path, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_cannot_open_folder)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.action_copy_path, (d, w) -> copyPathToClipboard(path))
                .show();
    }

    private void copyPathToClipboard(String path) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && path != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("folder_path", path));
            Toast.makeText(this, R.string.toast_path_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestExportMarkdownReport(String focusFolderPath) {
        if (!viewModel.canExportReport()) {
            Toast.makeText(this, R.string.toast_report_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.setPendingReportFocusPath(focusFolderPath);
        createReportLauncher.launch(MarkdownReportGenerator.suggestFileName(focusFolderPath));
    }

    private void writeMarkdownReportToUri(Uri uri) {
        final boolean folderMode = viewModel.isFolderMode();
        final FolderResultStore folderStore = viewModel.getActiveFolderStore();
        final FileResultStore fileStore = viewModel.getActiveFileStore();
        final String targetFolder = viewModel.getSelectedFolder();
        final String focusPath = viewModel.getPendingReportFocusPath();
        final String minSizeMib = viewModel.getMinSizeMibText();
        final List<String> excludedPaths = viewModel.getExcludedPaths();
        final ScannerViewModel.StatusUiInfo statusInfo = viewModel.getStatusUiInfo();
        final Locale locale = getResources().getConfiguration().getLocales().get(0);
        viewModel.setPendingReportFocusPath(null);

        if ((folderMode && folderStore == null) || (!folderMode && fileStore == null)) {
            Toast.makeText(this, R.string.toast_report_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Thread exportThread = new Thread(() -> {
            String error = null;
            try {
                String markdown = folderMode
                        ? MarkdownReportGenerator.generateFolderReport(
                                folderStore, targetFolder, focusPath, excludedPaths, statusInfo, locale)
                        : MarkdownReportGenerator.generateFileReport(
                                fileStore, targetFolder, focusPath, minSizeMib, excludedPaths, statusInfo, locale);
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new IllegalStateException("Cannot open output stream");
                    }
                    out.write(markdown.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String finalError = error;
            uiHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (finalError != null) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.dialog_report_failed)
                            .setMessage(finalError)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    Toast.makeText(this, R.string.toast_report_saved, Toast.LENGTH_SHORT).show();
                }
            });
        }, "export-md-report");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private void showCompletionDialog(ScannerViewModel.CompletionNotice notice) {
        int titleRes = notice.isError() ? R.string.dialog_scan_failed : R.string.dialog_scan_complete;
        String message;
        if (notice.hasMetrics()) {
            if (notice.isFolderMode()) {
                String totalsLabel = getString(notice.isPartial()
                        ? R.string.totals_state_partial
                        : R.string.totals_state_complete);
                message = getString(
                        R.string.completion_folder_format,
                        notice.getPrimaryCount(),
                        notice.getBytesScanned(),
                        totalsLabel,
                        notice.getFilesScanned(),
                        notice.getErrors(),
                        notice.getElapsedSeconds());
            } else {
                message = getString(
                        R.string.completion_file_format,
                        notice.getPrimaryCount(),
                        notice.getFilesScanned(),
                        notice.getErrors(),
                        notice.getElapsedSeconds());
            }
        } else {
            message = notice.getMessage();
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null);
        if (!notice.isError() && viewModel.canExportReport()) {
            builder.setNeutralButton(R.string.action_export_md, (d, w) -> requestExportMarkdownReport(null));
        }
        builder.show();
    }

    private void renderStateFromViewModel() {
        suppressInputListeners = true;
        try {
            int expectedButtonId = viewModel.isFileMode()
                    ? R.id.button_mode_file
                    : R.id.button_mode_folder;
            if (toggleGroupMode.getCheckedButtonId() != expectedButtonId) {
                toggleGroupMode.check(expectedButtonId);
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
        buttonScanRestart.setText(viewModel.hasRunStarted()
                ? R.string.action_restart
                : R.string.action_scan_restart);
        buttonCancel.setEnabled(viewModel.isCancelEnabled());
        buttonClearFolder.setEnabled(viewModel.hasSelectedFolder());
        buttonExportReport.setEnabled(viewModel.canExportReport());
        progressScanning.setVisibility(viewModel.isScanning() ? View.VISIBLE : View.GONE);

        String targetLabel = viewModel.hasSelectedFolder()
                ? viewModel.getSelectedFolder()
                : getString(R.string.no_folder_selected);
        textSelectedFolder.setText(targetLabel);

        boolean scopeExpanded = viewModel.isScopeExpanded();
        layoutScopeBody.setVisibility(scopeExpanded ? View.VISIBLE : View.GONE);
        imageScopeChevron.setImageResource(
                scopeExpanded ? R.drawable.ic_expand_more : R.drawable.ic_chevron_right);
        textScopeSummary.setVisibility(scopeExpanded ? View.GONE : View.VISIBLE);
        spacerScopeHeader.setVisibility(scopeExpanded ? View.VISIBLE : View.GONE);

        updateExclusionsUi();

        textModeMeaning.setText(viewModel.isFolderMode()
                ? R.string.meaning_folder_mode
                : R.string.meaning_file_mode);
        textHeadingSize.setText(resolveLocalizedSizeHeading());
        textStatus.setText(resolveLocalizedStatusText());
        textCurrentPath.setText(viewModel.getCurrentPathText());
        textCurrentPath.setVisibility(
                viewModel.getCurrentPathText().isEmpty() ? View.GONE : View.VISIBLE);

        List<TreeRowItem> rows = viewModel.getVisibleRows();
        adapter.submitList(rows);
        updateEmptyState(rows.isEmpty());
    }

    private String resolveLocalizedSizeHeading() {
        if (viewModel.isFileMode()) {
            return getString(R.string.heading_matched_size);
        }
        FolderTreeState folderTree = viewModel.getFolderTreeState();
        if (folderTree.getStore() == null || !folderTree.isSorted()) {
            return getString(R.string.heading_logical_size_aggregating);
        }
        return com.core.storagetracking.scan.folder.FolderResultStore.STATE_COMPLETE
                .equals(folderTree.getStore().getState())
                ? getString(R.string.heading_logical_size)
                : getString(R.string.heading_logical_size_partial);
    }

    private String resolveLocalizedStatusText() {
        ScannerViewModel.StatusUiInfo info = viewModel.getStatusUiInfo();
        switch (info.getKind()) {
            case SELECT_FOLDER:
                return getString(R.string.status_select_folder);
            case CHECKING_DRIVES:
                return getString(R.string.status_checking_drives);
            case DRIVES_UNAVAILABLE:
                return getString(R.string.status_drives_unavailable, info.getDetail());
            case READY:
                return getString(R.string.status_ready);
            case READY_PRESS_SCAN:
                return getString(R.string.status_ready_press_scan);
            case SCANNING_STARTING:
                return getString(R.string.status_scanning);
            case CANCELLING:
                return getString(R.string.status_cancelling);
            default:
                break;
        }
        String stateLabel;
        switch (info.getKind()) {
            case COMPLETED:
                stateLabel = getString(R.string.status_state_completed);
                break;
            case COMPLETED_PARTIAL:
                stateLabel = getString(R.string.status_state_completed_partial);
                break;
            case CANCELLED:
                stateLabel = getString(R.string.status_state_cancelled);
                break;
            case CANCELLED_PARTIAL:
                stateLabel = getString(R.string.status_state_cancelled_partial);
                break;
            case FAILED:
                stateLabel = getString(R.string.status_state_failed);
                break;
            case FAILED_PARTIAL:
                stateLabel = getString(R.string.status_state_failed_partial);
                break;
            case SCANNING:
            default:
                stateLabel = getString(R.string.status_state_scanning);
                break;
        }
        if (info.isFolderMode()) {
            return getString(
                    R.string.status_folder_metrics_format,
                    stateLabel,
                    info.getFilesScanned(),
                    info.getDirectoriesScanned(),
                    info.getFoldersStored(),
                    info.getBytesScanned(),
                    info.getErrors(),
                    info.getElapsedSeconds());
        }
        return getString(
                R.string.status_file_metrics_format,
                stateLabel,
                info.getFilesScanned(),
                info.getDirectoriesScanned(),
                info.getMatches(),
                info.getErrors(),
                info.getElapsedSeconds());
    }

    private void updateExclusionsUi() {
        List<String> exclusions = viewModel.getExcludedPaths();
        textExclusionsTitle.setText(getString(R.string.exclusions_count_format, exclusions.size()));
        buttonClearExclusions.setVisibility(exclusions.isEmpty() ? View.GONE : View.VISIBLE);

        String targetLabel = viewModel.hasSelectedFolder()
                ? viewModel.getSelectedFolder()
                : getString(R.string.no_folder_selected);
        textScopeSummary.setText(getString(R.string.scope_summary_format, targetLabel, exclusions.size()));

        boolean rawMode = viewModel.isEditExclusionsRaw();
        layoutExclusionsContainer.setVisibility(rawMode ? View.VISIBLE : View.GONE);
        buttonToggleExclusions.setText(rawMode
                ? R.string.action_done_editing
                : R.string.action_edit_paths);

        if (exclusions.isEmpty()) {
            scrollExclusionsChips.setVisibility(View.GONE);
            textExclusionsEmpty.setVisibility(rawMode ? View.GONE : View.VISIBLE);
        } else {
            textExclusionsEmpty.setVisibility(View.GONE);
            scrollExclusionsChips.setVisibility(View.VISIBLE);
        }

        if (!exclusions.equals(renderedExclusions)) {
            renderedExclusions = exclusions;
            chipGroupExclusions.removeAllViews();
            for (String path : exclusions) {
                Chip chip = new Chip(this);
                chip.setText(formatShortExclusionLabel(path));
                chip.setContentDescription(path);
                chip.setCloseIconVisible(true);
                chip.setEnsureMinTouchTargetSize(false);
                chip.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.action_exclusions)
                        .setMessage(path)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.action_clear, (d, w) -> {
                            viewModel.removeExcludedPath(path);
                            renderStateFromViewModel();
                        })
                        .show());
                chip.setOnCloseIconClickListener(v -> {
                    viewModel.removeExcludedPath(path);
                    renderStateFromViewModel();
                });
                chipGroupExclusions.addView(chip);
            }
        }
    }

    private static String formatShortExclusionLabel(String fullPath) {
        if (fullPath == null) {
            return "";
        }
        int lastSlash = fullPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < fullPath.length() - 1) {
            String base = fullPath.substring(lastSlash + 1);
            int prevSlash = fullPath.lastIndexOf('/', lastSlash - 1);
            if (prevSlash >= 0) {
                return "…/" + fullPath.substring(prevSlash + 1);
            }
            return base;
        }
        return fullPath;
    }

    private void updateEmptyState(boolean isEmpty) {
        if (!isEmpty) {
            layoutEmptyState.setVisibility(View.GONE);
            return;
        }
        layoutEmptyState.setVisibility(View.VISIBLE);
        if (viewModel.isScanning()) {
            textEmptyTitle.setText(R.string.empty_title_scanning);
            textEmptySubtitle.setText(R.string.empty_subtitle_scanning);
        } else if (viewModel.hasRunStarted()) {
            textEmptyTitle.setText(R.string.empty_title_no_results);
            textEmptySubtitle.setText(R.string.empty_subtitle_no_results);
        } else {
            textEmptyTitle.setText(R.string.empty_title_ready);
            textEmptySubtitle.setText(R.string.empty_subtitle_ready);
        }
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
