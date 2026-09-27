package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.util.FakePlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageGameTests {
    private static StorageInventory storage() { return new StorageInventory(() -> {}); }

    private static void rejects(GameTestHelper helper, String expected, Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException e) {
            helper.assertTrue(expected.equals(e.getMessage()), "Expected " + expected + ", got " + e.getMessage());
            return;
        }
        helper.fail("Expected rejection: " + expected);
    }

    @GameTest(template = "empty")
    public static void foldersAndCountsSurviveBlockEntitySerialization(GameTestHelper helper) throws Exception {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos, ModContent.STORAGE_BLOCK.get());
        StorageBlockEntity original = (StorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        StorageInventory inventory = original.inventory();
        int ores = inventory.createFolder(0, "矿物");
        int project = inventory.createFolder(ores, "城堡");
        inventory.insert(new ItemStack(Items.IRON_INGOT, 64), 64, ores);
        inventory.insert(new ItemStack(Items.IRON_INGOT, 64), 64, ores);
        int iron = inventory.entries().get(0).id();
        inventory.move(iron, project, 32);
        inventory.renameFolder(project, "城堡二期");
        rejects(helper, "not_empty", () -> inventory.deleteFolder(project));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(original.saveWithFullMetadata(), new DataOutputStream(bytes));
        StorageBlockEntity restored = new StorageBlockEntity(helper.absolutePos(pos), original.getBlockState());
        restored.load(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        helper.assertTrue(restored.inventory().total() == 128, "Save/load lost items");
        helper.assertTrue(restored.inventory().entry(iron).count() == 96, "Source quantity changed");
        helper.assertTrue(restored.inventory().entries().stream().anyMatch(e -> e.folder() == project && e.count() == 32), "Destination allocation lost");
        helper.assertTrue(restored.inventory().folder(project).name().equals("城堡二期"), "Folder rename lost");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryAndPartialRoomDoNotVoidItems(GameTestHelper helper) {
        StorageInventory storage = storage();
        storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        int id = storage.entries().get(0).id();
        var player = helper.makeMockPlayer();
        player.getAbilities().instabuild = true;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.STONE, 64));
        helper.assertTrue(StorageTransfers.withdraw(storage, inventory, id, 64) == 0, "Full creative inventory consumed items");
        helper.assertTrue(storage.total() == 64, "Items lost in full inventory");
        inventory.setItem(8, new ItemStack(Items.IRON_INGOT, 60));
        helper.assertTrue(StorageTransfers.withdraw(storage, inventory, id, 64) == 4, "Should fit exactly four ingots");
        helper.assertTrue(storage.total() == 60 && inventory.getItem(8).getCount() == 64, "Partial transfer did not conserve items");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void damageAndCustomNamesRemainDistinct(GameTestHelper helper) {
        StorageInventory storage = storage();
        ItemStack first = new ItemStack(Items.DIAMOND_PICKAXE);
        first.setDamageValue(10);
        ItemStack second = new ItemStack(Items.DIAMOND_PICKAXE);
        second.setDamageValue(20);
        second.setHoverName(Component.literal("Fortune project"));
        storage.insert(first, 1, 0); storage.insert(second, 1, 0);
        helper.assertTrue(storage.entries().size() == 2, "Different tools were merged");
        StorageInventory restored = storage(); restored.load(storage.save());
        helper.assertTrue(restored.entries().stream().anyMatch(e -> e.stack().getDamageValue() == 20
                && e.stack().getHoverName().getString().equals("Fortune project")), "Tool data was lost");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void capacityAndRejectedSplitsConserveItems(GameTestHelper helper) {
        StorageInventory full = storage();
        helper.assertTrue(full.insert(new ItemStack(Items.COBBLESTONE, 5000), 5000, 0) == StorageInventory.CAPACITY, "Capacity exceeded");
        helper.assertTrue(full.insert(new ItemStack(Items.DIAMOND), 1, 0) == 0, "Full storage accepted another item");
        StorageInventory storage = storage();
        int target = storage.createFolder(0, "Target");
        for (int i = 0; i < StorageInventory.MAX_ENTRIES; i++) {
            ItemStack stack = new ItemStack(Items.STONE, i == 0 ? 2 : 1);
            stack.setHoverName(Component.literal("Type " + i));
            storage.insert(stack, stack.getCount(), 0);
        }
        int id = storage.entries().get(0).id();
        long revision = storage.revision();
        rejects(helper, "entry_limit", () -> storage.move(id, target, 1));
        helper.assertTrue(storage.total() == 129 && storage.entry(id).count() == 2 && storage.revision() == revision, "Rejected split modified inventory");
        helper.assertTrue(storage.move(id, target, 2) == 2 && storage.entries().size() == 128, "Whole-entry move should work at entry limit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void foldersAndItemPayloadsAreBounded(GameTestHelper helper) {
        StorageInventory storage = storage();
        storage.createFolder(0, "Ore");
        rejects(helper, "duplicate_name", () -> storage.createFolder(0, "ore"));
        rejects(helper, "invalid_name", () -> storage.createFolder(0, "../outside"));
        rejects(helper, "root_folder", () -> storage.deleteFolder(0));
        rejects(helper, "nested_device", () -> storage.insert(ModContent.STORAGE_ITEM.get().getDefaultInstance(), 1, 0));
        ItemStack oversized = new ItemStack(Items.STONE);
        oversized.getOrCreateTag().putByteArray("Payload", new byte[9000]);
        rejects(helper, "item_too_large", () -> storage.insert(oversized, 1, 0));
        helper.assertTrue(storage.total() == 0, "Rejected item mutated storage");
        int parent = 0;
        for (int i = 0; i < StorageInventory.MAX_DEPTH; i++) parent = storage.createFolder(parent, "Level" + i);
        final int deepest = parent;
        rejects(helper, "folder_limit", () -> storage.createFolder(deepest, "Too deep"));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingBlockDropsContentsExactlyOnce(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, ModContent.STORAGE_BLOCK.get());
        BlockPos pos = helper.absolutePos(local);
        StorageBlockEntity blockEntity = (StorageBlockEntity) helper.getLevel().getBlockEntity(pos);
        blockEntity.inventory().insert(new ItemStack(Items.IRON_INGOT, 130), 130, 0);
        helper.setBlock(local, Blocks.AIR);
        helper.setBlock(local, Blocks.AIR);
        int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .filter(e -> e.getItem().is(Items.IRON_INGOT)).mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(dropped == 130, "Block removal must drop exactly 130 ingots, got " + dropped);
        helper.assertTrue(blockEntity.inventory().total() == 0, "Removed block retained duplicate inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void snapshotsArePagedAndIndependent(GameTestHelper helper) {
        StorageInventory storage = storage();
        for (int i = 0; i < 13; i++) {
            ItemStack stack = new ItemStack(Items.STONE);
            stack.setHoverName(Component.literal("Variant " + i));
            storage.insert(stack, 1, 0);
        }
        CompoundTag first = storage.view(0, 0, "");
        CompoundTag last = storage.view(0, 99, "");
        helper.assertTrue(first.getList("Entries", 10).size() == 6 && first.getInt("Pages") == 3, "Page size is not bounded");
        helper.assertTrue(last.getInt("Page") == 2 && last.getList("Entries", 10).size() == 1, "Invalid page was not clamped");
        storage.entries().get(0).stack().setHoverName(Component.literal("Mutated outside"));
        helper.assertTrue(storage.entries().get(0).stack().getHoverName().getString().equals("Variant 0"), "External stack reference mutated storage");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void childFoldersAndItemsSharePagesWithoutOmissions(GameTestHelper helper) {
        StorageInventory storage = storage();
        Set<Integer> expectedFolders = new HashSet<>();
        for (int i = 0; i < 7; i++) expectedFolders.add(storage.createFolder(0, "Folder " + i));
        int parent = expectedFolders.iterator().next();
        int nested = storage.createFolder(parent, "Nested");
        storage.insert(new ItemStack(Items.DIAMOND), 1, nested);
        Set<Integer> expectedItems = new HashSet<>();
        for (int i = 0; i < 13; i++) {
            ItemStack item = new ItemStack(Items.STONE);
            item.setHoverName(Component.literal("Root item " + i));
            storage.insert(item, 1, 0);
        }
        storage.entries().stream().filter(e -> e.folder() == 0).forEach(e -> expectedItems.add(e.id()));
        Set<Integer> seenFolders = new HashSet<>(), seenItems = new HashSet<>();
        CompoundTag first = storage.view(0, 0, 6, "");
        helper.assertTrue(first.getInt("Pages") == 4 && first.getIntArray("PageFolders").length == 6
                && first.getList("Entries", 10).isEmpty(), "Folders must occupy the first page before items");
        for (int page = 0; page < first.getInt("Pages"); page++) {
            CompoundTag view = storage.view(0, page, 6, "");
            int[] childIds = view.getIntArray("PageFolders");
            var itemTags = view.getList("Entries", 10);
            helper.assertTrue(childIds.length + itemTags.size() <= 6, "Combined page exceeded its budget");
            for (int id : childIds) helper.assertTrue(seenFolders.add(id), "Folder repeated across pages");
            for (int i = 0; i < itemTags.size(); i++) helper.assertTrue(seenItems.add(itemTags.getCompound(i).getInt("Id")), "Item repeated across pages");
        }
        helper.assertTrue(seenFolders.equals(expectedFolders) && seenItems.equals(expectedItems), "Mixed pagination omitted entries or included descendants");
        CompoundTag nestedView = storage.view(parent, 0, 25, "");
        helper.assertTrue(nestedView.getIntArray("PageFolders").length == 1
                && nestedView.getIntArray("PageFolders")[0] == nested, "Opening a folder did not expose its direct child");
        CompoundTag large = storage.view(0, 999, Integer.MAX_VALUE, "");
        helper.assertTrue(large.getInt("PageSize") == StorageInventory.MAX_PAGE_SIZE && large.getInt("Page") == 0
                && large.getIntArray("PageFolders").length + large.getList("Entries", 10).size() == 20, "Resize did not clamp and repaginate");
        helper.assertTrue(storage.view(0, 0, -1, "").getInt("PageSize") == 6 && storage.total() == 14, "Invalid size or browsing changed inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void responsiveLayoutPreservesInventorySlotIdentity(GameTestHelper helper) {
        var player = helper.makeMockPlayer();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.STONE, i + 1));
        StorageMenu clientMenu = new StorageMenu(1, inventory, BlockPos.ZERO, null);
        int[][] viewports = {{320, 240}, {360, 240}, {480, 270}, {720, 415}, {1920, 1080}};
        for (int[] viewport : viewports) {
            StorageLayout layout = StorageLayout.fit(viewport[0], viewport[1]);
            helper.assertTrue(layout.width() <= viewport[0] && layout.height() <= viewport[1], "Window extends outside viewport");
            helper.assertTrue(layout.pageSize() >= 6 && layout.pageSize() <= StorageInventory.MAX_PAGE_SIZE, "Unsupported page size");
            helper.assertTrue(layout.browserY() + layout.rows() * 30 < layout.controlsY()
                    && layout.inventoryY() + 58 + 16 < layout.height(), "Browser or inventory overlaps another region");
            clientMenu.arrangeClientSlots(layout.inventoryX(), layout.inventoryY());
            for (int i = 0; i < 36; i++) {
                var slot = clientMenu.slots.get(i);
                int inventoryIndex = i < 27 ? i + 9 : i - 27;
                helper.assertTrue(slot.index == i && slot.getContainerSlot() == inventoryIndex
                        && slot.getItem() == inventory.getItem(inventoryIndex), "Resize changed the slot's identity or backing item");
                helper.assertTrue(slot.x >= 0 && slot.x + 16 < layout.width() && slot.y + 16 < layout.height(), "Slot left the window");
            }
        }
        clientMenu.slots.get(0).remove(1);
        clientMenu.slots.get(27).remove(1);
        helper.assertTrue(inventory.getItem(9).getCount() == 9 && inventory.getItem(0).isEmpty(), "Reflowed slots modify the wrong inventory positions");
        helper.assertTrue(StorageLayout.fit(720, 415).pageSize() == 25, "Large viewport should expose 25 tiles");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void staleAndRemoteMenuActionsCannotExtractItems(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, ModContent.STORAGE_BLOCK.get());
        BlockPos pos = helper.absolutePos(local);
        StorageBlockEntity entity = (StorageBlockEntity) helper.getLevel().getBlockEntity(pos);
        StorageInventory storage = entity.inventory();
        storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        int id = storage.entries().get(0).id();
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "storage-test"));
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        StorageMenu menu = new StorageMenu(1, player.getInventory(), pos, entity);
        player.containerMenu = menu;
        long stale = storage.revision();
        storage.createFolder(0, "Other change");
        menu.handle(new StorageNetwork.Request(1, stale, StorageNetwork.Action.WITHDRAW, id, 0, 64, ""));
        helper.assertTrue(storage.total() == 64, "Stale request extracted items");
        menu.handle(new StorageNetwork.Request(1, storage.revision(), StorageNetwork.Action.WITHDRAW, id, 0, 32, ""));
        helper.assertTrue(storage.total() == 32, "Fresh request should extract exactly 32");
        player.setPos(pos.getX() + 20, pos.getY(), pos.getZ());
        menu.handle(new StorageNetwork.Request(1, storage.revision(), StorageNetwork.Action.WITHDRAW, id, 0, 32, ""));
        helper.assertTrue(storage.total() == 32, "Distant player extracted items");
        helper.succeed();
    }
}
