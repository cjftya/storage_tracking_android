package com.core.storagetracking.storage;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.core.storagetracking.scan.common.PathPolicy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers accessible storage volumes (internal shared storage, SD card, USB OTG)
 * and enforces root/boundary policies.
 */
public final class StorageVolumeProvider {
    private static final List<String> MANDATORY_EXCLUSIONS = Collections.unmodifiableList(Arrays.asList(
            "/proc",
            "/sys",
            "/dev",
            "/acct",
            "/config"
    ));

    private StorageVolumeProvider() {
    }

    public static StorageDiscovery discoverVolumes(Context context) {
        List<StorageVolumeInfo> volumes = new ArrayList<>();
        Set<String> seenCanonical = new LinkedHashSet<>();
        Set<String> roots = new LinkedHashSet<>();
        Set<String> boundaries = new LinkedHashSet<>(MANDATORY_EXCLUSIONS);

        if (context != null) {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                List<StorageVolume> sysVolumes = sm.getStorageVolumes();
                for (StorageVolume sv : sysVolumes) {
                    String state = sv.getState();
                    File dir = resolveVolumeDirectory(sv);
                    if (dir == null) {
                        continue;
                    }
                    String normalized = PathPolicy.pathKey(dir.getAbsolutePath());
                    boundaries.add(normalized);
                    if (Environment.MEDIA_MOUNTED.equals(state)
                            || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                        String canonical = canonicalOrNormalized(dir, normalized);
                        if (seenCanonical.add(canonical)) {
                            String desc = sv.getDescription(context);
                            if (desc == null || desc.trim().isEmpty()) {
                                desc = sv.isPrimary() ? "Internal shared storage" : dir.getName();
                            }
                            volumes.add(new StorageVolumeInfo(
                                    normalized,
                                    desc,
                                    sv.isPrimary(),
                                    sv.isRemovable(),
                                    state));
                            roots.add(normalized);
                        }
                    }
                }
            }
        }

        // Fallback to Environment.getExternalStorageDirectory() if StorageManager returned nothing
        if (volumes.isEmpty()) {
            try {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null && ext.exists()) {
                    String normalized = PathPolicy.pathKey(ext.getAbsolutePath());
                    String canonical = canonicalOrNormalized(ext, normalized);
                    if (seenCanonical.add(canonical)) {
                        volumes.add(new StorageVolumeInfo(
                                normalized,
                                "Internal shared storage",
                                true,
                                false,
                                Environment.getExternalStorageState()));
                        roots.add(normalized);
                        boundaries.add(normalized);
                    }
                }
            } catch (Throwable ignored) {
                // Unit test environment without Android runtime mocked
            }
        }

        // Also check context.getExternalFilesDirs(null) to find any secondary SD/USB volume paths on API 26..29
        if (context != null) {
            try {
                File[] appDirs = context.getExternalFilesDirs(null);
                if (appDirs != null) {
                    for (File appDir : appDirs) {
                        File volRoot = climbToVolumeRoot(appDir);
                        if (volRoot != null && volRoot.exists()) {
                            String normalized = PathPolicy.pathKey(volRoot.getAbsolutePath());
                            boundaries.add(normalized);
                            String canonical = canonicalOrNormalized(volRoot, normalized);
                            if (seenCanonical.add(canonical)) {
                                volumes.add(new StorageVolumeInfo(
                                        normalized,
                                        volRoot.getName(),
                                        false,
                                        true,
                                        Environment.MEDIA_MOUNTED));
                                roots.add(normalized);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return new StorageDiscovery(
                volumes,
                roots,
                boundaries,
                Collections.emptyList(),
                MANDATORY_EXCLUSIONS);
    }

    public static void validateRoots(StorageDiscovery discovery, Collection<String> candidateRoots) {
        if (candidateRoots == null || candidateRoots.isEmpty()) {
            throw new IllegalArgumentException("Select a folder first.");
        }
        if (candidateRoots.size() != 1) {
            throw new IllegalArgumentException("Select one folder only.");
        }
        String rawRoot = candidateRoots.iterator().next();
        if (rawRoot == null || rawRoot.trim().isEmpty()) {
            throw new IllegalArgumentException("Select a folder first.");
        }
        String candidate = PathPolicy.pathKey(rawRoot);
        if (discovery != null) {
            for (String mandatory : discovery.getMandatoryExclusions()) {
                String mandatoryKey = PathPolicy.pathKey(mandatory, false);
                if (PathPolicy.containsPath(mandatoryKey, candidate, false)) {
                    throw new IllegalArgumentException("Root is inside an excluded system/virtual mount: " + rawRoot);
                }
            }
        }
        Path path = Paths.get(candidate);
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Symbolic link cannot be used as scan root: " + rawRoot);
        }
        File dir = new File(candidate);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Selected folder is unavailable or not a directory: " + rawRoot);
        }
    }

    public static List<String> mergeExclusions(StorageDiscovery discovery, Collection<String> userExclusions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (discovery != null) {
            for (String m : discovery.getMandatoryExclusions()) {
                if (m != null && !m.trim().isEmpty()) {
                    merged.add(PathPolicy.pathKey(m));
                }
            }
        }
        if (userExclusions != null) {
            for (String u : userExclusions) {
                if (u != null && !u.trim().isEmpty()) {
                    merged.add(PathPolicy.pathKey(u));
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private static File resolveVolumeDirectory(StorageVolume sv) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return sv.getDirectory();
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

    private static File climbToVolumeRoot(File appExternalDir) {
        if (appExternalDir == null) {
            return null;
        }
        // Typically /storage/<volume>/Android/data/<pkg>/files -> climb 4 levels up
        File current = appExternalDir;
        for (int i = 0; i < 4 && current != null; i++) {
            current = current.getParentFile();
        }
        return current;
    }

    private static String canonicalOrNormalized(File file, String fallbackNormalized) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return fallbackNormalized;
        }
    }
}
