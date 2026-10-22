package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.ModSounds;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StationAppearanceTests {
    private record Station(BlockPos center, BaseStationBlockEntity controller) {}

    /** Independent, complete 3x3 base and five-high mast inside the 5x5x5 template. */
    private static Station build(GameTestHelper h, Direction facing) {
        BlockPos center = h.absolutePos(new BlockPos(2, 0, 2));
        var level = h.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, 0, -1), center.offset(1, 4, 1)))
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(center.offset(x, 0, z), ModContent.BASE_STATION_CASING_BLOCK.get().defaultBlockState());
        BlockPos controllerPos = center.relative(facing);
        level.setBlockAndUpdate(controllerPos, ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                .setValue(BaseStationControllerBlock.FACING, facing));
        level.setBlockAndUpdate(center.relative(facing.getOpposite()), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState()
                .setValue(BaseStationPartBlock.FACING, facing.getOpposite()));
        for (Direction side : List.of(facing.getClockWise(), facing.getCounterClockWise()))
            level.setBlockAndUpdate(center.relative(side), ModContent.BASE_STATION_MODULE_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        for (int y = 1; y <= 3; y++)
            level.setBlockAndUpdate(center.above(y), ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL)
            level.setBlockAndUpdate(center.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        level.setBlockAndUpdate(center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        return new Station(center, (BaseStationBlockEntity) level.getBlockEntity(controllerPos));
    }

    private static void assertLights(GameTestHelper h, BaseStationBlockEntity station, boolean formed, boolean networked) {
        BlockState state = h.getLevel().getBlockState(station.getBlockPos());
        h.assertTrue(state.getValue(BaseStationControllerBlock.FORMED) == formed, "Structure indicator disagrees with assembly");
        h.assertTrue(state.getValue(BaseStationControllerBlock.NETWORKED) == networked, "Wireless indicator is stale");
    }

    @GameTest(template = "empty")
    public static void wirelessToggleIsIndependentOfStructureInEveryOrientation(GameTestHelper h) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BaseStationBlockEntity station = build(h, facing).controller();
            assertLights(h, station, true, false);
            try (var sounds = new SoundFeedbackTests.DeviceCapture(h, station.getBlockPos(), ModSounds.STATION_STARTUP.get())) {
                station.setNetworkOnline(true);
                assertLights(h, station, true, true);
                station.refreshStructure();
                station.setNetworkOnline(false);
                assertLights(h, station, true, false);
                h.assertTrue(sounds.count == 0, "Wireless toggle or refresh replayed assembly effects");
                h.assertTrue(!station.chunkLoadingEnabled(), "Changing the wireless lamp enabled chunk maintenance");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void damagedAssemblyExtinguishesWirelessButPreservesItsPreference(GameTestHelper h) {
        Station fixture = build(h, Direction.WEST);
        BaseStationBlockEntity station = fixture.controller();
        station.setNetworkOnline(true);
        assertLights(h, station, true, true);
        h.getLevel().setBlockAndUpdate(fixture.center().above(4), Blocks.AIR.defaultBlockState());
        assertLights(h, station, false, false);
        h.assertTrue(station.networkOnline(), "Structural damage cleared the saved wireless preference");
        station.setNetworkOnline(true);
        assertLights(h, station, false, false);
        h.getLevel().setBlockAndUpdate(fixture.center().above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        assertLights(h, station, true, true);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyBlockStateRebuildsWirelessLampOnLoadWithoutStartup(GameTestHelper h) {
        BaseStationBlockEntity original = build(h, Direction.EAST).controller();
        original.setNetworkOnline(true);
        CompoundTag saved = original.saveWithFullMetadata();
        CompoundTag legacyState = NbtUtils.writeBlockState(original.getBlockState());
        legacyState.getCompound("Properties").remove("networked");
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), legacyState);
        h.assertTrue(!state.getValue(BaseStationControllerBlock.NETWORKED), "Old palette state did not default wireless lamp off");
        try (var sounds = new SoundFeedbackTests.DeviceCapture(h, original.getBlockPos(), ModSounds.STATION_STARTUP.get())) {
            BaseStationBlockEntity restored = restore(h, original.getBlockPos(), state, saved);
            assertLights(h, restored, true, true);
            h.assertTrue(restored.networkOnline() && sounds.count == 0, "Reload lost wireless preference or replayed startup");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadRejectsStaleLitStateWhenStructureIsMissing(GameTestHelper h) {
        Station fixture = build(h, Direction.SOUTH);
        BaseStationBlockEntity original = fixture.controller();
        original.setNetworkOnline(true);
        CompoundTag saved = original.saveWithFullMetadata();
        BlockState staleLit = original.getBlockState();
        h.getLevel().setBlockAndUpdate(fixture.center().above(4), Blocks.AIR.defaultBlockState());
        try (var sounds = new SoundFeedbackTests.DeviceCapture(h, original.getBlockPos(), ModSounds.STATION_STARTUP.get())) {
            BaseStationBlockEntity restored = restore(h, original.getBlockPos(), staleLit, saved);
            assertLights(h, restored, false, false);
            h.assertTrue(restored.networkOnline() && sounds.count == 0, "Reload trusted stale light or changed the saved preference");
        }
        h.succeed();
    }

    private static BaseStationBlockEntity restore(GameTestHelper h, BlockPos pos, BlockState state, CompoundTag saved) {
        h.getLevel().setBlock(pos, state, Block.UPDATE_CLIENTS);
        h.getLevel().removeBlockEntity(pos);
        BaseStationBlockEntity restored = new BaseStationBlockEntity(pos, state);
        restored.load(saved);
        h.getLevel().setBlockEntity(restored);
        restored.onLoad();
        return restored;
    }
}
