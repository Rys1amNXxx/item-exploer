package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageInteractionTests {
    private static StorageBlockEntity terminal(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, ModContent.STORAGE_BLOCK.get());
        return (StorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(local));
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos pos) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "p0-test")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        return player;
    }

    private static StorageMenu open(ServerPlayer player, StorageBlockEntity entity) {
        StorageMenu menu = new StorageMenu(1, player.getInventory(), entity.getBlockPos(), entity);
        player.containerMenu = menu;
        return menu;
    }

    private static StorageNetwork.Request request(StorageBlockEntity entity, Action action, int id, int target, int amount) {
        return new StorageNetwork.Request(1, entity.inventory().revision(), action, id, target, amount, "");
    }

    private static int iron(ServerPlayer player) {
        return player.getInventory().items.stream().filter(s -> s.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void competingMenusAndDelayedRequestsConserveItems(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        var inventory = entity.inventory();
        inventory.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        int id = inventory.entries().get(0).id();
        ServerPlayer first = player(helper, entity.getBlockPos()), second = player(helper, entity.getBlockPos());
        StorageMenu a = open(first, entity), b = open(second, entity);
        var delayed = request(entity, Action.WITHDRAW, id, 0, 48);
        a.handle(delayed.withSession(a.session()));
        helper.assertTrue(inventory.total() == 16 && iron(first) == 48, "First withdrawal failed");
        helper.runAtTickTime(10, () -> {
            b.handle(delayed.withSession(b.session()));
            a.handle(delayed.withSession(a.session())); // Retransmitted application action must also be rejected.
            helper.assertTrue(inventory.total() == 16 && iron(second) == 0 && iron(first) == 48, "Delayed/repeated request consumed inventory");
            b.handle(request(entity, Action.WITHDRAW, id, 0, 48).withSession(b.session()));
            helper.assertTrue(inventory.total() == 0 && iron(first) + iron(second) == 64, "Concurrent consumers did not conserve total quantity");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void deletingAnotherViewFolderReturnsItToRoot(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        int folder = entity.inventory().createFolder(0, "Shared");
        StorageMenu a = open(player(helper, entity.getBlockPos()), entity);
        StorageMenu b = open(player(helper, entity.getBlockPos()), entity);
        a.handle(request(entity, Action.OPEN, folder, 0, 0).withSession(a.session()));
        b.handle(request(entity, Action.OPEN, folder, 0, 0).withSession(b.session()));
        a.handle(request(entity, Action.DELETE, 0, 0, 0).withSession(a.session()));
        b.broadcastChanges();
        helper.assertTrue(b.currentFolder() == 0 && !entity.inventory().hasFolder(folder), "Viewer retained a deleted folder");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void closingMenuReturnsCursorAndRejectsLateRequests(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        entity.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        ServerPlayer player = player(helper, entity.getBlockPos());
        StorageMenu oldMenu = open(player, entity);
        oldMenu.setCarried(new ItemStack(Items.IRON_INGOT, 17));
        var late = request(entity, Action.WITHDRAW, entity.inventory().entries().get(0).id(), 0, 64);
        oldMenu.removed(player);
        oldMenu.removed(player);
        helper.assertTrue(oldMenu.getCarried().isEmpty() && iron(player) == 17, "Close lost or duplicated cursor contents");
        open(player, entity);
        helper.runAtTickTime(8, () -> {
            oldMenu.handle(late);
            oldMenu.quickMoveStack(player, 27);
            helper.assertTrue(entity.inventory().total() == 64 && iron(player) == 17 && !oldMenu.stillValid(player), "Closed menu continued transferring items");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void fullInventoryCloseAndDisconnectDropCursorOnce(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        for (boolean disconnected : new boolean[]{false, true}) {
            ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "close-test")) {
                @Override public boolean hasDisconnected() { return disconnected; }
            };
            BlockPos pos = entity.getBlockPos();
            player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            StorageMenu menu = open(player, entity);
            menu.setCarried(new ItemStack(Items.IRON_INGOT, 17));
            menu.removed(player); menu.removed(player);
            helper.assertTrue(menu.getCarried().isEmpty(), "Cursor not cleared on close");
        }
        int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(entity.getBlockPos()).inflate(4)).stream()
                .filter(e -> e.getItem().is(Items.IRON_INGOT)).mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(dropped == 34 && entity.inventory().total() == 0, "Full-inventory/disconnect close must drop exactly 34 ingots, got " + dropped);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void requestLimitResetsOnNextTick(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        entity.inventory().insert(new ItemStack(Items.IRON_INGOT, 32), 32, 0);
        int id = entity.inventory().entries().get(0).id();
        ServerPlayer player = player(helper, entity.getBlockPos());
        StorageMenu menu = open(player, entity);
        for (int i = 0; i < 11; i++) menu.handle(request(entity, Action.WITHDRAW, id, 0, 1).withSession(menu.session()));
        helper.assertTrue(iron(player) == 10 && entity.inventory().total() == 22, "Per-tick limit did not reject the eleventh request");
        helper.runAtTickTime(2, () -> {
            menu.handle(request(entity, Action.WITHDRAW, id, 0, 1).withSession(menu.session()));
            helper.assertTrue(iron(player) == 11 && entity.inventory().total() == 21, "Request budget failed to reset");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void invalidSessionsAndRemovedBlocksCannotTransfer(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        entity.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        ServerPlayer player = player(helper, entity.getBlockPos());
        StorageMenu menu = open(player, entity);
        int id = entity.inventory().entries().get(0).id();
        menu.handle(new StorageNetwork.Request(2, entity.inventory().revision(), Action.WITHDRAW, id, 0, 64, "").withSession(menu.session()));
        player.setGameMode(GameType.SPECTATOR);
        menu.handle(request(entity, Action.WITHDRAW, id, 0, 64).withSession(menu.session()));
        player.setGameMode(GameType.SURVIVAL);
        player.containerMenu = player.inventoryMenu;
        menu.handle(request(entity, Action.WITHDRAW, id, 0, 64).withSession(menu.session()));
        helper.assertTrue(entity.inventory().total() == 64 && iron(player) == 0, "Invalid session extracted items");
        player.containerMenu = menu;
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
        menu.handle(request(entity, Action.WITHDRAW, id, 0, 64).withSession(menu.session()));
        helper.assertTrue(!menu.stillValid(player) && iron(player) == 0 && entity.inventory().total() == 0, "Removed block accepted requests");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedStorageRejectsCursorAndShiftDeposits(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        CompoundTag raw = new CompoundTag(); raw.putInt("Version", 999);
        CompoundTag blockData = entity.saveWithFullMetadata(); blockData.put("Storage", raw); entity.load(blockData);
        ServerPlayer player = player(helper, entity.getBlockPos());
        StorageMenu menu = open(player, entity);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        menu.setCarried(new ItemStack(Items.DIAMOND, 5));
        menu.handle(request(entity, Action.DEPOSIT_SLOT, 27, 0, 0).withSession(menu.session()));
        menu.handle(request(entity, Action.DEPOSIT_CURSOR, 0, 0, 0).withSession(menu.session()));
        menu.handle(new StorageNetwork.Request(1, entity.inventory().revision(), Action.CREATE, 0, 0, 0, "Blocked").withSession(menu.session()));
        helper.assertTrue(iron(player) == 64 && menu.getCarried().getCount() == 5 && raw.equals(entity.inventory().save()), "Locked menu consumed player items or changed protected data");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void requestsAndSnapshotsSurviveWireEncoding(GameTestHelper helper) {
        StorageBlockEntity entity = terminal(helper);
        entity.inventory().createFolder(0, "材料目录");
        entity.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (Action action : Action.values()) {
                var original = new StorageNetwork.Request(1, 42, action, 2, 3, 64, "中文目录");
                original.encode(buffer);
                helper.assertTrue(original.equals(StorageNetwork.Request.decode(buffer)) && !buffer.isReadable(), "Request codec changed " + action);
                buffer.clear();
            }
            var snapshot = new StorageNetwork.Snapshot(1, entity.inventory().view(0, 0, ""));
            snapshot.encode(buffer);
            var decoded = StorageNetwork.Snapshot.decode(buffer);
            helper.assertTrue(snapshot.equals(decoded) && !buffer.isReadable(), "Snapshot codec changed inventory");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void actualChunkUnloadAndReloadPreserveStorage(GameTestHelper helper) {
        var level = helper.getLevel();
        // Outside the GameTest/spawn tickets. Observe an actual unload before requesting a reload.
        BlockPos pos = new BlockPos(8192, 80, 8192);
        int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
        level.setChunkForced(chunkX, chunkZ, true);
        level.getChunk(chunkX, chunkZ);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        StorageBlockEntity original = (StorageBlockEntity) level.getBlockEntity(pos);
        int folder = original.inventory().createFolder(0, "Unload test");
        original.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, folder);
        CompoundTag expected = original.saveWithFullMetadata();
        level.getChunkSource().save(true);
        AtomicBoolean unloaded = new AtomicBoolean();
        Consumer<ChunkEvent.Unload> listener = event -> {
            if (event.getLevel() == level && event.getChunk().getPos().x == chunkX && event.getChunk().getPos().z == chunkZ) unloaded.set(true);
        };
        MinecraftForge.EVENT_BUS.addListener(listener);
        helper.runAtTickTime(590, () -> MinecraftForge.EVENT_BUS.unregister(listener));
        level.setChunkForced(chunkX, chunkZ, false);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(unloaded.get() && level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "Remote chunk has not unloaded"))
                .thenExecute(() -> {
                    MinecraftForge.EVENT_BUS.unregister(listener);
                    helper.assertTrue(original.isRemoved(), "Old block entity survived chunk unload");
                    StorageBlockEntity restored = (StorageBlockEntity) level.getBlockEntity(pos);
                    helper.assertTrue(restored != null && restored != original && !restored.inventory().isLocked()
                            && expected.get("Storage").equals(restored.saveWithFullMetadata().get("Storage")), "Actual disk/chunk reload lost data");
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }).thenSucceed();
    }
}
