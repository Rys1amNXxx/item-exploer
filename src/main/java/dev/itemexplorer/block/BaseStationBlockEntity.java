package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.station.BaseStationStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** No inventory, power, or network identity is created by this structural controller. */
public final class BaseStationBlockEntity extends BlockEntity implements MenuProvider {
    private BaseStationStructure.Result validation;
    private boolean pendingRefresh = true;

    public BaseStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.BASE_STATION_ENTITY.get(), pos, state);
        validation = unchecked();
    }

    private BaseStationStructure.Result unchecked() {
        return new BaseStationStructure.Result(0, BaseStationStructure.TOTAL_PARTS,
                List.of(new BaseStationStructure.Issue(worldPosition, BaseStationStructure.Part.CONTROLLER, BaseStationStructure.Reason.UNLOADED)));
    }
    public BaseStationStructure.Result validation() { return validation; }

    public void refreshStructure() {
        if (level == null || level.isClientSide || isRemoved()) return;
        if (!level.hasChunkAt(worldPosition)) { validation = unchecked(); pendingRefresh = true; return; }
        BlockState state = level.getBlockState(worldPosition);
        if (!state.is(ModContent.BASE_STATION_CONTROLLER_BLOCK.get())) { validation = unchecked(); return; }
        validation = BaseStationStructure.validate(level, worldPosition, state.getValue(BaseStationControllerBlock.FACING));
        pendingRefresh = false;
        if (state.getValue(BaseStationControllerBlock.FORMED) != validation.complete())
            level.setBlock(worldPosition, state.setValue(BaseStationControllerBlock.FORMED, validation.complete()), Block.UPDATE_CLIENTS);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BaseStationBlockEntity station) {
        if (station.pendingRefresh || Math.floorMod(level.getGameTime() + pos.asLong(), 20) == 0) station.refreshStructure();
    }

    @Override public void onLoad() {
        super.onLoad();
        refreshStructure();
        // A neighboring chunk may finish loading after onLoad; do not reuse a saved formed flag.
        pendingRefresh = true;
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        validation = unchecked();
        pendingRefresh = true;
    }
    @Override public Component getDisplayName() { return Component.translatable("block.itemexplorer.base_station_controller"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new BaseStationMenu(id, inventory, this); }
}
