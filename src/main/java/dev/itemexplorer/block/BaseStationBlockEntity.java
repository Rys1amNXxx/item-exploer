package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.station.BaseStationStructure;
import dev.itemexplorer.station.StationChunkLoading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Structural controller and opt-in public wireless access. The controller never buffers items. */
public final class BaseStationBlockEntity extends BlockEntity implements MenuProvider {
    private static final int CHUNK_PULSE_TICKS = 40;
    private BaseStationStructure.Result validation;
    private boolean pendingRefresh = true;
    /**
     * False while the last check, or a restore from save, could not see every part. Becoming formed
     * right after that only means the chunks finished loading, so the formation effect is skipped.
     */
    private boolean settled = true;
    private boolean networkOnline;
    private boolean chunkLoadingEnabled;
    private long chunkLoadingRevision;
    private String chunkLoadingStatus = "off";
    private long nextTransferTick = Long.MIN_VALUE;

    public boolean networkOnline() { return networkOnline; }
    public void setNetworkOnline(boolean value) {
        if (networkOnline != value) { networkOnline = value; setChanged(); }
    }
    public boolean transferReady(long now) { return now >= nextTransferTick; }
    public void recordTransfer(long now) { nextTransferTick = now + dev.itemexplorer.transfer.RemoteTransfers.COOLDOWN_TICKS; }

    public boolean chunkLoadingEnabled() { return chunkLoadingEnabled; }
    public boolean chunkLoadingActive() { return StationChunkLoading.active(this); }
    public long chunkLoadingRevision() { return chunkLoadingRevision; }
    public String chunkLoadingStatus() { return chunkLoadingStatus; }
    public void updateChunkLoadingStatus(String status) { chunkLoadingStatus = status; }

    /** The persistent request is independent of wireless networking and survives temporary failures. */
    public void setChunkLoadingEnabled(boolean enabled) {
        if (level == null || level.isClientSide || isRemoved()) return;
        if (chunkLoadingEnabled != enabled) {
            if (chunkLoadingRevision == Long.MAX_VALUE) throw new IllegalArgumentException("station_chunk_config_changed");
            chunkLoadingEnabled = enabled;
            chunkLoadingRevision++;
            setChanged();
        }
        refreshStructure();
        StationChunkLoading.reconcile(this);
    }

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
        refreshStructure(true);
    }

    private void refreshStructure(boolean updateChunkLoading) {
        if (level == null || level.isClientSide || isRemoved()) return;
        if (!level.hasChunkAt(worldPosition)) { validation = unchecked(); settled = false; pendingRefresh = true; return; }
        BlockState state = level.getBlockState(worldPosition);
        if (!state.is(ModContent.BASE_STATION_CONTROLLER_BLOCK.get())) {
            validation = unchecked();
            if (updateChunkLoading) StationChunkLoading.structureChanged(this);
            return;
        }
        boolean announceFormed = settled;
        Direction front = state.getValue(BaseStationControllerBlock.FACING);
        validation = BaseStationStructure.validate(level, worldPosition, front);
        settled = validation.issues().stream().noneMatch(issue -> issue.reason() == BaseStationStructure.Reason.UNLOADED);
        pendingRefresh = false;
        if (state.getValue(BaseStationControllerBlock.FORMED) != validation.complete()) {
            level.setBlock(worldPosition, state.setValue(BaseStationControllerBlock.FORMED, validation.complete()), Block.UPDATE_CLIENTS);
            if (validation.complete() && announceFormed && level instanceof ServerLevel serverLevel)
                DeviceParticles.stationFormed(serverLevel, BaseStationStructure.capPosition(worldPosition, front));
        }
        if (updateChunkLoading) StationChunkLoading.structureChanged(this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BaseStationBlockEntity station) {
        if (station.pendingRefresh || Math.floorMod(level.getGameTime() + pos.asLong(), 20) == 0) {
            station.refreshStructure();
            StationChunkLoading.reconcile(station);
        }
        if (Math.floorMod(level.getGameTime() + pos.asLong(), CHUNK_PULSE_TICKS) == 0
                && level instanceof ServerLevel serverLevel && station.chunkLoadingActive()) {
            for (BaseStationStructure.Requirement module : BaseStationStructure.chunkLoaders(level, pos,
                    state.getValue(BaseStationControllerBlock.FACING)))
                DeviceParticles.chunkLoaderPulse(serverLevel, module.pos(), module.facing());
        }
    }

    @Override public void onLoad() {
        super.onLoad();
        // Neighboring chunks and Forge's saved tickets may still be loading. Never acquire or
        // revoke tickets recursively inside onLoad, and do not reuse a saved formed flag.
        refreshStructure(false);
        pendingRefresh = true;
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        networkOnline = tag.contains("NetworkOnline", net.minecraft.nbt.Tag.TAG_BYTE) && tag.getByte("NetworkOnline") == 1;
        chunkLoadingEnabled = tag.contains("ChunkLoadingEnabled", Tag.TAG_BYTE) && tag.getByte("ChunkLoadingEnabled") == 1;
        chunkLoadingRevision = tag.contains("ChunkLoadingRevision", Tag.TAG_LONG) ? tag.getLong("ChunkLoadingRevision") : 0;
        if (chunkLoadingRevision < 0 || chunkLoadingRevision == Long.MAX_VALUE) {
            chunkLoadingRevision = 0;
            chunkLoadingEnabled = false;
        }
        chunkLoadingStatus = chunkLoadingEnabled ? "unloaded" : "off";
        nextTransferTick = Long.MIN_VALUE;
        validation = unchecked();
        settled = false;
        pendingRefresh = true;
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("NetworkOnline", networkOnline);
        tag.putBoolean("ChunkLoadingEnabled", chunkLoadingEnabled);
        tag.putLong("ChunkLoadingRevision", chunkLoadingRevision);
    }
    @Override public Component getDisplayName() { return Component.translatable("block.itemexplorer.base_station_controller"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new BaseStationMenu(id, inventory, this); }
}
