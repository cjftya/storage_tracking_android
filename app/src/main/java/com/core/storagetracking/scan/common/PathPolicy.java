package com.core.storagetracking.scan.common;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Host and Android path normalization, boundary checks, and link policy.
 * Never resolves symbolic links or reads file contents.
 */
public final class PathPolicy {
    private static final boolean IS_WINDOWS = File.separatorChar == '\\';

    private PathPolicy() {
    }

    public static boolean isWindowsHost() {
        return IS_WINDOWS;
    }

    public static String pathKey(String path) {
        return pathKey(path, IS_WINDOWS);
    }

    public static String pathKey(String path, boolean windows) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Path must not be empty");
        }
        String raw = path.trim();
        if (windows != IS_WINDOWS) {
            return normalizeExplicitStyle(raw, windows);
        }
        try {
            Path p = Paths.get(raw).toAbsolutePath().normalize();
            String normalized = p.toString();
            normalized = trimTrailingSeparator(normalized, windows);
            return windows ? normalized.toLowerCase(Locale.ROOT) : normalized;
        } catch (InvalidPathException e) {
            String normalized = new File(raw).getAbsolutePath();
            normalized = trimTrailingSeparator(normalized, windows);
            return windows ? normalized.toLowerCase(Locale.ROOT) : normalized;
        }
    }

    public static String entryPathKey(Path entryPath) {
        String raw = entryPath.toString();
        return IS_WINDOWS ? raw.toLowerCase(Locale.ROOT) : raw;
    }

    public static String entryPathKey(String entryPath) {
        return IS_WINDOWS ? entryPath.toLowerCase(Locale.ROOT) : entryPath;
    }

    public static boolean containsPath(String parent, String child) {
        return containsPath(parent, child, IS_WINDOWS);
    }

    public static boolean containsPath(String parent, String child, boolean windows) {
        if (parent == null || child == null) {
            return false;
        }
        if (child.equals(parent)) {
            return true;
        }
        char sep = windows ? '\\' : '/';
        String strippedParent = trimTrailingSeparator(parent, windows);
        if (strippedParent.equals(String.valueOf(sep))) {
            return child.startsWith(String.valueOf(sep));
        }
        if (windows && strippedParent.length() == 2 && strippedParent.charAt(1) == ':') {
            return child.startsWith(strippedParent + sep);
        }
        return child.startsWith(strippedParent + sep);
    }

    public static String parentKey(String normalizedPath) {
        return parentKey(normalizedPath, IS_WINDOWS);
    }

    public static String parentKey(String normalizedPath, boolean windows) {
        if (normalizedPath == null) {
            return null;
        }
        char sep = windows ? '\\' : '/';
        String trimmed = trimTrailingSeparator(normalizedPath, windows);
        if (trimmed.equals(String.valueOf(sep))) {
            return null;
        }
        if (windows && trimmed.length() <= 3 && trimmed.length() >= 2 && trimmed.charAt(1) == ':') {
            return null;
        }
        int idx = trimmed.lastIndexOf(sep);
        if (idx < 0) {
            return null;
        }
        if (idx == 0) {
            return String.valueOf(sep);
        }
        if (windows && idx == 2 && trimmed.charAt(1) == ':') {
            return trimmed.substring(0, 3);
        }
        return trimmed.substring(0, idx);
    }

    public static String baseName(String path) {
        return baseName(path, IS_WINDOWS);
    }

    public static String baseName(String path, boolean windows) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        char sep = windows ? '\\' : '/';
        String trimmed = trimTrailingSeparator(path, windows);
        int idx = trimmed.lastIndexOf(sep);
        if (idx < 0 || idx == trimmed.length() - 1) {
            return trimmed;
        }
        return trimmed.substring(idx + 1);
    }

    public static boolean isLinkOrReparseDirectory(Path entry, BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink() || Files.isSymbolicLink(entry)) {
            return true;
        }
        // On Windows NTFS, junctions/reparse points report isOther() == true when read with NOFOLLOW_LINKS.
        return IS_WINDOWS && attrs.isOther();
    }

    public static BasicFileAttributes inspectRoot(Path rootPath) throws IOException {
        if (Files.isSymbolicLink(rootPath)) {
            return null;
        }
        BasicFileAttributes attrs = Files.readAttributes(rootPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || (IS_WINDOWS && attrs.isOther())) {
            return null;
        }
        return attrs;
    }

    private static String trimTrailingSeparator(String path, boolean windows) {
        char sep = windows ? '\\' : '/';
        String result = path;
        while (result.length() > 1 && result.charAt(result.length() - 1) == sep) {
            if (windows && result.length() == 3 && result.charAt(1) == ':') {
                break;
            }
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String normalizeExplicitStyle(String raw, boolean windows) {
        char sep = windows ? '\\' : '/';
        char other = windows ? '/' : '\\';
        String replaced = raw.replace(other, sep);
        StringBuilder sb = new StringBuilder();
        boolean prevSep = false;
        for (int i = 0; i < replaced.length(); i++) {
            char c = replaced.charAt(i);
            if (c == sep) {
                if (!prevSep) {
                    sb.append(c);
                }
                prevSep = true;
            } else {
                sb.append(c);
                prevSep = false;
            }
        }
        String normalized = trimTrailingSeparator(sb.toString(), windows);
        return windows ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }
}
