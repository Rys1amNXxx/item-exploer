package dev.itemexplorer.disk;

import dev.itemexplorer.storage.StorageLimits;

public enum DiskTier {
    K64("64k", 65_536L, 512), K256("256k", 262_144L, 1024),
    M1("1m", 1_048_576L, 2048), M16("16m", 16_777_216L, 4096);

    private final String id;
    private final StorageLimits limits;
    DiskTier(String id, long capacity, int entries) {
        this.id = id;
        this.limits = new StorageLimits(capacity, entries, 128, 2);
    }
    public String id() { return id; }
    public StorageLimits limits() { return limits; }
    public String translationKey() { return "item.itemexplorer.disk_" + id; }
    public static DiskTier fromId(String id) {
        for (DiskTier tier : values()) if (tier.id.equals(id)) return tier;
        throw new IllegalArgumentException("disk_tier_invalid");
    }
}
