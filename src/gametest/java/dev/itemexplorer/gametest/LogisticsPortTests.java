package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.*;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageInventory;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LogisticsPortTests {
    private static NasBlockEntity nas(GameTestHelper h) {
        h.setBlock(new BlockPos(2, 3, 2), ModContent.NAS_BLOCK.get());
        var nas = (NasBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 3, 2)));
        nas.install(0, new ItemStack(ModContent.DISK_64K.get())); return nas;
    }
    private static LogisticsPortBlockEntity port(GameTestHelper h, BlockPos host, Direction facing) {
        BlockPos pos = host.relative(facing);
        h.getLevel().setBlockAndUpdate(pos, ModContent.LOGISTICS_BLOCK.get().defaultBlockState().setValue(LogisticsPortBlock.FACING, facing));
        return (LogisticsPortBlockEntity) h.getLevel().getBlockEntity(pos);
    }
    private static IItemHandler handler(LogisticsPortBlockEntity port, Direction side) {
        return port.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElseThrow(() -> new AssertionError("Missing item handler"));
    }
    private static int slot(IItemHandler handler, net.minecraft.world.item.Item item) {
        for (int i = 1; i < handler.getSlots(); i++) if (handler.getStackInSlot(i).is(item)) return i;
        return -1;
    }
    private static long count(IItemHandler handler) { long total = 0; for (int i=0;i<handler.getSlots();i++) total += handler.getStackInSlot(i).getCount(); return total; }
    private static LogisticsPortBlockEntity bound(GameTestHelper h, NasBlockEntity nas, int folder, boolean recursive) {
        var port = port(h, nas.getBlockPos(), Direction.NORTH); port.configure(nas.volume(0).id().toString(), folder, true, true, recursive); return port;
    }
    @GameTest(template = "empty")
    public static void folderGatewaySimulatesWithoutMutation(GameTestHelper h) {
        var nas = nas(h); var storage = nas.volume(0).inventory(); int folder = storage.createFolder(0, "铁锭");
        var port = bound(h, nas, folder, false); var handler = handler(port, Direction.NORTH);
        ItemStack source = new ItemStack(Items.IRON_INGOT, 64); long revision = storage.revision(); Tag saved = storage.save();
        h.assertTrue(ItemHandlerHelper.insertItemStacked(handler, source, true).isEmpty() && storage.revision() == revision
                && saved.equals(storage.save()) && source.getCount() == 64, "Simulation mutated source or inventory");
        h.assertTrue(ItemHandlerHelper.insertItemStacked(handler, source, false).isEmpty() && source.getCount() == 64, "Insertion modified caller stack");
        h.assertTrue(storage.entries().get(0).folder() == folder && count(handler) == 64, "Input missed bound folder or double-counted slots");
        int slot = slot(handler, Items.IRON_INGOT); handler.getStackInSlot(slot).setCount(1);
        h.assertTrue(storage.total() == 64 && handler.extractItem(slot, 7, true).getCount() == 7 && storage.total() == 64, "Query or extraction simulation mutated storage");
        h.assertTrue(handler.extractItem(slot, 1000, false).getCount() == 64 && storage.total() == 0, "Extraction violated stack size or conservation"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void descendantsAreOptInAndInputStaysAtTarget(GameTestHelper h) {
        var nas = nas(h); var storage = nas.volume(0).inventory(); int folder = storage.createFolder(0, "公共"), child = storage.createFolder(folder, "子目录"), other = storage.createFolder(0, "项目");
        storage.insert(new ItemStack(Items.IRON_INGOT, 5), 5, folder); storage.insert(new ItemStack(Items.GOLD_INGOT, 7), 7, child); storage.insert(new ItemStack(Items.DIAMOND, 11), 11, other);
        var port = bound(h, nas, folder, false); var first = handler(port, Direction.NORTH); h.assertTrue(count(first) == 5, "Non-recursive scope leaked child or sibling");
        port.configure(port.volume(), folder, true, true, true); var recursive = handler(port, Direction.NORTH);
        h.assertTrue(count(first) == 0 && count(recursive) == 12, "Configuration did not revoke old view or recursive scope leaked sibling");
        recursive.insertItem(0, new ItemStack(Items.GOLD_INGOT, 3), false);
        h.assertTrue(storage.entries().stream().anyMatch(e -> e.folder() == folder && e.stack().is(Items.GOLD_INGOT) && e.count() == 3), "Recursive input routed to child");
        h.assertTrue(slot(recursive, Items.DIAMOND) == -1, "Project reserve became visible"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void remountInvalidatesRetainedHandlers(GameTestHelper h) {
        var nas = nas(h); var port = bound(h, nas, 0, false); var first = handler(port, Direction.NORTH);
        first.insertItem(0, new ItemStack(Items.IRON_INGOT, 16), false); int slot = slot(first, Items.IRON_INGOT);
        ItemStack disk = nas.eject(0);
        h.assertTrue(first.extractItem(slot, 16, false).isEmpty() && first.insertItem(0, new ItemStack(Items.GOLD_INGOT), false).getCount() == 1, "Offline handle still transferred");
        nas.install(0, disk); var current = handler(port, Direction.NORTH);
        h.assertTrue(first.extractItem(slot, 16, false).isEmpty() && count(current) == 16, "Reinserted disk revived old handler");
        nas.eject(0); nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        h.assertTrue(current.insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1 && nas.volume(0).inventory().total() == 0, "Replacement disk received stale input"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void stableSlotsAndConcurrentPortsConserveItems(GameTestHelper h) {
        var nas = nas(h); var a = bound(h, nas, 0, false); var b = port(h, nas.getBlockPos(), Direction.EAST); b.configure(a.volume(), 0, true, true, false);
        var first = handler(a, Direction.NORTH); var second = handler(b, Direction.EAST);
        first.insertItem(0, new ItemStack(Items.IRON_INGOT, 20), false); first.insertItem(0, new ItemStack(Items.GOLD_INGOT, 30), false);
        int iron = slot(first, Items.IRON_INGOT), gold = slot(first, Items.GOLD_INGOT);
        int taken = first.extractItem(iron, 15, false).getCount(); taken += second.extractItem(slot(second, Items.IRON_INGOT), 15, false).getCount();
        h.assertTrue(taken == 20 && first.getStackInSlot(iron).isEmpty() && slot(first, Items.GOLD_INGOT) == gold && count(first) == 30, "Slots compacted or concurrent extraction duplicated items"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void fullLockedDeletedAndForbiddenTargetsFailClosed(GameTestHelper h) {
        var nas = nas(h); var storage = nas.volume(0).inventory(); int folder = storage.createFolder(0, "temporary"); var port = bound(h, nas, folder, false);
        var handler = handler(port, Direction.NORTH);
        h.assertTrue(!handler.insertItem(0, new ItemStack(ModContent.NAS_ITEM.get()), false).isEmpty() && storage.total() == 0, "Nested storage device accepted");
        storage.deleteFolder(folder); h.assertTrue(handler.insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1 && storage.total() == 0, "Deleted target fell back to root");
        port.configure(port.volume(), 0, true, true, false); handler = handler(port, Direction.NORTH);
        storage.insert(new ItemStack(Items.IRON_INGOT), 1, 0);
        // Fill through the real insertion path; a 64K disk needs only 1,024 stack operations.
        while (storage.total() < storage.capacity()) storage.insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        h.assertTrue(handler.insertItem(0, new ItemStack(Items.GOLD_INGOT), false).getCount() == 1 && handler.extractItem(slot(handler, Items.IRON_INGOT), 64, false).getCount() == 64, "Full disk blocked output or swallowed input");
        CompoundTag data = (CompoundTag) storage.save(); data.putInt("Version", 999); storage.load(data);
        h.assertTrue(handler.extractItem(1, 64, false).isEmpty() && handler.insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "Protected inventory stayed accessible");
        h.assertTrue(port.status().equals("storage_locked"), "Protected disk was reported as merely offline"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void directionsAndSupportRemovalAreCorrect(GameTestHelper h) {
        var nas = nas(h);
        for (Direction facing : Direction.values()) {
            var port = port(h, nas.getBlockPos(), facing); port.configure(nas.volume(0).id().toString(), 0, true, true, false);
            h.assertTrue(port.getBlockState().canSurvive(h.getLevel(), port.getBlockPos()), "Valid face refused support");
            h.assertTrue(port.getCapability(ForgeCapabilities.ITEM_HANDLER, facing).isPresent() && !port.getCapability(ForgeCapabilities.ITEM_HANDLER, facing.getOpposite()).isPresent(), "Capability exposed wrong face");
            var shape = port.getBlockState().getShape(h.getLevel(), port.getBlockPos()).bounds();
            for (Direction.Axis axis : Direction.Axis.values())
                h.assertTrue(Math.abs(shape.max(axis) - shape.min(axis) - (axis == facing.getAxis() ? 2.0 / 16 : 6.0 / 16)) < .00001,
                        "Interface selection shape is oversized or rotated incorrectly");
        }
        var retainedPort = (LogisticsPortBlockEntity) h.getLevel().getBlockEntity(nas.getBlockPos().north()); var retained = handler(retainedPort, Direction.NORTH);
        h.getLevel().destroyBlock(nas.getBlockPos(), true);
        h.assertTrue(h.getLevel().getBlockState(retainedPort.getBlockPos()).isAir() && retained.insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "Host removal left active interface"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void grassAndTerrainDoNotExtendConnectorAndOldStatesRepair(GameTestHelper h) {
        var nas = nas(h); var port = bound(h, nas, 0, false); var level = h.getLevel();
        BlockPos outside = port.getBlockPos().north();
        level.setBlockAndUpdate(outside.below(), Blocks.DIRT.defaultBlockState());
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.STONE, Blocks.WATER, Blocks.GRASS}) {
            level.setBlockAndUpdate(outside, block.defaultBlockState());
            h.assertTrue(!port.getBlockState().getValue(LogisticsPortBlock.CONNECTED), "Terrain incorrectly extended connector");
        }
        // Model a pre-fix save: grass at the front and a persisted long connector.
        level.setBlock(port.getBlockPos(), port.getBlockState().setValue(LogisticsPortBlock.CONNECTED, true), 2);
        h.runAfterDelay(6, () -> {
            h.assertTrue(!port.getBlockState().getValue(LogisticsPortBlock.CONNECTED), "Old connection state did not retract");
            level.setBlockAndUpdate(outside, Blocks.CHEST.defaultBlockState());
            h.assertTrue(port.getBlockState().getValue(LogisticsPortBlock.CONNECTED), "Device did not extend connector");
            level.setBlockAndUpdate(outside, Blocks.AIR.defaultBlockState());
            h.assertTrue(!port.getBlockState().getValue(LogisticsPortBlock.CONNECTED), "Removed device left a floating connector");
            h.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void localTerminalAndPersistencePreserveBinding(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2,3,2)); h.getLevel().setBlockAndUpdate(pos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) h.getLevel().getBlockEntity(pos); int folder = terminal.inventory().createFolder(0, "Local");
        var port = port(h, pos, Direction.NORTH); port.configure("local", folder, true, false, true);
        CompoundTag saved = port.saveWithoutMetadata(); var old = handler(port, Direction.NORTH); old.insertItem(0, new ItemStack(Items.IRON_INGOT, 5), false);
        port.load(saved); var fresh = handler(port, Direction.NORTH);
        h.assertTrue(old.insertItem(0, new ItemStack(Items.GOLD_INGOT), false).getCount() == 1 && port.volume().equals("local") && port.folder() == folder && port.recursive() && !port.allowOutput(), "Reload changed config or revived handler");
        h.assertTrue(fresh.extractItem(slot(fresh, Items.IRON_INGOT), 5, false).isEmpty() && count(fresh) == 5, "Input-only port extracted");
        CompoundTag raw = saved.copy(); raw.getCompound("Port").putInt("Version", 99); port.load(raw);
        h.assertTrue(port.isLocked() && raw.get("Port").equals(port.saveWithoutMetadata().get("Port")) && !port.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).isPresent(), "Corrupt config wasn't preserved and disabled"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void realHoppersTransferThroughBothFaces(GameTestHelper h) {
        var nas = nas(h); var top = port(h, nas.getBlockPos(), Direction.UP); var bottom = port(h, nas.getBlockPos(), Direction.DOWN);
        String id = nas.volume(0).id().toString(); top.configure(id, 0, true, false, false); bottom.configure(id, 0, false, true, false);
        BlockPos sourcePos = top.getBlockPos().above(), destinationPos = bottom.getBlockPos().below();
        h.getLevel().setBlockAndUpdate(sourcePos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        h.getLevel().setBlockAndUpdate(destinationPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        var source = (HopperBlockEntity) h.getLevel().getBlockEntity(sourcePos); var destination = (HopperBlockEntity) h.getLevel().getBlockEntity(destinationPos);
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
        h.succeedWhen(() -> h.assertTrue(source.isEmpty() && destination.getItem(0).getCount() == 16 && nas.volume(0).inventory().total() == 0, "Hoppers have not conserved and transferred all 16 items"));
    }
    private static ServerPlayer player(GameTestHelper h, BlockPos pos) {
        ServerPlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "port-test")) { @Override public boolean hasDisconnected() { return false; } };
        player.setPos(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5); return player;
    }
    private static StorageNetwork.PortRequest request(LogisticsPortMenu menu, CompoundTag view, String volume) {
        return new StorageNetwork.PortRequest(menu.containerId, view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"), StorageNetwork.PortAction.APPLY, volume, 0, true, true, false);
    }
    @GameTest(template = "empty")
    public static void staleMenuMountAndClosedSessionsCannotReconfigure(GameTestHelper h) {
        var nas = nas(h); var port = port(h, nas.getBlockPos(), Direction.NORTH); String id = nas.volume(0).id().toString();
        var player = player(h, port.getBlockPos()); var menu = new LogisticsPortMenu(7, player.getInventory(), port.getBlockPos(), port); player.containerMenu = menu;
        var old = request(menu, menu.snapshot(), id); ItemStack disk = nas.eject(0); nas.install(0, disk); menu.handle(old);
        h.assertTrue(port.volume().isEmpty(), "Old mount request applied");
        var fresh = request(menu, menu.snapshot(), id); menu.handle(fresh); h.assertTrue(port.volume().equals(id), "Fresh config failed");
        port.disconnect(); menu.handle(fresh); h.assertTrue(port.volume().isEmpty(), "Old revision applied");
        fresh = request(menu, menu.snapshot(), id); menu.removed(player); menu.handle(fresh); h.assertTrue(port.volume().isEmpty(), "Closed menu applied"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void portPacketsRoundTrip(GameTestHelper h) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try { for (var action : StorageNetwork.PortAction.values()) {
            var packet = new StorageNetwork.PortRequest(4, 12, 13, 14, action, UUID.randomUUID().toString(), 123, true, false, true);
            packet.encode(buf); h.assertTrue(packet.equals(StorageNetwork.PortRequest.decode(buf)) && !buf.isReadable(), "Port packet changed on wire"); buf.clear();
        } } finally { buf.release(); }
        h.succeed();
    }
}
