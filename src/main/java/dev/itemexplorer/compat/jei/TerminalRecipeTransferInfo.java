package dev.itemexplorer.compat.jei;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** The fixed and portable terminals share real crafting slots and the same menu type. */
public final class TerminalRecipeTransferInfo implements IRecipeTransferInfo<StorageMenu, CraftingRecipe> {
    private final IRecipeTransferError returnSpaceError;

    public TerminalRecipeTransferInfo(IRecipeTransferError returnSpaceError) {
        this.returnSpaceError = java.util.Objects.requireNonNull(returnSpaceError);
    }

    @Override public Class<StorageMenu> getContainerClass() { return StorageMenu.class; }
    @Override public Optional<MenuType<StorageMenu>> getMenuType() { return Optional.of(ModContent.STORAGE_MENU.get()); }
    @Override public RecipeType<CraftingRecipe> getRecipeType() { return RecipeTypes.CRAFTING; }
    @Override public boolean canHandle(StorageMenu menu, CraftingRecipe recipe) {
        return recipe.canCraftInDimensions(3, 3) && hasReturnSpace(menu, recipe);
    }

    @Override public IRecipeTransferError getHandlingError(StorageMenu menu, CraftingRecipe recipe) {
        return recipe.canCraftInDimensions(3, 3) ? returnSpaceError : null;
    }

    /**
     * Older JEI versions count empty recipe cells as space for displaced items. Require room
     * to evacuate the current grid before delegating. This is intentionally conservative:
     * do not rely on a source slot becoming empty, or on which tag alternative JEI selects.
     */
    private boolean hasReturnSpace(StorageMenu menu, CraftingRecipe recipe) {
        List<Slot> inventory = getInventorySlots(menu, recipe);
        List<ItemStack> contents = inventory.stream().map(slot -> slot.getItem().copy())
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        for (Slot source : getRecipeSlots(menu, recipe)) {
            ItemStack remaining = source.getItem().copy();
            if (remaining.isEmpty()) continue;
            for (boolean empty : new boolean[]{false, true}) {
                for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
                    Slot target = inventory.get(i); ItemStack present = contents.get(i);
                    if (present.isEmpty() != empty || !target.mayPlace(remaining)
                            || !present.isEmpty() && !ItemStack.isSameItemSameTags(present, remaining)) continue;
                    int limit = Math.min(target.getMaxStackSize(remaining), remaining.getMaxStackSize());
                    int moved = Math.min(remaining.getCount(), Math.max(0, limit - present.getCount()));
                    if (moved > 0) {
                        contents.set(i, remaining.copyWithCount(present.getCount() + moved));
                        remaining.shrink(moved);
                    }
                }
            }
            if (!remaining.isEmpty()) return false;
        }
        return true;
    }

    @Override public List<Slot> getRecipeSlots(StorageMenu menu, CraftingRecipe recipe) {
        return List.copyOf(menu.slots.subList(StorageMenu.CRAFT_GRID_START, StorageMenu.CRAFT_GRID_END));
    }

    @Override public List<Slot> getInventorySlots(StorageMenu menu, CraftingRecipe recipe) {
        // Stored terminal/NAS entries are not menu slots and are deliberately not exposed here.
        return menu.craftingInventorySlots();
    }
}
