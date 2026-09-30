package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.*;
import dev.itemexplorer.cable.CableNetwork;
import dev.itemexplorer.cable.DataCableEndpoint;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.menu.CableConnectionData;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.station.StationConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DataCableTests {
    private record Fixture(Map<BlockPos, BlockState> blocks, BlockPos center, BlockPos controller, BlockPos terminal, BlockPos firstCable) {}
    // Independent nine-block-base fixture: do not derive the answer from the production validator.
    private static Fixture fixture(BlockPos center, Direction front) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            blocks.put(center.offset(x,0,z), ModContent.BASE_STATION_CASING_BLOCK.get().defaultBlockState());
        BlockPos controller = center.relative(front);
        blocks.put(controller, ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState().setValue(BaseStationControllerBlock.FACING, front));
        blocks.put(center.relative(front.getOpposite()), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, front.getOpposite()));
        for (Direction side : List.of(front.getClockWise(), front.getCounterClockWise()))
            blocks.put(center.relative(side), ModContent.BASE_STATION_MODULE_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, side));
        for (int y = 1; y <= 3; y++) blocks.put(center.above(y), ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL)
            blocks.put(center.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, side));
        blocks.put(center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        BlockPos cable = center.relative(front.getOpposite(), 2), terminal = cable.relative(front.getCounterClockWise());
        blocks.put(terminal, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        for (BlockPos p : List.of(cable, cable.above(), terminal.above())) blocks.put(p, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        return new Fixture(blocks, center, controller, terminal, cable);
    }
    private static Function<BlockPos,BlockState> states(Map<BlockPos,BlockState> blocks) {
        return p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
    }
    private static StationConnection.Result inspect(Fixture f) {
        return StationConnection.inspect(f.terminal, false, p -> true, states(f.blocks));
    }
    private static void status(GameTestHelper h, StationConnection.Result result, StationConnection.Status expected) {
        h.assertTrue(result.status() == expected, "Expected " + expected + " but got " + result);
    }
    @GameTest(template = "empty")
    public static void wiredStationWorksInAllFourOrientations(GameTestHelper h) {
        for (Direction front : Direction.Plane.HORIZONTAL) {
            Fixture f = fixture(BlockPos.ZERO, front);
            var result = inspect(f);
            status(h, result, StationConnection.Status.CONNECTED);
            h.assertTrue(result.controller().equals(f.controller) && result.cables() == 3 && result.terminals() == 1, "Wrong endpoints/counts facing " + front);
            status(h, StationConnection.inspect(f.controller, true, p -> true, states(f.blocks)), StationConnection.Status.CONNECTED);
        }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void brokenWireAndTowerRecoverWithoutSavedFlags(GameTestHelper h) {
        Fixture f = fixture(BlockPos.ZERO, Direction.NORTH);
        BlockState wire = f.blocks.remove(f.firstCable);
        status(h, inspect(f), StationConnection.Status.NO_STATION);
        f.blocks.put(f.firstCable, wire);
        status(h, inspect(f), StationConnection.Status.CONNECTED);
        BlockState cap = f.blocks.remove(f.center.above(4));
        status(h, inspect(f), StationConnection.Status.INCOMPLETE);
        f.blocks.put(f.center.above(4), cap);
        status(h, inspect(f), StationConnection.Status.CONNECTED);
        f.blocks.remove(f.terminal.above());
        f.blocks.remove(f.firstCable);
        status(h, inspect(f), StationConnection.Status.DISCONNECTED);
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void socketsRejectWrongFacesAndOrdinaryInventoryBlocks(GameTestHelper h) {
        Fixture f = fixture(BlockPos.ZERO, Direction.NORTH);
        BlockPos port = f.center.south();
        f.blocks.put(port, f.blocks.get(port).setValue(BaseStationPartBlock.FACING, Direction.EAST));
        status(h, inspect(f), StationConnection.Status.NO_STATION);
        BlockState terminal = ModContent.STORAGE_BLOCK.get().defaultBlockState();
        for (Direction d : Direction.values()) h.assertTrue(DataCableEndpoint.accepts(terminal,d)
                && DataCableEndpoint.accepts(ModContent.NAS_BLOCK.get().defaultBlockState(), d), "Storage device rejected a cable face: " + d);
        h.assertTrue(!DataCableEndpoint.accepts(ModContent.LOGISTICS_BLOCK.get().defaultBlockState(), Direction.NORTH), "Logistics interfaces must not implicitly become cable endpoints");
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void branchesAndCyclesCountEachCableAndTerminalOnce(GameTestHelper h) {
        Fixture f = fixture(BlockPos.ZERO, Direction.NORTH);
        BlockPos second = f.firstCable.east();
        f.blocks.put(second, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        for (BlockPos p : List.of(second.above(), second.above(2), f.firstCable.above(2)))
            f.blocks.put(p, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        var result = inspect(f);
        status(h, result, StationConnection.Status.CONNECTED);
        h.assertTrue(result.cables() == 6 && result.terminals() == 2, "Cycle duplicated nodes: " + result);
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void multipleStationPortsConflictAndDisconnectResolves(GameTestHelper h) {
        Fixture f = fixture(BlockPos.ZERO, Direction.NORTH), second = fixture(new BlockPos(10,0,0), Direction.NORTH);
        f.blocks.putAll(second.blocks);
        for (int x = 1; x <= 10; x++) f.blocks.put(new BlockPos(x,1,2), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        status(h, inspect(f), StationConnection.Status.CONFLICT);
        f.blocks.remove(new BlockPos(5,1,2));
        status(h, inspect(f), StationConnection.Status.CONNECTED);
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void unloadedGraphAndStructureAreNeverRead(GameTestHelper h) {
        Fixture f = fixture(BlockPos.ZERO, Direction.NORTH);
        for (BlockPos unavailable : List.of(f.firstCable, f.center.above(4))) {
            var result = StationConnection.inspect(f.terminal, false, p -> !p.equals(unavailable), p -> {
                h.assertTrue(!p.equals(unavailable), "An unloaded position was read: " + p);
                return states(f.blocks).apply(p);
            });
            status(h, result, StationConnection.Status.UNLOADED);
        }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void cableLimitStopsTraversalAndAllowsExactly256(GameTestHelper h) {
        Map<BlockPos,BlockState> blocks = new HashMap<>();
        blocks.put(BlockPos.ZERO, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        for (int x = 0; x < 256; x++) blocks.put(new BlockPos(x,1,0), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        var exact = CableNetwork.scan(BlockPos.ZERO, p -> true, states(blocks));
        h.assertTrue(exact.cables() == 256 && !exact.tooLarge(), "Exactly 256 cables should be allowed");
        blocks.put(new BlockPos(256,1,0), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        status(h, StationConnection.inspect(BlockPos.ZERO, false, p -> true, states(blocks)), StationConnection.Status.TOO_LARGE);
        h.succeed();
    }
    // A future machine adapter uses only the socket contract, without becoming a station or a wire bridge.
    @GameTest(template = "empty")
    public static void sharedCableDiscoversFutureDeviceWithoutBridgingThroughIt(GameTestHelper h) {
        Map<BlockPos,BlockState> blocks = new HashMap<>();
        blocks.put(BlockPos.ZERO, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        blocks.put(BlockPos.ZERO.above(), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        blocks.put(new BlockPos(1,1,0), Blocks.IRON_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(2,1,0), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        DataCableEndpoint futureSocket = (state, face) -> true;
        var graph = CableNetwork.scan(BlockPos.ZERO, p -> true, states(blocks),
                (state, face) -> state.is(Blocks.IRON_BLOCK) ? futureSocket.acceptsDataCable(state, face) : DataCableEndpoint.accepts(state, face));
        h.assertTrue(graph.endpoints().size() == 2 && graph.cables() == 1, "Shared topology lost future endpoint or bridged through a device");
        status(h, StationConnection.inspect(BlockPos.ZERO, false, p -> true, states(blocks)), StationConnection.Status.NO_STATION);
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void emptyStationLineHasExplicitNoTerminalStatus(GameTestHelper h) {
        Fixture f = fixture(BlockPos.ZERO, Direction.NORTH);
        f.blocks.remove(f.terminal);
        status(h, StationConnection.inspect(f.controller, true, p -> true, states(f.blocks)), StationConnection.Status.NO_TERMINAL);
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void realBlockConnectionsUpdateAndCableDropsItself(GameTestHelper h) {
        var level = h.getLevel(); BlockPos cable = h.absolutePos(new BlockPos(2,2,2));
        level.setBlockAndUpdate(cable.below(), ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "wire-placement"));
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(ModContent.DATA_CABLE_ITEM.get()),
                new BlockHitResult(Vec3.atCenterOf(cable.below()).add(0, .5, 0), Direction.UP, cable.below(), false));
        BlockState placed = ModContent.DATA_CABLE_BLOCK.get().getStateForPlacement(context);
        h.assertTrue(context.getClickedPos().equals(cable) && placed.getValue(DataCableBlock.connection(Direction.DOWN)),
                "Placement did not immediately connect to the terminal top socket");
        level.setBlockAndUpdate(cable, placed);
        for (Direction d : Direction.values()) level.setBlockAndUpdate(cable.relative(d), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        BlockState connected = level.getBlockState(cable);
        for (Direction d : Direction.values()) h.assertTrue(connected.getValue(DataCableBlock.connection(d)), "Missing cable arm: " + d);
        level.setBlockAndUpdate(cable.east(), Blocks.AIR.defaultBlockState());
        h.assertTrue(!level.getBlockState(cable).getValue(DataCableBlock.connection(Direction.EAST)), "Removed neighbor left an arm");
        var shape = level.getBlockState(cable).getShape(level, cable, CollisionContext.empty());
        h.assertTrue(!Block.isShapeFullBlock(shape), "Cable has a full cube outline");
        var drops = Block.getDrops(connected, level, cable, null);
        h.assertTrue(drops.size() == 1 && drops.get(0).is(ModContent.DATA_CABLE_ITEM.get()) && drops.get(0).getCount() == 1, "Cable drop is wrong");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void openMenusUpdateAfterBreakAndRepairWithoutTouchingInventory(GameTestHelper h) {
        Fixture f = fixture(h.absolutePos(new BlockPos(2,0,2)), Direction.NORTH);
        f.blocks.forEach((p,s) -> h.getLevel().setBlockAndUpdate(p,s));
        StorageBlockEntity terminal = (StorageBlockEntity) h.getLevel().getBlockEntity(f.terminal);
        terminal.inventory().insert(new ItemStack(Items.IRON_INGOT), 12, 0);
        var user = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "cable-test"));
        user.setPos(f.terminal.getX(), f.terminal.getY()+1, f.terminal.getZ());
        StorageMenu menu = new StorageMenu(1, user.getInventory(), f.terminal, terminal); user.containerMenu = menu;
        var stationUser = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "station-wire-test"));
        stationUser.setPos(f.controller.getX(), f.controller.getY()+1, f.controller.getZ());
        BaseStationMenu stationMenu = new BaseStationMenu(2, stationUser.getInventory(), (BaseStationBlockEntity) h.getLevel().getBlockEntity(f.controller));
        stationUser.containerMenu = stationMenu;
        h.assertTrue(menu.cable().status() == StationConnection.Status.CONNECTED && stationMenu.cable().status() == StationConnection.Status.CONNECTED, "Menus did not open with connected status");
        long session = menu.session(), revision = terminal.inventory().revision();
        h.getLevel().setBlockAndUpdate(f.center.above(4), Blocks.AIR.defaultBlockState());
        h.runAfterDelay(21, () -> {
            menu.broadcastChanges(); stationMenu.broadcastChanges();
            h.assertTrue(menu.cable().status() == StationConnection.Status.INCOMPLETE && stationMenu.cable().status() == StationConnection.Status.INCOMPLETE, "Open menus retained connected status after tower break");
            h.getLevel().setBlockAndUpdate(f.center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        });
        h.runAfterDelay(43, () -> {
            menu.broadcastChanges(); stationMenu.broadcastChanges();
            h.assertTrue(menu.cable().status() == StationConnection.Status.CONNECTED && stationMenu.cable().status() == StationConnection.Status.CONNECTED, "Open menus did not recover");
            h.assertTrue(menu.session() == session && terminal.inventory().revision() == revision, "Connectivity polling changed storage/session");
            h.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void menuCoordinatesSurviveSignedShortTransport(GameTestHelper h) {
        var server = new CableConnectionData(); var client = new CableConnectionData();
        int[] coordinates = {-29999984, -64, 29999984};
        for (int i = 0; i < 3; i++) { server.set(3+i*2, coordinates[i] & 0xffff); server.set(4+i*2, coordinates[i] >>> 16); }
        for (int i = 0; i < server.getCount(); i++) client.set(i, (short) server.get(i));
        h.assertTrue(client.controller().equals(new BlockPos(coordinates[0], coordinates[1], coordinates[2])), "Coordinates were truncated by vanilla menu transport");
        h.succeed();
    }
}
