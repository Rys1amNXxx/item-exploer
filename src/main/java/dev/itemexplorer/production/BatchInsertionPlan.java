package dev.itemexplorer.production;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A transient, simulated allocation. Only the handler's real remainder can commit a transfer. */
final class BatchInsertionPlan {
    private static final int MAX_SEARCH_NODES = 32_768;
    record Move(int row, int slot, int count) {}
    private record Slot(int index, int[] capacity) {
        int choices() { return (int) Arrays.stream(capacity).filter(value -> value > 0).count(); }
        int largest() { return Arrays.stream(capacity).max().orElse(0); }
    }
    private record SearchState(int slot, String demand) {}

    private final List<Move> moves;

    private BatchInsertionPlan(List<Move> moves) { this.moves = List.copyOf(moves); }
    List<Move> moves() { return moves; }

    static BatchInsertionPlan find(IItemHandler handler, List<ItemStack> materials) {
        return find(handler, materials, false);
    }

    static BatchInsertionPlan find(IItemHandler handler, List<ItemStack> materials, boolean allowPartial) {
        int[] fixedSlots = new int[materials.size()]; Arrays.fill(fixedSlots, -1);
        return find(handler, materials, fixedSlots, Set.of(), allowPartial);
    }

    static BatchInsertionPlan find(IItemHandler handler, List<ItemStack> materials, int[] fixedSlots,
                                   Set<Integer> excludedAutomaticSlots, boolean allowPartial) {
        if (handler == null || handler.getSlots() < 1 || handler.getSlots() > ItemHandlerAccess.MAX_SLOTS) return null;
        if (fixedSlots.length != materials.size()) throw new IllegalArgumentException("production_invalid_config");
        List<Move> result = new ArrayList<>();
        ItemStack[] fixedItems = new ItemStack[handler.getSlots()];
        int[] fixedAmounts = new int[handler.getSlots()];
        for (int row = 0; row < materials.size(); row++) {
            int slot = fixedSlots[row]; ItemStack sample = materials.get(row);
            if (slot < -1 || slot >= handler.getSlots()) return null;
            if (slot < 0 || sample.isEmpty()) continue;
            ItemStack present = handler.getStackInSlot(slot);
            if ((!present.isEmpty() && !ProcessingPattern.matches(present, sample))
                    || (fixedItems[slot] != null && !ProcessingPattern.matches(fixedItems[slot], sample))) {
                if (!allowPartial) return null;
                continue;
            }
            int room = Math.min(handler.getSlotLimit(slot), sample.getMaxStackSize()) - present.getCount();
            int requested = Math.min(room, sample.getCount() + fixedAmounts[slot]);
            int amount = 0;
            if (requested > 0) {
                ItemStack offered = sample.copyWithCount(requested);
                amount = Math.min(sample.getCount(), Math.max(0,
                        accepted(offered, handler.insertItem(slot, offered.copy(), true)) - fixedAmounts[slot]));
            }
            if (amount != sample.getCount() && !allowPartial) return null;
            if (amount > 0) {
                fixedItems[slot] = sample; fixedAmounts[slot] += amount;
                result.add(new Move(row, slot, amount));
            }
        }
        List<ItemStack> kinds = new ArrayList<>();
        int[] rowKinds = new int[materials.size()];
        for (int row = 0; row < materials.size(); row++) {
            ItemStack stack = materials.get(row);
            rowKinds[row] = -1;
            if (stack.isEmpty() || fixedSlots[row] >= 0) continue;
            for (int kind = 0; kind < kinds.size(); kind++) if (ProcessingPattern.matches(kinds.get(kind), stack)) {
                rowKinds[row] = kind; break;
            }
            if (rowKinds[row] < 0) { rowKinds[row] = kinds.size(); kinds.add(stack.copy()); }
        }
        if (kinds.size() > ProcessingPattern.MAX_MATERIALS) return null;
        int[] demand = new int[kinds.size()];
        for (int row = 0; row < materials.size(); row++) if (rowKinds[row] >= 0)
            demand[rowKinds[row]] = Math.addExact(demand[rowKinds[row]], materials.get(row).getCount());

        List<Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (excludedAutomaticSlots.contains(slot)) continue;
            ItemStack present = handler.getStackInSlot(slot);
            int[] capacity = new int[kinds.size()];
            for (int kind = 0; kind < kinds.size(); kind++) {
                ItemStack sample = kinds.get(kind);
                if (!present.isEmpty() && !ProcessingPattern.matches(present, sample)) continue;
                if (fixedItems[slot] != null && !ProcessingPattern.matches(fixedItems[slot], sample)) continue;
                int room = Math.min(handler.getSlotLimit(slot), sample.getMaxStackSize()) - present.getCount() - fixedAmounts[slot];
                int count = Math.min(demand[kind], room);
                if (count <= 0) continue;
                ItemStack offered = sample.copyWithCount(count + fixedAmounts[slot]);
                capacity[kind] = Math.max(0, accepted(offered, handler.insertItem(slot, offered.copy(), true)) - fixedAmounts[slot]);
            }
            Slot candidate = new Slot(slot, capacity);
            if (candidate.choices() > 0) slots.add(candidate);
        }
        // Restricted slots first is a useful shortcut, but backtracking is still needed when
        // equally flexible slots accept different sets of items or have different capacities.
        slots.sort(Comparator.comparingInt(Slot::choices).thenComparing(Comparator.comparingInt(Slot::largest).reversed())
                .thenComparingInt(Slot::index));
        Search search = new Search(slots, demand);
        if (!search.solve(0)) {
            if (!allowPartial) return null;
            // Sided wrappers may alias the same slots, and a machine may change its accepting
            // capacity after reservation. Keep making bounded progress with the owned remainder.
            search.allocatePartial();
        }

