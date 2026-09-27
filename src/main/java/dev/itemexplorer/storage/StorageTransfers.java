package dev.itemexplorer.storage;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class StorageTransfers {
    private StorageTransfers() {}

    /** Fill existing stacks, then free main-inventory slots. Creative mode cannot void overflow. */
    public static int withdraw(StorageInventory storage, Inventory inventory, int entryId, int requested) {
        StorageInventory.Entry entry = storage.entry(entryId);
        if (entry == null || requested <= 0) return 0;
        int remaining = Math.min(requested, entry.count());
        int moved = 0;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if ((pass == 0 && current.isEmpty()) || (pass == 1 && !current.isEmpty())) continue;
                if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, entry.stack())) continue;
                int room = Math.min(inventory.getMaxStackSize(), entry.stack().getMaxStackSize()) - current.getCount();
                if (room <= 0) continue;
                ItemStack taken = storage.take(entryId, Math.min(remaining, room));
                if (taken.isEmpty()) break;
                int amount = taken.getCount();
                if (current.isEmpty()) inventory.setItem(slot, taken);
                else current.grow(amount);
                remaining -= amount;
                moved += amount;
            }
        }
        if (moved > 0) inventory.setChanged();
        return moved;
    }
}
