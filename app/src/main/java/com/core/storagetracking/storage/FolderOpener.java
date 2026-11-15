package com.core.storagetracking.storage;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves installed file managers capable of opening a folder and launches them
 * without using file:// URIs or Play Store links.
 */
public final class FolderOpener {

    public static final class FolderAppTarget {
        private final CharSequence label;
        private final Drawable icon;
        private final Intent intent;

        public FolderAppTarget(CharSequence label, Drawable icon, Intent intent) {
            this.label = label;
            this.icon = icon;
            this.intent = intent;
        }

        public CharSequence getLabel() {
            return label;
        }

        public Drawable getIcon() {
            return icon;
        }

        public Intent getIntent() {
            return intent;
        }
    }

    private FolderOpener() {
    }

    public static void openFolder(Context context, String path) {
        List<FolderAppTarget> targets = resolveOpenFolderTargets(context, path);
        if (!targets.isEmpty()) {
            for (FolderAppTarget target : targets) {
                try {
                    context.startActivity(target.getIntent());
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
            }
        }
        tryDirectFallbackOpen(context, path);
    }

    public static List<FolderAppTarget> resolveOpenFolderTargets(Context context, String path) {
        File dir = validateDirectory(context, path);
        PackageManager pm = context.getPackageManager();
        List<Intent> candidates = buildCandidateIntents(context, dir);
        Map<String, FolderAppTarget> uniqueByComponent = new LinkedHashMap<>();
        boolean nonActivity = !(context instanceof Activity);

        for (Intent candidate : candidates) {
            List<ResolveInfo> resolved = pm.queryIntentActivities(candidate, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null) {
                    continue;
                }
                String pkg = info.activityInfo.packageName;
                String cls = info.activityInfo.name;
                String key = pkg + "/" + cls;
                if (uniqueByComponent.containsKey(key)) {
                    continue;
                }
                Intent targeted = new Intent(candidate);
                targeted.setComponent(new ComponentName(pkg, cls));
                if (nonActivity) {
                    targeted.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                CharSequence label = info.loadLabel(pm);
                if (label == null || label.length() == 0) {
                    label = pkg;
                }
                Drawable icon = null;
                try {
                    icon = info.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                uniqueByComponent.put(key, new FolderAppTarget(label, icon, targeted));
            }
        }
        return new ArrayList<>(uniqueByComponent.values());
    }

    public static void tryDirectFallbackOpen(Context context, String path) {
        File dir = validateDirectory(context, path);
        List<Intent> candidates = buildCandidateIntents(context, dir);
        boolean nonActivity = !(context instanceof Activity);
        for (Intent candidate : candidates) {
            Intent launch = new Intent(candidate);
            if (nonActivity) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            try {
                context.startActivity(launch);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        throw new IllegalStateException(
                "No file manager app on this device supports opening folders directly.\nPath: "
                        + dir.getAbsolutePath());
    }

    private static File validateDirectory(Context context, String path) {
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
        return dir;
    }

    private static List<Intent> buildCandidateIntents(Context context, File dir) {
        List<Intent> candidates = new ArrayList<>();
        DocumentRef docRef = resolveExternalStorageDocument(context, dir);

        if (docRef != null) {
            Uri docUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docRef.documentId);

            Intent viewDocDir = new Intent(Intent.ACTION_VIEW);
            viewDocDir.setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR);
            viewDocDir.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            candidates.add(viewDocDir);

            Intent viewResourceFolder = new Intent(Intent.ACTION_VIEW);
            viewResourceFolder.setDataAndType(docUri, "resource/folder");
            viewResourceFolder.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            candidates.add(viewResourceFolder);

            Intent viewInodeDir = new Intent(Intent.ACTION_VIEW);
            viewInodeDir.setDataAndType(docUri, "inode/directory");
            viewInodeDir.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            candidates.add(viewInodeDir);

            Intent oiViewDir = new Intent("org.openintents.action.VIEW_DIRECTORY");
            oiViewDir.setData(docUri);
            oiViewDir.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            candidates.add(oiViewDir);
        }

        Intent samsungMyFiles = new Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES");
        samsungMyFiles.putExtra("samsung.myfiles.intent.extra.START_PATH", dir.getAbsolutePath());
        candidates.add(samsungMyFiles);

        if (docRef != null) {
            Uri rootUri = DocumentsContract.buildRootUri(
                    "com.android.externalstorage.documents",
                    docRef.rootId);
            Intent viewRoot = new Intent(Intent.ACTION_VIEW);
            viewRoot.setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM);
            viewRoot.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            candidates.add(viewRoot);
        }

        return candidates;
    }

    private static final class DocumentRef {
        final String rootId;
        final String documentId;

        DocumentRef(String rootId, String documentId) {
            this.rootId = rootId;
            this.documentId = documentId;
        }
    }

    private static DocumentRef resolveExternalStorageDocument(Context context, File dir) {
        try {
            String targetPath = dir.getCanonicalPath();
            File primaryRoot = Environment.getExternalStorageDirectory();
            if (primaryRoot != null) {
                String rootPath = primaryRoot.getCanonicalPath();
                if (targetPath.equals(rootPath)) {
                    return new DocumentRef("primary", "primary:");
                }
                if (targetPath.startsWith(rootPath + File.separator)) {
                    String rel = targetPath.substring(rootPath.length() + 1).replace('\\', '/');
                    return new DocumentRef("primary", "primary:" + rel);
                }
            }

            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume sv : sm.getStorageVolumes()) {
                    File volDir = resolveVolumeDirectory(sv);
                    if (volDir == null) {
                        continue;
                    }
                    String volPath = volDir.getCanonicalPath();
                    String rootId = sv.isPrimary() ? "primary" : sv.getUuid();
                    if (rootId == null || rootId.isEmpty()) {
                        rootId = volDir.getName();
                    }
                    if (targetPath.equals(volPath)) {
                        return new DocumentRef(rootId, rootId + ":");
                    }
                    if (targetPath.startsWith(volPath + File.separator)) {
                        String rel = targetPath.substring(volPath.length() + 1).replace('\\', '/');
                        return new DocumentRef(rootId, rootId + ":" + rel);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File resolveVolumeDirectory(StorageVolume sv) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File dir = sv.getDirectory();
            if (dir != null) {
                return dir;
            }
        }
        try {
            Method getPathFile = StorageVolume.class.getDeclaredMethod("getPathFile");
            getPathFile.setAccessible(true);
            Object res = getPathFile.invoke(sv);
            if (res instanceof File) {
                return (File) res;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getPath = StorageVolume.class.getDeclaredMethod("getPath");
            getPath.setAccessible(true);
            Object res = getPath.invoke(sv);
            if (res instanceof String && !((String) res).isEmpty()) {
                return new File((String) res);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
