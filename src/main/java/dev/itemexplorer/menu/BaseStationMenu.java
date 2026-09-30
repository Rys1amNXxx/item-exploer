package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.station.BaseStationStructure;
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

/** Read-only structure diagnostics. No inventory or transfer authority is exposed. */
public final class BaseStationMenu extends AbstractContainerMenu {
    private static final int DATA_SIZE = 3 + BaseStationStructure.TOTAL_PARTS;
    private final BaseStationBlockEntity station;
    private final Player owner;
    private final BlockPos pos;
    private final ContainerData data;

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
    }

    public boolean ready() { return data.get(0) != 0; }
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
