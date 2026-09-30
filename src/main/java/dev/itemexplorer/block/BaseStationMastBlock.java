package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.station.BaseStationStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BaseStationMastBlock extends Block {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    private static final VoxelShape BODY = Shapes.or(box(5, 2, 5, 11, 14, 11),
            box(4, 0, 4, 12, 2, 12), box(4, 14, 4, 12, 16, 12));
    private static final VoxelShape[] SHAPES = createShapes();

    public BaseStationMastBlock() {
        super(Properties.of().mapColor(MapColor.METAL).strength(2.5F).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false));
    }

    public static BooleanProperty connection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException("Mast arms must be horizontal");
        };
    }
    private static boolean connects(BlockState neighbor, Direction direction) {
        return neighbor.is(ModContent.BASE_STATION_ANTENNA_BLOCK.get())
                && neighbor.getValue(BaseStationPartBlock.FACING) == direction;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(NORTH, EAST, SOUTH, WEST); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = context.getClickedPos().relative(direction);
            state = state.setValue(connection(direction), context.getLevel().hasChunkAt(neighbor)
                    && connects(context.getLevel().getBlockState(neighbor), direction));
        }
        return state;
    }
    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction.getAxis().isHorizontal() ? state.setValue(connection(direction), connects(neighbor, direction)) : state;
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        BlockState rotated = state;
        for (Direction direction : Direction.Plane.HORIZONTAL)
            rotated = rotated.setValue(connection(rotation.rotate(direction)), state.getValue(connection(direction)));
        return rotated;
    }
    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state;
        for (Direction direction : Direction.Plane.HORIZONTAL)
            mirrored = mirrored.setValue(connection(mirror.mirror(direction)), state.getValue(connection(direction)));
        return mirrored;
    }

    private static VoxelShape[] createShapes() {
        VoxelShape[] shapes = new VoxelShape[16];
        for (int mask = 0; mask < shapes.length; mask++) {
            VoxelShape shape = BODY;
            if ((mask & 1) != 0) shape = Shapes.or(shape, box(7, 7, 0, 9, 9, 5));
            if ((mask & 2) != 0) shape = Shapes.or(shape, box(11, 7, 7, 16, 9, 9));
            if ((mask & 4) != 0) shape = Shapes.or(shape, box(7, 7, 11, 9, 9, 16));
            if ((mask & 8) != 0) shape = Shapes.or(shape, box(0, 7, 7, 5, 9, 9));
            shapes[mask] = shape;
        }
        return shapes;
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = (state.getValue(NORTH) ? 1 : 0) | (state.getValue(EAST) ? 2 : 0)
                | (state.getValue(SOUTH) ? 4 : 0) | (state.getValue(WEST) ? 8 : 0);
        return SHAPES[mask];
    }
    @Override public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }
    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!state.is(old.getBlock())) BaseStationStructure.refreshNearby(level, pos);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!state.is(next.getBlock())) BaseStationStructure.refreshNearby(level, pos);
    }
}
