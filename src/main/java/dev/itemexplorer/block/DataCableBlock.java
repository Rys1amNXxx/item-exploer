package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.cable.DataCableEndpoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class DataCableBlock extends Block {
    public static final BooleanProperty[] CONNECTIONS = {BlockStateProperties.DOWN, BlockStateProperties.UP,
            BlockStateProperties.NORTH, BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.EAST};
    private static final VoxelShape[] SHAPES = shapes();
    public DataCableBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_CYAN).strength(0.7F).sound(SoundType.METAL).noOcclusion());
        BlockState state = stateDefinition.any();
        for (BooleanProperty property : CONNECTIONS) state = state.setValue(property, false);
        registerDefaultState(state);
    }
    public static BooleanProperty connection(Direction face) { return CONNECTIONS[face.get3DDataValue()]; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(CONNECTIONS); }
    private boolean connects(LevelAccessor level, BlockPos pos, Direction face) {
        BlockPos next = pos.relative(face);
        if (!level.hasChunkAt(next)) return false;
        BlockState state = level.getBlockState(next);
        return state.is(ModContent.DATA_CABLE_BLOCK.get()) || DataCableEndpoint.accepts(state, face.getOpposite());
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction face : Direction.values()) state = state.setValue(connection(face), connects(context.getLevel(), context.getClickedPos(), face));
        return state;
    }
    @Override public BlockState updateShape(BlockState state, Direction face, BlockState neighbor,
                                            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(connection(face), connects(level, pos, face));
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        BlockState rotated = state;
        for (Direction face : Direction.values()) rotated = rotated.setValue(connection(rotation.rotate(face)), state.getValue(connection(face)));
        return rotated;
    }
    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state;
        for (Direction face : Direction.values()) mirrored = mirrored.setValue(connection(mirror.mirror(face)), state.getValue(connection(face)));
        return mirrored;
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = 0;
        for (Direction face : Direction.values()) if (state.getValue(connection(face))) mask |= 1 << face.get3DDataValue();
        return SHAPES[mask];
    }
    @Override public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }
    private static VoxelShape[] shapes() {
        VoxelShape[] arms = {box(7, 0, 7, 9, 7, 9), box(7, 9, 7, 9, 16, 9), box(7, 7, 0, 9, 9, 7),
                box(7, 7, 9, 9, 9, 16), box(0, 7, 7, 7, 9, 9), box(9, 7, 7, 16, 9, 9)};
        VoxelShape[] shapes = new VoxelShape[64];
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = box(7, 7, 7, 9, 9, 9);
            for (int d = 0; d < 6; d++) if ((mask & (1 << d)) != 0) shape = Shapes.or(shape, arms[d]);
            shapes[mask] = shape.optimize();
        }
        return shapes;
    }
}
