package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.production.ProgramFile;
import dev.itemexplorer.production.TerminalPrograms;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** A program document editor authorized at the terminal, independent of machine distance. */
public final class ProductionPortMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final StorageBlockEntity terminal;
    private final Player player;
    private final int fileId, returnFolder, returnPage, returnPageSize;
    private final long session = MenuSession.next();
    private long context = MenuSession.next(), tick = -1, selectedMachine = Long.MIN_VALUE;
    private int requests;
    private boolean closed;
    private String topology = "", message = "";
    private CompoundTag clientView = new CompoundTag(), sentView;

    public ProductionPortMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, data.readBlockPos(), null, data.readVarInt(), data.readVarInt(), data.readVarInt());
    }
    public ProductionPortMenu(int id, Inventory inventory, BlockPos pos, StorageBlockEntity terminal,
                              int fileId, int returnFolder, int returnPage) {
        this(id, inventory, pos, terminal, fileId, returnFolder, returnPage, dev.itemexplorer.storage.StorageInventory.PAGE_SIZE);
    }
    public ProductionPortMenu(int id, Inventory inventory, BlockPos pos, StorageBlockEntity terminal,
                              int fileId, int returnFolder, int returnPage, int returnPageSize) {
        super(ModContent.PRODUCTION_MENU.get(), id);
        this.pos = pos.immutable(); this.terminal = terminal; player = inventory.player;
        this.fileId = fileId; this.returnFolder = returnFolder; this.returnPage = returnPage;
        this.returnPageSize = returnPageSize;
        if (terminal != null) {
            ProgramFile file = terminal.programs().file(fileId);
            if (file != null && file.configured()) selectedMachine = file.portPos().asLong();
        }
    }
    public CompoundTag view() { return clientView; }
    public void acceptView(CompoundTag view) { clientView = view.copy(); }
    @Override public boolean stillValid(Player player) {
        if (terminal == null) return player.level().isClientSide;
        return !closed && this.player == player && player.containerMenu == this && player.level() == terminal.getLevel()
                && !player.isSpectator() && !terminal.isRemoved() && player.level().hasChunkAt(pos)
                && player.level().getBlockEntity(pos) == terminal && terminal.programs().file(fileId) != null
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    private void refreshContext() {
        TerminalPrograms.reconcile(terminal);
        String next = TerminalPrograms.topologyStamp(terminal);
        if (!topology.equals(next)) { topology = next; context = MenuSession.next(); }
    }
    public CompoundTag snapshot() {
        refreshContext();
        ProgramFile file = terminal.programs().file(fileId);
        CompoundTag view = file != null && file.configured() ? file.definition().save() : new CompoundTag();
        var machines = TerminalPrograms.discover(terminal); ListTag machineTags = new ListTag();
        if (selectedMachine == Long.MIN_VALUE && !machines.ports().isEmpty()) selectedMachine = machines.ports().get(0).getBlockPos().asLong();
        ProductionPortBlockEntity selectedPort = null;
        for (var port : machines.ports()) {
            CompoundTag machine = new CompoundTag(); long position = port.getBlockPos().asLong();
            machine.putLong("Pos", position); machine.putString("Id", port.identity().toString());
            machine.putString("Type", port.machineType()); machine.putBoolean("Connected", true);
            machine.putString("Label", "block.minecraft." + (port.machineType().equals("brewing") ? "brewing_stand" : port.machineType()));
            machine.putString("Coordinates", port.machinePosition().toShortString()); machineTags.add(machine);
            if (position == selectedMachine) selectedPort = port;
        }
        view.put("Machines", machineTags); view.putLong("MachinePos", selectedMachine);
        view.putString("MachineType", selectedPort == null ? file != null && file.configured() ? file.definition().kind() : "" : selectedPort.machineType());
        view.putBoolean("MachineOnline", selectedPort != null);
        if (file != null && file.configured()) view.putLong("SavedMachinePos", file.portPos().asLong());
        view.putString("Name", file == null ? "" : file.name()); view.putInt("ProgramId", fileId);
        view.putBoolean("Configured", file != null && file.configured());
        view.putBoolean("Running", file != null && file.active()); view.putBoolean("Active", file != null && file.active());
        view.putBoolean("Locked", terminal.inventory().isLocked() || terminal.programs().isLocked());
        view.putInt("Completed", 0); view.putInt("Count", 1);
        view.putString("Status", file == null ? "production_missing_program" : TerminalPrograms.status(terminal, file));
        if (file != null && file.configured()) {
            var runtime = TerminalPrograms.loadedPort(terminal, file);
            if (runtime != null && runtime.ownsProgram(terminal.productionIdentity(), fileId)
                    && (!file.active() || runtime.ownsRun(terminal.productionIdentity(), fileId, file.runIdentity()))) {
                CompoundTag state = runtime.runtimeView(); view.putInt("Completed", state.getInt("Completed")); view.putInt("Count", state.getInt("Count"));
            }
        }
        if (selectedPort != null) {
            CompoundTag choices = selectedPort.view();
            view.put("Folders", choices.getList("Folders", Tag.TAG_COMPOUND).copy());
            view.put("Entries", choices.getList("Entries", Tag.TAG_COMPOUND).copy());
        } else {
            view.put("Folders", new ListTag()); view.put("Entries", new ListTag());
            if (!machines.problem().isEmpty()) view.putString("Status", machines.problem());
        }
        view.putLong("Revision", terminal.programs().revision()); view.putLong("Session", session);
        view.putLong("Context", context); view.putString("Message", message);
        return view;
    }
    private void sync(boolean force) {
        if (terminal == null || !(player instanceof ServerPlayer server)) return;
        CompoundTag view = snapshot();
        if (force || !view.equals(sentView)) { StorageNetwork.snapshot(server, containerId, view); sentView = view; }
        message = "";
    }
    @Override public void broadcastChanges() { super.broadcastChanges(); if (terminal != null && stillValid(player)) sync(false); }
    public void handle(StorageNetwork.ProductionRequest request) {
        if (terminal == null || request.menuId() != containerId || !stillValid(player)) return;
        long now = player.level().getGameTime(); if (now != tick) { tick = now; requests = 0; }
        if (++requests > 10 || request.session() != session) return;
        if (request.action() == StorageNetwork.ProductionAction.BACK) {
            TerminalPrograms.back((ServerPlayer) player, terminal, returnFolder, returnPage, returnPageSize); return;
        }
        refreshContext();
        if (request.context() != context || request.revision() != terminal.programs().revision()) {
            message = "production_stale"; sync(true); return;
        }
        try {
            if (terminal.programs().isLocked() || terminal.inventory().isLocked()) throw new IllegalArgumentException("production_storage_locked");
            ProgramFile file = terminal.programs().file(fileId);
            if (file == null) throw new IllegalArgumentException("production_missing_program");
            switch (request.action()) {
                case SELECT_MACHINE -> {
                    if (file.active()) throw new IllegalArgumentException("production_running");
                    TerminalPrograms.reachable(terminal, request.machinePos());
                    selectedMachine = request.machinePos(); context = MenuSession.next();
                }
                case SAVE -> {
                    if (request.machinePos() != selectedMachine) throw new IllegalArgumentException("production_stale");
                    if (file.active()) throw new IllegalArgumentException("production_running");
                    var port = TerminalPrograms.reachable(terminal, selectedMachine);
                    var definition = port.createDefinition(request.name(), request.inputFolder(), request.fuelFolder(), request.outputFolder(), request.inputEntry(), request.fuelEntry());
                    terminal.programs().rename(fileId, request.name());
                    terminal.programs().configure(fileId, definition, port.getBlockPos(), port.identity()); message = "production_saved";
                }
                case START -> {
                    if (!file.configured()) throw new IllegalArgumentException("production_unconfigured");
                    var port = TerminalPrograms.reachable(terminal, file.portPos().asLong());
                    if (!port.identity().equals(file.portIdentity())) throw new IllegalArgumentException("production_machine_changed");
                    port.startProgram(terminal, fileId, file.definition(), request.count()); message = "production_started";
                }
                case CANCEL -> { TerminalPrograms.cancel(terminal, file); message = "production_cancelled"; }
                default -> { }
            }
        } catch (IllegalArgumentException rejected) { message = rejected.getMessage(); }
        sync(true);
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { closed = true; super.removed(player); }
}
