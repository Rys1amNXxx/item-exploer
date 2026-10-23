package dev.itemexplorer.production;

import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageLimits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/** Owned items and monotonic counters for one batch; machine inventories are never assigned directly. */
public final class ProcessingBatch {
    private final ProcessingPattern pattern;
    private final List<ItemStack> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private final int[] reserved, sent, returned, collected, delivered;
    private final Runnable changed;

    public ProcessingBatch(ProcessingPattern pattern, Runnable changed) {
        this.pattern = pattern; this.changed = changed;
        int in = pattern.inputs().size(), out = pattern.outputs().size();
        reserved = new int[in]; sent = new int[in]; returned = new int[in];
        collected = new int[out]; delivered = new int[out];
        for (int i = 0; i < in; i++) inputs.add(ItemStack.EMPTY);
        for (int i = 0; i < out; i++) outputs.add(ItemStack.EMPTY);
    }

    public static boolean empty(IItemHandler handler) {
        if (handler == null || handler.getSlots() < 1 || handler.getSlots() > ItemHandlerAccess.MAX_SLOTS) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty()) return false;
        return true;
    }

    /** Find a shared slot allocation for all rows without changing any real inventory. */
    public static boolean canInsertBatch(IItemHandler handler, List<ItemStack> materials) {
        return BatchInsertionPlan.find(handler, materials) != null;
    }

    private static StorageInventory.Entry find(StorageInventory inventory, ProcessingPattern.Material material) {
        return inventory.entries().stream().filter(e -> e.folder() == material.folder()
                && ProcessingPattern.matches(e.stack(), material.stack())).findFirst().orElse(null);
    }

    private static StorageInventory copy(StorageInventory inventory) {
        CompoundTag saved = (CompoundTag) inventory.save();
        StorageInventory copy = new StorageInventory(() -> {},
                new StorageLimits(inventory.capacity(), inventory.entryLimit(), 256, saved.getInt("Version")));
        copy.load(saved);
        if (copy.isLocked()) throw new IllegalArgumentException("production_storage_locked");
        return copy;
    }

    /** Check the whole transaction on a private inventory before removing any real ingredients. */
    public void reserve(StorageInventory inventory, IItemHandler input) {
        reserve(inventory, face -> input);
    }

    public void reserve(StorageInventory inventory, Function<Direction, IItemHandler> handlers) {
        Map<IItemHandler, List<ItemStack>> remaining = new IdentityHashMap<>();
        StorageInventory shadow = copy(inventory);
        for (int row = 0; row < inputs.size(); row++) {
            ProcessingPattern.Material material = pattern.inputs().get(row);
            IItemHandler handler = handlers.apply(pattern.inputFace(material));
            List<ItemStack> items = remaining.computeIfAbsent(handler, unused -> new ArrayList<>());
            if (!inputs.get(row).isEmpty()) items.add(inputs.get(row).copy());
            int count = material.stack().getCount() - reserved[row];
            if (count <= 0) continue;
            StorageInventory.Entry entry = find(shadow, material);
            if (entry == null || shadow.take(entry.id(), count, false).getCount() != count)
                throw new IllegalArgumentException("production_missing_input");
            items.add(material.stack().copyWithCount(count));
        }
        // Identity-equal sided capabilities share one shadow. Different wrappers may still alias;
        // actual insertion remains authoritative and any unaccepted remainder stays in our buffer.
        for (var entry : remaining.entrySet()) if (!canInsertBatch(entry.getKey(), entry.getValue()))
            throw new IllegalArgumentException("production_waiting_input");
        try {
            for (ProcessingPattern.Material output : pattern.outputs()) {
                ItemStack stack = output.stack();
                if (shadow.insert(stack, stack.getCount(), output.folder(), false) != stack.getCount())
                    throw new IllegalArgumentException("production_output_full");
            }
        } catch (IllegalArgumentException full) {
            throw new IllegalArgumentException("production_output_full");
        }
        for (int row = 0; row < inputs.size(); row++) {
            ProcessingPattern.Material material = pattern.inputs().get(row);
            int count = material.stack().getCount() - reserved[row];
            if (count <= 0) continue;
            StorageInventory.Entry entry = find(inventory, material);
            if (entry == null) throw new IllegalArgumentException("production_missing_input");
            ItemStack taken = inventory.take(entry.id(), count, false);
            if (!taken.isEmpty()) {
                inputs.set(row, material.stack().copyWithCount(inputs.get(row).getCount() + taken.getCount()));
                reserved[row] += taken.getCount(); changed.run();
            }
            if (taken.getCount() != count) throw new IllegalArgumentException("production_missing_input");
        }
    }

    public boolean staged() {
        for (int row = 0; row < reserved.length; row++) if (reserved[row] != pattern.inputs().get(row).stack().getCount()) return false;
        return true;
    }

    public boolean sentAll() {
        for (int row = 0; row < sent.length; row++) if (sent[row] != pattern.inputs().get(row).stack().getCount()) return false;
        return true;
    }

    public void insert(IItemHandler handler) {
        insert(face -> handler);
    }

    public void insert(Function<Direction, IItemHandler> handlers) {
        if (!staged()) return;
        record Insertion(IItemHandler handler, int row, int slot, int count) {}
        List<Insertion> insertions = new ArrayList<>();
        Map<IItemHandler, List<Integer>> rowsByHandler = new IdentityHashMap<>();
        for (int row = 0; row < inputs.size(); row++) {
            if (inputs.get(row).isEmpty()) continue;
            IItemHandler handler = handlers.apply(pattern.inputFace(pattern.inputs().get(row)));
            rowsByHandler.computeIfAbsent(handler, unused -> new ArrayList<>()).add(row);
        }
        for (var group : rowsByHandler.entrySet()) {
            List<Integer> rows = group.getValue();
            BatchInsertionPlan plan = BatchInsertionPlan.find(group.getKey(), rows.stream().map(inputs::get).toList(), true);
            if (plan == null) continue;
            for (var move : plan.moves())
                insertions.add(new Insertion(group.getKey(), rows.get(move.row()), move.slot(), move.count()));
        }
        // Slot search order must not change material priority when a machine shares a
        // real transfer budget across slots or sided handlers.
        insertions.sort(Comparator.comparingInt(Insertion::row).thenComparingInt(Insertion::slot));
        for (Insertion insertion : insertions) {
            int row = insertion.row();
            ItemStack offered = inputs.get(row).copyWithCount(insertion.count());
            ItemStack remainder = insertion.handler().insertItem(insertion.slot(), offered.copy(), false);
            int amount = BatchInsertionPlan.accepted(offered, remainder);
            if (amount > 0) {
                reduce(inputs, row, amount); sent[row] += amount; changed.run();
            }
        }
    }

    /** Only actual extractions count. Rows sharing a type consume from the same remaining budget. */
    public void collect(IItemHandler handler) {
        collect(face -> handler);
    }

    public void collect(Function<Direction, IItemHandler> handlers) {
        if (java.util.Arrays.stream(sent).noneMatch(value -> value > 0)) return;
        for (int row = 0; row < outputs.size(); row++) {
            IItemHandler handler = handlers.apply(pattern.outputFace(pattern.outputs().get(row)));
            ItemStack target = pattern.outputs().get(row).stack();
            for (int slot = 0; slot < handler.getSlots() && collected[row] < target.getCount(); slot++) {
                if (!ProcessingPattern.matches(handler.getStackInSlot(slot), target)) continue;
                int requested = target.getCount() - collected[row];
                ItemStack simulated = handler.extractItem(slot, requested, true);
                if (!ProcessingPattern.matches(simulated, target)) continue;
                ItemStack extracted = handler.extractItem(slot, Math.min(requested, simulated.getCount()), false);
                if (extracted.isEmpty()) continue;
                if (!ProcessingPattern.matches(extracted, target) || extracted.getCount() > requested)
                    throw new IllegalArgumentException("production_interference");
                outputs.set(row, target.copyWithCount(outputs.get(row).getCount() + extracted.getCount()));
                collected[row] += extracted.getCount(); changed.run();
            }
        }
    }

    private int deliver(StorageInventory inventory, ItemStack stack, int folder) {
        if (stack.isEmpty() || !inventory.hasFolder(folder)) return 0;
        try { return inventory.insert(stack.copy(), stack.getCount(), folder, false); }
        catch (IllegalArgumentException full) { return 0; }
    }

    public void deliver(StorageInventory inventory) {
        for (int row = 0; row < outputs.size(); row++) {
            int amount = deliver(inventory, outputs.get(row), pattern.outputs().get(row).folder());
            if (amount > 0) { reduce(outputs, row, amount); delivered[row] += amount; changed.run(); }
        }
    }

    public void returnOwned(StorageInventory inventory) {
        for (int row = 0; row < inputs.size(); row++) {
            int amount = deliver(inventory, inputs.get(row), pattern.inputs().get(row).folder());
            if (amount > 0) { reduce(inputs, row, amount); returned[row] += amount; changed.run(); }
        }
        deliver(inventory);
    }

    private static void reduce(List<ItemStack> buffers, int row, int amount) {
        ItemStack buffer = buffers.get(row);
        // A zero-count stack can retain its old NBT; always persist the canonical empty stack.
        buffers.set(row, amount == buffer.getCount() ? ItemStack.EMPTY : buffer.copyWithCount(buffer.getCount() - amount));
    }

    public boolean hasOwned() {
        return inputs.stream().anyMatch(stack -> !stack.isEmpty()) || outputs.stream().anyMatch(stack -> !stack.isEmpty());
    }

    public boolean outputBlocked() { return outputs.stream().anyMatch(stack -> !stack.isEmpty()); }

    public boolean complete() {
        if (!sentAll()) return false;
        for (int row = 0; row < delivered.length; row++) if (delivered[row] != pattern.outputs().get(row).stack().getCount()) return false;
        return true;
    }

    /** Drain only items owned by this interface; sent ingredients are owned by the real machine. */
    public List<ItemStack> drainOwned() {
        List<ItemStack> result = new ArrayList<>();
        for (int row = 0; row < inputs.size(); row++) {
            if (!inputs.get(row).isEmpty()) result.add(inputs.get(row).copy());
            returned[row] += inputs.get(row).getCount(); inputs.set(row, ItemStack.EMPTY);
        }
        for (int row = 0; row < outputs.size(); row++) {
            if (!outputs.get(row).isEmpty()) result.add(outputs.get(row).copy());
            delivered[row] += outputs.get(row).getCount(); outputs.set(row, ItemStack.EMPTY);
        }
        changed.run(); return result;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); ListTag in = new ListTag(), out = new ListTag();
        for (int row = 0; row < inputs.size(); row++) {
            CompoundTag data = new CompoundTag(); data.put("Buffer", savedBuffer(inputs.get(row)));
            data.putInt("Reserved", reserved[row]); data.putInt("Sent", sent[row]); data.putInt("Returned", returned[row]); in.add(data);
        }
        for (int row = 0; row < outputs.size(); row++) {
            CompoundTag data = new CompoundTag(); data.put("Buffer", savedBuffer(outputs.get(row)));
            data.putInt("Collected", collected[row]); data.putInt("Delivered", delivered[row]); out.add(data);
        }
        tag.put("Inputs", in); tag.put("Outputs", out); return tag;
    }

    private static CompoundTag savedBuffer(ItemStack stack) {
        return (stack.isEmpty() ? ItemStack.EMPTY : stack).save(new CompoundTag());
    }

    public static ProcessingBatch load(ProcessingPattern pattern, CompoundTag tag, boolean active, Runnable changed) {
        ProcessingBatch batch = new ProcessingBatch(pattern, changed);
        ListTag in = rows(tag, "Inputs", pattern.inputs().size()), out = rows(tag, "Outputs", pattern.outputs().size());
        for (int row = 0; row < in.size(); row++) {
            CompoundTag data = in.getCompound(row); ItemStack target = pattern.inputs().get(row).stack();
            ItemStack buffer = FurnaceProgram.stack(data, "Buffer", true);
            int reserved = nonnegative(data, "Reserved"), sent = nonnegative(data, "Sent"), returned = nonnegative(data, "Returned");
            if ((!buffer.isEmpty() && !ProcessingPattern.matches(buffer, target)) || reserved > target.getCount()
                    || (long) sent + returned + buffer.getCount() != reserved || (active && returned != 0)) throw new IllegalArgumentException();
            batch.inputs.set(row, buffer); batch.reserved[row] = reserved; batch.sent[row] = sent; batch.returned[row] = returned;
        }
        for (int row = 0; row < out.size(); row++) {
            CompoundTag data = out.getCompound(row); ItemStack target = pattern.outputs().get(row).stack();
            ItemStack buffer = FurnaceProgram.stack(data, "Buffer", true);
            int collected = nonnegative(data, "Collected"), delivered = nonnegative(data, "Delivered");
            if ((!buffer.isEmpty() && !ProcessingPattern.matches(buffer, target)) || collected > target.getCount()
                    || (long) delivered + buffer.getCount() != collected) throw new IllegalArgumentException();
            batch.outputs.set(row, buffer); batch.collected[row] = collected; batch.delivered[row] = delivered;
        }
        boolean hasSent = java.util.Arrays.stream(batch.sent).anyMatch(value -> value > 0);
        boolean hasCollected = java.util.Arrays.stream(batch.collected).anyMatch(value -> value > 0);
        if ((!batch.staged() && (hasSent || hasCollected)) || (hasCollected && !hasSent)) throw new IllegalArgumentException();
        if (!tag.equals(batch.save())) throw new IllegalArgumentException();
        return batch;
    }

    private static int nonnegative(CompoundTag tag, String key) {
        FurnaceProgram.require(tag, key, Tag.TAG_INT); int value = tag.getInt(key);
        if (value < 0) throw new IllegalArgumentException(); return value;
    }

    private static ListTag rows(CompoundTag tag, String key, int count) {
        FurnaceProgram.require(tag, key, Tag.TAG_LIST); ListTag list = (ListTag) tag.get(key);
        if (list.size() != count || list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException(); return list;
    }
}
