package dev.itemexplorer.station;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Structural readiness only: a complete station does not imply an active network. */
public final class BaseStationStructure {
    public static final int TOTAL_PARTS = 17;

    public enum Part { CASING, CONTROLLER, NETWORK_PORT, MODULE, MAST, ANTENNA, CAP }
    public enum Reason { MISSING, WRONG_BLOCK, WRONG_FACING, UNLOADED }
    public record Issue(BlockPos pos, Part expectedPart, Reason reason) {}
    public record Requirement(BlockPos pos, Part part, Direction facing) {}
    public record Result(int matched, int total, List<Issue> issues) {
        public Result { issues = List.copyOf(issues); }
        public boolean complete() { return matched == total && issues.isEmpty(); }
    }

    private BaseStationStructure() {}

    /** Coordinates are relative to the front controller, with positive Z pointing behind it. */
    public static List<Requirement> requirements(BlockPos controller, Direction front) {
        if (front.getAxis().isVertical()) throw new IllegalArgumentException("Station facing must be horizontal");
        List<Requirement> result = new ArrayList<>(TOTAL_PARTS);
        add(result, controller, front, 0, 0, 0, Part.CONTROLLER, Direction.NORTH);
        add(result, controller, front, -1, 0, 0, Part.CASING, null);
        add(result, controller, front, 1, 0, 0, Part.CASING, null);
        add(result, controller, front, -1, 0, 1, Part.MODULE, Direction.WEST);
        add(result, controller, front, 0, 0, 1, Part.CASING, null);
        add(result, controller, front, 1, 0, 1, Part.MODULE, Direction.EAST);
        add(result, controller, front, -1, 0, 2, Part.CASING, null);
        add(result, controller, front, 0, 0, 2, Part.NETWORK_PORT, Direction.SOUTH);
        add(result, controller, front, 1, 0, 2, Part.CASING, null);
        for (int y = 1; y <= 3; y++) add(result, controller, front, 0, y, 1, Part.MAST, null);
        add(result, controller, front, 0, 3, 0, Part.ANTENNA, Direction.NORTH);
        add(result, controller, front, 1, 3, 1, Part.ANTENNA, Direction.EAST);
        add(result, controller, front, 0, 3, 2, Part.ANTENNA, Direction.SOUTH);
        add(result, controller, front, -1, 3, 1, Part.ANTENNA, Direction.WEST);
        add(result, controller, front, 0, 4, 1, Part.CAP, null);
        return List.copyOf(result);
    }

    private static void add(List<Requirement> result, BlockPos controller, Direction front,
                            int x, int y, int z, Part part, Direction facing) {
        Direction right = front.getClockWise(), back = front.getOpposite();
        BlockPos pos = controller.offset(right.getStepX() * x + back.getStepX() * z, y,
                right.getStepZ() * x + back.getStepZ() * z);
        Direction rotated = facing == null ? null : switch (facing) {
            case NORTH -> front;
            case EAST -> right;
            case SOUTH -> back;
            case WEST -> front.getCounterClockWise();
            default -> throw new IllegalArgumentException("Station facing must be horizontal");
        };
        result.add(new Requirement(pos, part, rotated));
    }

    public static Result validate(Level level, BlockPos controller, Direction front) {
        return validate(controller, front, level::hasChunkAt, level::getBlockState);
    }

    /** The loaded check always precedes a state read; validation never requests missing chunks. */
    public static Result validate(BlockPos controller, Direction front, Predicate<BlockPos> loaded,
                                  Function<BlockPos, BlockState> states) {
        int matched = 0;
        List<Issue> issues = new ArrayList<>();
        for (Requirement required : requirements(controller, front)) {
            Reason reason = null;
            if (!loaded.test(required.pos())) reason = Reason.UNLOADED;
            else {
                BlockState state = states.apply(required.pos());
                if (!state.is(block(required.part()))) reason = state.isAir() ? Reason.MISSING : Reason.WRONG_BLOCK;
                else if (required.facing() != null && state.getValue(BaseStationControllerBlock.FACING) != required.facing())
                    reason = Reason.WRONG_FACING;
            }
            if (reason == null) matched++;
            else issues.add(new Issue(required.pos(), required.part(), reason));
        }
        return new Result(matched, TOTAL_PARTS, issues);
    }

    public static Block block(Part part) {
        return switch (part) {
            case CASING -> ModContent.BASE_STATION_CASING_BLOCK.get();
            case CONTROLLER -> ModContent.BASE_STATION_CONTROLLER_BLOCK.get();
            case NETWORK_PORT -> ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get();
            case MODULE -> ModContent.BASE_STATION_MODULE_BLOCK.get();
            case MAST -> ModContent.BASE_STATION_MAST_BLOCK.get();
            case ANTENNA -> ModContent.BASE_STATION_ANTENNA_BLOCK.get();
            case CAP -> ModContent.BASE_STATION_CAP_BLOCK.get();
        };
    }

    /** At most 125 candidate cells, in already loaded chunks, around a changed component. */
    public static void refreshNearby(Level level, BlockPos changed) {
        if (level.isClientSide) return;
        for (BlockPos candidate : BlockPos.betweenClosed(changed.offset(-2, -4, -2), changed.offset(2, 0, 2))) {
            if (level.hasChunkAt(candidate) && level.getBlockState(candidate).is(ModContent.BASE_STATION_CONTROLLER_BLOCK.get())
                    && level.getBlockEntity(candidate) instanceof BaseStationBlockEntity station) station.refreshStructure();
        }
    }
}
