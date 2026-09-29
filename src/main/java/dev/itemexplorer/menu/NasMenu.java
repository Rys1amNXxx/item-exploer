package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Virtual bays make insertion/ejection explicit; only real player inventory uses vanilla slots. */
public final class NasMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final NasBlockEntity nas;
    private final Player player;
    private final long session = MenuSession.next();
    private boolean closed;
    private long actionTick = -1;
    private int actions;
    private String message = "";
    private CompoundTag clientView = new CompoundTag(), sentView;
    public NasMenu(int id, Inventory inventory, FriendlyByteBuf data) { this(id, inventory, data.readBlockPos(), null); }
    public NasMenu(int id, Inventory inventory, BlockPos pos, NasBlockEntity nas) {
        super(ModContent.NAS_MENU.get(), id); this.pos = pos; this.nas = nas; this.player = inventory.player;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, 9 + row * 9 + col, 61 + col * 18, 152 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 61 + col * 18, 210));
    }
    public long session() { return session; }
    public CompoundTag view() { return clientView; }
    public void acceptView(CompoundTag view) { clientView = view.copy(); }
    @Override public boolean stillValid(Player player) {
        if (nas == null) return player.level().isClientSide;
        return !closed && this.player == player && player.containerMenu == this && player.level() == nas.getLevel()
                && !player.isSpectator() && !nas.isRemoved() && player.level().hasChunkAt(pos)
                && player.level().getBlockEntity(pos) == nas && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    private void sync(boolean force) {
        if (!(player instanceof ServerPlayer serverPlayer) || nas == null) return;
        CompoundTag view = nas.view(); view.putLong("Session", session); view.putString("Message", message);
        if (force || !view.equals(sentView)) { StorageNetwork.snapshot(serverPlayer, containerId, view); sentView = view; }
        message = "";
    }
    @Override public void broadcastChanges() { super.broadcastChanges(); if (nas != null && stillValid(player)) sync(false); }
    public void handle(StorageNetwork.NasRequest request) {
        if (nas == null || request.menuId() != containerId || !stillValid(player)) return;
        long tick = player.level().getGameTime(); if (tick != actionTick) { actionTick = tick; actions = 0; }
        if (++actions > 10) return;
        if (request.session() != session || request.revision() != nas.revision()) { message = "stale"; sync(true); return; }
        try {
            if (request.eject()) {
                int free = player.getInventory().getFreeSlot();
                if (free < 0) throw new IllegalArgumentException("inventory_full");
                ItemStack disk = nas.eject(request.bay());
                if (!disk.isEmpty()) { player.getInventory().setItem(free, disk); player.getInventory().setChanged(); }
            } else {
                ItemStack disk = getCarried(); nas.install(request.bay(), disk); disk.shrink(1); setCarried(disk);
            }
        } catch (IllegalArgumentException e) { message = e.getMessage(); }
        sync(true); super.broadcastChanges();
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (nas == null || !stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index); ItemStack source = slot.getItem(), original = source.copy();
        try {
            int bay = 0; while (bay < NasBlockEntity.BAYS && !nas.disk(bay).isEmpty()) bay++;
            if (bay == NasBlockEntity.BAYS) throw new IllegalArgumentException("nas_full");
            nas.install(bay, source); source.shrink(1); slot.setChanged(); sync(true); return original;
        } catch (IllegalArgumentException e) { message = e.getMessage(); sync(true); return ItemStack.EMPTY; }
    }
    @Override public void removed(Player player) { closed = true; super.removed(player); }
}
