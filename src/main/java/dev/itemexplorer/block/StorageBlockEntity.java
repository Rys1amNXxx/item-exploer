package dev.itemexplorer.block;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

public final class StorageBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final StorageInventory inventory = new StorageInventory(this::setChanged);
    private final String accessSession = java.util.UUID.randomUUID().toString();
    private Path recoveryArchive;

    public StorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.STORAGE_ENTITY.get(), pos, state);
    }

    public StorageInventory inventory() { return inventory; }
    public String accessSession() { return accessSession; }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Storage", inventory.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        inventory.load(tag.get("Storage"));
        recoveryArchive = null;
        archiveProtectedData();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        archiveProtectedData();
    }

    /** Retry before removal as well, so a successful archive outlives the block. */
    public void archiveProtectedData() {
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
