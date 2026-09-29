package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskSavedData;
import dev.itemexplorer.disk.DiskTier;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageLimits;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NasGameTests {
    private static NasBlockEntity nas(GameTestHelper h, int x, int z) {
        BlockPos p = new BlockPos(x, 2, z); h.setBlock(p, ModContent.NAS_BLOCK.get());
        return (NasBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(p));
    }
    private static ServerPlayer player(GameTestHelper h, BlockPos pos) {
        ServerPlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "nas-test")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5); return player;
    }
    private static StorageMenu terminal(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 2, 2); h.setBlock(p, ModContent.STORAGE_BLOCK.get());
        var entity = (StorageBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(p));
        ServerPlayer player = player(h, entity.getBlockPos());
        StorageMenu menu = new StorageMenu(1, player.getInventory(), entity.getBlockPos(), entity); player.containerMenu = menu; return menu;
    }
    private static StorageNetwork.Request request(StorageMenu menu, Action action, int id, long amount, String name) {
        CompoundTag view = menu.snapshot();
        return new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), action, id, 0, amount, name, view.getLong("Session"));
    }
    private static void select(StorageMenu menu, ItemStack disk) {
        menu.handle(request(menu, Action.SELECT_VOLUME, 0, 0, DiskItem.id(disk).toString()));
    }
    private static void rejected(GameTestHelper h, Runnable call, String reason) {
        boolean rejected = false;
        try { call.run(); } catch (IllegalArgumentException e) { rejected = e.getMessage().equals(reason); }
        h.assertTrue(rejected, "Expected rejection: " + reason);
    }

    @GameTest(template = "empty")
    public static void independentDisksRenameAndMoveBetweenNas(GameTestHelper h) {
        NasBlockEntity a = nas(h, 1, 2), b = nas(h, 3, 2);
        a.install(0, new ItemStack(ModContent.DISK_64K.get())); a.install(1, new ItemStack(ModContent.DISK_64K.get()));
        var disk = a.volume(0); var storage = disk.inventory();
        int folder = storage.createFolder(0, "自行分类"); storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, folder);
        a.rename(0, "我的第一块硬盘"); UUID id = disk.id(); Tag contents = storage.save();
        h.assertTrue(a.volume(1).inventory().total() == 0 && !a.volume(1).id().equals(id), "Independent disks shared data or identity");
        ItemStack ejected = a.eject(0); b.install(3, ejected);
        h.assertTrue(a.disk(0).isEmpty() && b.volume(3).id().equals(id) && b.volume(3).name().equals("我的第一块硬盘")
                && b.volume(3).inventory().save().equals(contents) && ejected.getHoverName().getString().equals("我的第一块硬盘"), "Moving disk changed contents/name/identity");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tiersCapacityAndMetadataBudgets(GameTestHelper h) {
        Item[] items = {ModContent.DISK_64K.get(), ModContent.DISK_256K.get(), ModContent.DISK_1M.get(), ModContent.DISK_16M.get()};
        NasBlockEntity nas = nas(h, 2, 2);
        for (int i = 0; i < 4; i++) {
            nas.install(i, new ItemStack(items[i])); StorageInventory storage = nas.volume(i).inventory();
            h.assertTrue(storage.capacity() == DiskTier.values()[i].limits().capacity(), "Wrong disk capacity");
            storage.insert(new ItemStack(Items.IRON_INGOT), 1, 0);
            CompoundTag saved = (CompoundTag) storage.save(); saved.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).putLong("Count", storage.capacity() - 1); storage.load(saved);
            h.assertTrue(!storage.isLocked() && storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0) == 1 && storage.total() == storage.capacity(), "Capacity boundary failed");
            h.assertTrue(storage.insert(new ItemStack(Items.DIAMOND), 1, 0) == 0, "Full disk accepted items");
            h.assertTrue(storage.take(1, Long.MAX_VALUE).getCount() == 64 && storage.total() == storage.capacity() - 64, "Withdrawal exceeded vanilla stack size");
        }
        StorageInventory limited = new StorageInventory(() -> {}, new StorageLimits(1000, 1, 2, 2));
        limited.insert(new ItemStack(Items.IRON_INGOT), 1, 0); limited.createFolder(0, "one");
        rejected(h, () -> limited.insert(new ItemStack(Items.DIAMOND), 1, 0), "entry_limit");
        rejected(h, () -> limited.createFolder(0, "two"), "folder_limit");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void longCountsSimulationAndWireProtocol(GameTestHelper h) {
        AtomicInteger dirty = new AtomicInteger(); StorageLimits limits = new StorageLimits(10_000_000_000L, 128, 64, 2);
        StorageInventory storage = new StorageInventory(dirty::incrementAndGet, limits);
        storage.insert(new ItemStack(Items.IRON_INGOT), 1, 0);
        CompoundTag saved = (CompoundTag) storage.save(); saved.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).putLong("Count", 5_000_000_000L); storage.load(saved);
        Tag before = storage.save(); long revision = storage.revision(); int changes = dirty.get();
        ItemStack input = new ItemStack(Items.DIAMOND, 17);
        h.assertTrue(storage.insert(input, 17, 0, true) == 17 && storage.take(1, Long.MAX_VALUE, true).getCount() == 64, "Simulation returned incorrect quantities");
        h.assertTrue(before.equals(storage.save()) && revision == storage.revision() && changes == dirty.get() && input.getCount() == 17, "Simulation mutated inventory");
        int folder = storage.createFolder(0, "large"); h.assertTrue(storage.move(1, folder, 4_000_000_000L) == 4_000_000_000L && storage.total() == 5_000_000_000L, "Large move overflowed");
        StorageInventory restored = new StorageInventory(() -> {}, limits); restored.load(storage.save());
        h.assertTrue(!restored.isLocked() && restored.total() == storage.total() && restored.view(folder, 0, "").getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getLong("Count") == 4_000_000_000L, "Large count serialization failed");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new StorageNetwork.Request(1, 4, Action.MOVE, 1, folder, Long.MAX_VALUE, "中文", 9876);
            packet.encode(buffer); h.assertTrue(packet.equals(StorageNetwork.Request.decode(buffer)), "Long request codec failed"); buffer.clear();
            var nasPacket = new StorageNetwork.NasRequest(4, 123, 99, 3, true); nasPacket.encode(buffer);
            h.assertTrue(nasPacket.equals(StorageNetwork.NasRequest.decode(buffer)), "NAS request codec failed");
        } finally { buffer.release(); }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateMissingAndWrongTierDisksAreRejected(GameTestHelper h) {
        NasBlockEntity a = nas(h, 1, 2), b = nas(h, 3, 2); a.install(0, new ItemStack(ModContent.DISK_64K.get()));
        ItemStack copy = a.disk(0); rejected(h, () -> b.install(0, copy), "disk_conflict");
        ItemStack missing = new ItemStack(ModContent.DISK_64K.get()); missing.getOrCreateTag().putUUID("DiskId", UUID.randomUUID());
        rejected(h, () -> b.install(0, missing), "disk_missing");
        ItemStack wrongTier = new ItemStack(ModContent.DISK_16M.get()); wrongTier.setTag(copy.getTag().copy());
        rejected(h, () -> b.install(0, wrongTier), "disk_mismatch");
        h.assertTrue(b.disk(0).isEmpty() && a.volume(0) != null, "Rejected insertion mutated bays"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void diskAndNasCorruptionPreservesOriginalData(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 2); nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        CompoundTag disk = nas.volume(0).save(new CompoundTag());
        for (boolean outer : new boolean[]{true, false}) {
            CompoundTag broken = disk.copy(); if (outer) broken.putInt("Version", 999); else broken.put("Inventory", StringTag.valueOf("broken"));
            DiskSavedData restored = DiskSavedData.load(broken);
            h.assertTrue(restored.isLocked() && restored.save(new CompoundTag()).equals(broken), "Invalid disk data was replaced");
        }
        CompoundTag raw = nas.saveWithFullMetadata(); CompoundTag broken = raw.getCompound("Nas"); broken.putInt("Version", 999);
        nas.load(raw); h.assertTrue(nas.isLocked() && nas.saveWithFullMetadata().get("Nas").equals(broken), "Invalid NAS was replaced");
        rejected(h, () -> nas.eject(0), "invalid_bay"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void diskFilesSurviveFreshDataStorageAndNasReload(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 2); nas.install(0, new ItemStack(ModContent.DISK_1M.get()));
        DiskSavedData disk = nas.volume(0); disk.inventory().insert(new ItemStack(Items.DIAMOND, 33), 33, 0); nas.rename(0, "持久盘");
        CompoundTag block = nas.saveWithFullMetadata(), contents = disk.save(new CompoundTag());
        var server = h.getLevel().getServer(); server.overworld().getDataStorage().save();
        DimensionDataStorage fresh = new DimensionDataStorage(server.getWorldPath(LevelResource.ROOT).resolve("data").toFile(), server.getFixerUpper());
        DiskSavedData restored = fresh.get(DiskSavedData::load, "itemexplorer_disk_" + disk.id());
        h.assertTrue(restored != null && restored != disk && !restored.isLocked() && contents.equals(restored.save(new CompoundTag())), "Fresh disk-file load did not preserve data/owner");
        // Swap the cache entry to exercise the NAS against the freshly decoded authoritative file.
        server.overworld().getDataStorage().set("itemexplorer_disk_" + disk.id(), restored);
        nas.load(block); nas.onLoad();
        h.assertTrue(nas.volume(0) == restored && nas.volume(0).inventory().total() == 33, "Restored NAS did not reclaim its disk");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void terminalRootsSelectionAndRename(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 3); nas.install(0, new ItemStack(ModContent.DISK_64K.get())); nas.install(1, new ItemStack(ModContent.DISK_256K.get()));
        StorageMenu menu = terminal(h);
        h.assertTrue(menu.snapshot().getList("Volumes", Tag.TAG_COMPOUND).size() == 3 && menu.snapshot().getLong("Capacity") == 4096, "Terminal roots/local capacity incorrect");
        select(menu, nas.disk(1)); menu.handle(request(menu, Action.RENAME_DISK, 0, 0, "随意命名"));
        menu.setCarried(new ItemStack(Items.DIAMOND, 16)); menu.handle(request(menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
        h.assertTrue(menu.snapshot().getLong("Capacity") == 262144 && nas.volume(1).inventory().total() == 16 && nas.volume(0).inventory().total() == 0 && nas.volume(1).name().equals("随意命名"), "Selection/rename/deposit crossed disk boundaries");
        menu.handle(request(menu, Action.SELECT_VOLUME, 0, 0, ""));
        h.assertTrue(menu.snapshot().getLong("Total") == 0 && menu.snapshot().getLong("Capacity") == 4096, "NAS altered local storage"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void switchAndReinsertRejectOldRequestsEvenAtSameRevision(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 3); nas.install(0, new ItemStack(ModContent.DISK_64K.get())); nas.install(1, new ItemStack(ModContent.DISK_64K.get()));
        for (int i = 0; i < 2; i++) nas.volume(i).inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        StorageMenu menu = terminal(h); select(menu, nas.disk(0));
        var old = request(menu, Action.WITHDRAW, 1, 32, ""); select(menu, nas.disk(1)); menu.handle(old);
        h.assertTrue(nas.volume(0).inventory().total() == 64 && nas.volume(1).inventory().total() == 64, "Same-revision disk switch accepted old request");
        var beforeEject = request(menu, Action.WITHDRAW, 1, 32, ""); ItemStack disk = nas.eject(1); nas.install(1, disk); menu.handle(beforeEject);
        h.assertTrue(nas.volume(1).inventory().total() == 64, "Reinsert revived an old mount session");
        nas.eject(1); menu.setCarried(new ItemStack(Items.DIAMOND, 5)); menu.handle(request(menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
        h.assertTrue(!menu.snapshot().getBoolean("Available") && !menu.snapshot().getString("Volume").isEmpty() && menu.getCarried().getCount() == 5, "Offline disk consumed items or fell back to local storage"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void contextualShiftDepositAndReopenedMenu(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 3); nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        BlockPos p = new BlockPos(2, 2, 2); h.setBlock(p, ModContent.STORAGE_BLOCK.get()); var entity = (StorageBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(p));
        ServerPlayer player = player(h, entity.getBlockPos()); StorageMenu menu = new StorageMenu(1, player.getInventory(), entity.getBlockPos(), entity); player.containerMenu = menu;
        select(menu, nas.disk(0)); player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        var delayed = request(menu, Action.DEPOSIT_SLOT, 27, 0, "");
        nas.install(0, nas.eject(0)); menu.handle(delayed); h.assertTrue(player.getInventory().getItem(0).getCount() == 64, "Old shift deposit consumed player stack");
        menu.handle(request(menu, Action.DEPOSIT_SLOT, 27, 0, "")); h.assertTrue(nas.volume(0).inventory().total() == 64 && player.getInventory().getItem(0).isEmpty(), "Contextual shift deposit failed");
        var old = request(menu, Action.WITHDRAW, 1, 64, ""); menu.removed(player);
        StorageMenu reopened = new StorageMenu(1, player.getInventory(), entity.getBlockPos(), entity); player.containerMenu = reopened; select(reopened, nas.disk(0)); reopened.handle(old);
        h.assertTrue(nas.volume(0).inventory().total() == 64, "Reused menu ID accepted a previous menu's request"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void nasMenuReservesSpaceAndRejectsStaleEjection(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 2); ServerPlayer player = player(h, nas.getBlockPos());
        NasMenu menu = new NasMenu(1, player.getInventory(), nas.getBlockPos(), nas); player.containerMenu = menu;
        menu.setCarried(new ItemStack(ModContent.DISK_64K.get())); menu.handle(new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, false));
        h.assertTrue(menu.getCarried().isEmpty() && nas.volume(0) != null, "Cursor disk insertion failed");
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        var old = new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, true); menu.handle(old);
        h.assertTrue(!nas.disk(0).isEmpty(), "Full player inventory lost a disk");
        player.getInventory().setItem(0, ItemStack.EMPTY); menu.handle(old);
        h.assertTrue(nas.disk(0).isEmpty() && player.getInventory().getItem(0).getItem() instanceof DiskItem, "Disk was not returned to reserved space");
        menu.quickMoveStack(player, 27); menu.handle(old);
        h.assertTrue(!nas.disk(0).isEmpty() && player.getInventory().getItem(0).isEmpty(), "Stale eject removed replacement disk");
        player.setPos(nas.getBlockPos().getX() + 30, nas.getBlockPos().getY(), nas.getBlockPos().getZ());
        menu.handle(new StorageNetwork.NasRequest(1, menu.session(), nas.revision(), 0, true));
        h.assertTrue(!nas.disk(0).isEmpty(), "Distant NAS access succeeded"); h.succeed();
    }

    private static void checkDrops(GameTestHelper h, NasBlockEntity nas, UUID id) {
        var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(nas.getBlockPos()).inflate(3));
        h.assertTrue(drops.stream().filter(e -> id.equals(DiskItem.id(e.getItem()))).mapToInt(e -> e.getItem().getCount()).sum() == 1, "NAS did not drop exactly one disk");
        h.assertTrue(drops.stream().noneMatch(e -> e.getItem().is(Items.DIAMOND)), "NAS unpacked disk contents into the world");
        DiskSavedData data = DiskSavedData.find(h.getLevel(), id); h.assertTrue(data != null && data.inventory().total() == 64, "Breaking NAS changed volume");
        NasBlockEntity replacement = nas(h, 3, 2); replacement.install(0, drops.stream().filter(e -> id.equals(DiskItem.id(e.getItem()))).findFirst().orElseThrow().getItem());
        h.assertTrue(replacement.volume(0).id().equals(id), "Dropped disk retained stale owner");
    }
    @GameTest(template = "empty")
    public static void breakingNasDropsDiskOnceAndKeepsInventory(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 1, 2); nas.install(0, new ItemStack(ModContent.DISK_64K.get())); nas.volume(0).inventory().insert(new ItemStack(Items.DIAMOND, 64), 64, 0); UUID id = nas.volume(0).id();
        h.getLevel().destroyBlock(nas.getBlockPos(), true); h.getLevel().destroyBlock(nas.getBlockPos(), true); checkDrops(h, nas, id); h.succeed();
    }
    @GameTest(template = "empty")
    public static void explosionDropsDiskWithoutUnpackingInventory(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 1, 2); nas.install(0, new ItemStack(ModContent.DISK_64K.get())); nas.volume(0).inventory().insert(new ItemStack(Items.DIAMOND, 64), 64, 0); UUID id = nas.volume(0).id(); BlockPos p = nas.getBlockPos();
        new Explosion(h.getLevel(), null, p.getX() + .5, p.getY() + .5, p.getZ() + .5, 2, false, Explosion.BlockInteraction.DESTROY, List.of(p)).finalizeExplosion(false);
        checkDrops(h, nas, id); h.succeed();
    }

    @GameTest(template = "empty")
    public static void storageDevicesCannotBeNested(GameTestHelper h) {
        StorageInventory storage = new StorageInventory(() -> {}, DiskTier.K64.limits());
        for (Item item : new Item[]{ModContent.DISK_64K.get(), ModContent.NAS_ITEM.get(), ModContent.STORAGE_ITEM.get()})
            rejected(h, () -> storage.insert(new ItemStack(item), 1, 0), "nested_device");
        h.assertTrue(storage.total() == 0, "Nested device changed inventory"); h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void unloadedNasKeepsOwnershipAndReloadsDisk(GameTestHelper h) {
        var level = h.getLevel(); BlockPos pos = new BlockPos(12288, 80, 12288);
        int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
        level.setChunkForced(chunkX, chunkZ, true); level.getChunk(chunkX, chunkZ);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); level.setBlockAndUpdate(pos, ModContent.NAS_BLOCK.get().defaultBlockState());
        NasBlockEntity original = (NasBlockEntity) level.getBlockEntity(pos); original.install(0, new ItemStack(ModContent.DISK_1M.get()));
        original.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        ItemStack copied = original.disk(0); Tag expected = original.volume(0).inventory().save();
        level.getServer().overworld().getDataStorage().save(); level.getChunkSource().save(true);
        NasBlockEntity other = nas(h, 2, 2); AtomicBoolean unloaded = new AtomicBoolean();
        Consumer<ChunkEvent.Unload> listener = event -> {
            if (event.getLevel() == level && event.getChunk().getPos().x == chunkX && event.getChunk().getPos().z == chunkZ) unloaded.set(true);
        };
        MinecraftForge.EVENT_BUS.addListener(listener); h.runAtTickTime(590, () -> MinecraftForge.EVENT_BUS.unregister(listener));
        level.setChunkForced(chunkX, chunkZ, false);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(unloaded.get() && level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "NAS chunk has not unloaded"))
                .thenExecute(() -> {
                    MinecraftForge.EVENT_BUS.unregister(listener);
                    h.assertTrue(original.isRemoved() && original.volume(0) == null, "Unloaded NAS exposed its disk");
                    rejected(h, () -> other.install(0, copied), "disk_conflict");
                    h.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "Conflict check force-loaded owner chunk");
                    NasBlockEntity restored = (NasBlockEntity) level.getBlockEntity(pos); restored.onLoad();
                    h.assertTrue(restored != original && restored.volume(0) != null && expected.equals(restored.volume(0).inventory().save()), "Reload changed NAS ownership or contents");
                    other.install(0, restored.eject(0)); h.assertTrue(other.volume(0).inventory().total() == 64, "Ejection after reload retained stale ownership");
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void twoTerminalsShareDiskRevisionAndConserveItems(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 3); nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        nas.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        StorageMenu first = terminal(h); select(first, nas.disk(0));
        BlockPos p = new BlockPos(2, 2, 4);
        h.setBlock(p, ModContent.STORAGE_BLOCK.get().defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.SOUTH));
        var entity = (StorageBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(p));
        ServerPlayer player = player(h, entity.getBlockPos()); StorageMenu second = new StorageMenu(1, player.getInventory(), entity.getBlockPos(), entity); player.containerMenu = second;
        select(second, nas.disk(0)); var delayed = request(second, Action.WITHDRAW, 1, 48, "");
        first.handle(request(first, Action.WITHDRAW, 1, 48, ""));
        h.runAtTickTime(4, () -> {
            second.handle(delayed); h.assertTrue(nas.volume(0).inventory().total() == 16 && player.getInventory().isEmpty(), "Second terminal consumed stale inventory");
            second.handle(request(second, Action.WITHDRAW, 1, 48, ""));
            h.assertTrue(nas.volume(0).inventory().total() == 0 && player.getInventory().countItem(Items.IRON_INGOT) == 16, "Shared disk over-withdrawal"); h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void oversizedAndDuplicateBayMetadataStayProtected(GameTestHelper h) {
        NasBlockEntity nas = nas(h, 2, 2);
        ItemStack huge = new ItemStack(ModContent.DISK_64K.get()); huge.getOrCreateTag().putString("Large", "x".repeat(5000));
        rejected(h, () -> nas.install(0, huge), "item_too_large"); h.assertTrue(nas.disk(0).isEmpty(), "Oversized disk entered bay");
        nas.install(0, new ItemStack(ModContent.DISK_64K.get())); CompoundTag saved = nas.saveWithFullMetadata();
        var bays = saved.getCompound("Nas").getList("Bays", Tag.TAG_COMPOUND); bays.getCompound(1).put("Stack", bays.getCompound(0).get("Stack").copy());
        nas.load(saved); h.assertTrue(nas.isLocked() && saved.get("Nas").equals(nas.saveWithFullMetadata().get("Nas")), "Duplicate disk identities inside saved NAS became active"); h.succeed();
    }
}
