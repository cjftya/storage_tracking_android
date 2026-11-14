package com.core.storagetracking.util;

import java.util.Locale;

/**
 * Pure formatting and overflow-safe byte arithmetic utilities.
 */
public final class SizeFormatter {
    private static final String[] UNITS = {"KiB", "MiB", "GiB", "TiB", "PiB"};

    private SizeFormatter() {
    }

    public static String formatDisplaySize(long sizeBytes) {
        long clamped = Math.max(0L, sizeBytes);
        if (clamped < 1024L) {
            return String.format(Locale.US, "%,d B", clamped);
        }
        double value = clamped;
        for (String unit : UNITS) {
            value /= 1024.0;
            if (value < 1024.0 || "PiB".equals(unit)) {
                return String.format(Locale.US, "%,.2f %s", value, unit);
            }
        }
        return String.format(Locale.US, "%,d B", clamped);
    }

    public static String formatBytes(long sizeBytes) {
        return String.format(Locale.US, "%,d", Math.max(0L, sizeBytes));
    }

    public static long safeAdd(long a, long b) {
        if (b <= 0L) {
            return a;
        }
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}
