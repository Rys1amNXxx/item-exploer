package dev.itemexplorer.production;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;

/** Furnace, blast furnace and smoker share slots but resolve recipes and fuel against their own type. */
public final class FurnaceMachineAdapter implements MachineAdapter {
    public static final FurnaceMachineAdapter FURNACE = new FurnaceMachineAdapter("furnace", Blocks.FURNACE, RecipeType.SMELTING);
    public static final FurnaceMachineAdapter BLAST_FURNACE = new FurnaceMachineAdapter("blast_furnace", Blocks.BLAST_FURNACE, RecipeType.BLASTING);
    public static final FurnaceMachineAdapter SMOKER = new FurnaceMachineAdapter("smoker", Blocks.SMOKER, RecipeType.SMOKING);
    private final String kind;
    private final Block block;
    private final RecipeType<? extends AbstractCookingRecipe> recipeType;
    private FurnaceMachineAdapter(String kind, Block block, RecipeType<? extends AbstractCookingRecipe> recipeType) {
        this.kind = kind; this.block = block; this.recipeType = recipeType;
    }
    @Override public String kind() { return kind; }
    @Override public boolean supports(BlockState state) { return state.is(block); }
    private AbstractCookingRecipe recipe(Level level, ItemStack input) {
        return level.getRecipeManager().getRecipeFor(recipeType, new SimpleContainer(input), level).orElse(null);
    }
    @Override public RecipeSpec resolve(Level level, ItemStack input, ItemStack fuel) {
        AbstractCookingRecipe recipe = recipe(level, input);
        if (recipe == null) throw new IllegalArgumentException("production_invalid_recipe");
        if (!acceptsFuel(fuel)) throw new IllegalArgumentException("production_invalid_fuel");
        return new RecipeSpec(recipe.getId(), recipe.assemble(new SimpleContainer(input), level.registryAccess()));
    }
    @Override public boolean acceptsInput(Level level, ItemStack input) { return recipe(level, input) != null; }
    @Override public boolean acceptsFuel(ItemStack fuel) { return ForgeHooks.getBurnTime(fuel, recipeType) > 0; }
    private static AbstractFurnaceBlockEntity furnace(BlockEntity machine) { return (AbstractFurnaceBlockEntity) machine; }
    @Override public ItemStack input(BlockEntity machine, FurnaceProgram program) { return furnace(machine).getItem(0); }
    @Override public ItemStack fuel(BlockEntity machine) { return furnace(machine).getItem(1); }
    @Override public ItemStack output(BlockEntity machine, FurnaceProgram program) { return furnace(machine).getItem(2); }
    @Override public void setInput(BlockEntity machine, ItemStack stack) { furnace(machine).setItem(0, stack); machine.setChanged(); }
    @Override public void setFuel(BlockEntity machine, ItemStack stack) { furnace(machine).setItem(1, stack); machine.setChanged(); }
    @Override public void removeFuel(BlockEntity machine, int count) { furnace(machine).removeItem(1, count); machine.setChanged(); }
    @Override public void removeOutput(BlockEntity machine, int count) { furnace(machine).removeItem(2, count); machine.setChanged(); }
    @Override public boolean empty(BlockEntity machine) { return furnace(machine).isEmpty(); }
    @Override public boolean burning(BlockEntity machine) { return machine.saveWithoutMetadata().getShort("BurnTime") > 0; }
    @Override public boolean needsFuel(BlockEntity machine) { return !burning(machine) && fuel(machine).isEmpty(); }
}
