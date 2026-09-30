package dev.itemexplorer.station;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.cable.CableNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/** Station-specific interpretation of the shared wire graph. Does not enable wireless transfers. */
public final class StationConnection {
    public enum Status {
        CHECKING, DISCONNECTED, NO_STATION, INCOMPLETE, CONNECTED, CONFLICT, UNLOADED, TOO_LARGE, NO_TERMINAL;
        public String key() { return "gui.itemexplorer.cable_" + name().toLowerCase(Locale.ROOT); }
        public static Status fromCode(int code) { return code >= 0 && code < values().length ? values()[code] : CHECKING; }
    }
    public record Result(Status status, int cables, int terminals, BlockPos controller) {}
    private StationConnection() {}

    public static Result inspect(Level level, BlockPos device, boolean controller) {
        return inspect(device, controller, level::hasChunkAt, level::getBlockState);
    }

    public static Result inspect(BlockPos device, boolean controller, Predicate<BlockPos> loaded,
                                 Function<BlockPos, BlockState> states) {
        if (!loaded.test(device)) return new Result(Status.UNLOADED, 0, 0, null);
        BlockPos socket = device;
        if (controller) {
            BlockState state = states.apply(device);
            if (!state.is(ModContent.BASE_STATION_CONTROLLER_BLOCK.get())) return new Result(Status.INCOMPLETE, 0, 0, null);
            Direction front = state.getValue(BaseStationControllerBlock.FACING);
            var structure = BaseStationStructure.validate(device, front, loaded, states);
            if (!structure.complete()) return new Result(structureStatus(structure), 0, 0, device);
            socket = device.relative(front.getOpposite(), 2);
        } else if (!states.apply(device).is(ModContent.STORAGE_BLOCK.get())) {
            return new Result(Status.DISCONNECTED, 0, 0, null);
        }
        var scan = CableNetwork.scan(socket, loaded, states);
        int terminals = (int) scan.endpoints().values().stream().filter(s -> s.is(ModContent.STORAGE_BLOCK.get())).count();
        if (scan.tooLarge()) return new Result(Status.TOO_LARGE, scan.cables(), terminals, null);
        if (scan.unloaded()) return new Result(Status.UNLOADED, scan.cables(), terminals, null);
        if (scan.cables() == 0) return new Result(Status.DISCONNECTED, 0, terminals, null);
        var ports = scan.endpoints().entrySet().stream()
                .filter(e -> e.getValue().is(ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get())).toList();
        if (ports.size() > 1) return new Result(Status.CONFLICT, scan.cables(), terminals, null);
        if (ports.isEmpty()) return new Result(Status.NO_STATION, scan.cables(), terminals, null);
        var port = ports.get(0);
        Direction front = port.getValue().getValue(BaseStationPartBlock.FACING).getOpposite();
        BlockPos station = port.getKey().relative(front, 2);
        var structure = BaseStationStructure.validate(station, front, loaded, states);
        Status status = !structure.complete() ? structureStatus(structure) : terminals == 0 ? Status.NO_TERMINAL : Status.CONNECTED;
        return new Result(status, scan.cables(), terminals, station);
    }

    private static Status structureStatus(BaseStationStructure.Result structure) {
        return structure.issues().stream().anyMatch(i -> i.reason() == BaseStationStructure.Reason.UNLOADED)
                ? Status.UNLOADED : Status.INCOMPLETE;
    }
}
