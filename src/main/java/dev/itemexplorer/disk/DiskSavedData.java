package dev.itemexplorer.disk;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.UUID;

/** One authoritative file per disk in the overworld's data directory. */
public final class DiskSavedData extends SavedData {
    private UUID id;
    private DiskTier tier;
    private String name = "";
    private CompoundTag owner;
    private CompoundTag protectedData;
    private StorageInventory inventory;
    private boolean archived;

    private DiskSavedData(UUID id, DiskTier tier) {
        this.id = id; this.tier = tier;
        inventory = new StorageInventory(this::setDirty, tier.limits());
    }

    private static String key(UUID id) { return "itemexplorer_disk_" + id; }
    public static DiskSavedData find(ServerLevel level, UUID id) {
        return level.getServer().overworld().getDataStorage().get(DiskSavedData::load, key(id));
    }
    public static DiskSavedData create(ServerLevel level, DiskTier tier) {
        UUID id;
        do { id = UUID.randomUUID(); } while (find(level, id) != null);
        DiskSavedData data = new DiskSavedData(id, tier);
        level.getServer().overworld().getDataStorage().set(key(id), data);
        data.setDirty();
        return data;
    }
    public UUID id() { return id; }
    public DiskTier tier() { return tier; }
    public String name() { return name; }
    public StorageInventory inventory() { return inventory; }
    public boolean isLocked() { return protectedData != null || inventory.isLocked(); }
    public void rename(String name) {
        if (isLocked()) throw new IllegalArgumentException("storage_locked");
        this.name = StorageInventory.validName(name);
        setDirty();
    }
    public static CompoundTag owner(ServerLevel level, BlockPos pos, UUID nas, int slot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", level.dimension().location().toString()); tag.putLong("Pos", pos.asLong());
        tag.putUUID("Nas", nas); tag.putInt("Slot", slot);
        return tag;
    }
    public boolean claim(CompoundTag candidate) {
        if (protectedData != null || owner != null && !owner.equals(candidate)) return false;
        if (owner == null) { owner = candidate.copy(); setDirty(); }
        return true;
    }
    public boolean ownedBy(CompoundTag candidate) { return owner != null && owner.equals(candidate); }
    public void release(CompoundTag candidate) {
        if (ownedBy(candidate)) { owner = null; setDirty(); }
    }

    public static DiskSavedData load(CompoundTag tag) {
        DiskSavedData data = new DiskSavedData(new UUID(0, 0), DiskTier.K64);
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.hasUUID("Id")
                    || !tag.contains("Tier", Tag.TAG_STRING) || !tag.contains("Name", Tag.TAG_STRING)
                    || !tag.contains("Inventory")) throw new IllegalArgumentException("invalid_disk_data");
            data.id = tag.getUUID("Id"); data.tier = DiskTier.fromId(tag.getString("Tier"));
            data.name = tag.getString("Name");
            if (!data.name.isEmpty() && !StorageInventory.validName(data.name).equals(data.name)) throw new IllegalArgumentException("invalid_name");
            if (tag.contains("Owner")) {
                if (!tag.contains("Owner", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("invalid_owner");
                CompoundTag owner = tag.getCompound("Owner");
                if (!owner.hasUUID("Nas") || !owner.contains("Dimension", Tag.TAG_STRING) || !owner.contains("Pos", Tag.TAG_LONG)
                        || !owner.contains("Slot", Tag.TAG_INT) || owner.getInt("Slot") < 0 || owner.getInt("Slot") >= 4) throw new IllegalArgumentException("invalid_owner");
                data.owner = owner.copy();
            }
            data.inventory = new StorageInventory(data::setDirty, data.tier.limits());
            data.inventory.load(tag.get("Inventory"));
        } catch (RuntimeException failure) {
            data.protectedData = tag.copy();
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (protectedData != null) return protectedData.copy();
        tag.putInt("Version", 1); tag.putUUID("Id", id); tag.putString("Tier", tier.id()); tag.putString("Name", name);
        if (owner != null) tag.put("Owner", owner.copy());
        tag.put("Inventory", inventory.save());
        return tag;
    }

    public void archiveIfLocked(ServerLevel level, BlockPos pos) {
        if (!isLocked() || archived) return;
        try {
            var path = StorageRecovery.archive(level.getServer().getWorldPath(LevelResource.ROOT).resolve("itemexplorer-recovery"),
                    save(new CompoundTag()), level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), "disk_data_locked");
            LogUtils.getLogger().error("Protected disk {}: original data archived at {}", id, path);
            archived = true;
        } catch (IOException | RuntimeException failure) {
            LogUtils.getLogger().error("Cannot archive protected disk {}. Preserve the world backup.", id, failure);
        }
    }
}
