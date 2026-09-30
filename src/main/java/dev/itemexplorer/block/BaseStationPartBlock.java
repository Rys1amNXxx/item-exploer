package dev.itemexplorer.block;

import dev.itemexplorer.station.BaseStationStructure;
import dev.itemexplorer.cable.DataCableEndpoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/** Visible parts keep their individual models before and after the station is assembled. */
public final class BaseStationPartBlock extends Block implements DataCableEndpoint {
    public enum Part { CASING, NETWORK_PORT, MODULE, ANTENNA, CAP }
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final Map<Direction, VoxelShape> ANTENNA_SHAPES = antennaShapes();
    private static final VoxelShape CAP_SHAPE = Shapes.or(box(4, 0, 4, 12, 4, 12), box(6.5, 4, 6.5, 9.5, 16, 9.5));
    private final Part part;

    public BaseStationPartBlock(Part part) {
        super(properties(part));
        this.part = part;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    private static Properties properties(Part part) {
        Properties properties = Properties.of().mapColor(MapColor.METAL).strength(2.5F).sound(SoundType.METAL);
        return part == Part.ANTENNA || part == Part.CAP ? properties.noOcclusion() : properties;
    }

    public Part part() { return part; }
    @Override public boolean acceptsDataCable(BlockState state, Direction face) {
        return part == Part.NETWORK_PORT && face == state.getValue(FACING);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = part == Part.ANTENNA && face.getAxis().isHorizontal()
                ? face : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (part) {
            case ANTENNA -> ANTENNA_SHAPES.get(state.getValue(FACING));
            case CAP -> CAP_SHAPE;
            default -> Shapes.block();
        };
    }

    @Override public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return part == Part.ANTENNA || part == Part.CAP ? Shapes.empty() : Shapes.block();
    }

    private static Map<Direction, VoxelShape> antennaShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        VoxelShape shape = Shapes.or(box(3, 1, 4, 13, 15, 6), box(7, 7, 6, 9, 9, 16));
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            shapes.put(facing, shape);
            VoxelShape[] rotated = {Shapes.empty()};
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                    rotated[0] = Shapes.or(rotated[0], Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
            shape = rotated[0];
        }
        return Map.copyOf(shapes);
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (!player.isShiftKeyDown() || !player.getItemInHand(hand).isEmpty() || player.isSpectator()
                || !player.getAbilities().mayBuild || !level.mayInteract(player, pos)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            level.setBlock(pos, rotate(state, Rotation.CLOCKWISE_90), Block.UPDATE_ALL);
            BaseStationStructure.refreshNearby(level, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!state.equals(old)) BaseStationStructure.refreshNearby(level, pos);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!state.equals(next)) BaseStationStructure.refreshNearby(level, pos);
    }
}