        int[] left = materials.stream().mapToInt(ItemStack::getCount).toArray();
        for (int slot = 0; slot < slots.size(); slot++) {
            int kind = search.assigned[slot], count = search.amounts[slot];
            for (int row = 0; count > 0 && row < materials.size(); row++) {
                if (rowKinds[row] != kind || left[row] == 0) continue;
                int amount = Math.min(count, left[row]);
                result.add(new Move(row, slots.get(slot).index(), amount));
                left[row] -= amount; count -= amount;
            }
        }
        return new BatchInsertionPlan(result);
    }

    static int accepted(ItemStack offered, ItemStack remainder) {
        if (!remainder.isEmpty() && (!ProcessingPattern.matches(offered, remainder) || remainder.getCount() > offered.getCount()))
            throw new IllegalArgumentException("production_interference");
        return offered.getCount() - remainder.getCount();
    }

    private static final class Search {
        private final List<Slot> slots;
        private final int[] demand, assigned, amounts;
        private final long[][] suffixCapacity;
        private final Set<SearchState> failed = new HashSet<>();
        private int nodes;

        private Search(List<Slot> slots, int[] demand) {
            this.slots = slots; this.demand = demand;
            assigned = new int[slots.size()]; Arrays.fill(assigned, -1);
            amounts = new int[slots.size()];
            suffixCapacity = new long[slots.size() + 1][demand.length];
            for (int slot = slots.size() - 1; slot >= 0; slot--) for (int kind = 0; kind < demand.length; kind++)
                suffixCapacity[slot][kind] = suffixCapacity[slot + 1][kind] + slots.get(slot).capacity()[kind];
        }

        private boolean solve(int slot) {
            if (++nodes > MAX_SEARCH_NODES) return false;
            boolean finished = true;
            for (int kind = 0; kind < demand.length; kind++) {
                if (demand[kind] > suffixCapacity[slot][kind]) return false;
                if (demand[kind] > 0) finished = false;
            }
            if (finished) return true;
            if (slot == slots.size()) return false;
            SearchState state = new SearchState(slot, Arrays.toString(demand));
            if (failed.contains(state)) return false;
            boolean useful = false;
            for (int kind = 0; kind < demand.length; kind++) {
                int count = Math.min(demand[kind], slots.get(slot).capacity()[kind]);
                if (count == 0) continue;
                useful = true; assigned[slot] = kind; amounts[slot] = count; demand[kind] -= count;
                if (solve(slot + 1)) return true;
                demand[kind] += count; assigned[slot] = -1; amounts[slot] = 0;
                if (nodes > MAX_SEARCH_NODES) return false;
            }
            // A useful slot need not be skipped: assigning it to a needed kind can only
            // free capacity in later slots. Each slot holds at most one kind of item.
            if (!useful && solve(slot + 1)) return true;
            failed.add(state); return false;
        }

        private void allocatePartial() {
            Arrays.fill(assigned, -1); Arrays.fill(amounts, 0);
            for (int slot = 0; slot < slots.size(); slot++) {
                int selected = -1;
                for (int kind = 0; kind < demand.length; kind++) {
                    if (demand[kind] == 0 || slots.get(slot).capacity()[kind] == 0) continue;
                    if (selected < 0 || suffixCapacity[slot][kind] - demand[kind]
                            < suffixCapacity[slot][selected] - demand[selected]) selected = kind;
                }
                if (selected < 0) continue;
                int count = Math.min(demand[selected], slots.get(slot).capacity()[selected]);
                assigned[slot] = selected; amounts[slot] = count; demand[selected] -= count;
            }
        }
    }
}
