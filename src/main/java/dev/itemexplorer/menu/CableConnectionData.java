package dev.itemexplorer.menu;

import dev.itemexplorer.station.StationConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.level.Level;

/** Independent menu data: cable polling must not reset an inventory/search session.
 * Coordinates use two 16-bit slots each because vanilla menu data travels as shorts. */
public final class CableConnectionData extends SimpleContainerData {
    private long nextCheck = Long.MIN_VALUE;
    public CableConnectionData() { super(9); }
    public void refresh(Level level, BlockPos device, boolean station) {
        if (level.isClientSide || level.getGameTime() < nextCheck) return;
        nextCheck = level.getGameTime() + 20;
        var result = StationConnection.inspect(level, device, station);
        set(0, result.status().ordinal()); set(1, result.cables()); set(2, result.terminals());
        BlockPos pos = result.controller() == null ? BlockPos.ZERO : result.controller();
        putInt(3, pos.getX()); putInt(5, pos.getY()); putInt(7, pos.getZ());
    }
    private void putInt(int index, int value) { set(index, value & 0xffff); set(index + 1, (value >>> 16) & 0xffff); }
    private int readInt(int index) { return (get(index) & 0xffff) | ((get(index + 1) & 0xffff) << 16); }
    public StationConnection.Status status() { return StationConnection.Status.fromCode(get(0)); }
    public int cables() { return get(1); }
    public int terminals() { return get(2); }
    public BlockPos controller() { return new BlockPos(readInt(3), readInt(5), readInt(7)); }
}
