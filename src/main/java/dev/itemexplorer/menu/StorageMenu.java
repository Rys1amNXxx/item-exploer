package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class StorageMenu extends AbstractContainerMenu {
    public static final int WIDTH = 320;
    public static final int HEIGHT = 234;
    private final BlockPos pos;
    private final StorageBlockEntity blockEntity;
    private final Player player;
    private int currentFolder;
    private int page;
    private long sentRevision = -1;
    private String message = "";
    private long actionTick = -1;
    private int actionsThisTick;
    private CompoundTag clientView = new CompoundTag();

    public StorageMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, data.readBlockPos(), null);
    }

    public StorageMenu(int id, Inventory inventory, BlockPos pos, StorageBlockEntity blockEntity) {
        super(ModContent.STORAGE_MENU.get(), id);
        this.pos = pos;
        this.blockEntity = blockEntity;
        this.player = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, 9 + row * 9 + col, 79 + col * 18, 154 + row * 18));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 79 + col * 18, 212));
    }

    public CompoundTag view() { return clientView; }
    public void acceptView(CompoundTag view) { clientView = view.copy(); }
    public int currentFolder() { return currentFolder; }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return player.level().isClientSide;
        return !player.isSpectator() && !blockEntity.isRemoved()
                && player.level().getBlockEntity(pos) == blockEntity
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }

    private StorageInventory storage() { return blockEntity.inventory(); }

    private void sync() {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) return;
        if (!storage().hasFolder(currentFolder)) { currentFolder = 0; page = 0; }
        CompoundTag view = storage().view(currentFolder, page, message);
        page = view.getInt("Page");
        StorageNetwork.snapshot(serverPlayer, containerId, view);
        sentRevision = storage().revision();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (blockEntity != null && sentRevision != storage().revision()) sync();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || blockEntity == null || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        if (!storage().hasFolder(currentFolder)) currentFolder = 0;
        Slot slot = slots.get(index);
        ItemStack source = slot.getItem();
        ItemStack original = source.copy();
        try {
            int count = storage().insert(source, source.getCount(), currentFolder);
            if (count == 0) { message = "full"; sync(); return ItemStack.EMPTY; }
            source.shrink(count);
            slot.setChanged();
            message = "deposited";
            sync();
            return original;
        } catch (IllegalArgumentException e) {
            message = e.getMessage(); sync(); return ItemStack.EMPTY;
        }
    }

    public void handle(StorageNetwork.Request request) {
        if (blockEntity == null || request.menuId() != containerId || !stillValid(player)) return;
        long tick = player.level().getGameTime();
        if (tick != actionTick) { actionTick = tick; actionsThisTick = 0; }
        if (++actionsThisTick > 10) return;
        boolean navigation = request.action() == StorageNetwork.Action.OPEN || request.action() == StorageNetwork.Action.PAGE;
        if (!navigation && request.revision() != storage().revision()) { message = "stale"; sync(); return; }
        if (!storage().hasFolder(currentFolder)) currentFolder = 0;
        int amount = Math.max(0, Math.min(StorageInventory.CAPACITY, request.amount()));
        message = "";
        try {
            switch (request.action()) {
                case OPEN -> {
                    if (!storage().hasFolder(request.id())) throw new IllegalArgumentException("invalid_folder");
                    currentFolder = request.id(); page = 0;
                }
                case PAGE -> page = Math.max(0, Math.min(1000, request.amount()));
                case CREATE -> { currentFolder = storage().createFolder(currentFolder, request.name()); page = 0; message = "created"; }
                case RENAME -> { storage().renameFolder(currentFolder, request.name()); message = "renamed"; }
                case DELETE -> { currentFolder = storage().deleteFolder(currentFolder); page = 0; message = "deleted"; }
                case MOVE -> {
                    requireVisibleEntry(request.id());
                    message = storage().move(request.id(), request.target(), amount) > 0 ? "moved" : "no_change";
                }
                case WITHDRAW -> {
                    requireVisibleEntry(request.id());
                    message = StorageTransfers.withdraw(storage(), player.getInventory(), request.id(), amount) > 0 ? "withdrawn" : "inventory_full";
                }
                case DEPOSIT_CURSOR -> {
                    ItemStack carried = getCarried();
                    int count = storage().insert(carried, carried.getCount(), currentFolder);
                    if (count > 0) { carried.shrink(count); setCarried(carried); message = "deposited"; }
                    else message = "full";
                }
            }
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        sync();
        super.broadcastChanges();
    }

    private void requireVisibleEntry(int id) {
        StorageInventory.Entry entry = storage().entry(id);
        if (entry == null || entry.folder() != currentFolder) throw new IllegalArgumentException("missing_item");
    }
}
