package dev.itemexplorer.block;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.production.ProgramLibrary;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class StorageBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final StorageInventory inventory = new StorageInventory(this::setChanged);
    private final ProgramLibrary programs = new ProgramLibrary(inventory, this::setChanged);
    private final String accessSession = java.util.UUID.randomUUID().toString();
    // Persisted separately from the transient menu session: replacing a terminal must not retarget a job.
    private java.util.UUID productionIdentity = java.util.UUID.randomUUID();
    private final dev.itemexplorer.transfer.TransferConfig transferConfig = new dev.itemexplorer.transfer.TransferConfig();
    private final dev.itemexplorer.portable.PortableConfig portableConfig = new dev.itemexplorer.portable.PortableConfig();
    private boolean productionIdentityNeedsSave = true;
    private java.util.UUID defaultVolume;
    private Path recoveryArchive;
    private Path programRecoveryArchive;
    // Fixed and portable windows alike; never saved, so a restart always starts idle.
    private final Set<StorageMenu> viewers = Collections.newSetFromMap(new IdentityHashMap<>());

    public StorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.STORAGE_ENTITY.get(), pos, state);
    }

    public StorageInventory inventory() { return inventory; }
    public ProgramLibrary programs() { return programs; }
    public String accessSession() { return accessSession; }
    public String defaultVolume() { return defaultVolume == null ? "" : defaultVolume.toString(); }
    public void setDefaultVolume(String volume) {
        java.util.UUID selected = volume.isEmpty() ? null : java.util.UUID.fromString(volume);
        if (java.util.Objects.equals(defaultVolume, selected)) return;
        defaultVolume = selected;
        setChanged();
    }
    public dev.itemexplorer.transfer.TransferConfig transferConfig() { return transferConfig; }
    public boolean portableEnabled() { return portableConfig.enabled(); }
    public int portableDeviceCount() { return portableConfig.deviceCount(); }
    public long portableRevision() { return portableConfig.revision(); }
    public void setPortableEnabled(boolean enabled) {
        portableConfig.setEnabled(enabled);
        setChanged();
    }
    public void revokePortableDevices() {
        portableConfig.revokeAll();
        setChanged();
    }
    public java.util.UUID grantPortableDevice(java.util.UUID device, java.util.UUID previousGrant) {
        java.util.UUID grant = portableConfig.grant(device, previousGrant);
        setChanged();
        return grant;
    }
    public boolean acceptsPortableDevice(java.util.UUID device, java.util.UUID grant) {
        return portableConfig.accepts(device, grant);
    }
    public java.util.UUID productionIdentity() {
        if (productionIdentityNeedsSave) { setChanged(); productionIdentityNeedsSave = false; }
        return productionIdentity;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Storage", inventory.save());
        tag.put("Programs", programs.save());
        tag.putUUID("ProductionIdentity", productionIdentity);
        tag.put("RemoteReceiver", transferConfig.save());
        tag.put("PortableAccess", portableConfig.save());
        if (defaultVolume != null) tag.putUUID("DefaultVolume", defaultVolume);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        inventory.load(tag.get("Storage"));
        programs.load(tag.get("Programs"));
        productionIdentity = tag.hasUUID("ProductionIdentity") ? tag.getUUID("ProductionIdentity") : java.util.UUID.randomUUID();
        productionIdentityNeedsSave = !tag.hasUUID("ProductionIdentity");
        transferConfig.load(tag.get("RemoteReceiver"));
        portableConfig.load(tag.get("PortableAccess"));
        defaultVolume = tag.hasUUID("DefaultVolume") ? tag.getUUID("DefaultVolume") : null;
        recoveryArchive = null;
        programRecoveryArchive = null;
        archiveProtectedData();
        refreshScreen();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        archiveProtectedData();
        dev.itemexplorer.transfer.RemoteTransfers.register(this);
        // Chunk loads run this from the block-entity tick, after the saved (possibly stale) state is in place.
        refreshScreen();
    }

    public void openedBy(StorageMenu menu) { if (viewers.add(menu)) refreshScreen(); }
    public void closedBy(StorageMenu menu) { if (viewers.remove(menu)) refreshScreen(); }

    public StorageBlock.Screen screen() {
        if (inventory.isLocked() || programs.isLocked()) return StorageBlock.Screen.LOCKED;
        return viewers.isEmpty() ? StorageBlock.Screen.IDLE : StorageBlock.Screen.ACTIVE;
    }

    /** Appearance only: clients follow the block state, and no neighbor updates are sent. */
    public void refreshScreen() {
        if (level == null || level.isClientSide || isRemoved()) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(StorageBlock.SCREEN) || state.getValue(StorageBlock.SCREEN) == screen()) return;
        level.setBlock(worldPosition, state.setValue(StorageBlock.SCREEN, screen()), Block.UPDATE_CLIENTS);
    }

    @Override public void setRemoved() {
        dev.itemexplorer.transfer.RemoteTransfers.unregister(this);
        super.setRemoved();
    }

    /** Retry before removal as well, so a successful archive outlives the block. */
    public void archiveProtectedData() {
        if (programs.isLocked() && programRecoveryArchive == null && level != null && !level.isClientSide && level.getServer() != null) {
            try {
                programRecoveryArchive = StorageRecovery.archive(level.getServer().getWorldPath(LevelResource.ROOT).resolve("itemexplorer-recovery"),
                        programs.save(), level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), "terminal_programs");
            } catch (IOException | RuntimeException failure) { LOGGER.error("Cannot archive terminal programs at {}", worldPosition, failure); }
        }
        if (!inventory.isLocked() || recoveryArchive != null || level == null || level.isClientSide || level.getServer() == null) return;
        try {
            recoveryArchive = StorageRecovery.archive(level.getServer().getWorldPath(LevelResource.ROOT).resolve("itemexplorer-recovery"),
                    inventory.save(), level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), inventory.loadProblem());
            LOGGER.error("Item Explorer terminal at {} in {} is locked ({}). Original data archived at {}",
                    worldPosition, level.dimension().location(), inventory.loadProblem(), recoveryArchive);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Cannot archive locked Item Explorer terminal at {} in {}. Preserve the world backup and do not remove this block. Reason: {}",
                    worldPosition, level.dimension().location(), inventory.loadProblem(), failure);
        }
    }

    public Path recoveryArchive() { return recoveryArchive; }

    @Override
    public Component getDisplayName() { return Component.translatable("block.itemexplorer.storage_terminal"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new StorageMenu(id, playerInventory, getBlockPos(), this);
    }
}
