package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;

/** Controls one bottle in a real brewing stand. The stand remains responsible for brewing and fuel use. */
public final class BrewingMachineAdapter implements MachineAdapter {
    public static final BrewingMachineAdapter INSTANCE = new BrewingMachineAdapter();
    public static final int BOTTLE = 0, REAGENT = 3, BLAZE = 4;
    private static final String EXPECTED_POWDER = "ExpectedPowder";
    private static final ResourceLocation RECIPE = ResourceLocation.fromNamespaceAndPath("itemexplorer", "brewing");

    private BrewingMachineAdapter() {}

    @Override public String kind() { return "brewing"; }
    @Override public boolean supports(BlockState state) { return state.is(Blocks.BREWING_STAND); }
    @Override public boolean acceptsInput(Level level, ItemStack input) {
        return !input.isEmpty() && input.getCount() == 1 && BrewingRecipeRegistry.isValidInput(input);
    }
    @Override public boolean acceptsFuel(ItemStack reagent) {
        return !reagent.isEmpty() && BrewingRecipeRegistry.isValidIngredient(reagent);
    }
    @Override public RecipeSpec resolve(Level level, ItemStack input, ItemStack reagent) {
        if (!acceptsFuel(reagent)) throw failure("production_invalid_fuel");
        if (!acceptsInput(level, input)) throw failure("production_invalid_recipe");
        ItemStack result = BrewingRecipeRegistry.getOutput(input.copy(), reagent.copy());
        // One owned bottle must turn into exactly one different bottle. A registry entry may never
        // make an unchanged bottle look like a completed operation to the generic executor.
        if (result.isEmpty() || result.getCount() != 1 || same(result, input))
            throw failure("production_invalid_recipe");
        return new RecipeSpec(RECIPE, result.copy());
    }

    private static BrewingStandBlockEntity stand(BlockEntity machine) {
        if (!(machine instanceof BrewingStandBlockEntity stand)) throw failure("production_machine_offline");
        return stand;
    }
    private static boolean same(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b.isEmpty() : !b.isEmpty() && a.getCount() == b.getCount()
                && ItemStack.isSameItemSameTags(a, b);
    }
    private static IllegalArgumentException failure(String reason) { return new IllegalArgumentException(reason); }
    private static ItemStack bottle(BlockEntity machine, FurnaceProgram program) {
        ItemStack bottle = stand(machine).getItem(BOTTLE);
        if (!bottle.isEmpty() && !same(bottle, program.input()) && !same(bottle, program.result()))
            throw failure("production_interference");
        return bottle;
    }
    @Override public ItemStack input(BlockEntity machine, FurnaceProgram program) {
        ItemStack bottle = bottle(machine, program);
        return same(bottle, program.input()) ? bottle : ItemStack.EMPTY;
    }
    @Override public ItemStack output(BlockEntity machine, FurnaceProgram program) {
        ItemStack bottle = bottle(machine, program);
        return same(bottle, program.result()) ? bottle : ItemStack.EMPTY;
    }
    @Override public ItemStack fuel(BlockEntity machine) { return stand(machine).getItem(REAGENT); }
    @Override public void setInput(BlockEntity machine, ItemStack input) { stand(machine).setItem(BOTTLE, input); }
    @Override public void setFuel(BlockEntity machine, ItemStack reagent) { stand(machine).setItem(REAGENT, reagent); }
    @Override public void removeFuel(BlockEntity machine, int count) { stand(machine).removeItem(REAGENT, count); }
    @Override public void removeOutput(BlockEntity machine, int count) { stand(machine).removeItem(BOTTLE, count); }
    @Override public boolean empty(BlockEntity machine) { return stand(machine).isEmpty() && !burning(machine); }
    @Override public boolean burning(BlockEntity machine) { return stand(machine).saveWithoutMetadata().getShort("BrewTime") > 0; }
    @Override public boolean needsFuel(BlockEntity machine) { return fuel(machine).isEmpty(); }

    @Override public CompoundTag freshState(BlockEntity machine) {
        CompoundTag state = new CompoundTag();
        state.putBoolean(EXPECTED_POWDER, false);
        return state;
    }
    @Override public void validateSavedState(CompoundTag state) {
        if (state.getAllKeys().size() != 1 || !state.contains(EXPECTED_POWDER, Tag.TAG_BYTE)
                || (state.getByte(EXPECTED_POWDER) != 0 && state.getByte(EXPECTED_POWDER) != 1))
            throw failure("production_invalid_data");
    }
    @Override public void validateExtraSlots(BlockEntity machine, CompoundTag state) {
        validateSavedState(state);
        BrewingStandBlockEntity stand = stand(machine);
        if (!stand.getItem(1).isEmpty() || !stand.getItem(2).isEmpty()) throw failure("production_interference");
        ItemStack powder = stand.getItem(BLAZE);
        if (!state.getBoolean(EXPECTED_POWDER)) {
            if (!powder.isEmpty()) throw failure("production_interference");
            return;
        }
        if (same(powder, new ItemStack(Items.BLAZE_POWDER))) return;
        CompoundTag data = stand.saveWithoutMetadata();
        // The only permitted transition is our one powder becoming the stand's vanilla fuel reserve.
        // No further bottle can be submitted while this ownership check is pending, including when
        // disconnected, so a consumed powder necessarily leaves fuel or a brew in progress.
        if (powder.isEmpty() && (data.getByte("Fuel") > 0 || data.getShort("BrewTime") > 0)) {
            state.putBoolean(EXPECTED_POWDER, false);
            return;
        }
        throw failure("production_interference");
    }
    @Override public void prepare(BlockEntity machine, StorageInventory inventory, FurnaceProgram program, CompoundTag state) {
        BrewingStandBlockEntity stand = stand(machine);
        CompoundTag data = stand.saveWithoutMetadata();
        if (data.getByte("Fuel") > 0 || data.getShort("BrewTime") > 0 || state.getBoolean(EXPECTED_POWDER)) return;
        if (!stand.getItem(BLAZE).isEmpty()) throw failure("production_interference");
        ItemStack sample = new ItemStack(Items.BLAZE_POWDER);
        var powder = inventory.entries().stream().filter(e -> e.folder() == program.fuelFolder()
                && ItemStack.isSameItemSameTags(e.stack(), sample)).findFirst().orElse(null);
        if (powder == null) throw failure("production_missing_blaze_powder");
        // When blaze powder is also the recipe reagent, reserve a second unit before spending fuel.
        if (ItemStack.isSameItemSameTags(program.fuel(), sample) && fuel(machine).isEmpty() && powder.count() < 2)
            throw failure("production_missing_fuel");
        ItemStack taken = inventory.take(powder.id(), 1, false);
        stand.setItem(BLAZE, taken);
        state.putBoolean(EXPECTED_POWDER, true);
        stand.setChanged();
    }
}
