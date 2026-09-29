package dev.itemexplorer.logistics;

import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Slot 0 is an insertion gateway; other slots expose each in-scope entry exactly once.
 * Entry slots never compact. Slots are scoped to this configuration and mount session.
 * Every callback revalidates the live mount, including calls through retained old handlers.
 */
public final class FolderItemHandler implements IItemHandler {
    private final LogisticsPortBlockEntity port;
    private final LogisticsPortBlockEntity.Target target;
    private final long epoch;
    private final StorageInventory.Entry[] visible;
    private final Map<Integer, Integer> slots = new HashMap<>();
    private long observedRevision = -1;

    public FolderItemHandler(LogisticsPortBlockEntity port, LogisticsPortBlockEntity.Target target, long epoch) {
        this.port = port; this.target = target; this.epoch = epoch;
        visible = new StorageInventory.Entry[target.inventory().entryLimit() + 1];
    }
    public boolean matches(LogisticsPortBlockEntity.Target other, long epoch) { return this.epoch == epoch && target.sameMount(other); }
    private StorageInventory live() { return port.isCurrent(target, epoch) ? target.inventory() : null; }
    private boolean valid(int slot) { return slot >= 0 && slot < visible.length; }
    private void refresh(StorageInventory inventory) {
        if (observedRevision == inventory.revision()) return;
        var entries = inventory.entries();
        var inScope = new HashMap<Integer, StorageInventory.Entry>();
        for (var entry : entries) if (port.includes(inventory, entry.folder())) inScope.put(entry.id(), entry);
        slots.entrySet().removeIf(pair -> !inScope.containsKey(pair.getKey()));
        Arrays.fill(visible, null);
        slots.forEach((id, slot) -> visible[slot] = inScope.get(id));
        int free = 1;
        for (var entry : entries) {
            if (!inScope.containsKey(entry.id()) || slots.containsKey(entry.id())) continue;
            while (free < visible.length && visible[free] != null) free++;
            if (free == visible.length) break;
            slots.put(entry.id(), free); visible[free] = entry;
        }
        observedRevision = inventory.revision();
    }
    @Override public int getSlots() { return visible.length; }
    @Override public ItemStack getStackInSlot(int slot) {
        StorageInventory inventory = live();
        if (inventory == null || !valid(slot) || slot == 0) return ItemStack.EMPTY;
        refresh(inventory);
        var entry = visible[slot];
        return entry == null ? ItemStack.EMPTY : entry.stack().copyWithCount((int) Math.min(Integer.MAX_VALUE, entry.count()));
    }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        StorageInventory inventory = live();
        if (slot != 0 || inventory == null || !port.allowInput() || stack.isEmpty()) return stack;
        try {
            int accepted = inventory.insert(stack, stack.getCount(), port.folder(), simulate);
            if (accepted > 0 && !simulate) port.activity();
            return accepted == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
        } catch (IllegalArgumentException rejected) { return stack; }
    }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        StorageInventory inventory = live();
        if (inventory == null || !port.allowOutput() || !valid(slot) || slot == 0 || amount <= 0) return ItemStack.EMPTY;
        refresh(inventory);
        var entry = visible[slot];
        if (entry == null) return ItemStack.EMPTY;
        try {
            ItemStack taken = inventory.take(entry.id(), amount, simulate);
            if (!taken.isEmpty() && !simulate) port.activity();
            return taken;
        } catch (IllegalArgumentException rejected) { return ItemStack.EMPTY; }
    }
    @Override public int getSlotLimit(int slot) { return valid(slot) && live() != null ? (int) Math.min(Integer.MAX_VALUE, target.inventory().capacity()) : 0; }
    @Override public boolean isItemValid(int slot, ItemStack stack) {
        return slot == 0 && port.allowInput() && live() != null && !stack.isEmpty() && StorageInventory.acceptsItem(stack);
    }
}
