package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class StorageBlockEntity extends BlockEntity implements MenuProvider {
    private final StorageInventory inventory = new StorageInventory(this::setChanged);

    public StorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.STORAGE_ENTITY.get(), pos, state);
    }

    public StorageInventory inventory() { return inventory; }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Storage", inventory.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        inventory.load(tag.getCompound("Storage"));
    }

    @Override
    public Component getDisplayName() { return Component.translatable("block.itemexplorer.storage_terminal"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new StorageMenu(id, playerInventory, getBlockPos(), this);
    }
}
