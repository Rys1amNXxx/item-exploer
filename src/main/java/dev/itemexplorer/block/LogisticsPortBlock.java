package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A face-mounted block occupying its own grid cell. FACING points away from its host. */
public final class LogisticsPortBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");
    public static final IntegerProperty STATUS = IntegerProperty.create("status", 0, 3);

    public LogisticsPortBlock() {
        super(Properties.of().mapColor(MapColor.METAL).strength(1.5F).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CONNECTED, false).setValue(STATUS, 0));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, CONNECTED, STATUS); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        BlockState state = defaultBlockState().setValue(FACING, facing)
                .setValue(CONNECTED, hasConnectorNeighbor(context.getLevel().getBlockState(context.getClickedPos().relative(facing))));
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState host = level.getBlockState(pos.relative(state.getValue(FACING).getOpposite()));
        return host.is(ModContent.NAS_BLOCK.get()) || host.is(ModContent.STORAGE_BLOCK.get());
    }
    @Override public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        if (direction == state.getValue(FACING)) return state.setValue(CONNECTED, hasConnectorNeighbor(neighbor));
        return state;
    }
    /** Visual bridge to an adjacent device. Grass, fluids and plain terrain are not devices.
     * This does not probe capabilities: many pipes expose their transport network rather than an inventory.
     */
    public static boolean hasConnectorNeighbor(BlockState neighbor) { return neighbor.hasBlockEntity() && !neighbor.canBeReplaced(); }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof LogisticsPortBlockEntity port) port.archiveProtectedData();
        super.onRemove(state, level, pos, next, moving);
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        VoxelShape plate = orientedBox(facing, 5, 5, 15, 11, 11, 16);
        VoxelShape socket = orientedBox(facing, 6.5, 6.5, 14, 9.5, 9.5, 15);
        return state.getValue(CONNECTED)
                ? Shapes.or(plate, socket, orientedBox(facing, 7.25, 7.25, 0, 8.75, 8.75, 14)) : Shapes.or(plate, socket);
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
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new LogisticsPortBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModContent.LOGISTICS_ENTITY.get(), LogisticsPortBlockEntity::serverTick);
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof LogisticsPortBlockEntity port)
            net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer, port, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
