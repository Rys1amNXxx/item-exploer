package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.station.BaseStationStructure;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** On-site structure diagnostics and an explicit station network switch. */
public final class BaseStationMenu extends AbstractContainerMenu {
    private static final int DATA_SIZE = 3 + BaseStationStructure.TOTAL_PARTS;
    private final BaseStationBlockEntity station;
    private final Player owner;
    private final BlockPos pos;
    private final ContainerData data;
    private final long session = MenuSession.next();
    private long networkActionTick = Long.MIN_VALUE;
    private CompoundTag networkView = new CompoundTag(), sentNetworkView;
    private final CableConnectionData cable = new CableConnectionData();
    public CableConnectionData cable() { return cable; }

    public BaseStationMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), null);
    }

    public BaseStationMenu(int id, Inventory inventory, BaseStationBlockEntity station) {
        this(id, inventory, station.getBlockPos(), station);
    }

    private BaseStationMenu(int id, Inventory inventory, BlockPos pos, BaseStationBlockEntity station) {
        super(ModContent.BASE_STATION_MENU.get(), id);
        this.station = station;
        this.owner = inventory.player;
        this.pos = pos.immutable();
        this.data = station == null ? new SimpleContainerData(DATA_SIZE) : new ContainerData() {
            @Override public int get(int index) {
                if (index == 0) return 1;
                if (index == 1) return station.validation().matched();
                Direction facing = station.getBlockState().getValue(BaseStationControllerBlock.FACING);
                if (index == 2) return facing.get2DDataValue();
                var required = BaseStationStructure.requirements(pos, facing).get(index - 3);
                for (var issue : station.validation().issues()) {
                    if (issue.pos().equals(required.pos())) return issue.reason().ordinal() + 1;
                }
                return 0;
            }
            @Override public void set(int index, int value) { /* server-derived */ }
            @Override public int getCount() { return DATA_SIZE; }
        };
        addDataSlots(data);
        if (station != null) cable.refresh(owner.level(), pos, true);
        addDataSlots(cable);
    }

    public boolean ready() { return data.get(0) != 0; }
    public CompoundTag networkView() { return networkView; }
    public void acceptView(CompoundTag view) { networkView = view.copy(); }
    public boolean networkOnline() { return networkView.getBoolean("NetworkOnline"); }
    public void handleNetwork(StorageNetwork.StationNetworkRequest request) {
        if (station == null || request.menuId() != containerId || !stillValid(owner)) return;
        long tick = owner.level().getGameTime();
        if (request.session() != session || networkActionTick == tick) return;
        networkActionTick = tick;
        station.refreshStructure();
        if (request.expectedOnline() == station.networkOnline() && (!request.online() || station.validation().complete()))
            station.setNetworkOnline(request.online());
        syncNetwork(true);
    }
    private void syncNetwork(boolean force) {
        if (station == null || !(owner instanceof ServerPlayer player)) return;
        CompoundTag view = new CompoundTag();
        view.putLong("Session", session); view.putBoolean("NetworkOnline", station.networkOnline());
        if (force || !view.equals(sentNetworkView)) {
            StorageNetwork.snapshot(player, containerId, view); sentNetworkView = view;
        }
    }
    @Override public void broadcastChanges() {
        if (station != null && stillValid(owner)) cable.refresh(owner.level(), pos, true);
        super.broadcastChanges();
        if (station != null && stillValid(owner)) syncNetwork(false);
    }
    public int matched() { return data.get(1); }
    public boolean complete() { return ready() && matched() == BaseStationStructure.TOTAL_PARTS; }
    public BlockPos position() { return pos; }
    public List<BaseStationStructure.Issue> issues() {
        if (!ready()) return List.of();
        var required = BaseStationStructure.requirements(pos, Direction.from2DDataValue(data.get(2)));
        var issues = new ArrayList<BaseStationStructure.Issue>();
        var reasons = BaseStationStructure.Reason.values();
        for (int i = 0; i < required.size(); i++) {
            int code = data.get(i + 3);
            if (code > 0 && code <= reasons.length) {
                var part = required.get(i);
                issues.add(new BaseStationStructure.Issue(part.pos(), part.part(), reasons[code - 1]));
            }
        }
        return List.copyOf(issues);
    }

    @Override public boolean stillValid(Player player) {
        if (station == null) return player.level().isClientSide;
        return player == owner && player.containerMenu == this && !player.isSpectator()
                && player.level() == station.getLevel() && !station.isRemoved()
                && player.level().hasChunkAt(pos) && player.level().getBlockEntity(pos) == station
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
