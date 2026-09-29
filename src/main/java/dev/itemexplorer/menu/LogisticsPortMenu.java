package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Configuration only: no inventory slots and no item transfer through client requests. */
public final class LogisticsPortMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final LogisticsPortBlockEntity port;
    private final Player player;
    private final long session = MenuSession.next();
    private long context = MenuSession.next(), tick = -1;
    private int requests;
    private boolean closed;
    private String topology = "", selected = "", message = "";
    private CompoundTag clientView = new CompoundTag(), sentView;
    public LogisticsPortMenu(int id, Inventory inventory, FriendlyByteBuf data) { this(id, inventory, data.readBlockPos(), null); }
    public LogisticsPortMenu(int id, Inventory inventory, BlockPos pos, LogisticsPortBlockEntity port) {
        super(ModContent.LOGISTICS_MENU.get(), id); this.pos = pos; this.port = port; player = inventory.player;
        if (port != null) selected = port.volume();
    }
    public CompoundTag view() { return clientView; }
    public void acceptView(CompoundTag view) { clientView = view.copy(); }
    @Override public boolean stillValid(Player player) {
        if (port == null) return player.level().isClientSide;
        return !closed && this.player == player && player.containerMenu == this && player.level() == port.getLevel()
                && !player.isSpectator() && !port.isRemoved() && player.level().hasChunkAt(pos) && player.level().getBlockEntity(pos) == port
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    private void refreshContext() {
        String next = port.topologyStamp();
        if (!topology.equals(next)) { topology = next; context = MenuSession.next(); }
    }
    public CompoundTag snapshot() {
        refreshContext();
        CompoundTag view = port.view(selected);
        var volumes = view.getList("Volumes", Tag.TAG_COMPOUND);
        if (selected.isEmpty() && !volumes.isEmpty()) { selected = volumes.getCompound(0).getString("Id"); view = port.view(selected); }
        view.putLong("Session", session); view.putLong("Context", context); view.putString("Message", message); return view;
    }
    private void sync(boolean force) {
        if (port == null || !(player instanceof ServerPlayer server)) return;
        CompoundTag view = snapshot();
        if (force || !view.equals(sentView)) { StorageNetwork.snapshot(server, containerId, view); sentView = view; }
        message = "";
    }
    @Override public void broadcastChanges() { super.broadcastChanges(); if (port != null && stillValid(player)) sync(false); }
    public void handle(StorageNetwork.PortRequest request) {
        if (port == null || request.menuId() != containerId || !stillValid(player)) return;
        long now = player.level().getGameTime(); if (now != tick) { tick = now; requests = 0; }
        if (++requests > 10) return;
        refreshContext();
        if (request.session() != session || request.context() != context || request.revision() != port.revision()) { message = "stale"; sync(true); return; }
        try {
            switch (request.action()) {
                case SELECT -> {
                    var target = port.resolveVolume(request.volume());
                    if (target == null) throw new IllegalArgumentException("disk_offline");
                    selected = request.volume();
                }
                case APPLY -> {
                    if (!request.volume().equals(selected)) throw new IllegalArgumentException("stale");
                    port.configure(selected, request.folder(), request.input(), request.output(), request.recursive()); message = "port_saved";
                }
                case DISCONNECT -> { port.disconnect(); message = "port_disconnected"; }
            }
        } catch (IllegalArgumentException rejected) { message = rejected.getMessage(); }
        sync(true);
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { closed = true; super.removed(player); }
}
