package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.production.ProcessingBatch;
import dev.itemexplorer.production.ProcessingPattern;
import dev.itemexplorer.production.ProcessingPattern.Material;
import dev.itemexplorer.production.ProcessingPattern.Role;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;

import static dev.itemexplorer.gametest.GameTestSupport.storage;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdvancedProcessingBatchTests {
    private static Material row(Item item, int count, int slot, Role role) {
        return new Material(0, new ItemStack(item, count), null, null, slot, role);
    }
    private static ProcessingPattern pattern(List<Material> input, List<Material> output) {
        return new ProcessingPattern(Direction.UP, Direction.DOWN, input, output);
    }
    private static void rejects(GameTestHelper h, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid route/state was accepted");
    }

    @GameTest(template = "empty")
    public static void fixedAndAutomaticRowsShareCapacityWithoutOverbooking(GameTestHelper h) {
        var machine = new ItemStackHandler(2) { @Override public int getSlotLimit(int slot) { return 1; } };
        var inventory = storage(); inventory.insert(new ItemStack(Items.SAND), 1, 0); inventory.insert(new ItemStack(Items.IRON_INGOT), 1, 0);
        var pattern = pattern(List.of(row(Items.SAND, 1, -1, Role.STANDARD), row(Items.IRON_INGOT, 1, 0, Role.STANDARD)),
                List.of(row(Items.GLASS, 1, -1, Role.STANDARD)));
        var batch = new ProcessingBatch(pattern, () -> {}); batch.reserve(inventory, machine); batch.insert(machine);
        h.assertTrue(batch.sentAll() && machine.getStackInSlot(0).is(Items.IRON_INGOT)
                && machine.getStackInSlot(1).is(Items.SAND) && inventory.total() == 0, "Automatic row stole the fixed slot");
        var overbooked = pattern(List.of(row(Items.SAND, 1, 0, Role.STANDARD), row(Items.SAND, 1, 0, Role.STANDARD)), pattern.outputs());
        var empty = new ItemStackHandler(1) { @Override public int getSlotLimit(int slot) { return 1; } };
        inventory.insert(new ItemStack(Items.SAND, 2), 2, 0); var before = inventory.save();
        rejects(h, () -> new ProcessingBatch(overbooked, () -> {}).reserve(inventory, empty));
        h.assertTrue(before.equals(inventory.save()) && empty.getStackInSlot(0).isEmpty(), "Rejected fixed-slot reservation moved items");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void partialSupplyReservationSurvivesReloadAndCancellation(GameTestHelper h) {
        var machine = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (simulate) return super.insertItem(slot, stack, true);
                var remainder = super.insertItem(slot, stack.copyWithCount(Math.min(1, stack.getCount())), false);
                return stack.copyWithCount(stack.getCount() - Math.min(1, stack.getCount()) + remainder.getCount());
            }
        };
        machine.setStackInSlot(0, new ItemStack(Items.COAL, 2));
        var inventory = storage(); inventory.insert(new ItemStack(Items.COAL, 8), 8, 0);
        var pattern = pattern(List.of(row(Items.COAL, 4, 0, Role.SUPPLY)), List.of(row(Items.GLASS, 1, -1, Role.STANDARD)));
        var batch = new ProcessingBatch(pattern, () -> {});
        batch = ProcessingBatch.load(pattern, batch.save(), true, () -> {});
        batch.reserve(inventory, machine); batch.insert(machine);
        h.assertTrue(inventory.total() == 6 && machine.getStackInSlot(0).getCount() == 3, "Supply did not reserve only its deficit");
        batch = ProcessingBatch.load(pattern, batch.save(), true, () -> {});
        batch.reserve(inventory, machine); batch.insert(machine); batch.returnOwned(inventory);
        h.assertTrue(batch.sentAll() && inventory.total() == 6 && machine.getStackInSlot(0).getCount() == 4,
                "Reload/retry/cancel supplied or refunded the same items twice");
        var corrupt = batch.save(); corrupt.getList("Inputs", 10).getCompound(0).putInt("Required", 1);
        rejects(h, () -> ProcessingBatch.load(pattern, corrupt, true, () -> {}));
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void exhaustedRetainedToolDoesNotBlockAlreadyCommittedOutput(GameTestHelper h) {
        var machine = new ItemStackHandler(3); machine.setStackInSlot(1, new ItemStack(Items.IRON_PICKAXE));
        var inventory = storage(); inventory.insert(new ItemStack(Items.SAND), 1, 0);
        var pattern = pattern(List.of(row(Items.SAND, 1, 0, Role.STANDARD), row(Items.IRON_PICKAXE, 1, 1, Role.RETAINED)),
                List.of(row(Items.GLASS, 1, 2, Role.STANDARD)));
        ProcessingBatch.requireReady(pattern, material -> machine);
        var batch = new ProcessingBatch(pattern, () -> {}); batch.reserve(inventory, machine); batch.insert(machine);
        machine.setStackInSlot(0, ItemStack.EMPTY); machine.setStackInSlot(1, ItemStack.EMPTY); machine.setStackInSlot(2, new ItemStack(Items.GLASS));
        batch.insert(machine); batch.collect(machine); batch.deliver(inventory);
        h.assertTrue(batch.complete() && inventory.total() == 1, "A tool exhausted during processing prevented its batch from finishing");
        rejects(h, () -> ProcessingBatch.requireReady(pattern, material -> machine));
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void optionalOutputCannotStarveRequiredOutputOfTheSameItem(GameTestHelper h) {
        var machine = new ItemStackHandler(1); var inventory = storage(); inventory.insert(new ItemStack(Items.SAND), 1, 0);
        var pattern = pattern(List.of(row(Items.SAND, 1, 0, Role.STANDARD)),
                List.of(row(Items.GLASS, 1, 0, Role.OPTIONAL), row(Items.GLASS, 1, 0, Role.STANDARD)));
        var batch = new ProcessingBatch(pattern, () -> {}); batch.reserve(inventory, machine); batch.insert(machine);
        machine.setStackInSlot(0, new ItemStack(Items.GLASS)); batch.collect(machine); batch.deliver(inventory);
        h.assertTrue(batch.complete() && inventory.total() == 1, "Optional output stole the only required product"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void optionalProductRemainsOwnedWhenItsDestinationIsFull(GameTestHelper h) {
        var machine = new ItemStackHandler(2); var inventory = storage(); inventory.insert(new ItemStack(Items.SAND), 1, 0);
        var pattern = pattern(List.of(row(Items.SAND, 1, 0, Role.STANDARD)),
                List.of(row(Items.GLASS, 1, 0, Role.STANDARD), row(Items.IRON_NUGGET, 1, 1, Role.OPTIONAL)));
        var batch = new ProcessingBatch(pattern, () -> {}); batch.reserve(inventory, machine); batch.insert(machine);
        machine.setStackInSlot(0, new ItemStack(Items.GLASS)); machine.setStackInSlot(1, new ItemStack(Items.IRON_NUGGET)); batch.collect(machine);
        while (inventory.total() < inventory.capacity() - 1) {
            int fill = (int) Math.min(64, inventory.capacity() - 1 - inventory.total());
            inventory.insert(new ItemStack(Items.STONE, fill), fill, 0);
        }
        batch.deliver(inventory);
        h.assertTrue(!batch.complete() && batch.hasOwned() && batch.outputBlocked(), "Full storage discarded an already collected optional product");
        batch = ProcessingBatch.load(pattern, batch.save(), true, () -> {});
        int stone = inventory.entries().stream().filter(entry -> entry.stack().is(Items.STONE)).findFirst().orElseThrow().id();
        inventory.take(stone, 1); batch.deliver(inventory); h.assertTrue(batch.complete() && !batch.hasOwned(), "Optional product could not be delivered after reload");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void auxiliaryOnlyBatchAndLegacyPatternRoundTrip(GameTestHelper h) {
        var machine = new ItemStackHandler(2); machine.setStackInSlot(0, new ItemStack(Items.COAL, 4)); var inventory = storage();
        var pattern = pattern(List.of(row(Items.COAL, 4, 0, Role.SUPPLY)), List.of(row(Items.GLASS, 1, 1, Role.STANDARD)));
        var batch = new ProcessingBatch(pattern, () -> {}); batch.reserve(inventory, machine); batch.insert(machine);
        machine.setStackInSlot(1, new ItemStack(Items.GLASS)); batch.collect(machine);
        batch = ProcessingBatch.load(pattern, batch.save(), true, () -> {}); batch.deliver(inventory);
        h.assertTrue(batch.complete() && inventory.total() == 1 && machine.getStackInSlot(0).getCount() == 4, "Zero-deficit supply batch failed to collect output");
        var legacy = pattern(List.of(new Material(0, new ItemStack(Items.SAND))), List.of(new Material(0, new ItemStack(Items.GLASS))));
        var oldRow = legacy.save().getList("Inputs", 10).getCompound(0);
        h.assertTrue(oldRow.getAllKeys().equals(java.util.Set.of("Folder", "Stack"))
                && legacy.save().equals(ProcessingPattern.load(legacy.save()).save()), "Default pattern changed legacy save format");
        rejects(h, () -> pattern(pattern.inputs(), List.of(row(Items.GLASS, 1, 1, Role.OPTIONAL))));
        h.succeed();
    }
}
