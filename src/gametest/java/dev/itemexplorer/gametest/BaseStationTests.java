package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationMastBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.station.BaseStationStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BaseStationTests {
    private record Station(BlockPos center, BlockPos controller, Direction facing, Map<BlockPos, BlockState> blocks) {}

    /** Independent fixture: nine whole base blocks and an eight-block tower, not validator-generated geometry. */
    private static Station layout(BlockPos center, Direction facing) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            blocks.put(center.offset(x, 0, z), ModContent.BASE_STATION_CASING_BLOCK.get().defaultBlockState());
        BlockPos controller = center.relative(facing);
        blocks.put(controller, ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                .setValue(BaseStationControllerBlock.FACING, facing));
        blocks.put(center.relative(facing.getOpposite()), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState()
                .setValue(BaseStationPartBlock.FACING, facing.getOpposite()));
        for (Direction side : List.of(facing.getClockWise(), facing.getCounterClockWise()))
            blocks.put(center.relative(side), ModContent.BASE_STATION_MODULE_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        for (int y = 1; y <= 3; y++) blocks.put(center.above(y), ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL)
            blocks.put(center.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        blocks.put(center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        return new Station(center, controller, facing, blocks);
    }

    private static Station build(GameTestHelper h, Direction facing) {
        // The existing empty template is exactly 5 x 5 x 5; the complete tower remains inside it.
        Station station = layout(h.absolutePos(new BlockPos(2, 0, 2)), facing);
        for (BlockPos pos : BlockPos.betweenClosed(station.center().offset(-1, 0, -1), station.center().offset(1, 4, 1)))
            h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        station.blocks().forEach((pos, state) -> h.getLevel().setBlockAndUpdate(pos, state));
        return station;
    }

    private static BaseStationBlockEntity controller(GameTestHelper h, Station station) {
        var entity = h.getLevel().getBlockEntity(station.controller());
        h.assertTrue(entity instanceof BaseStationBlockEntity, "Station controller has no registered block entity");
        return (BaseStationBlockEntity) entity;
    }

    private static void assertFormed(GameTestHelper h, Station station, boolean expected, String context) {
        h.assertTrue(controller(h, station).validation().complete() == expected, context + ": controller validation is stale");
        h.assertTrue(h.getLevel().getBlockState(station.controller()).getValue(BaseStationControllerBlock.FORMED) == expected,
                context + ": visible formed state is stale");
    }

    private static void assertIssue(GameTestHelper h, BaseStationStructure.Result result, BlockPos pos,
                                    BaseStationStructure.Reason reason, String context) {
        h.assertTrue(!result.complete() && result.issues().stream().anyMatch(issue -> issue.pos().equals(pos) && issue.reason() == reason),
                context + ": missing precise position/reason diagnostic: " + result.issues());
    }

    @GameTest(template = "empty")
    public static void nineBlockBaseFormsInAllFourOrientations(GameTestHelper h) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Station station = build(h, facing);
            var result = controller(h, station).validation();
            h.assertTrue(station.blocks().size() == 17 && result.total() == 17 && result.matched() == 17,
                    "The full nine-block base and centered tower were not recognized facing " + facing);
            assertFormed(h, station, true, "Initial assembly facing " + facing);
            // Unused cells are not extra mandatory parts of the structure.
            h.getLevel().setBlockAndUpdate(station.center().offset(1, 2, 1), Blocks.STONE.defaultBlockState());
            assertFormed(h, station, true, "Unrelated block inside the bounding box");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyRequiredPartBreaksAndRepairsImmediately(GameTestHelper h) {
        Station station = build(h, Direction.NORTH);
        for (var entry : station.blocks().entrySet()) {
            BlockPos pos = entry.getKey();
            var oldController = controller(h, station);
            h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            if (pos.equals(station.controller())) {
                h.assertTrue(h.getLevel().getBlockEntity(pos) == null && oldController.isRemoved(),
                        "Removing the controller retained an active block entity");
            } else {
                assertFormed(h, station, false, "Removed " + entry.getValue().getBlock() + " at " + pos);
                assertIssue(h, oldController.validation(), pos, BaseStationStructure.Reason.MISSING, "Missing part");
            }
            h.getLevel().setBlockAndUpdate(pos, entry.getValue());
            assertFormed(h, station, true, "Reinstalled " + entry.getValue().getBlock() + " at " + pos);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void outwardFacesAreRequiredForEveryDirectionalPart(GameTestHelper h) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Station station = build(h, facing);
            for (var entry : station.blocks().entrySet()) {
                BlockState correct = entry.getValue();
                if (!correct.is(ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get())
                        && !correct.is(ModContent.BASE_STATION_MODULE_BLOCK.get())
                        && !correct.is(ModContent.BASE_STATION_ANTENNA_BLOCK.get())) continue;
                Direction outward = correct.getValue(BaseStationPartBlock.FACING);
                h.getLevel().setBlockAndUpdate(entry.getKey(), correct.setValue(BaseStationPartBlock.FACING, outward.getOpposite()));
                assertFormed(h, station, false, "Inward-facing " + correct.getBlock());
                assertIssue(h, controller(h, station).validation(), entry.getKey(), BaseStationStructure.Reason.WRONG_FACING,
                        "Rotated antenna, port or expansion module");
                h.getLevel().setBlockAndUpdate(entry.getKey(), correct);
                assertFormed(h, station, true, "Fixed facing " + outward);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void missingAndWrongPartsHaveDifferentDiagnostics(GameTestHelper h) {
        Station station = build(h, Direction.WEST);
        BlockPos cap = station.center().above(4);
        h.getLevel().setBlockAndUpdate(cap, Blocks.IRON_BLOCK.defaultBlockState());
        var wrong = controller(h, station).validation();
        assertIssue(h, wrong, cap, BaseStationStructure.Reason.WRONG_BLOCK, "Iron cannot substitute for a station cap");
        h.assertTrue(wrong.matched() == 16 && wrong.issues().size() == 1
                        && wrong.issues().get(0).expectedPart() == BaseStationStructure.Part.CAP,
                "Diagnostic lost the required cap or counted unrelated failures");
        h.getLevel().setBlockAndUpdate(cap, Blocks.AIR.defaultBlockState());
        // Neither iron nor air is a station component; opening the inspector refreshes this already-invalid structure.
        controller(h, station).refreshStructure();
        assertIssue(h, controller(h, station).validation(), cap, BaseStationStructure.Reason.MISSING, "Empty cap position");
        h.getLevel().setBlockAndUpdate(cap, station.blocks().get(cap));
        assertFormed(h, station, true, "Cap repair");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void unavailableChunksAreNeverReadOrTreatedAsMissing(GameTestHelper h) {
        // A fixture straddling x=16 emulates an unloaded neighboring chunk without changing global world tickets.
        Station station = layout(new BlockPos(15, 80, 15), Direction.NORTH);
        AtomicInteger reads = new AtomicInteger();
        var partial = BaseStationStructure.validate(station.controller(), station.facing(), pos -> pos.getX() < 16, pos -> {
            h.assertTrue(pos.getX() < 16, "Validator read a required block in an unavailable chunk");
            reads.incrementAndGet();
            return station.blocks().getOrDefault(pos, Blocks.AIR.defaultBlockState());
        });
        h.assertTrue(!partial.complete() && reads.get() > 0 && partial.total() == 17,
                "Partial chunk availability became a valid station");
        h.assertTrue(!partial.issues().isEmpty() && partial.issues().stream().allMatch(issue ->
                        issue.pos().getX() >= 16 && issue.reason() == BaseStationStructure.Reason.UNLOADED),
                "Unavailable parts were reported as broken or missing");
        var absent = BaseStationStructure.validate(station.controller(), station.facing(), pos -> false, pos -> {
            throw new AssertionError("Completely unavailable structure accessed block state");
        });
        h.assertTrue(!absent.complete() && absent.matched() == 0, "Unavailable structure retained matches");
        var available = BaseStationStructure.validate(station.controller(), station.facing(), pos -> true,
                pos -> station.blocks().getOrDefault(pos, Blocks.AIR.defaultBlockState()));
        h.assertTrue(available.complete(), "Station did not recover once all required chunks were available");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadedControllerRechecksInsteadOfTrustingOldFormedState(GameTestHelper h) {
        Station station = build(h, Direction.SOUTH);
        var original = controller(h, station);
        assertFormed(h, station, true, "Before saving");
        CompoundTag saved = original.saveWithFullMetadata();
        BlockState savedState = h.getLevel().getBlockState(station.controller());
        h.getLevel().setBlockAndUpdate(station.center().above(4), Blocks.AIR.defaultBlockState());
        h.getLevel().setBlock(station.controller(), savedState, Block.UPDATE_CLIENTS);
        h.getLevel().removeBlockEntity(station.controller());
        var restored = new BaseStationBlockEntity(station.controller(), savedState);
        restored.load(saved);
        h.getLevel().setBlockEntity(restored);
        restored.onLoad();
        assertFormed(h, station, false, "Reload with an old formed block state and missing cap");
        assertIssue(h, restored.validation(), station.center().above(4), BaseStationStructure.Reason.MISSING, "Reload diagnostic");
        h.getLevel().setBlockAndUpdate(station.center().above(4), station.blocks().get(station.center().above(4)));
        assertFormed(h, station, true, "Repair after reload");
        h.succeed();
    }

    private static BooleanProperty arm(Direction direction) {
        return switch (direction) {
            case NORTH -> BaseStationMastBlock.NORTH;
            case EAST -> BaseStationMastBlock.EAST;
            case SOUTH -> BaseStationMastBlock.SOUTH;
            case WEST -> BaseStationMastBlock.WEST;
            default -> throw new IllegalArgumentException("Horizontal arm expected");
        };
    }

    @GameTest(template = "empty")
    public static void mastArmsFollowOnlyCorrectlyOrientedAntennas(GameTestHelper h) {
        BlockPos mast = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlockAndUpdate(mast, ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos antenna = mast.relative(side);
            BlockState correct = ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, side);
            h.getLevel().setBlockAndUpdate(antenna, correct);
            for (Direction arm : Direction.Plane.HORIZONTAL)
                h.assertTrue(h.getLevel().getBlockState(mast).getValue(arm(arm)) == (side == arm),
                        "Mast connection appeared on the wrong side");
            h.getLevel().setBlockAndUpdate(antenna, correct.setValue(BaseStationPartBlock.FACING, side.getOpposite()));
            h.assertTrue(!h.getLevel().getBlockState(mast).getValue(arm(side)), "Mast attached to an inward-facing antenna");
            h.getLevel().setBlockAndUpdate(antenna, correct);
            h.getLevel().setBlockAndUpdate(antenna, Blocks.IRON_BLOCK.defaultBlockState());
            h.assertTrue(!h.getLevel().getBlockState(mast).getValue(arm(side)), "Mast attached to an unrelated solid block");
            h.getLevel().setBlockAndUpdate(antenna, correct);
            h.getLevel().setBlockAndUpdate(antenna, Blocks.AIR.defaultBlockState());
            h.assertTrue(!h.getLevel().getBlockState(mast).getValue(arm(side)), "Removed antenna left a floating arm");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void thinPartsKeepTheirOutlineAndEveryPartDropsItself(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        var stone = Blocks.STONE.defaultBlockState();
        List<Block> blocks = List.of(ModContent.BASE_STATION_CASING_BLOCK.get(), ModContent.BASE_STATION_CONTROLLER_BLOCK.get(),
                ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get(), ModContent.BASE_STATION_MODULE_BLOCK.get(),
                ModContent.BASE_STATION_MAST_BLOCK.get(), ModContent.BASE_STATION_ANTENNA_BLOCK.get(), ModContent.BASE_STATION_CAP_BLOCK.get());
        for (Block block : blocks) {
            h.assertTrue(block.asItem() != Items.AIR, "Station part has no placeable item: " + block);
            var drops = Block.getDrops(block.defaultBlockState(), h.getLevel(), pos, null);
            h.assertTrue(drops.size() == 1 && drops.get(0).is(block.asItem()) && drops.get(0).getCount() == 1,
                    "Station part did not drop exactly one reusable component: " + block);
        }
        for (Block block : List.of(ModContent.BASE_STATION_MAST_BLOCK.get(), ModContent.BASE_STATION_ANTENNA_BLOCK.get(), ModContent.BASE_STATION_CAP_BLOCK.get())) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState state = block.defaultBlockState();
                if (state.hasProperty(BaseStationPartBlock.FACING)) state = state.setValue(BaseStationPartBlock.FACING, facing);
                h.getLevel().setBlockAndUpdate(pos, state);
                var selection = state.getShape(h.getLevel(), pos);
                var collision = state.getCollisionShape(h.getLevel(), pos);
                h.assertTrue(!selection.isEmpty() && !collision.isEmpty()
                                && Shapes.joinIsNotEmpty(selection, Shapes.block(), BooleanOp.NOT_SAME)
                                && Shapes.joinIsNotEmpty(collision, Shapes.block(), BooleanOp.NOT_SAME),
                        "Thin station part uses a full block or empty selection/collision shape: " + state);
                h.assertTrue(selection.bounds().minX >= 0 && selection.bounds().minY >= 0 && selection.bounds().minZ >= 0
                                && selection.bounds().maxX <= 1 && selection.bounds().maxY <= 1 && selection.bounds().maxZ <= 1,
                        "Station part crossed its own block cell: " + state);
                for (Direction side : Direction.values()) {
                    BlockPos neighbor = pos.relative(side);
                    h.getLevel().setBlockAndUpdate(neighbor, stone);
                    h.assertTrue(Block.shouldRenderFace(stone, h.getLevel(), neighbor, side.getOpposite(), pos),
                            "Thin station part hid an exposed adjacent face: " + state + ", side=" + side);
                    h.getLevel().setBlockAndUpdate(neighbor, Blocks.AIR.defaultBlockState());
                }
            }
        }
        h.succeed();
    }
}
