package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.disk.DiskTier;
import dev.itemexplorer.storage.AtomicStorageTransfer;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageLimits;
import dev.itemexplorer.storage.StorageTransfers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageMergeTests {
    private static ItemStack dirt(boolean emptyTag, int count) {
        ItemStack stack = new ItemStack(Items.DIRT, count);
        if (emptyTag) stack.setTag(new CompoundTag());
        return stack;
    }

    private static List<Integer> ids(StorageInventory inventory, int folder) {
        return inventory.entries().stream().filter(e -> e.folder() == folder).map(StorageInventory.Entry::id).toList();
    }

    private static List<CompoundTag> rows(StorageInventory inventory, int folder) {
        List<CompoundTag> result = new ArrayList<>();
        for (Tag value : ((CompoundTag) inventory.save()).getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (row.getInt("Folder") == folder) result.add(row.copy());
        }
        return result;
    }

    private static CompoundTag row(StorageLimits limits, int id, int folder, long count, ItemStack sample) {
        CompoundTag row = new CompoundTag();
        row.putInt("Id", id); row.putInt("Folder", folder);
        if (limits.format() == 1) row.putInt("Count", (int) count);
        else row.putLong("Count", count);
        row.put("Stack", sample.copyWithCount(1).save(new CompoundTag()));
        return row;
    }

    /** Old saves can legitimately contain both null-tag and empty-tag rows. */
    private static CompoundTag legacySave(StorageLimits limits) {
        var structure = new StorageInventory(() -> {}, limits);
        int folder = structure.createFolder(0, "Sort"), other = structure.createFolder(0, "Other");
        structure.createFolder(folder, "Child");
        CompoundTag data = (CompoundTag) structure.save();
        ListTag entries = new ListTag();
        // Reverse ID order ensures the retained sample is selected by ID, not iteration order.
        entries.add(row(limits, 151, folder, 53, dirt(true, 1)));
        entries.add(row(limits, 2, other, 17, dirt(false, 1)));
        entries.add(row(limits, 60, folder, 1, new ItemStack(Items.STONE)));
        entries.add(row(limits, 61, folder, 2, new ItemStack(Items.APPLE)));
        entries.add(row(limits, 3, other, 19, dirt(true, 1)));
        entries.add(row(limits, 62, folder, 3, new ItemStack(Items.COAL)));
        entries.add(row(limits, 63, folder, 4, new ItemStack(Items.DIAMOND)));
        entries.add(row(limits, 64, folder, 5, new ItemStack(Items.IRON_INGOT)));
        entries.add(row(limits, 65, folder, 6, new ItemStack(Items.GOLD_INGOT)));
        entries.add(row(limits, 55, folder, 444, dirt(false, 1)));
        data.put("Entries", entries); data.putInt("NextEntry", 200);
        return data;
    }

    @GameTest(template = "empty")
    public static void absentAndEmptyTagsMergeInEitherOrderWithoutSimulationSideEffects(GameTestHelper h) {
        for (StorageLimits limits : List.of(StorageLimits.LOCAL, DiskTier.M16.limits())) {
            for (boolean firstEmpty : List.of(false, true)) {
                AtomicInteger dirty = new AtomicInteger();
                var storage = new StorageInventory(dirty::incrementAndGet, limits);
                ItemStack first = dirt(firstEmpty, 444), incoming = dirt(!firstEmpty, 53);
                h.assertTrue(storage.insert(first, 444, 0) == 444, "Initial dirt was not inserted");
                var original = storage.entry(1).stack().save(new CompoundTag());
                var incomingData = incoming.save(new CompoundTag());
                Snapshot before = Snapshot.of(storage); int changes = dirty.get();
                h.assertTrue(storage.insert(incoming, 53, 0, true) == 53, "Equivalent dirt simulation was rejected");
                h.assertTrue(before.equals(Snapshot.of(storage)) && dirty.get() == changes,
                        "Simulated merge changed rows, IDs, counters, search metadata or dirty state");
                h.assertTrue(storage.insert(incoming, 53, 0) == 53 && storage.entries().size() == 1
                        && storage.entry(1).count() == 497 && storage.total() == 497,
                        "Absent and empty tags failed to merge in input order " + firstEmpty);
                h.assertTrue(original.equals(storage.entry(1).stack().save(new CompoundTag()))
                        && incomingData.equals(incoming.save(new CompoundTag())), "Merge rewrote an item sample or caller stack");
                h.assertTrue(storage.revision() == before.revision() + 1 && storage.searchRevision() == before.search()
                        && storage.transferRevision() == before.transfers() + 1 && dirty.get() == changes + 1
                        && ((CompoundTag) storage.save()).getInt("NextEntry") == 2,
                        "Count-only merge changed membership, IDs or activity counters");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sortRepairsLegacyDuplicatesAcrossPagesAndPersistsExactlyOnce(GameTestHelper h) {
        for (StorageLimits limits : List.of(StorageLimits.LOCAL, DiskTier.M16.limits())) {
            AtomicInteger dirty = new AtomicInteger();
            var storage = new StorageInventory(dirty::incrementAndGet, limits);
            CompoundTag legacy = legacySave(limits); storage.load(legacy);
            h.assertTrue(!storage.isLocked() && storage.save().equals(legacy), "Old empty-tag rows were rejected or rewritten on load");
            h.assertTrue(storage.locatePage(151, 6) == 0 && storage.locatePage(55, 6) == 1,
                    "Duplicate fixture must span two pages");
            var otherBefore = rows(storage, 2); var folders = storage.folders();
            var retainedSample = storage.entry(55).stack().save(new CompoundTag());
            Snapshot before = Snapshot.of(storage); int changes = dirty.get();
            storage.sort(1);
            h.assertTrue(ids(storage, 1).equals(List.of(61, 62, 63, 55, 65, 64, 60)), "Whole-folder merge/sort order is wrong");
            h.assertTrue(storage.entry(151) == null && storage.entry(55).count() == 497
                    && retainedSample.equals(storage.entry(55).stack().save(new CompoundTag())),
                    "Legacy merge did not retain the smallest ID and its exact sample");
            h.assertTrue(storage.total() == before.total() && otherBefore.equals(rows(storage, 2))
                    && folders.equals(storage.folders()) && ((CompoundTag) storage.save()).getInt("NextEntry") == 200,
                    "Repair changed totals, another directory, folders or the next entry ID");
            h.assertTrue(storage.searchCatalog().stream().noneMatch(row -> row.getInt("Id") == 151)
                    && storage.searchCatalog().stream().filter(row -> row.getInt("Id") == 55).count() == 1,
                    "Search metadata retained a removed duplicate");
            h.assertTrue(storage.revision() == before.revision() + 1 && storage.searchRevision() == before.search() + 1
                    && storage.transferRevision() == before.transfers() && dirty.get() == changes + 1,
                    "Repair did not update revision/search exactly once or counted as a transfer");
            Snapshot sorted = Snapshot.of(storage);
            storage.sort(1);
            h.assertTrue(sorted.equals(Snapshot.of(storage)) && dirty.get() == changes + 1, "Repeated repair was not idempotent");
            var restored = new StorageInventory(() -> {}, limits); restored.load(storage.save());
            h.assertTrue(!restored.isLocked() && restored.save().equals(storage.save())
                    && restored.entry(55).count() == 497 && restored.total() == storage.total(), "Repaired rows did not survive save/load");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void realNamesAndCustomDataStayDistinctAndExactDuplicatesStayProtected(GameTestHelper h) {
        var storage = new StorageInventory(() -> {});
        ItemStack named = dirt(false, 2); named.setHoverName(Component.literal("Building dirt"));
        ItemStack custom = dirt(false, 3); custom.getOrCreateTag().putString("Project", "Keep");
        ItemStack otherCustom = dirt(false, 4); otherCustom.getOrCreateTag().putString("Project", "Other");
        ItemStack nested = dirt(false, 5); nested.getOrCreateTag().put("Project", new CompoundTag());
        List<ItemStack> variants = List.of(dirt(false, 6), named, custom, otherCustom, nested);
        for (ItemStack stack : variants) storage.insert(stack, stack.getCount(), 0);
        storage.insert(dirt(true, 7), 7, 0);
        storage.sort(0);
        h.assertTrue(storage.entries().size() == variants.size() && storage.total() == 27 && storage.entry(1).count() == 13,
                "Sorting merged real item data or failed to merge only the empty root tag");
        for (int i = 0; i < variants.size(); i++) {
            h.assertTrue(variants.get(i).copyWithCount(1).save(new CompoundTag())
                    .equals(storage.entry(i + 1).stack().save(new CompoundTag())), "Real item data changed for variant " + i);
        }
        var restored = new StorageInventory(() -> {}); restored.load(storage.save());
        h.assertTrue(!restored.isLocked() && restored.save().equals(storage.save()), "Distinct real tags failed to reload safely");
        CompoundTag duplicateSave = (CompoundTag) storage.save();
        ListTag entries = duplicateSave.getList("Entries", Tag.TAG_COMPOUND);
        CompoundTag duplicate = entries.getCompound(0).copy(); duplicate.putInt("Id", 99); entries.add(duplicate);
        var protectedStorage = new StorageInventory(() -> {}); protectedStorage.load(duplicateSave);
        h.assertTrue(protectedStorage.isLocked() && "duplicate_entry".equals(protectedStorage.loadProblem())
                && protectedStorage.save().equals(duplicateSave), "Exact duplicate corruption lost its protection or original data");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void directoryAndAtomicTransfersMergeEquivalentTags(GameTestHelper h) {
        for (boolean firstEmpty : List.of(false, true)) {
            var shared = new StorageInventory(() -> {}); int folder = shared.createFolder(0, "Target");
            shared.insert(dirt(firstEmpty, 7), 7, folder);
            shared.insert(dirt(!firstEmpty, 13), 13, 0);
            var kept = shared.entry(1).stack().save(new CompoundTag());
            h.assertTrue(shared.move(2, folder, 5) == 5 && shared.entry(1).count() == 12
                    && shared.entry(2).count() == 8 && shared.total() == 20 && shared.entries().size() == 2,
                    "Directory move allocated an equivalent tag as another row");
            h.assertTrue(kept.equals(shared.entry(1).stack().save(new CompoundTag())), "Directory move replaced the destination sample");
            var target = new StorageInventory(() -> {}); target.insert(dirt(!firstEmpty, 3), 3, 0);
            var targetSample = target.entry(1).stack().save(new CompoundTag());
            h.assertTrue(AtomicStorageTransfer.move(shared, 1, target, 0, 12) == 12 && shared.entry(1) == null
                    && target.entries().size() == 1 && target.entry(1).count() == 15 && shared.total() + target.total() == 23,
                    "Atomic transfer did not merge/conserve equivalent tag rows");
            h.assertTrue(targetSample.equals(target.entry(1).stack().save(new CompoundTag())), "Atomic transfer replaced the destination sample");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawalFillsEquivalentBackpackStacksBeforeFreeSlots(GameTestHelper h) {
        for (boolean storedEmpty : List.of(false, true)) {
            var storage = new StorageInventory(() -> {}); storage.insert(dirt(storedEmpty, 5), 5, 0);
            var inventory = h.makeMockPlayer().getInventory(); inventory.clearContent();
            inventory.setItem(0, dirt(!storedEmpty, 10));
            var beforeSample = inventory.getItem(0).copyWithCount(1).save(new CompoundTag());
            h.assertTrue(StorageTransfers.withdraw(storage, inventory, 1, 5) == 5
                    && inventory.getItem(0).getCount() == 15 && storage.total() == 0,
                    "Withdrawal did not fill an equivalent existing backpack stack");
            for (int slot = 1; slot < 36; slot++) h.assertTrue(inventory.getItem(slot).isEmpty(), "Withdrawal unnecessarily used another backpack slot");
            h.assertTrue(beforeSample.equals(inventory.getItem(0).copyWithCount(1).save(new CompoundTag())),
                    "Withdrawal rewrote the backpack item data");
        }
        h.succeed();
    }
}
