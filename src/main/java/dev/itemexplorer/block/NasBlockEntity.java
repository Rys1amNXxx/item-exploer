package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskSavedData;
import dev.itemexplorer.menu.NasMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Arrays;
import java.util.UUID;

public final class NasBlockEntity extends BlockEntity implements MenuProvider {
    public static final int BAYS = 4;
    private UUID nasId = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final ItemStack[] disks = new ItemStack[BAYS];
    private final long[] generations = new long[BAYS];
    private Tag protectedData;
    private long revision;
    // Visual-only data is not persisted and never contains disk IDs, names or inventory.
    private int appearance;
    private final UUID[] observedDisks = new UUID[BAYS];
    private final long[] observedTransfers = new long[BAYS], activeUntil = new long[BAYS];

    public boolean hasVisibleDisk(int slot) { return (appearance & (1 << slot)) != 0; }
    public boolean isVisibleOnline(int slot) { return (appearance & (1 << (4 + slot))) != 0; }
    public int visibleTier(int slot) { return (appearance >> (8 + slot * 2)) & 3; }
    public boolean isVisibleActive(int slot) { return (appearance & (1 << (16 + slot))) != 0; }
    public boolean isVisibleLocked() { return (appearance & (1 << 20)) != 0; }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NasBlockEntity nas) {
        // Coalesce bursts, sampling four small counters at most four times per second.
        if ((level.getGameTime() + pos.asLong()) % 5 == 0) nas.refreshAppearance();
    }

    private void refreshAppearance() {
        if (!online() || !level.getBlockState(worldPosition).is(ModContent.NAS_BLOCK.get())) return;
        int next = isLocked() ? 1 << 20 : 0;
        long now = level.getGameTime();
        for (int i = 0; i < BAYS; i++) {
            if (!disks[i].isEmpty()) {
                next |= 1 << i;
                next |= ((DiskItem) disks[i].getItem()).tier().ordinal() << (8 + i * 2);
            }
            DiskSavedData data = volume(i);
            if (data == null) { observedDisks[i] = null; activeUntil[i] = 0; continue; }
            next |= 1 << (4 + i);
            long transfers = data.inventory().transferRevision();
            if (data.id().equals(observedDisks[i]) && transfers != observedTransfers[i]) activeUntil[i] = now + 8;
            observedDisks[i] = data.id(); observedTransfers[i] = transfers;
            if (now < activeUntil[i]) next |= 1 << (16 + i);
        }
        if (appearance != next) {
            appearance = next;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Appearance", appearance); return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void handleUpdateTag(CompoundTag tag) { appearance = tag.getInt("Appearance") & 0x1FFFFF; }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) handleUpdateTag(packet.getTag());
    }

    public NasBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.NAS_ENTITY.get(), pos, state);
        Arrays.fill(disks, ItemStack.EMPTY);
    }
    public long revision() { return revision; }
    public boolean isLocked() { return protectedData != null; }
    public ItemStack disk(int slot) { return validSlot(slot) ? disks[slot].copy() : ItemStack.EMPTY; }
    public String stamp(int slot) { return session + ":" + slot + ":" + generations[slot]; }
    private static boolean validSlot(int slot) { return slot >= 0 && slot < BAYS; }
    private ServerLevel server() { return (ServerLevel) level; }
    private boolean online() { return level instanceof ServerLevel && !isRemoved() && level.hasChunkAt(worldPosition) && level.getBlockEntity(worldPosition) == this; }
    private CompoundTag owner(int slot) { return DiskSavedData.owner(server(), worldPosition, nasId, slot); }
    private void touch(int slot) { generations[slot]++; revision++; setChanged(); refreshAppearance(); }

    public DiskSavedData volume(int slot) {
        if (!online() || !validSlot(slot) || isLocked()) return null;
        UUID id = DiskItem.id(disks[slot]);
        if (id == null || !(disks[slot].getItem() instanceof DiskItem item)) return null;
        DiskSavedData data = DiskSavedData.find(server(), id);
        if (data != null && !data.isLocked() && data.id().equals(id) && data.tier() == item.tier() && data.ownedBy(owner(slot))) return data;
        return null;
    }

    /** Read-only mount diagnosis for attached interfaces; never claims a disk. */
    public String mountStatus(int slot) {
        return online() && validSlot(slot) ? mount(slot, false) : "disk_offline";
    }

    private String mount(int slot, boolean claim) {
        if (isLocked()) return "storage_locked";
        if (disks[slot].isEmpty()) return "empty_bay";
        UUID id = DiskItem.id(disks[slot]);
        if (id == null) return "disk_missing";
        DiskSavedData data = DiskSavedData.find(server(), id);
        if (data == null) return "disk_missing";
        if (claim) data.archiveIfLocked(server(), worldPosition);
        if (data.isLocked()) return "storage_locked";
        if (!data.id().equals(id) || data.tier() != ((DiskItem) disks[slot].getItem()).tier()) return "disk_mismatch";
        return (claim ? data.claim(owner(slot)) : data.ownedBy(owner(slot))) ? "online" : "disk_conflict";
    }

    public void install(int slot, ItemStack source) {
        if (!online() || !validSlot(slot) || isLocked() || !disks[slot].isEmpty()) throw new IllegalArgumentException("invalid_bay");
        if (!(source.getItem() instanceof DiskItem item) || source.getCount() != 1) throw new IllegalArgumentException("disk_only");
        DiskItem.checkMetadata(source);
        ItemStack disk = source.copy();
        UUID id = DiskItem.id(disk);
        DiskSavedData data;
        if (id == null) {
            if (disk.hasTag() && disk.getTag().contains("DiskId")) throw new IllegalArgumentException("disk_missing");
            // Validate the name before allocating persistent data.
            String name = disk.hasCustomHoverName() ? dev.itemexplorer.storage.StorageInventory.validName(disk.getHoverName().getString()) : "";
            data = DiskSavedData.create(server(), item.tier());
            if (!name.isEmpty()) data.rename(name);
            disk.getOrCreateTag().putUUID("DiskId", data.id());
        } else {
            data = DiskSavedData.find(server(), id);
            if (data == null) throw new IllegalArgumentException("disk_missing");
            data.archiveIfLocked(server(), worldPosition);
            if (data.isLocked()) throw new IllegalArgumentException("storage_locked");
            if (!data.id().equals(id) || data.tier() != item.tier()) throw new IllegalArgumentException("disk_mismatch");
        }
        updateName(disk, data);
        DiskItem.checkMetadata(disk);
        if (!data.claim(owner(slot))) throw new IllegalArgumentException("disk_conflict");
        disks[slot] = disk;
        touch(slot);
    }

    /** Caller must secure space before removing a disk. This never deletes the authoritative volume. */
    public ItemStack eject(int slot) {
        if (!online() || !validSlot(slot) || isLocked()) throw new IllegalArgumentException("invalid_bay");
        ItemStack result = disks[slot];
        if (result.isEmpty()) return ItemStack.EMPTY;
        UUID id = DiskItem.id(result);
        DiskSavedData data = id == null ? null : DiskSavedData.find(server(), id);
        if (data != null) {
            data.archiveIfLocked(server(), worldPosition);
            if (data.ownedBy(owner(slot))) { updateName(result, data); data.release(owner(slot)); }
        }
        disks[slot] = ItemStack.EMPTY;
        touch(slot);
        return result;
    }

    private static void updateName(ItemStack disk, DiskSavedData data) {
        if (data.name().isEmpty()) disk.resetHoverName();
        else disk.setHoverName(Component.literal(data.name()));
    }

    public void rename(int slot, String name) {
        DiskSavedData data = volume(slot);
        if (data == null) throw new IllegalArgumentException("disk_offline");
        ItemStack renamed = disks[slot].copy();
        renamed.setHoverName(Component.literal(dev.itemexplorer.storage.StorageInventory.validName(name)));
        DiskItem.checkMetadata(renamed);
        data.rename(name); updateName(disks[slot], data); revision++; setChanged();
    }

    public CompoundTag view() {
        CompoundTag view = new CompoundTag(); view.putLong("Revision", revision); view.putBoolean("Locked", isLocked());
        ListTag bays = new ListTag();
        for (int i = 0; i < BAYS; i++) {
            CompoundTag bay = new CompoundTag(); bay.putInt("Slot", i); bay.put("Stack", disks[i].save(new CompoundTag()));
            bay.putString("Status", mount(i, false));
            DiskSavedData data = volume(i);
            if (data != null) { bay.putLong("Total", data.inventory().total()); bay.putLong("Capacity", data.inventory().capacity()); }
            bays.add(bay);
        }
        view.put("Bays", bays); return view;
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            for (int i = 0; i < BAYS; i++) mount(i, true);
            archiveProtectedData();
            refreshAppearance();
        }
    }

    public void archiveProtectedData() {
        if (!isLocked() || level == null || level.isClientSide) return;
        try {
            var path = dev.itemexplorer.storage.StorageRecovery.archive(server().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("itemexplorer-recovery"), protectedData, level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), "nas_data_locked");
            com.mojang.logging.LogUtils.getLogger().error("Protected NAS at {} archived at {}", worldPosition, path);
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Cannot archive protected NAS at {}", worldPosition, failure); }
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (isLocked()) { tag.put("Nas", protectedData.copy()); return; }
        CompoundTag data = new CompoundTag(); data.putInt("Version", 1); data.putUUID("Id", nasId);
        ListTag bays = new ListTag();
        for (int i = 0; i < BAYS; i++) { CompoundTag bay = new CompoundTag(); bay.putInt("Slot", i); bay.put("Stack", disks[i].save(new CompoundTag())); bays.add(bay); }
        data.put("Bays", bays); tag.put("Nas", data);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag); Arrays.fill(disks, ItemStack.EMPTY); protectedData = null;
        appearance = 0; Arrays.fill(observedDisks, null); Arrays.fill(activeUntil, 0);
        if (!tag.contains("Nas")) return;
        Tag raw = tag.get("Nas");
        try {
            if (!(raw instanceof CompoundTag data) || !data.contains("Version", Tag.TAG_INT) || data.getInt("Version") != 1
                    || !data.hasUUID("Id") || !data.contains("Bays", Tag.TAG_LIST)) throw new IllegalArgumentException();
            ListTag bays = data.getList("Bays", Tag.TAG_COMPOUND);
            if (bays.size() != BAYS) throw new IllegalArgumentException();
            ItemStack[] parsed = new ItemStack[BAYS];
            java.util.Set<UUID> identities = new java.util.HashSet<>();
            for (Tag rawBay : bays) {
                CompoundTag bay = (CompoundTag) rawBay;
                int slot = bay.getInt("Slot");
                if (!bay.contains("Slot", Tag.TAG_INT) || !validSlot(slot) || parsed[slot] != null || !bay.contains("Stack", Tag.TAG_COMPOUND)) throw new IllegalArgumentException();
                ItemStack disk = ItemStack.of(bay.getCompound("Stack"));
                if (!disk.isEmpty() && (!(disk.getItem() instanceof DiskItem) || disk.getCount() != 1 || DiskItem.id(disk) == null)) throw new IllegalArgumentException();
                if (!disk.isEmpty() && !identities.add(DiskItem.id(disk))) throw new IllegalArgumentException();
                DiskItem.checkMetadata(disk);
                if (!disk.save(new CompoundTag()).equals(bay.getCompound("Stack"))) throw new IllegalArgumentException();
                parsed[slot] = disk;
            }
            nasId = data.getUUID("Id"); System.arraycopy(parsed, 0, disks, 0, BAYS);
        } catch (RuntimeException failure) { protectedData = raw.copy(); }
        for (int i = 0; i < BAYS; i++) generations[i]++;
        revision++;
    }

    @Override public Component getDisplayName() { return Component.translatable("block.itemexplorer.nas"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new NasMenu(id, inventory, worldPosition, this); }
}
