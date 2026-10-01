package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Machine-specific slots, recipes and preparation; the interface owns only the running job. */
public interface MachineAdapter {
    record RecipeSpec(ResourceLocation recipe, ItemStack result) {}
    String kind();
    boolean supports(BlockState state);
    default BlockEntity find(Level level, BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos) || !supports(level.getBlockState(pos))) return null;
        BlockEntity entity = level.getBlockEntity(pos); return entity == null || entity.isRemoved() ? null : entity;
    }
    RecipeSpec resolve(Level level, ItemStack input, ItemStack fuel);
    default boolean acceptsInput(Level level, ItemStack input) { return true; }
    default boolean acceptsFuel(ItemStack fuel) { return true; }
    ItemStack input(BlockEntity machine, FurnaceProgram program);
    ItemStack fuel(BlockEntity machine);
    ItemStack output(BlockEntity machine, FurnaceProgram program);
    void setInput(BlockEntity machine, ItemStack stack);
    void setFuel(BlockEntity machine, ItemStack stack);
    void removeFuel(BlockEntity machine, int count);
    void removeOutput(BlockEntity machine, int count);
    boolean empty(BlockEntity machine);
    boolean burning(BlockEntity machine);
    boolean needsFuel(BlockEntity machine);
    default CompoundTag freshState(BlockEntity machine) { return new CompoundTag(); }
    default void validateSavedState(CompoundTag state) { if (!state.isEmpty()) throw new IllegalArgumentException(); }
    default void validateExtraSlots(BlockEntity machine, CompoundTag state) {}
    default void prepare(BlockEntity machine, StorageInventory inventory, FurnaceProgram program, CompoundTag state) {}
}
