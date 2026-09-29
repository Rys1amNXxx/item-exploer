package dev.itemexplorer.storage;

public record StorageLimits(long capacity, int entries, int folders, int format) {
    public static final StorageLimits LOCAL = new StorageLimits(4096, 128, 64, 1);

    public StorageLimits {
        if (capacity <= 0 || entries < 1 || entries > 4096 || folders < 1 || folders > 256
                || (format != 1 && format != 2) || (format == 1 && capacity > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("invalid_limits");
        }
    }
}
