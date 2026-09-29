package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskSavedData;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageAccess;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class StorageMenu extends AbstractContainerMenu {
    public static final int WIDTH = 320, HEIGHT = 234;
    private final BlockPos pos;
    private final StorageBlockEntity blockEntity;
    private final Player player;
    private int currentFolder, page, pageSize = StorageInventory.PAGE_SIZE;
    private String volume = "", mountStamp = "local", message = "";
    private long session = MenuSession.next(), actionTick = -1;
    private int actionsThisTick;
    private boolean closed;
    private CompoundTag clientView = new CompoundTag(), sentView;
    private long sentRevision = -2, sentNasRevision = -1, sentSession;
    private String sentNasStamp = "";

    public StorageMenu(int id, Inventory inventory, FriendlyByteBuf data) { this(id, inventory, data.readBlockPos(), null); }
    public StorageMenu(int id, Inventory inventory, BlockPos pos, StorageBlockEntity blockEntity) {
        super(ModContent.STORAGE_MENU.get(), id);
        this.pos = pos; this.blockEntity = blockEntity; this.player = inventory.player;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, 9 + row * 9 + col, 79 + col * 18, 154 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 79 + col * 18, 212));
    }
    public CompoundTag view() { return clientView; }
    public void acceptView(CompoundTag view) { clientView = view.copy(); }
    public int currentFolder() { return currentFolder; }
    public long session() { refreshMount(); return session; }
    public void arrangeClientSlots(int x, int y) {
        if (blockEntity != null) return;
        for (int i = 0; i < slots.size(); i++) {
            Slot previous = slots.get(i);
            Slot replacement = new Slot(previous.container, previous.getContainerSlot(),
                    x + (i % 9) * 18, y + (i < 27 ? i / 9 * 18 : 58));
            replacement.index = previous.index; slots.set(i, replacement);
        }
    }
    @Override public boolean stillValid(Player player) {
        if (blockEntity == null) return player.level().isClientSide;
        return !closed && player == this.player && player.containerMenu == this
                && player.level() == blockEntity.getLevel() && !player.isSpectator() && !blockEntity.isRemoved()
                && player.level().hasChunkAt(pos) && player.level().getBlockEntity(pos) == blockEntity
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    private NasBlockEntity nas() {
        if (blockEntity == null || blockEntity.isRemoved()) return null;
        BlockPos back = pos.relative(blockEntity.getBlockState().getValue(HorizontalDirectionalBlock.FACING).getOpposite());
        return player.level().hasChunkAt(back) && player.level().getBlockEntity(back) instanceof NasBlockEntity nas && !nas.isRemoved() ? nas : null;
    }
    private int bay(NasBlockEntity nas) {
        if (nas != null && !volume.isEmpty()) for (int i = 0; i < NasBlockEntity.BAYS; i++)
            if (volume.equals(String.valueOf(DiskItem.id(nas.disk(i))))) return i;
        return -1;
    }
    /** Resolve for every operation: callers never retain a removed disk's inventory handle. */
    private StorageAccess storage() {
        if (volume.isEmpty()) return blockEntity.inventory();
        NasBlockEntity nas = nas(); int bay = bay(nas);
        DiskSavedData disk = bay < 0 ? null : nas.volume(bay);
        return disk == null ? null : disk.inventory();
    }
    private void refreshMount() {
        if (blockEntity == null) return;
        NasBlockEntity nas = nas(); int bay = bay(nas);
        String stamp = volume.isEmpty() ? "local" : bay < 0 || nas.volume(bay) == null ? "offline:" + volume : nas.stamp(bay);
        if (!stamp.equals(mountStamp)) {
            mountStamp = stamp; session = MenuSession.next(); currentFolder = page = 0;
        }
    }
    public CompoundTag snapshot() {
        refreshMount();
        StorageAccess storage = storage();
        if (storage != null && !storage.hasFolder(currentFolder)) { currentFolder = 0; page = 0; }
        CompoundTag view;
        if (storage == null) {
            view = new CompoundTag(); view.putInt("Current", 0); view.putInt("Pages", 1); view.putLong("Revision", -1); view.putInt("PageSize", pageSize);
            view.putString("Message", message.isEmpty() ? "disk_offline" : message);
        } else view = storage.view(currentFolder, page, pageSize, message);
        page = view.getInt("Page");
        view.putBoolean("Available", storage != null); view.putLong("Session", session); view.putString("Volume", volume);
        ListTag volumes = new ListTag();
        CompoundTag local = new CompoundTag(); local.putString("Id", ""); local.putBoolean("Online", true); volumes.add(local);
        NasBlockEntity nas = nas(); boolean found = volume.isEmpty();
        if (nas != null) for (int i = 0; i < NasBlockEntity.BAYS; i++) {
            ItemStack item = nas.disk(i);
            if (item.isEmpty() || DiskItem.id(item) == null) continue;
            CompoundTag disk = new CompoundTag(); String id = DiskItem.id(item).toString();
            DiskSavedData data = nas.volume(i);
            disk.putString("Id", id); disk.putString("Name", data == null ? item.hasCustomHoverName() ? item.getHoverName().getString() : "" : data.name());
            disk.putString("Tier", ((DiskItem) item.getItem()).tier().id());
            disk.putInt("Bay", i + 1); disk.putBoolean("Online", data != null && !data.isLocked()); volumes.add(disk);
            found |= volume.equals(id);
        }
        if (!found) { CompoundTag missing = new CompoundTag(); missing.putString("Id", volume); missing.putBoolean("Online", false); volumes.add(missing); }
        view.put("Volumes", volumes);
        return view;
    }
    private void sync(boolean force) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) return;
        refreshMount(); StorageAccess storage = storage(); NasBlockEntity nas = nas();
        long revision = storage == null ? -1 : storage.revision(), nasRevision = nas == null ? -1 : nas.revision();
        String nasStamp = nas == null ? "" : nas.stamp(0);
        if (!force && revision == sentRevision && nasRevision == sentNasRevision && session == sentSession && nasStamp.equals(sentNasStamp)) return;
        CompoundTag view = snapshot();
        if (force || !view.equals(sentView)) { StorageNetwork.snapshot(serverPlayer, containerId, view); sentView = view; }
        sentRevision = revision; sentNasRevision = nasRevision; sentSession = session; sentNasStamp = nasStamp;
        message = "";
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (blockEntity != null && stillValid(player)) sync(false);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        // Terminal shift deposits use the contextual protocol, including disk and mount session.
        return ItemStack.EMPTY;
    }
    private void depositSlot(StorageAccess storage, int index) {
        if (index < 0 || index >= slots.size()) throw new IllegalArgumentException("missing_item");
        Slot slot = slots.get(index); ItemStack source = slot.getItem();
        int count = storage.insert(source, source.getCount(), currentFolder);
        if (count == 0) { message = "full"; return; }
        source.shrink(count); slot.setChanged(); message = "deposited";
    }
    public void handle(StorageNetwork.Request request) {
        if (blockEntity == null || request.menuId() != containerId || !stillValid(player)) return;
        long tick = player.level().getGameTime();
        if (tick != actionTick) { actionTick = tick; actionsThisTick = 0; }
        if (++actionsThisTick > 10) return;
        refreshMount();
        if (request.session() != session) { message = "stale"; sync(true); return; }
        StorageAccess storage = storage();
        boolean navigation = request.action() == StorageNetwork.Action.OPEN || request.action() == StorageNetwork.Action.PAGE
                || request.action() == StorageNetwork.Action.RESIZE || request.action() == StorageNetwork.Action.SELECT_VOLUME;
        if (!navigation && storage != null && storage.isLocked()) { message = "storage_locked"; sync(true); return; }
        if (!navigation && storage != null && request.revision() != storage.revision()) { message = "stale"; sync(true); return; }
        long amount = Math.max(0, request.amount()); message = "";
        try {
            if (request.action() == StorageNetwork.Action.SELECT_VOLUME) {
                String previous = volume; volume = request.name();
                if (!volume.isEmpty() && bay(nas()) < 0) { volume = previous; throw new IllegalArgumentException("disk_offline"); }
                session = MenuSession.next(); currentFolder = page = 0; refreshMount();
            } else if (request.action() == StorageNetwork.Action.RESIZE) {
                pageSize = (int) Math.max(StorageInventory.PAGE_SIZE, Math.min(StorageInventory.MAX_PAGE_SIZE, amount)); page = 0;
            } else {
                if (storage == null) throw new IllegalArgumentException("disk_offline");
                if (!storage.hasFolder(currentFolder)) currentFolder = 0;
                switch (request.action()) {
                    case OPEN -> { if (!storage.hasFolder(request.id())) throw new IllegalArgumentException("invalid_folder"); currentFolder = request.id(); page = 0; }
                    case PAGE -> page = (int) Math.min(1000, amount);
                    case CREATE -> { currentFolder = storage.createFolder(currentFolder, request.name()); page = 0; message = "created"; }
                    case RENAME -> { storage.renameFolder(currentFolder, request.name()); message = "renamed"; }
                    case RENAME_DISK -> {
                        NasBlockEntity nas = nas(); int bay = bay(nas);
                        if (currentFolder != 0 || bay < 0) throw new IllegalArgumentException("invalid_folder");
                        nas.rename(bay, request.name()); message = "renamed";
                    }
                    case DELETE -> { currentFolder = storage.deleteFolder(currentFolder); page = 0; message = "deleted"; }
                    case MOVE -> { requireVisibleEntry(storage, request.id()); message = storage.move(request.id(), request.target(), amount) > 0 ? "moved" : "no_change"; }
                    case WITHDRAW -> { requireVisibleEntry(storage, request.id()); message = StorageTransfers.withdraw(storage, player.getInventory(), request.id(), amount) > 0 ? "withdrawn" : "inventory_full"; }
                    case DEPOSIT_CURSOR -> {
                        ItemStack carried = getCarried(); int count = storage.insert(carried, carried.getCount(), currentFolder);
                        if (count > 0) { carried.shrink(count); setCarried(carried); message = "deposited"; } else message = "full";
                    }
                    case DEPOSIT_SLOT -> depositSlot(storage, request.id());
                    default -> { }
                }
            }
        } catch (IllegalArgumentException e) { message = e.getMessage(); }
        sync(true); super.broadcastChanges();
    }
    private void requireVisibleEntry(StorageAccess storage, int id) {
        StorageInventory.Entry entry = storage.entry(id);
        if (entry == null || entry.folder() != currentFolder) throw new IllegalArgumentException("missing_item");
    }
    @Override public void removed(Player player) { closed = true; super.removed(player); }
}
