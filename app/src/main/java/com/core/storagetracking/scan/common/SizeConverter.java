package com.core.storagetracking.scan.common;

import java.math.BigDecimal;

/**
 * Exact conversion between user-entered MiB strings and integer byte counts.
 */
public final class SizeConverter {
    public static final long BYTES_PER_MIB = 1024L * 1024L;
    public static final long DEFAULT_MIN_SIZE_BYTES = 100L * BYTES_PER_MIB;
    private static final BigDecimal MIB_MULTIPLIER = BigDecimal.valueOf(BYTES_PER_MIB);

    private SizeConverter() {
    }

    public static long mibToBytes(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Enter a nonnegative MiB value representing whole bytes");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter a nonnegative MiB value representing whole bytes");
        }
        try {
            BigDecimal number = new BigDecimal(trimmed);
            if (number.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Enter a nonnegative MiB value representing whole bytes");
            }
            BigDecimal bytes = number.multiply(MIB_MULTIPLIER).stripTrailingZeros();
            if (bytes.scale() > 0) {
                throw new IllegalArgumentException("Enter a nonnegative MiB value representing whole bytes");
            }
            return bytes.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Enter a nonnegative MiB value representing whole bytes", e);
        }
    }

    public static String bytesToMib(long bytes) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("Byte count must be nonnegative");
        }
        BigDecimal value = BigDecimal.valueOf(bytes)
                .divide(MIB_MULTIPLIER)
                .stripTrailingZeros();
        return value.toPlainString();
    }

    public static boolean matchesSize(long sizeBytes, long minimumBytes) {
        return sizeBytes >= minimumBytes;
    }
}
