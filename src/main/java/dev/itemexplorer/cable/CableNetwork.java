package dev.itemexplorer.cable;

import dev.itemexplorer.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Shared, bounded physical topology for station access and future production devices.
 * Endpoints are leaves, never bridges; no inventories, station policy or chunk tickets live here. */
public final class CableNetwork {
    public static final int MAX_CABLES = 256;
    public record Scan(Map<BlockPos, BlockState> endpoints, int cables, boolean unloaded, boolean tooLarge) {
        public Scan { endpoints = Map.copyOf(endpoints); }
    }
    private CableNetwork() {}

    public static Scan scan(Level level, BlockPos socket) {
        return scan(socket, level::hasChunkAt, level::getBlockState);
    }

    public static Scan scan(BlockPos socket, Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states) {
        return scan(socket, loaded, states, DataCableEndpoint::accepts);
    }

    /** Injectable socket lookup lets topology be tested without registering test-only game blocks. */
    public static Scan scan(BlockPos socket, Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states,
                            BiPredicate<BlockState, Direction> accepts) {
        Map<BlockPos, BlockState> endpoints = new LinkedHashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boolean unloaded = false;
        if (!loaded.test(socket)) return new Scan(endpoints, 0, true, false);
        BlockState origin = states.apply(socket);
        endpoints.put(socket.immutable(), origin);
        for (Direction face : Direction.values()) {
            if (!accepts.test(origin, face)) continue;
            BlockPos next = socket.relative(face);
            if (!loaded.test(next)) { unloaded = true; continue; }
            if (states.apply(next).is(ModContent.DATA_CABLE_BLOCK.get()) && seen.add(next)) queue.add(next);
        }
        while (!queue.isEmpty()) {
            BlockPos cable = queue.remove();
            for (Direction face : Direction.values()) {
                BlockPos next = cable.relative(face);
                if (!loaded.test(next)) { unloaded = true; continue; }
                BlockState state = states.apply(next);
                if (state.is(ModContent.DATA_CABLE_BLOCK.get())) {
                    if (seen.contains(next)) continue;
                    if (seen.size() == MAX_CABLES) return new Scan(endpoints, seen.size(), unloaded, true);
                    seen.add(next); queue.add(next);
                } else if (accepts.test(state, face.getOpposite())) {
                    endpoints.put(next.immutable(), state);
                }
            }
        }
        return new Scan(endpoints, seen.size(), unloaded, false);
    }
}
