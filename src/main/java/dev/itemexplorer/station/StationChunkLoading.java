package dev.itemexplorer.station;

import com.mojang.logging.LogUtils;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A controller owns its own Forge tickets, so overlapping stations can be released independently.
 * Ordinary structure/portable reads never add tickets. Acquisition happens only on an explicit
 * toggle, the station's server tick, or after Forge has finished reinstating saved tickets.
 */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class StationChunkLoading {
    public static final int RADIUS = 1;
    public static final int CHUNKS_PER_STATION = 9;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, Map<Owner, Lease>> LEASES = new WeakHashMap<>();
    private static final Set<ServerLevel> RESTORING = Collections.newSetFromMap(new WeakHashMap<>());

    private record Owner(ServerLevel level, BlockPos pos) {}
    private static final class Lease {
        final Set<Long> chunks = new LinkedHashSet<>();
        boolean acquiring;
        boolean revokeRequested;
    }

    private StationChunkLoading() {}

    public static Set<Long> chunks(BlockPos controller) {
        ChunkPos center = new ChunkPos(controller);
        Set<Long> result = new LinkedHashSet<>();
        for (int x = center.x - RADIUS; x <= center.x + RADIUS; x++)
            for (int z = center.z - RADIUS; z <= center.z + RADIUS; z++) result.add(ChunkPos.asLong(x, z));
        return Collections.unmodifiableSet(result);
    }

    public static int activeStationCount(MinecraftServer server) {
        Map<Owner, Lease> leases = LEASES.get(server);
        return leases == null ? 0 : leases.size();
    }

    public static int maxActiveStations() { return StationChunkLoadingConfig.MAX_ACTIVE_STATIONS.get(); }

    public static boolean active(BaseStationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level)) return false;
        Map<Owner, Lease> leases = LEASES.get(level.getServer());
        Lease lease = leases == null ? null : leases.get(new Owner(level, station.getBlockPos()));
        return lease != null && !lease.acquiring && lease.chunks.size() == CHUNKS_PER_STATION;
    }

    /** Returns null when the station is eligible; never loads missing chunks. */
    private static String inactiveReason(BaseStationBlockEntity station) {
        if (!station.chunkLoadingEnabled()) return "off";
        if (!station.validation().complete()) return station.validation().issues().stream()
                .anyMatch(issue -> issue.reason() == BaseStationStructure.Reason.UNLOADED) ? "unloaded" : "incomplete";
        if (BaseStationStructure.countChunkLoaders(station.getLevel(), station.getBlockPos(),
                station.getBlockState().getValue(BaseStationControllerBlock.FACING)) == 0) return "no_module";
        return null;
    }

    /** Called by structural notifications: release promptly, but never acquire from a read path. */
    public static void structureChanged(BaseStationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level) || RESTORING.contains(level)) return;
        String reason = inactiveReason(station);
        if (reason != null) {
            release(level, station.getBlockPos());
            station.updateChunkLoadingStatus(reason);
        }
    }

    public static void reconcile(BaseStationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level) || station.isRemoved() || RESTORING.contains(level)) return;
        String reason = inactiveReason(station);
        if (reason != null) {
            release(level, station.getBlockPos());
            station.updateChunkLoadingStatus(reason);
            return;
        }
        Map<Owner, Lease> leases = LEASES.computeIfAbsent(level.getServer(), ignored -> new LinkedHashMap<>());
        Owner owner = new Owner(level, station.getBlockPos().immutable());
        Lease lease = leases.get(owner);
        int limit = StationChunkLoadingConfig.MAX_ACTIVE_STATIONS.get();
        if ((lease == null && leases.size() >= limit) || (lease != null && leases.size() > limit)) {
            release(level, owner.pos());
            station.updateChunkLoadingStatus("limit_reached");
            return;
        }
        if (lease == null) {
            lease = new Lease();
            leases.put(owner, lease); // Reserve the global slot before synchronous chunk loading.
        }
        if (lease.acquiring) return;
        lease.acquiring = true;
        try {
            for (long packed : chunks(owner.pos())) {
                if (lease.revokeRequested) break;
                if (!lease.chunks.add(packed)) continue;
                ChunkPos chunk = new ChunkPos(packed);
                // false means this exact owner already has the ticket; ownership is still valid.
                ForgeChunkManager.forceChunk(level, ItemExplorer.MOD_ID, owner.pos(), chunk.x, chunk.z, true, true);
            }
            if (!lease.revokeRequested) station.updateChunkLoadingStatus("active");
        } catch (RuntimeException exception) {
            release(level, owner.pos());
            station.updateChunkLoadingStatus("loading_failed");
            LOGGER.error("Could not maintain station chunks at {} in {}", owner.pos(), level.dimension().location(), exception);
        } finally {
            lease.acquiring = false;
            if (lease.revokeRequested) release(level, owner.pos());
        }
    }

    /** Do not call this from BlockEntity#setRemoved/onChunkUnloaded: shutdown must retain tickets. */
    public static void release(ServerLevel level, BlockPos controller) {
        if (RESTORING.contains(level)) return;
        Map<Owner, Lease> leases = LEASES.get(level.getServer());
        if (leases == null) return;
        Owner owner = new Owner(level, controller);
        Lease lease = leases.get(owner);
        if (lease == null) return;
        // forceChunk loads synchronously before installing its region ticket. Releasing inside
        // that load would remove saved ownership before the outer call installs an orphan ticket.
        // Finish the in-flight call first, then release everything it acquired in the finally block.
        if (lease.acquiring) {
            lease.revokeRequested = true;
            return;
        }
        leases.remove(owner);
        for (long packed : new ArrayList<>(lease.chunks)) {
            ChunkPos chunk = new ChunkPos(packed);
            ForgeChunkManager.forceChunk(level, ItemExplorer.MOD_ID, controller, chunk.x, chunk.z, false, true);
        }
    }

    /**
     * Forge invokes this before reinstating tickets. Only saved station candidates may load here.
     * Load all structure chunks before validating: a controller may sit against a chunk boundary.
     * Prune foreign-sized/non-ticking tickets without loading their target chunks.
     */
    public static void validateTickets(ServerLevel level, ForgeChunkManager.TicketHelper helper) {
        RESTORING.add(level);
        try {
            for (var entity : helper.getEntityTickets().keySet()) helper.removeAllTickets(entity);
            var owners = new ArrayList<>(helper.getBlockTickets().keySet());
            owners.sort(Comparator.comparingLong(BlockPos::asLong));
            Map<Owner, Lease> leases = LEASES.computeIfAbsent(level.getServer(), ignored -> new LinkedHashMap<>());
            for (BlockPos pos : owners) {
                Owner owner = new Owner(level, pos.immutable());
                leases.remove(owner);
                if (leases.size() >= StationChunkLoadingConfig.MAX_ACTIVE_STATIONS.get()
                        || level.isOutsideBuildHeight(pos)) {
                    helper.removeAllTickets(pos);
                    continue;
                }
                level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                if (!level.getBlockState(pos).is(ModContent.BASE_STATION_CONTROLLER_BLOCK.get())
                        || !(level.getBlockEntity(pos) instanceof BaseStationBlockEntity station)
                        || !station.chunkLoadingEnabled()) {
                    helper.removeAllTickets(pos);
                    continue;
                }
                var front = level.getBlockState(pos).getValue(BaseStationControllerBlock.FACING);
                Set<Long> structureChunks = new HashSet<>();
                for (var requirement : BaseStationStructure.requirements(pos, front))
                    structureChunks.add(new ChunkPos(requirement.pos()).toLong());
                for (long packed : structureChunks) {
                    ChunkPos chunk = new ChunkPos(packed);
                    level.getChunk(chunk.x, chunk.z);
                }
                station.refreshStructure();
                if (inactiveReason(station) != null) {
                    helper.removeAllTickets(pos);
                    continue;
                }
                Set<Long> expected = chunks(pos);
                var saved = helper.getBlockTickets().get(pos);
                for (long packed : saved.getFirst()) helper.removeTicket(pos, packed, false);
                Lease lease = new Lease();
                for (long packed : saved.getSecond()) {
                    if (expected.contains(packed)) lease.chunks.add(packed);
                    else helper.removeTicket(pos, packed, true);
                }
                leases.put(owner, lease);
                station.updateChunkLoadingStatus("active");
            }
        } finally {
            RESTORING.remove(level);
        }
    }

    /** Repair incomplete saved ticket sets only after Forge's callback/reinstatement has returned. */
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        Map<Owner, Lease> leases = LEASES.get(event.getServer());
        if (leases == null) return;
        for (Owner owner : new ArrayList<>(leases.keySet())) {
            if (owner.level().getBlockEntity(owner.pos()) instanceof BaseStationBlockEntity station) {
                station.refreshStructure();
                reconcile(station);
            } else release(owner.level(), owner.pos());
        }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        // Memory only: the tickets in each world's saved data are needed for the next start.
        LEASES.remove(event.getServer());
        RESTORING.removeIf(level -> level.getServer() == event.getServer());
    }
}
