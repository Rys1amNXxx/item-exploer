package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.logistics.FolderItemHandler;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.util.UUID;

public final class LogisticsPortBlockEntity extends BlockEntity implements MenuProvider {
    public record Target(BlockEntity host, StorageInventory inventory, String stamp) {
        public boolean sameMount(Target other) { return other != null && host == other.host && inventory == other.inventory && stamp.equals(other.stamp); }
    }
    private String volume = "";
    private int folder;
    private boolean input, output, recursive;
    private long revision, epoch, activeUntil;
    private Tag protectedData;
    private boolean archived;
    private FolderItemHandler handler;
    private LazyOptional<IItemHandler> capability = LazyOptional.empty();

    public LogisticsPortBlockEntity(BlockPos pos, BlockState state) { super(ModContent.LOGISTICS_ENTITY.get(), pos, state); }
    public long revision() { return revision; }
    public String volume() { return volume; }
    public int folder() { return folder; }
    public boolean allowInput() { return input; }
    public boolean allowOutput() { return output; }
    public boolean recursive() { return recursive; }
    public boolean isLocked() { return protectedData != null; }
    private BlockEntity loaded(BlockPos pos) { return level != null && level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null; }
    private boolean online() { return level instanceof ServerLevel && !isRemoved() && loaded(worldPosition) == this; }
    private BlockEntity host() {
        if (!online()) return null;
        BlockEntity host = loaded(worldPosition.relative(getBlockState().getValue(LogisticsPortBlock.FACING).getOpposite()));
        return host != null && !host.isRemoved() ? host : null;
    }
    private NasBlockEntity nas(BlockEntity host) {
        if (host instanceof NasBlockEntity nas) return nas;
        if (host instanceof StorageBlockEntity terminal) {
            BlockEntity back = loaded(terminal.getBlockPos().relative(terminal.getBlockState().getValue(HorizontalDirectionalBlock.FACING).getOpposite()));
            if (back instanceof NasBlockEntity nas && !nas.isRemoved()) return nas;
        }
        return null;
    }
    public Target resolveVolume(String id) {
        BlockEntity host = host();
        if (id.equals("local") && host instanceof StorageBlockEntity terminal)
            return new Target(terminal, terminal.inventory(), terminal.accessSession());
        NasBlockEntity nas = nas(host);
        if (nas == null || id.isEmpty()) return null;
        for (int i = 0; i < NasBlockEntity.BAYS; i++) {
            if (!id.equals(String.valueOf(DiskItem.id(nas.disk(i))))) continue;
            var disk = nas.volume(i);
            return disk == null ? null : new Target(host, disk.inventory(), nas.stamp(i));
        }
        return null;
    }
    public Target resolve() {
        if (isLocked() || volume.isEmpty()) return null;
        Target target = resolveVolume(volume);
        return target == null || target.inventory().isLocked() || !target.inventory().hasFolder(folder) ? null : target;
    }
    public boolean isCurrent(Target target, long expectedEpoch) { return epoch == expectedEpoch && target.sameMount(resolve()); }
    public boolean includes(StorageInventory inventory, int candidate) {
        if (candidate == folder) return true;
        if (!recursive) return false;
        for (int depth = 0; depth <= StorageInventory.MAX_DEPTH; depth++) {
            var node = inventory.folder(candidate);
            if (node == null || node.parent() < 0) return false;
            if (node.parent() == folder) return true;
            candidate = node.parent();
        }
        return false;
    }
    /** Does not include inventory revisions: an active pipe must not prevent configuring its port. */
    public String topologyStamp() {
        BlockEntity host = host();
        String stamp = host instanceof StorageBlockEntity terminal ? terminal.accessSession() : "";
        NasBlockEntity nas = nas(host);
        if (nas != null) for (int i = 0; i < NasBlockEntity.BAYS; i++) stamp += ":" + nas.stamp(i) + ":" + (nas.volume(i) != null);
        return stamp;
    }
    public void configure(String id, int folder, boolean input, boolean output, boolean recursive) {
        if (isLocked()) throw new IllegalArgumentException("storage_locked");
        Target target = resolveVolume(id);
        if (target == null) throw new IllegalArgumentException("disk_offline");
        if (target.inventory().isLocked()) throw new IllegalArgumentException("storage_locked");
        if (!target.inventory().hasFolder(folder)) throw new IllegalArgumentException("invalid_folder");
        this.volume = id; this.folder = folder; this.input = input; this.output = output; this.recursive = recursive;
        revision++; resetHandler(); setChanged(); updateStatus();
    }
    public void disconnect() {
        if (isLocked()) throw new IllegalArgumentException("storage_locked");
        volume = ""; folder = 0; input = output = recursive = false;
        revision++; resetHandler(); setChanged(); updateStatus();
    }
    public void activity() { if (level != null) activeUntil = level.getGameTime() + 8; }
    private void resetHandler() {
        epoch++; handler = null; activeUntil = 0;
        LazyOptional<IItemHandler> old = capability; capability = LazyOptional.empty(); old.invalidate();
    }
    private void refreshHandler() {
        Target target = resolve();
        if (handler != null && !handler.matches(target, epoch)) resetHandler();
        if (handler == null && target != null) {
            handler = new FolderItemHandler(this, target, epoch);
            IItemHandler created = handler; capability = LazyOptional.of(() -> created);
        }
    }
    @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER && online() && (side == null || side == getBlockState().getValue(LogisticsPortBlock.FACING))) {
            refreshHandler(); return capability.cast();
        }
        return super.getCapability(cap, side);
    }
    @Override public void invalidateCaps() { super.invalidateCaps(); resetHandler(); }
    @Override public void reviveCaps() { super.reviveCaps(); resetHandler(); }
    public String status() {
        if (isLocked()) return "storage_locked";
        if (volume.isEmpty()) return "port_unconfigured";
        Target target = resolveVolume(volume);
        if (target == null) {
            NasBlockEntity nas = nas(host());
            if (nas != null) {
                if (nas.isLocked()) return "storage_locked";
                for (int i = 0; i < NasBlockEntity.BAYS; i++)
                    if (volume.equals(String.valueOf(DiskItem.id(nas.disk(i))))) return nas.mountStatus(i);
            }
            return "disk_offline";
        }
        if (target.inventory().isLocked()) return "storage_locked";
        if (!target.inventory().hasFolder(folder)) return "invalid_folder";
        if (!input && !output) return "port_disabled";
        return target.inventory().total() >= target.inventory().capacity() ? "port_full" : "online";
    }
    private void updateStatus() {
        if (!online()) return;
        String status = status();
        int light = status.equals("online") || status.equals("port_full") ? level.getGameTime() < activeUntil ? 2 : 1
                : status.equals("storage_locked") || status.equals("invalid_folder") || status.equals("disk_missing")
                    || status.equals("disk_mismatch") || status.equals("disk_conflict") ? 3 : 0;
        if (getBlockState().getValue(LogisticsPortBlock.STATUS) != light)
            level.setBlock(worldPosition, getBlockState().setValue(LogisticsPortBlock.STATUS, light), Block.UPDATE_CLIENTS);
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, LogisticsPortBlockEntity port) {
        if ((level.getGameTime() + pos.asLong()) % 5 == 0) {
            // Also repairs a saved CONNECTED=true from the earlier non-air neighbor check.
            BlockPos neighbor = pos.relative(state.getValue(LogisticsPortBlock.FACING));
            boolean connected = level.hasChunkAt(neighbor) && LogisticsPortBlock.hasConnectorNeighbor(level.getBlockState(neighbor));
            if (state.getValue(LogisticsPortBlock.CONNECTED) != connected)
                level.setBlock(pos, state.setValue(LogisticsPortBlock.CONNECTED, connected), Block.UPDATE_CLIENTS);
            port.refreshHandler(); port.updateStatus();
        }
    }
    public CompoundTag view(String selected) {
        CompoundTag view = new CompoundTag();
        view.putLong("Revision", revision); view.putString("Volume", volume); view.putInt("Folder", folder);
        view.putBoolean("Input", input); view.putBoolean("Output", output); view.putBoolean("Recursive", recursive);
        view.putBoolean("Locked", isLocked()); view.putString("Status", status()); view.putString("Selected", selected);
        ListTag volumes = new ListTag(); BlockEntity host = host();
        if (host instanceof StorageBlockEntity) { CompoundTag local = new CompoundTag(); local.putString("Id", "local"); local.putBoolean("Local", true); volumes.add(local); }
        NasBlockEntity nas = nas(host);
        if (nas != null) for (int i = 0; i < NasBlockEntity.BAYS; i++) {
            var data = nas.volume(i); if (data == null) continue;
            CompoundTag disk = new CompoundTag(); disk.putString("Id", data.id().toString()); disk.putInt("Bay", i + 1);
            disk.putString("Name", data.name()); disk.putString("Tier", data.tier().id()); volumes.add(disk);
        }
        view.put("Volumes", volumes);
        Target target = resolveVolume(selected); ListTag folders = new ListTag();
        if (target != null && !target.inventory().isLocked()) {
            for (var f : target.inventory().folders()) {
                CompoundTag node = new CompoundTag(); node.putInt("Id", f.id()); node.putInt("Parent", f.parent()); node.putString("Name", f.name()); folders.add(node);
            }
            view.putLong("Total", target.inventory().total()); view.putLong("Capacity", target.inventory().capacity());
        }
        view.put("Folders", folders); return view;
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (protectedData != null) { tag.put("Port", protectedData.copy()); return; }
        CompoundTag data = new CompoundTag(); data.putInt("Version", 1); data.putString("Volume", volume); data.putInt("Folder", folder);
        data.putBoolean("Input", input); data.putBoolean("Output", output); data.putBoolean("Recursive", recursive); tag.put("Port", data);
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag); resetHandler(); revision++; protectedData = null; archived = false;
        volume = ""; folder = 0; input = output = recursive = false;
        if (!tag.contains("Port")) return;
        Tag raw = tag.get("Port");
        try {
            if (!(raw instanceof CompoundTag data) || !data.contains("Version", Tag.TAG_INT) || data.getInt("Version") != 1
                    || !data.contains("Volume", Tag.TAG_STRING) || !data.contains("Folder", Tag.TAG_INT) || data.getInt("Folder") < 0
                    || !data.contains("Input", Tag.TAG_BYTE) || !data.contains("Output", Tag.TAG_BYTE) || !data.contains("Recursive", Tag.TAG_BYTE)) throw new IllegalArgumentException();
            String id = data.getString("Volume");
            if (!id.isEmpty() && !id.equals("local") && !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            volume = id; folder = data.getInt("Folder"); input = data.getBoolean("Input"); output = data.getBoolean("Output"); recursive = data.getBoolean("Recursive");
        } catch (RuntimeException invalid) { protectedData = raw.copy(); }
    }
    @Override public void onLoad() { super.onLoad(); archiveProtectedData(); }
    public void archiveProtectedData() {
        if (!isLocked() || archived || !(level instanceof ServerLevel server)) return;
        try {
            var path = StorageRecovery.archive(server.getServer().getWorldPath(LevelResource.ROOT).resolve("itemexplorer-recovery"),
                    protectedData, level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), "logistics_port_config");
            com.mojang.logging.LogUtils.getLogger().error("Protected logistics interface at {} archived at {}", worldPosition, path); archived = true;
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Cannot archive logistics interface at {}", worldPosition, failure); }
    }
    @Override public Component getDisplayName() { return Component.translatable("block.itemexplorer.logistics_port"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new LogisticsPortMenu(id, inventory, worldPosition, this); }
}
