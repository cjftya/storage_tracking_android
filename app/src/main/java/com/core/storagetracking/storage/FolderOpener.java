package com.core.storagetracking.storage;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;

/**
 * Opens a scanned folder in the platform file manager when supported by the device.
 */
public final class FolderOpener {

    private FolderOpener() {
    }

    public static void openFolder(Context context, String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Folder unavailable");
        }
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Folder unavailable: " + path);
        }
        if (context == null) {
            throw new IllegalStateException("Context unavailable");
        }

        Uri docUri = buildExternalStorageDocumentUri(dir);
        if (docUri != null) {
            Intent docIntent = new Intent(Intent.ACTION_VIEW);
            docIntent.setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR);
            docIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(docIntent);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }

        throw new IllegalStateException(
                "No system file manager activity supports opening folder directly.\nPath: " + dir.getAbsolutePath());
    }

    private static Uri buildExternalStorageDocumentUri(File dir) {
        try {
            File primaryRoot = Environment.getExternalStorageDirectory();
            if (primaryRoot != null) {
                String rootPath = primaryRoot.getCanonicalPath();
                String targetPath = dir.getCanonicalPath();
                if (targetPath.equals(rootPath)) {
                    return DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:");
                }
                if (targetPath.startsWith(rootPath + File.separator)) {
                    String rel = targetPath.substring(rootPath.length() + 1).replace('\\', '/');
                    return DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:" + rel);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
