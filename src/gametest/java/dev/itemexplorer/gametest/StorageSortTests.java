package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskTier;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageLimits;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageSortTests {
    private static List<Integer> ids(StorageInventory storage, int folder) {
        return storage.entries().stream().filter(e -> e.folder() == folder).map(StorageInventory.Entry::id).toList();
    }

    @GameTest(template = "empty")
    public static void wholeFolderSortPreservesSamplesOtherFoldersAndSavedOrder(GameTestHelper h) {
        for (StorageLimits limits : List.of(StorageLimits.LOCAL, DiskTier.M16.limits())) {
            AtomicInteger dirty = new AtomicInteger();
            var storage = new StorageInventory(dirty::incrementAndGet, limits);
            int folder = storage.createFolder(0, "Sort"), other = storage.createFolder(0, "Other");
            storage.createFolder(folder, "Child");
            storage.insert(new ItemStack(Items.STONE, 64), 64, folder); // 1
            storage.insert(new ItemStack(Items.GOLD_INGOT, 11), 11, other); // 2
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.setDamageValue(17); sword.enchant(Enchantments.SHARPNESS, 3);
            sword.setHoverName(Component.literal("Keep every tag"));
            storage.insert(sword, 1, folder); // 3
            storage.insert(new ItemStack(Items.IRON_INGOT, 23), 23, folder); // 4
            storage.insert(new ItemStack(Items.APPLE, 7), 7, other); // 5
            storage.insert(new ItemStack(Items.APPLE, 19), 19, folder); // 6
            storage.insert(new ItemStack(Items.DIAMOND_SWORD), 1, folder); // 7
            storage.insert(new ItemStack(Items.COAL, 29), 29, folder); // 8
            storage.insert(new ItemStack(Items.GOLD_INGOT, 31), 31, folder); // 9
            storage.insert(new ItemStack(Items.DIAMOND, 37), 37, folder); // 10
            var before = storage.entries(); var folders = storage.folders(); long total = storage.total();
            long revision = storage.revision(), searchRevision = storage.searchRevision(), transfers = storage.transferRevision();
            int changes = dirty.get();
            List<Integer> expected = List.of(6, 8, 10, 3, 7, 9, 4, 1);
            storage.sort(folder);
            h.assertTrue(ids(storage, folder).equals(expected), "Whole-folder order is wrong");
            h.assertTrue(ids(storage, other).equals(List.of(2, 5)) && folders.equals(storage.folders()), "Other folders changed");
            h.assertTrue(storage.total() == total && storage.entries().size() == before.size(), "Sort changed quantities or merged entries");
            for (var entry : before) {
                var after = storage.entry(entry.id());
                h.assertTrue(after != null && after.folder() == entry.folder() && after.count() == entry.count()
                        && after.stack().save(new CompoundTag()).equals(entry.stack().save(new CompoundTag())), "Sort changed entry " + entry.id());
            }
            List<Integer> paged = new ArrayList<>();
            for (int page = 0; page < 2; page++) for (Tag row : storage.view(folder, page, 6, "").getList("Entries", Tag.TAG_COMPOUND))
                paged.add(((CompoundTag) row).getInt("Id"));
            h.assertTrue(paged.equals(expected) && storage.locatePage(1, 6) == 1, "Paging/locate ignored the sorted order");
            h.assertTrue(storage.revision() == revision + 1 && dirty.get() == changes + 1
                    && storage.searchRevision() == searchRevision && storage.transferRevision() == transfers, "Wrong sort activity counters");
            var saved = storage.save();
            storage.sort(folder);
            h.assertTrue(storage.save().equals(saved) && storage.revision() == revision + 1 && dirty.get() == changes + 1,
                    "Repeated sort mutated an already sorted folder");
            var restored = new StorageInventory(() -> {}, limits); restored.load(saved);
            h.assertTrue(!restored.isLocked() && restored.save().equals(saved) && ids(restored, folder).equals(expected), "Saved sort order was lost");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidAndProtectedFoldersCannotBeSorted(GameTestHelper h) {
        var storage = new StorageInventory(() -> {});
        storage.insert(new ItemStack(Items.STONE, 5), 5, 0);
        storage.insert(new ItemStack(Items.APPLE, 3), 3, 0);
        var before = storage.save(); long revision = storage.revision();
        rejected(h, () -> storage.sort(999), "invalid_folder");
        h.assertTrue(before.equals(storage.save()) && storage.revision() == revision, "Invalid sort changed storage");
        CompoundTag broken = ((CompoundTag) before).copy(); broken.putInt("Version", 999);
        storage.load(broken); revision = storage.revision();
        rejected(h, () -> storage.sort(0), "storage_locked");
        h.assertTrue(broken.equals(storage.save()) && storage.revision() == revision, "Sort changed protected data");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sortRequestsRespectContextAndRefreshOtherViewers(GameTestHelper h) {
        var opened = openTerminal(h); var storage = opened.entity().inventory(); var menu = opened.menu();
        int folder = storage.createFolder(0, "Other");
        for (var item : List.of(Items.STONE, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.COAL, Items.APPLE))
            storage.insert(new ItemStack(item, 7), 7, 0);
        var viewer = openTerminal(h, opened.entity()).menu();
        var original = storage.save();
        var valid = request(menu, Action.SORT, 0, 0, "");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            valid.encode(buffer);
            h.assertTrue(valid.equals(StorageNetwork.Request.decode(buffer)), "Sort request codec lost context");
        } finally { buffer.release(); }
        menu.handle(valid.withSession(valid.session() + 1));
        menu.handle(new StorageNetwork.Request(menu.containerId, valid.revision() - 1, Action.SORT, 0, 0, 0, "", valid.session()));
        menu.handle(request(menu, Action.SORT, folder, 0, ""));
        h.assertTrue(original.equals(storage.save()), "Stale session/revision/folder request sorted storage");
        menu.handle(request(menu, Action.PAGE, 0, 1, ""));
        h.assertTrue(menu.snapshot().getInt("Page") == 1, "Missing second-page fixture");
        menu.handle(valid);
        viewer.broadcastChanges();
        h.assertTrue(menu.snapshot().getInt("Page") == 0 && ids(storage, 0).equals(List.of(6, 5, 4, 3, 2, 1)), "Sort did not reset the page");
        h.assertTrue(menu.snapshot().get("Entries").equals(viewer.snapshot().get("Entries")), "Viewers disagree about sorted items");
        var sorted = storage.save(); long revision = storage.revision();
        menu.handle(valid);
        h.assertTrue(sorted.equals(storage.save()) && revision == storage.revision(), "Delayed request changed sorted storage");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sortingMountedDiskLeavesLocalStorageAndSearchUntouched(GameTestHelper h) {
        var opened = openTerminal(h); var menu = opened.menu(); var local = opened.entity().inventory();
        local.insert(new ItemStack(Items.STONE, 5), 5, 0); local.insert(new ItemStack(Items.APPLE, 3), 3, 0);
        var localBefore = local.save();
        BlockPos nasPos = opened.entity().getBlockPos().relative(opened.entity().getBlockState().getValue(HorizontalDirectionalBlock.FACING).getOpposite());
        h.getLevel().setBlockAndUpdate(nasPos, ModContent.NAS_BLOCK.get().defaultBlockState());
        var nas = (NasBlockEntity) h.getLevel().getBlockEntity(nasPos); nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        var disk = nas.volume(0).inventory();
        disk.insert(new ItemStack(Items.STONE, 9), 9, 0); disk.insert(new ItemStack(Items.APPLE, 4), 4, 0);
        menu.handle(request(menu, Action.SELECT_VOLUME, 0, 0, DiskItem.id(nas.disk(0)).toString()));
        h.assertTrue(!menu.snapshot().getString("Volume").isEmpty(), "Disk was not selected");
        menu.handle(request(menu, Action.SORT, 0, 0, ""));
        h.assertTrue(ids(disk, 0).equals(List.of(2, 1)) && local.save().equals(localBefore), "Disk sort affected another volume");
        menu.handle(request(menu, Action.SELECT_VOLUME, 0, 0, ""));
        var view = menu.snapshot();
        menu.handleSearch(new StorageNetwork.SearchRequest(menu.containerId, view.getLong("Session"), 1, -1, 0,
                StorageNetwork.SearchAction.START, false, 0, 6, 0, 0, view.getLong("Revision"), new int[0]));
        h.assertTrue(menu.snapshot().getBoolean("Searching"), "Search did not start");
        menu.handle(request(menu, Action.SORT, 0, 0, ""));
        h.assertTrue(menu.snapshot().getBoolean("Searching") && local.save().equals(localBefore), "Sort mutated a search session");
        h.succeed();
    }
}
