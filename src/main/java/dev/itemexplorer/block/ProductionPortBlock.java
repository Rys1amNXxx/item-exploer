package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.cable.DataCableEndpoint;
import dev.itemexplorer.production.MachineAdapters;
import dev.itemexplorer.production.ItemHandlerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A machine-mounted data interface. FACING points from the supported machine to its cable socket. */
public final class ProductionPortBlock extends BaseEntityBlock implements DataCableEndpoint {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Indicator> STATUS = EnumProperty.create("status", Indicator.class);
    public enum Indicator implements StringRepresentable {
        IDLE("idle"), RUNNING("running"), WAITING("waiting"), ERROR("error"), COMPLETE("complete");

        private final String name;
        Indicator(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }
    private static final VoxelShape[] SHAPES = shapes();

    public ProductionPortBlock() {
        super(Properties.of().mapColor(MapColor.METAL).strength(1.5F).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STATUS, Indicator.IDLE));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATUS);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getClickedFace());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos machinePos = pos.relative(state.getValue(FACING).getOpposite());
        return level.hasChunkAt(machinePos) && (MachineAdapters.forState(level.getBlockState(machinePos)) != null
                // Some mods expose inventories only on the server. Let the server authorize
                // placement instead of preventing the client from requesting it at all.
                || (level instanceof Level client && client.isClientSide && level.getBlockState(machinePos).hasBlockEntity())
                || ItemHandlerAccess.hasHandler(level.getBlockEntity(machinePos)));
    }

    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                             LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // A machine may temporarily disable all automation faces. Keep the physical interface
        // attached and let its task wait, rather than deleting its saved material buffer.
        if (direction == state.getValue(FACING).getOpposite() && level.hasChunkAt(neighborPos)
                && MachineAdapters.forState(neighbor) == null && !neighbor.hasBlockEntity()) return Blocks.AIR.defaultBlockState();
        return state;
    }

    @Override public boolean acceptsDataCable(BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof ProductionPortBlockEntity port) port.onDetached();
        super.onRemove(state, level, pos, next, moving);
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get3DDataValue()];
    }

    @Override public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    private static VoxelShape[] shapes() {
        VoxelShape[] shapes = new VoxelShape[6];
        for (Direction facing : Direction.values()) {
            shapes[facing.get3DDataValue()] = Shapes.or(
                    orientedBox(facing, 5, 5, 14.75, 11, 11, 16),
                    orientedBox(facing, 6.5, 6.5, 13.5, 9.5, 9.5, 14.75),
                    orientedBox(facing, 7.25, 7.25, 0, 8.75, 8.75, 13.5)).optimize();
        }
        return shapes;
    }

    private static VoxelShape orientedBox(Direction facing, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (facing) {
            case NORTH -> box(x1, y1, z1, x2, y2, z2);
            case SOUTH -> box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case EAST -> box(16 - z2, y1, x1, 16 - z1, y2, x2);
            case WEST -> box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            case UP -> box(x1, 16 - z2, y1, x2, 16 - z1, y2);
            case DOWN -> box(x1, z1, 16 - y2, x2, z2, 16 - y1);
        };
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProductionPortBlockEntity(pos, state); }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModContent.PRODUCTION_ENTITY.get(), ProductionPortBlockEntity::serverTick);
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
