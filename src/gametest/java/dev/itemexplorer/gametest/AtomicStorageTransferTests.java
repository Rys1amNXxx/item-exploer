package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.storage.AtomicStorageTransfer;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageLimits;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AtomicStorageTransferTests {
    private record Snapshot(Tag data, long total, long revision, long search, long transfers,
                            List<CompoundTag> catalog) {
        static Snapshot of(StorageInventory inventory) {
            return new Snapshot(inventory.save(), inventory.total(), inventory.revision(),
                    inventory.searchRevision(), inventory.transferRevision(), inventory.searchCatalog());
        }
    }

    private static StorageInventory storage() { return new StorageInventory(() -> {}); }
    private static int insert(StorageInventory storage, ItemStack stack) {
        storage.insert(stack, stack.getCount(), 0);
        return storage.entries().get(0).id();
    }
    private static void rejectsUnchanged(GameTestHelper h, String expected, StorageInventory source,
                                         int entry, StorageInventory target, int folder, long count) {
        Snapshot beforeSource = Snapshot.of(source), beforeTarget = Snapshot.of(target);
        boolean rejected = false;
        try { AtomicStorageTransfer.move(source, entry, target, folder, count); }
        catch (IllegalArgumentException failure) {
            h.assertTrue(expected.equals(failure.getMessage()), "Wrong rejection: " + failure.getMessage());
            rejected = true;
        }
        h.assertTrue(rejected, "Expected " + expected);
        h.assertTrue(beforeSource.equals(Snapshot.of(source)) && beforeTarget.equals(Snapshot.of(target)),
                "Rejected transfer changed data, ids, counters or search metadata: " + expected);
    }

    @GameTest(template = "empty")
    public static void exactTransfersPreserveNbtAndIgnoreVanillaStackLimit(GameTestHelper h) {
        StorageInventory source = storage(), target = storage();
        ItemStack sample = new ItemStack(Items.DIAMOND_PICKAXE, 7);
        sample.setDamageValue(19);
        sample.setHoverName(Component.literal("Remote project"));
        sample.getOrCreateTag().putString("Project", "kept verbatim");
        int sourceId = insert(source, sample);
        int targetId = insert(target, sample.copyWithCount(2));
        Tag itemData = source.entry(sourceId).stack().save(new CompoundTag());
        h.assertTrue(AtomicStorageTransfer.move(source, sourceId, target, 0, 5) == 5,
                "Exact transfer was clamped to the tool stack limit");
        h.assertTrue(source.entry(sourceId).count() == 2 && target.entry(targetId).count() == 7,
                "Merge did not conserve the exact quantity");
        h.assertTrue(itemData.equals(target.entry(targetId).stack().save(new CompoundTag())), "Item NBT changed");
        h.assertTrue(target.entries().size() == 1, "Identical item data did not merge");
        AtomicStorageTransfer.move(source, sourceId, target, 0, 2);
        h.assertTrue(source.entry(sourceId) == null && source.total() == 0 && target.total() == 9,
                "Full-entry transfer left a ghost entry or lost items");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidRequestsAndPartialCapacityNeverMutateEitherSide(GameTestHelper h) {
        AtomicInteger sourceDirty = new AtomicInteger(), targetDirty = new AtomicInteger();
        StorageInventory source = new StorageInventory(sourceDirty::incrementAndGet);
        StorageInventory target = new StorageInventory(targetDirty::incrementAndGet, new StorageLimits(6, 4, 4, 1));
        int id = insert(source, new ItemStack(Items.IRON_INGOT, 8));
        insert(target, new ItemStack(Items.IRON_INGOT, 4));
        int sourceChanges = sourceDirty.get(), targetChanges = targetDirty.get();
        rejectsUnchanged(h, "invalid_amount", source, id, target, 0, 0);
        rejectsUnchanged(h, "invalid_amount", source, id, target, 0, -1);
        rejectsUnchanged(h, "missing_item", source, id + 1, target, 0, 1);
        rejectsUnchanged(h, "insufficient_items", source, id, target, 0, 9);
        rejectsUnchanged(h, "insufficient_items", source, id, target, 0, Long.MAX_VALUE);
        rejectsUnchanged(h, "invalid_folder", source, id, target, 99, 1);
        rejectsUnchanged(h, "storage_full", source, id, target, 0, 3);
        h.assertTrue(sourceDirty.get() == sourceChanges && targetDirty.get() == targetChanges,
                "Rejected transfer marked an owner dirty");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedSourceAndDestinationStayVerbatim(GameTestHelper h) {
        StorageInventory source = storage(), target = storage();
        int id = insert(source, new ItemStack(Items.IRON_INGOT, 3));
        StorageInventory locked = storage();
        locked.load(StringTag.valueOf("future-format-original-data"));
        rejectsUnchanged(h, "storage_locked", source, id, locked, 0, 1);
        rejectsUnchanged(h, "storage_locked", locked, 1, target, 0, 1);
        h.assertTrue(locked.save().equals(StringTag.valueOf("future-format-original-data")),
                "Protected storage was rewritten");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void entryAndIdentifierLimitsRejectOnlyNewRows(GameTestHelper h) {
        StorageInventory source = storage();
        StorageInventory target = new StorageInventory(() -> {}, new StorageLimits(100, 1, 4, 1));
        int id = insert(source, new ItemStack(Items.IRON_INGOT, 3));
        insert(target, new ItemStack(Items.STONE, 2));
        rejectsUnchanged(h, "entry_limit", source, id, target, 0, 1);
        StorageInventory matching = new StorageInventory(() -> {}, new StorageLimits(100, 1, 4, 1));
        insert(matching, new ItemStack(Items.IRON_INGOT, 2));
        h.assertTrue(AtomicStorageTransfer.move(source, id, matching, 0, 1) == 1 && matching.total() == 3,
                "A merge incorrectly required a new entry slot");
        StorageInventory exhaustedIds = storage();
        CompoundTag exhausted = (CompoundTag) exhaustedIds.save();
        exhausted.putInt("NextEntry", Integer.MAX_VALUE);
        exhaustedIds.load(exhausted);
        rejectsUnchanged(h, "entry_limit", source, id, exhaustedIds, 0, 1);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedInventoryMovesDirectoriesWithoutDuplicatingOrConsumingCapacity(GameTestHelper h) {
        StorageInventory shared = new StorageInventory(() -> {}, new StorageLimits(6, 1, 4, 1));
        int folder = shared.createFolder(0, "Inbox");
        int id = insert(shared, new ItemStack(Items.IRON_INGOT, 6));
        Snapshot before = Snapshot.of(shared);
        h.assertTrue(AtomicStorageTransfer.move(shared, id, shared, 0, 6) == 0
                && before.equals(Snapshot.of(shared)), "Same inventory and folder should be a no-op");
        rejectsUnchanged(h, "entry_limit", shared, id, shared, folder, 1);
        rejectsUnchanged(h, "insufficient_items", shared, id, shared, folder, 7);
        h.assertTrue(AtomicStorageTransfer.move(shared, id, shared, folder, 6) == 6,
                "Full inventory could not move the whole row");
        h.assertTrue(shared.total() == 6 && shared.entries().size() == 1
                && shared.entry(id).folder() == folder, "Shared inventory move duplicated inventory");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void searchRevisionsChangeOnlyForMembershipChanges(GameTestHelper h) {
        StorageInventory source = storage(), target = storage();
        int id = insert(source, new ItemStack(Items.IRON_INGOT, 10));
        int folder = target.createFolder(0, "Inbox");
        source.searchCatalog(); target.searchCatalog();
        long sourceSearch = source.searchRevision(), targetSearch = target.searchRevision();
        AtomicStorageTransfer.move(source, id, target, folder, 2);
        h.assertTrue(source.searchRevision() == sourceSearch && target.searchRevision() == targetSearch + 1,
                "Wrong search revisions for a partial transfer into a new row");
        h.assertTrue(target.searchCatalog().size() == 1 && target.searchCatalog().get(0).getInt("Folder") == folder,
                "New target row missing from search catalog");
        AtomicStorageTransfer.move(source, id, target, folder, 2);
        h.assertTrue(source.searchRevision() == sourceSearch && target.searchRevision() == targetSearch + 1,
                "Count-only merge invalidated search metadata");
        AtomicStorageTransfer.move(source, id, target, folder, 6);
        h.assertTrue(source.searchRevision() == sourceSearch + 1 && source.searchCatalog().isEmpty()
                && target.searchRevision() == targetSearch + 1, "Removed source row remained searchable");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dirtyCallbacksObserveBothInventoriesAlreadyCommitted(GameTestHelper h) {
        StorageInventory[] inventories = new StorageInventory[2];
        AtomicBoolean observing = new AtomicBoolean();
        AtomicInteger notifications = new AtomicInteger();
        Runnable changed = () -> {
            if (!observing.get()) return;
            h.assertTrue(inventories[0].total() == 5 && inventories[1].total() == 8,
                    "An owner observed a half-applied transfer");
            notifications.incrementAndGet();
        };
        inventories[0] = new StorageInventory(changed);
        inventories[1] = new StorageInventory(changed);
        int id = insert(inventories[0], new ItemStack(Items.IRON_INGOT, 10));
        insert(inventories[1], new ItemStack(Items.IRON_INGOT, 3));
        long revisionA = inventories[0].revision(), revisionB = inventories[1].revision();
        long transferA = inventories[0].transferRevision(), transferB = inventories[1].transferRevision();
        observing.set(true);
        AtomicStorageTransfer.move(inventories[0], id, inventories[1], 0, 5);
        h.assertTrue(notifications.get() == 2 && inventories[0].revision() == revisionA + 1
                && inventories[1].revision() == revisionB + 1 && inventories[0].transferRevision() == transferA + 1
                && inventories[1].transferRevision() == transferB + 1, "Commit notified or advanced counters more than once");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void longQuantitiesSurviveOrdinaryIndependentSaveAndLoad(GameTestHelper h) {
        StorageLimits limits = new StorageLimits(10_000_000_000L, 8, 4, 2);
        StorageInventory source = new StorageInventory(() -> {}, limits);
        StorageInventory target = new StorageInventory(() -> {}, limits);
        int id = insert(source, new ItemStack(Items.IRON_INGOT));
        CompoundTag initial = (CompoundTag) source.save();
        initial.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).putLong("Count", 5_000_000_000L);
        source.load(initial);
        h.assertTrue(AtomicStorageTransfer.move(source, id, target, 0, 3_000_000_000L) == 3_000_000_000L,
                "Quantity was narrowed to an integer");
        StorageInventory restoredSource = new StorageInventory(() -> {}, limits);
        StorageInventory restoredTarget = new StorageInventory(() -> {}, limits);
        restoredSource.load(source.save()); restoredTarget.load(target.save());
        h.assertTrue(!restoredSource.isLocked() && !restoredTarget.isLocked()
                && restoredSource.total() == 2_000_000_000L && restoredTarget.total() == 3_000_000_000L,
                "Ordinary save/load lost long transfer quantities");
        h.succeed();
    }
}
