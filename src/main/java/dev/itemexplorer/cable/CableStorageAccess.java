package dev.itemexplorer.cable;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local NAS discovery is independent of station readiness, pairing and wireless access. */
public final class CableStorageAccess {
    public record Result(List<NasBlockEntity> cabinets, int wiredCabinets, String wireStatus) {
        public Result { cabinets = List.copyOf(cabinets); }
    }
    private CableStorageAccess() {}

    public static Result discover(Level level, BlockPos terminal) {
        Map<BlockPos, NasBlockEntity> cabinets = new LinkedHashMap<>();
        if (!level.hasChunkAt(terminal)) return new Result(List.of(), 0, "unloaded");
        var state = level.getBlockState(terminal);
        if (!state.is(ModContent.STORAGE_BLOCK.get())) return new Result(List.of(), 0, "disconnected");
        // Preserve old saves' direct rear connection, including when an unrelated cable is incomplete.
        add(level, terminal.relative(state.getValue(HorizontalDirectionalBlock.FACING).getOpposite()), cabinets);
        var graph = CableNetwork.scan(level, terminal);
        String status = graph.tooLarge() ? "too_large" : graph.unloaded() ? "unloaded"
                : graph.cables() == 0 ? "disconnected" : "connected";
        int wired = 0;
        if (!graph.tooLarge() && !graph.unloaded()) {
            for (var endpoint : graph.endpoints().entrySet()) {
                if (endpoint.getValue().is(ModContent.NAS_BLOCK.get()) && add(level, endpoint.getKey(), cabinets)) wired++;
            }
        }
        return new Result(cabinets.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ())))
                .map(Map.Entry::getValue).toList(), wired, status);
    }

    private static boolean add(Level level, BlockPos pos, Map<BlockPos, NasBlockEntity> cabinets) {
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof NasBlockEntity nas) || nas.isRemoved()) return false;
        cabinets.put(pos.immutable(), nas);
        return true;
    }
}
