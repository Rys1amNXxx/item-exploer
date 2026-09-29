package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;

import java.util.EnumMap;
import java.util.Map;

public final class NasBlock extends StorageBlock {
    private static final Map<Direction, VoxelShape> OCCLUSION = createOcclusionShapes();

    private static Map<Direction, VoxelShape> createOcclusionShapes() {
        // Solid body and outer rim from generate-nas-models.mjs. Interior details do not
        // touch the block boundary and must not hide the neighboring block's faces.
        VoxelShape north = Shapes.or(
                box(.125, .125, 2.75, 15.875, 15.875, 15.875),
                box(0, 0, 0, 1.25, 16, 2.75), box(14.75, 0, 0, 16, 16, 2.75),
                box(1.25, 14.5, 0, 14.75, 16, 2.75), box(1.25, 0, 0, 14.75, 1.5, 2.75));
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        VoxelShape shape = north;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            shapes.put(direction, shape);
            VoxelShape[] rotated = {Shapes.empty()};
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                    rotated[0] = Shapes.or(rotated[0], Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
            shape = rotated[0];
        }
        return Map.copyOf(shapes);
    }

    @Override public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return OCCLUSION.get(state.getValue(HorizontalDirectionalBlock.FACING));
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new NasBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModContent.NAS_ENTITY.get(), NasBlockEntity::serverTick);
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof NasBlockEntity nas) {
            NetworkHooks.openScreen(serverPlayer, nas, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof NasBlockEntity nas) {
            nas.archiveProtectedData();
            if (!nas.isLocked()) for (int i = 0; i < NasBlockEntity.BAYS; i++) {
                var disk = nas.eject(i);
                if (!disk.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, disk);
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
