package dev.itemexplorer.transfer;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.cable.CableStorageAccess;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.station.StationConnection;
import dev.itemexplorer.storage.AtomicStorageTransfer;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Synchronous, server-thread-only transfers. Discovery never requests a chunk ticket.
 * The public dimension network is opt-in at both controllers; local routing needs neither switch.
 * There are no queued items. Persistence uses the existing world/disk saves, not a cross-file WAL.
 */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class RemoteTransfers {
    public static final int MAX_AMOUNT = 64, COOLDOWN_TICKS = 20, MAX_TARGETS = 64;
    private static final Map<MinecraftServer, List<WeakReference<StorageBlockEntity>>> TERMINALS = new WeakHashMap<>();
    private record ResolvedVolume(StorageInventory inventory, String name) {}

    private RemoteTransfers() {}

    public static void register(StorageBlockEntity terminal) {
        if (!(terminal.getLevel() instanceof ServerLevel level)) return;
        var terminals = TERMINALS.computeIfAbsent(level.getServer(), ignored -> new ArrayList<>());
        terminals.removeIf(ref -> ref.get() == null || ref.get() == terminal);
        terminals.add(new WeakReference<>(terminal));
    }

    public static void unregister(StorageBlockEntity terminal) {
        if (!(terminal.getLevel() instanceof ServerLevel level)) return;
        var terminals = TERMINALS.get(level.getServer());
        if (terminals != null) terminals.removeIf(ref -> ref.get() == null || ref.get() == terminal);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { TERMINALS.remove(event.getServer()); }

    private static ServerLevel level(StorageBlockEntity terminal) {
        if (!(terminal.getLevel() instanceof ServerLevel level) || terminal.isRemoved()
                || !level.hasChunkAt(terminal.getBlockPos()) || level.getBlockEntity(terminal.getBlockPos()) != terminal)
            throw new IllegalArgumentException("remote_offline");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Transfers require the server thread");
        return level;
    }

    private static BaseStationBlockEntity station(StorageBlockEntity terminal) {
        ServerLevel level = level(terminal);
        var connection = StationConnection.inspect(level, terminal.getBlockPos(), false);
        if (connection.status() != StationConnection.Status.CONNECTED || connection.controller() == null
                || !level.hasChunkAt(connection.controller())
                || !(level.getBlockEntity(connection.controller()) instanceof BaseStationBlockEntity station)
                || station.isRemoved()) throw new IllegalArgumentException("remote_no_station");
        return station;
    }

    /** Freshly resolve the exact physical mount before touching either inventory. */
    public static StorageInventory inventory(StorageBlockEntity terminal, String volume) {
        return resolveVolume(terminal, volume).inventory();
    }

    private static ResolvedVolume resolveVolume(StorageBlockEntity terminal, String volume) {
        ServerLevel level = level(terminal);
        TransferConfig.checkedVolume(volume);
        if (volume.isEmpty()) {
            if (terminal.inventory().isLocked()) throw new IllegalArgumentException("storage_locked");
            return new ResolvedVolume(terminal.inventory(), "");
        }
        for (NasBlockEntity nas : CableStorageAccess.discover(level, terminal.getBlockPos()).cabinets()) {
            for (int bay = 0; bay < NasBlockEntity.BAYS; bay++) {
                if (!volume.equals(String.valueOf(DiskItem.id(nas.disk(bay))))) continue;
                var disk = nas.volume(bay);
                if (disk != null) return new ResolvedVolume(disk.inventory(), disk.name());
            }
        }
        throw new IllegalArgumentException("disk_offline");
    }

    /** Display metadata is derived only from an already validated, reachable mount. */
    private static void putInbox(CompoundTag tag, String volume, int folderId, ResolvedVolume resolved) {
        StorageInventory inbox = resolved.inventory();
        if (!inbox.hasFolder(folderId)) throw new IllegalArgumentException("invalid_folder");
        List<String> parts = new ArrayList<>();
        for (var folder = inbox.folder(folderId); folder.id() != 0; folder = inbox.folder(folder.parent()))
            parts.add(0, folder.name());
        tag.putString("Volume", volume);
        tag.putString("InboxPath", "/" + String.join(" / ", parts));
        tag.putString("InboxVolumeName", resolved.name());
    }

    public static void configure(StorageBlockEntity terminal, String name, String volume, int folder,
                                 boolean enabled, long expectedRevision) {
        level(terminal);
        // Disabling is always possible, including when the previous receiver drive went offline.
        if (enabled) {
            StorageInventory inventory = inventory(terminal, volume);
            if (!inventory.hasFolder(folder)) throw new IllegalArgumentException("invalid_folder");
        }
        terminal.transferConfig().configure(name, volume, folder, enabled, expectedRevision);
        terminal.setChanged();
    }

    public static CompoundTag configuration(StorageBlockEntity terminal) {
        var config = terminal.transferConfig();
        CompoundTag tag = config.save();
        tag.putBoolean("StationConnected", false);
        tag.putBoolean("NetworkOnline", false);
        String inboxProblem = "";
        try {
            putInbox(tag, config.volume(), config.folder(), resolveVolume(terminal, config.volume()));
        } catch (IllegalArgumentException failure) { inboxProblem = failure.getMessage(); }
        try {
            BaseStationBlockEntity station = station(terminal);
            tag.putBoolean("StationConnected", true);
            tag.putBoolean("NetworkOnline", station.networkOnline());
            tag.putLong("StationPos", station.getBlockPos().asLong());
            if (config.enabled() && !inboxProblem.isEmpty()) throw new IllegalArgumentException(inboxProblem);
            tag.putString("Status", config.enabled() ? "remote_ready" : "remote_receiving_disabled");
        } catch (IllegalArgumentException failure) {
            tag.putString("Status", failure.getMessage());
        }
        return tag;
    }

    private static List<StorageBlockEntity> loaded(ServerLevel level) {
        var refs = TERMINALS.get(level.getServer());
        if (refs == null) return List.of();
        refs.removeIf(ref -> { var terminal = ref.get(); return terminal == null || terminal.isRemoved(); });
        List<StorageBlockEntity> result = new ArrayList<>();
        for (var ref : refs) {
            var terminal = ref.get();
            if (terminal == null || terminal.getLevel() != level || !level.hasChunkAt(terminal.getBlockPos())
                    || level.getBlockEntity(terminal.getBlockPos()) != terminal) continue;
            result.add(terminal);
        }
        result.sort(Comparator.comparingLong(terminal -> terminal.getBlockPos().asLong()));
        return result;
    }

    private static boolean localOrConnected(BaseStationBlockEntity source, BaseStationBlockEntity target) {
        return source == target || source.networkOnline() && target.networkOnline();
    }

    /** Only opt-in, currently reachable receivers are advertised; no remote inventory is exposed. */
    public static ListTag targets(StorageBlockEntity source) {
        ListTag result = new ListTag();
        try {
            ServerLevel level = level(source);
            BaseStationBlockEntity sourceStation = station(source);
            List<StorageBlockEntity> loaded = loaded(level);
            Map<UUID, Long> counts = loaded.stream().collect(java.util.stream.Collectors.groupingBy(
                    StorageBlockEntity::productionIdentity, java.util.stream.Collectors.counting()));
            for (var target : loaded) {
                if (result.size() == MAX_TARGETS) break;
                if (target == source || !target.transferConfig().enabled() || counts.get(target.productionIdentity()) != 1) continue;
                try {
                    BaseStationBlockEntity targetStation = station(target);
                    if (!localOrConnected(sourceStation, targetStation)) continue;
                    var config = target.transferConfig();
                    ResolvedVolume inbox = resolveVolume(target, config.volume());
                    CompoundTag tag = new CompoundTag();
                    putInbox(tag, config.volume(), config.folder(), inbox);
                    tag.putUUID("Id", target.productionIdentity());
                    tag.putString("Name", config.name());
                    tag.putLong("Pos", target.getBlockPos().asLong());
                    tag.putLong("Revision", config.revision());
                    tag.putBoolean("Local", sourceStation == targetStation);
                    result.add(tag);
                } catch (IllegalArgumentException ignored) { /* Stale or incomplete targets are not advertised. */ }
            }
        } catch (IllegalArgumentException ignored) { /* No complete local connection. */ }
        return result;
    }

    public static long send(StorageBlockEntity source, String sourceVolume, int entry, long amount,
                            UUID targetId, long targetRevision) {
        if (amount <= 0 || amount > MAX_AMOUNT) throw new IllegalArgumentException("invalid_amount");
        ServerLevel level = level(source);
        BaseStationBlockEntity sourceStation = station(source);
        StorageBlockEntity target = null;
        for (var candidate : loaded(level)) {
            if (!candidate.productionIdentity().equals(targetId)) continue;
            if (target != null) throw new IllegalArgumentException("remote_target_changed");
            target = candidate;
        }
        if (target == null) throw new IllegalArgumentException("remote_offline");
        if (target == source) throw new IllegalArgumentException("remote_same_folder");
        BaseStationBlockEntity targetStation = station(target);
        if (!localOrConnected(sourceStation, targetStation)) throw new IllegalArgumentException("remote_network_offline");
        TransferConfig config = target.transferConfig();
        if (config.revision() != targetRevision) throw new IllegalArgumentException("remote_target_changed");
        if (!config.enabled()) throw new IllegalArgumentException("remote_receiving_disabled");
        StorageInventory from = inventory(source, sourceVolume), to = inventory(target, config.volume());
        var item = from.entry(entry);
        if (from == to && item != null && item.folder() == config.folder())
            throw new IllegalArgumentException("remote_same_folder");
        long now = level.getGameTime();
        if (!sourceStation.transferReady(now) || !targetStation.transferReady(now))
            throw new IllegalArgumentException("remote_busy");
        long moved = AtomicStorageTransfer.move(from, entry, to, config.folder(), amount);
        if (moved > 0) {
            sourceStation.recordTransfer(now);
            if (targetStation != sourceStation) targetStation.recordTransfer(now);
        }
        return moved;
    }
}
