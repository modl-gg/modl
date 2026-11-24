package gg.modl.minecraft.core.embedded;

import java.util.Locale;

final class Megabytes {
    private static final double BYTES_PER_MEGABYTE = 1_000_000.0;

    private Megabytes() {
    }

    static String of(long bytes) {
        return amount(bytes) + " MB";
    }

    static String amount(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MEGABYTE);
    }
}
