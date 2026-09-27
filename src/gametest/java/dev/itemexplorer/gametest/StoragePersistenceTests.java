package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoragePersistenceTests {
    private static StorageInventory storage() { return new StorageInventory(() -> {}); }

    private static CompoundTag fixture() {
        StorageInventory inventory = storage();
        int parent = inventory.createFolder(0, "Materials");
        int child = inventory.createFolder(parent, "Project");
        inventory.insert(new ItemStack(Items.IRON_INGOT, 64), 64, child);
        inventory.insert(new ItemStack(Items.DIAMOND, 12), 12, parent);
        return (CompoundTag) inventory.save();
    }

    private static void protectedRoundTrip(GameTestHelper helper, Tag data) {
        Tag original = data.copy();
        StorageInventory inventory = storage();
        inventory.load(data);
        helper.assertTrue(inventory.isLocked() && !inventory.loadProblem().isBlank(), "Invalid storage was not protected: " + data);
        helper.assertTrue(inventory.total() == 0 && inventory.entries().isEmpty() && inventory.folders().size() == 1,
                "Partially validated contents became accessible");
        helper.assertTrue(inventory.view(0, 0, "").getBoolean("Locked"), "Protection missing from snapshot");
        Runnable[] changes = {
                () -> inventory.createFolder(0, "New"), () -> inventory.renameFolder(0, "New"),
                () -> inventory.deleteFolder(1), () -> inventory.insert(new ItemStack(Items.GOLD_INGOT), 1, 0),
                () -> inventory.take(1, 1), () -> inventory.move(1, 0, 1)
        };
        long revision = inventory.revision();
        for (Runnable change : changes) {
            boolean rejected = false;
            try { change.run(); }
            catch (IllegalArgumentException expected) { rejected = "storage_locked".equals(expected.getMessage()); }
            helper.assertTrue(rejected, "Protected storage allowed a mutation");
        }
        helper.assertTrue(inventory.revision() == revision && original.equals(inventory.save()), "Protection changed original data");
        if (data instanceof CompoundTag compound) compound.putString("ExternalMutation", "ignored");
        Tag saved = inventory.save();
        if (saved instanceof CompoundTag compound) compound.putString("ExternalMutation", "ignored");
        helper.assertTrue(original.equals(inventory.save()), "Protected data leaked a mutable reference");
        StorageInventory reloaded = storage();
        reloaded.load(inventory.save());
        helper.assertTrue(reloaded.isLocked() && original.equals(reloaded.save()), "Second load discarded recovery data");
        inventory.load(fixture());
        helper.assertTrue(!inventory.isLocked() && inventory.total() == 76, "Repaired data could not be loaded");
    }

    private static void corrupt(GameTestHelper helper, Consumer<CompoundTag> change) {
        CompoundTag data = fixture();
        change.accept(data);
        protectedRoundTrip(helper, data);
    }

    @GameTest(template = "empty")
    public static void unknownVersionsAndWrongTypesPreserveOriginalData(GameTestHelper helper) {
        corrupt(helper, tag -> tag.putInt("Version", 999));
        corrupt(helper, tag -> tag.putInt("Version", 0));
        corrupt(helper, tag -> tag.remove("Version"));
        corrupt(helper, tag -> tag.putString("Version", "1"));
        corrupt(helper, tag -> tag.remove("Entries"));
        corrupt(helper, tag -> tag.putString("Folders", "not a list"));
        corrupt(helper, tag -> { ListTag list = new ListTag(); list.add(StringTag.valueOf("bad")); tag.put("Entries", list); });
        corrupt(helper, tag -> tag.putInt("NextEntry", -1));
        protectedRoundTrip(helper, StringTag.valueOf("unrecognized storage payload"));
        protectedRoundTrip(helper, new CompoundTag());
        StorageInventory fresh = storage();
        fresh.load(null);
        helper.assertTrue(!fresh.isLocked() && fresh.total() == 0, "Absent Storage should initialize a new block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidTreesAndIdsAreProtected(GameTestHelper helper) {
        corrupt(helper, tag -> tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(0).putInt("Parent", 2));
        corrupt(helper, tag -> tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(0).putInt("Parent", 999));
        corrupt(helper, tag -> tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(1).putInt("Id", 1));
        corrupt(helper, tag -> tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(0).putInt("Id", 0));
        corrupt(helper, tag -> tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(1).putString("Name", "../bad"));
        corrupt(helper, tag -> tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(1).putString("Name", "hidden\u202ename"));
        corrupt(helper, tag -> {
            CompoundTag folder = tag.getList("Folders", Tag.TAG_COMPOUND).getCompound(1);
            folder.putInt("Parent", 0); folder.putString("Name", "materials");
        });
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(1).putInt("Id", 1));
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).putInt("Folder", 999));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidCountsAndItemsAreProtected(GameTestHelper helper) {
        for (int count : new int[]{-1, 0, StorageInventory.CAPACITY, Integer.MAX_VALUE}) {
            corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).putInt("Count", count));
        }
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("Stack").putString("id", "missing_mod:lost_item"));
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("Stack").putByte("Count", (byte) 2));
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("Stack").putString("tag", "wrong type"));
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("Stack").putString("UnrecognizedItemData", "must not be silently lost"));
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("Stack").putByteArray("Oversized", new byte[9000]));
        corrupt(helper, tag -> tag.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).put("Stack", ModContent.STORAGE_ITEM.get().getDefaultInstance().save(new CompoundTag())));
        corrupt(helper, tag -> {
            ListTag items = tag.getList("Entries", Tag.TAG_COMPOUND);
            CompoundTag duplicate = items.getCompound(0).copy(); duplicate.putInt("Id", 3); items.add(duplicate);
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedLimitsAreValidatedBeforePublishing(GameTestHelper helper) {
        corrupt(helper, tag -> {
            ListTag folders = new ListTag();
            for (int i = 1; i <= StorageInventory.MAX_FOLDERS; i++) {
                CompoundTag folder = new CompoundTag(); folder.putInt("Id", i); folder.putInt("Parent", 0); folder.putString("Name", "F" + i); folders.add(folder);
            }
            tag.put("Folders", folders);
        });
        corrupt(helper, tag -> {
            ListTag folders = new ListTag();
            for (int i = 1; i <= StorageInventory.MAX_DEPTH + 1; i++) {
                CompoundTag folder = new CompoundTag(); folder.putInt("Id", i); folder.putInt("Parent", i - 1); folder.putString("Name", "F" + i); folders.add(folder);
            }
            tag.put("Folders", folders);
        });
        corrupt(helper, tag -> {
            ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
            while (entries.size() <= StorageInventory.MAX_ENTRIES) entries.add(entries.getCompound(0).copy());
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reorderedVersionOneFoldersAndIdCountersLoadSafely(GameTestHelper helper) {
        CompoundTag tag = fixture();
        ListTag old = tag.getList("Folders", Tag.TAG_COMPOUND);
        ListTag reversed = new ListTag(); reversed.add(old.get(1)); reversed.add(old.get(0)); tag.put("Folders", reversed);
        tag.putInt("NextFolder", 1); tag.putInt("NextEntry", 1);
        StorageInventory loaded = storage(); loaded.load(tag);
        helper.assertTrue(!loaded.isLocked() && loaded.total() == 76 && loaded.folder(2).parent() == 1, "Order-dependent loading lost folders/items");
        int id = loaded.createFolder(2, "Next");
        loaded.insert(new ItemStack(Items.GOLD_INGOT), 1, id);
        helper.assertTrue(id > 2 && loaded.entries().size() == 3 && loaded.total() == 77, "ID counter repair overwrote an entry");
        StorageInventory second = storage(); second.load(loaded.save());
        helper.assertTrue(!second.isLocked() && loaded.save().equals(second.save()), "Valid version-one save did not round trip");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedBlockDataAndArchiveSurviveRemoval(GameTestHelper helper) throws Exception {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, ModContent.STORAGE_BLOCK.get());
        StorageBlockEntity entity = (StorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(local));
        CompoundTag broken = fixture(); broken.putInt("Version", 999);
        CompoundTag blockTag = entity.saveWithFullMetadata(); blockTag.put("Storage", broken);
        entity.load(blockTag);
        helper.assertTrue(entity.inventory().isLocked() && entity.saveWithFullMetadata().get("Storage").equals(broken), "Block save discarded raw Storage");
        Path archive = entity.recoveryArchive();
        helper.assertTrue(archive != null && Files.isRegularFile(archive), "No independent recovery archive created");
        entity.onLoad();
        helper.assertTrue(archive.equals(entity.recoveryArchive()), "Unchanged data produced another archive");
        helper.setBlock(local, Blocks.AIR);
        try (var input = Files.newInputStream(archive)) {
            CompoundTag saved = NbtIo.readCompressed(input);
            helper.assertTrue(saved.get("Storage").equals(broken) && saved.getInt("X") == entity.getBlockPos().getX(), "Removal destroyed or changed archived data");
        }
        // A malformed outer tag must also survive the block entity's load/save boundary.
        blockTag.putString("Storage", "broken outer type");
        StorageBlockEntity detached = new StorageBlockEntity(entity.getBlockPos(), entity.getBlockState());
        detached.load(blockTag);
        helper.assertTrue(detached.inventory().isLocked() && detached.saveWithFullMetadata().getString("Storage").equals("broken outer type"), "Wrong tag type became empty inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void archivesAreIdempotentAndWriteFailureKeepsOriginal(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory("itemexplorer-recovery-test-");
        Path archive = null;
        Path obstruction = directory.resolve("not-a-directory");
        try {
            CompoundTag source = fixture(); source.putInt("Version", 999);
            StorageInventory protectedInventory = storage(); protectedInventory.load(source);
            archive = StorageRecovery.archive(directory, protectedInventory.save(), "minecraft:overworld", 1, 2, 3, protectedInventory.loadProblem());
            helper.assertTrue(archive.equals(StorageRecovery.archive(directory, source, "minecraft:overworld", 1, 2, 3, protectedInventory.loadProblem())), "Archive is not idempotent");
            Files.writeString(obstruction, "blocked");
            boolean failed = false;
            try { StorageRecovery.archive(obstruction, source, "minecraft:overworld", 1, 2, 3, "test"); }
            catch (java.io.IOException expected) { failed = true; }
            helper.assertTrue(failed && source.equals(protectedInventory.save()) && protectedInventory.isLocked(), "Backup failure changed live data");
            Files.writeString(archive, "truncated backup");
            failed = false;
            try { StorageRecovery.archive(directory, source, "minecraft:overworld", 1, 2, 3, protectedInventory.loadProblem()); }
            catch (java.io.IOException expected) { failed = true; }
            helper.assertTrue(failed, "Corrupt existing backup was accepted as a successful archive");
        } finally {
            Files.deleteIfExists(obstruction);
            if (archive != null) Files.deleteIfExists(archive);
            Files.deleteIfExists(directory);
        }
        helper.succeed();
    }
}
