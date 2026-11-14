package com.core.storagetracking.storage;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

/**
 * Checks storage access permissions across Android 8..10 (API 26..29) and Android 11+ (API 30+).
 */
public final class StorageAccessManager {

    private StorageAccessManager() {
    }

    public static boolean requiresSpecialAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static boolean hasStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (context == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static Intent createManageAppAllFilesIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context != null) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            return intent;
        }
        return createFallbackSettingsIntent(context);
    }

    public static Intent createFallbackSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        if (context != null) {
            intent.setData(Uri.fromParts("package", context.getPackageName(), null));
        }
        return intent;
    }
}
