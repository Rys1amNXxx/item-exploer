package dev.itemexplorer.production;

import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.cable.CableNetwork;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import java.util.Comparator;
import java.util.List;

/** Terminal-owned documents and reachability; interfaces are execution endpoints only. */
public final class TerminalPrograms {
    public record Machines(List<ProductionPortBlockEntity> ports, String problem) {}
    private TerminalPrograms() {}
    public static Machines discover(StorageBlockEntity terminal) {
        Level level = terminal.getLevel();
        if (level == null || !level.hasChunkAt(terminal.getBlockPos()) || terminal.isRemoved())
            return new Machines(List.of(), "production_disconnected");
        var graph = CableNetwork.scan(level, terminal.getBlockPos());
        if (graph.tooLarge()) return new Machines(List.of(), "production_network_large");
        if (graph.unloaded()) return new Machines(List.of(), "production_unloaded");
        for (BlockPos pos : graph.endpoints().keySet())
            if (level.getBlockEntity(pos) instanceof StorageBlockEntity other && other != terminal)
                return new Machines(List.of(), "production_terminal_conflict");
        var ports = graph.endpoints().keySet().stream().map(level::getBlockEntity)
                .filter(ProductionPortBlockEntity.class::isInstance).map(ProductionPortBlockEntity.class::cast)
                .filter(port -> !port.isRemoved()).sorted(Comparator.comparingLong(port -> port.getBlockPos().asLong())).toList();
        return new Machines(ports, ports.isEmpty() ? "production_disconnected" : "");
    }
    public static ProductionPortBlockEntity reachable(StorageBlockEntity terminal, long position) {
        Machines machines = discover(terminal);
        if (!machines.problem().isEmpty()) throw new IllegalArgumentException(machines.problem());
        return machines.ports().stream().filter(port -> port.getBlockPos().asLong() == position).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("production_machine_offline"));
    }
    public static ProductionPortBlockEntity loadedPort(StorageBlockEntity terminal, ProgramFile file) {
        Level level = terminal.getLevel();
        if (!file.configured() || level == null || !level.hasChunkAt(file.portPos())) return null;
        return level.getBlockEntity(file.portPos()) instanceof ProductionPortBlockEntity port
                && !port.isRemoved() && port.identity().equals(file.portIdentity()) ? port : null;
    }
    /** Keep reservations for unloaded machines; clear only positively identified removal or stopped runtime. */
    public static void reconcile(StorageBlockEntity terminal) {
        if (terminal.programs().isLocked() || terminal.inventory().isLocked() || terminal.getLevel() == null) return;
        for (ProgramFile file : terminal.programs().files()) {
            if (!file.active() || !terminal.getLevel().hasChunkAt(file.portPos())) continue;
            ProductionPortBlockEntity port = loadedPort(terminal, file);
            if (port == null || (!port.isLocked()
                    && port.ownsRun(terminal.productionIdentity(), file.id(), file.runIdentity()) && !port.isRunning()))
                terminal.programs().setActive(file.id(), false);
        }
    }
    public static String topologyStamp(StorageBlockEntity terminal) {
        Machines machines = discover(terminal);
        StringBuilder result = new StringBuilder(terminal.accessSession()).append(':').append(machines.problem());
        for (var port : machines.ports()) result.append('|').append(port.getBlockPos().asLong()).append(':')
                .append(port.identity()).append(':').append(port.machineType()).append(':').append(port.topologyStamp());
        return result.toString();
    }
    public static String status(StorageBlockEntity terminal, ProgramFile file) {
        if (!file.configured()) return "production_unconfigured";
        ProductionPortBlockEntity port = loadedPort(terminal, file);
        if (port == null) return "production_machine_offline";
        if (file.active() && !port.ownsRun(terminal.productionIdentity(), file.id(), file.runIdentity()))
            return "production_program_changed";
        if (port.ownsProgram(terminal.productionIdentity(), file.id())) return port.runtimeView().getString("Status");
        return "production_ready";
    }
    public static void cancel(StorageBlockEntity terminal, ProgramFile file) {
        if (!file.active()) return;
        ProductionPortBlockEntity port = loadedPort(terminal, file);
        if (port != null && port.ownsProgram(terminal.productionIdentity(), file.id()))
            port.cancelProgram(terminal.productionIdentity(), file.id());
        // Returning old runtimes check this authoritative reservation before any further transfer.
        terminal.programs().setActive(file.id(), false);
    }
    public static void open(ServerPlayer player, StorageBlockEntity terminal, int id, int folder, int page) {
        open(player, terminal, id, folder, page, StorageInventory.PAGE_SIZE);
    }
    public static void open(ServerPlayer player, StorageBlockEntity terminal, int id, int folder, int page, int pageSize) {
        ProgramFile file = terminal.programs().file(id);
        if (file == null) throw new IllegalArgumentException("production_missing_program");
        NetworkHooks.openScreen(player, new SimpleMenuProvider((menu, inventory, owner) ->
                new ProductionPortMenu(menu, inventory, terminal.getBlockPos(), terminal, id, folder, page, pageSize),
                Component.literal(file.name())), buffer -> {
            buffer.writeBlockPos(terminal.getBlockPos()); buffer.writeVarInt(id); buffer.writeVarInt(folder); buffer.writeVarInt(page);
        });
    }
    public static void back(ServerPlayer player, StorageBlockEntity terminal, int folder, int page, int pageSize) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, owner) -> {
            StorageMenu menu = new StorageMenu(id, inventory, terminal.getBlockPos(), terminal);
            menu.restoreLocation(folder, page, pageSize); return menu;
        }, terminal.getDisplayName()), terminal.getBlockPos());
    }
    /** Folders, program files, then materials share one bounded page. Files never change item quantities. */
    public static CompoundTag directoryView(StorageBlockEntity terminal, int current, int requestedPage, int requestedSize, String message) {
        StorageInventory inventory = terminal.inventory();
        int size = Math.max(StorageInventory.PAGE_SIZE, Math.min(StorageInventory.MAX_PAGE_SIZE, requestedSize));
        var folders = inventory.folders().stream().filter(folder -> folder.parent() == current).toList();
        var files = terminal.programs().files().stream().filter(file -> file.folder() == current).toList();
        var entries = inventory.entries().stream().filter(entry -> entry.folder() == current).toList();
        int total = folders.size() + files.size() + entries.size(), pages = Math.max(1, (total + size - 1) / size);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        CompoundTag view = inventory.view(current, 0, size, message); view.putInt("Page", page); view.putInt("Pages", pages);
        ListTag fileTags = new ListTag(), itemTags = new ListTag(); java.util.ArrayList<Integer> folderIds = new java.util.ArrayList<>();
        for (int i = page * size; i < Math.min(total, (page + 1) * size); i++) {
            if (i < folders.size()) folderIds.add(folders.get(i).id());
            else if (i < folders.size() + files.size()) {
                ProgramFile file = files.get(i - folders.size()); CompoundTag node = new CompoundTag();
                node.putInt("Id", file.id()); node.putInt("Folder", file.folder()); node.putString("Name", file.name());
                node.putBoolean("Configured", file.configured()); node.putBoolean("Active", file.active());
                node.putString("Machine", file.configured() ? file.definition().kind() : "");
                node.putString("Status", status(terminal, file)); fileTags.add(node);
            } else {
                var entry = entries.get(i - folders.size() - files.size()); CompoundTag node = new CompoundTag();
                node.putInt("Id", entry.id()); node.putInt("Folder", entry.folder()); node.putLong("Count", entry.count());
                node.put("Stack", entry.stack().save(new CompoundTag())); itemTags.add(node);
            }
        }
        view.putIntArray("PageFolders", folderIds.stream().mapToInt(Integer::intValue).toArray());
        view.put("ProgramFiles", fileTags); view.put("Entries", itemTags); return view;
    }
}
