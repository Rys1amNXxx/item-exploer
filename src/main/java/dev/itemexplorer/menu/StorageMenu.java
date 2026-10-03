package dev.itemexplorer.menu;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.cable.CableStorageAccess;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.disk.DiskSavedData;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageAccess;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageTransfers;
import dev.itemexplorer.storage.SearchCatalogSupport;
import dev.itemexplorer.production.TerminalPrograms;
import dev.itemexplorer.production.ProgramFile;
import dev.itemexplorer.transfer.RemoteTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StorageMenu extends AbstractContainerMenu {
    public static final int WIDTH = 320, HEIGHT = 234;
    // Keep the existing player slot IDs stable for contextual storage requests.
    public static final int PLAYER_SLOT_COUNT = 36, CRAFT_RESULT_SLOT = 36,
            CRAFT_GRID_START = 37, CRAFT_GRID_END = 46;
    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, 3, 3);
    private final ResultContainer craftResult = new ResultContainer();
    private final BlockPos pos;
    private final StorageBlockEntity blockEntity;
    private final Player player;
    private final CableConnectionData cable = new CableConnectionData();
    public CableConnectionData cable() { return cable; }
    private int currentFolder, page, pageSize = StorageInventory.PAGE_SIZE;
    private String volume = "", mountStamp = "local", message = "";
    private long session = MenuSession.next(), actionTick = -1;
    private int actionsThisTick;
    private boolean closed;
    private CompoundTag clientView = new CompoundTag(), sentView;
    private long sentRevision = -2, sentSession;
    private long sentProgramRevision = -2, nextProgramRefresh;
    private String sentNasStamp = "";
    private long sentTransferConfigRevision = -1;
    private ListTag transferTargets = new ListTag();
    private CableStorageAccess.Result nasAccess = new CableStorageAccess.Result(List.of(), 0, "checking");
    private long nextTopologyCheck = Long.MIN_VALUE;
    private boolean searching, searchReady, searchRecursive;
    private int searchRoot, savedFolder, savedPage, savedPageSize, located = -1;
    private long querySeq, resultViewSeq, catalogRevision = -1, catalogSentTick = -1;
    private long publishedRevision = -1;
    private Set<Integer> searchMatches = Set.of(), publishedSearchEntries = Set.of();
    private final Map<Integer, CompoundTag> sentCatalog = new LinkedHashMap<>();

    public StorageMenu(int id, Inventory inventory, FriendlyByteBuf data) { this(id, inventory, data.readBlockPos(), null); }
    public StorageMenu(int id, Inventory inventory, BlockPos pos, StorageBlockEntity blockEntity) {
        super(ModContent.STORAGE_MENU.get(), id);
        this.pos = pos; this.blockEntity = blockEntity; this.player = inventory.player;
        if (blockEntity != null) cable.refresh(player.level(), pos, false);
        addDataSlots(cable);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, 9 + row * 9 + col, 79 + col * 18, 154 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 79 + col * 18, 212));
        StorageLayout layout = new StorageLayout(WIDTH, HEIGHT);
        addSlot(new ResultSlot(player, craftSlots, craftResult, 0, layout.craftingResultX(), layout.craftingResultY()));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            addSlot(new Slot(craftSlots, row * 3 + col, layout.craftingX() + col * 18, layout.craftingY() + row * 18));
    }
    public CompoundTag view() { return clientView; }
    public void acceptView(CompoundTag view) { clientView = view.copy(); }
    public int currentFolder() { return currentFolder; }
    public void restoreLocation(int folder, int requestedPage) {
        restoreLocation(folder, requestedPage, StorageInventory.PAGE_SIZE);
    }
    public void restoreLocation(int folder, int requestedPage, int requestedSize) {
        if (blockEntity != null && blockEntity.inventory().hasFolder(folder)) {
            currentFolder = folder; page = Math.max(0, requestedPage);
            pageSize = Math.max(StorageInventory.PAGE_SIZE, Math.min(StorageInventory.MAX_PAGE_SIZE, requestedSize));
        }
    }
    public long session() { refreshMount(); return session; }
    public void arrangeClientSlots(int x, int y) {
        if (blockEntity != null) return;
        for (int i = 0; i < PLAYER_SLOT_COUNT; i++) {
            Slot previous = slots.get(i);
            Slot replacement = new Slot(previous.container, previous.getContainerSlot(),
                    x + (i % 9) * 18, y + (i < 27 ? i / 9 * 18 : 58));
            replacement.index = previous.index; slots.set(i, replacement);
        }
    }
    public void arrangeClientSlots(StorageLayout layout) {
        if (blockEntity != null) return;
        arrangeClientSlots(layout.inventoryX(), layout.inventoryY());
        replaceClientSlot(CRAFT_RESULT_SLOT, new ResultSlot(player, craftSlots, craftResult, 0,
                layout.craftingResultX(), layout.craftingResultY()));
        for (int i = 0; i < 9; i++) replaceClientSlot(CRAFT_GRID_START + i,
                new Slot(craftSlots, i, layout.craftingX() + i % 3 * 18, layout.craftingY() + i / 3 * 18));
    }
    private void replaceClientSlot(int index, Slot slot) {
        slot.index = index;
        slots.set(index, slot);
    }
    @Override public void slotsChanged(Container container) {
        if (closed || !(player instanceof ServerPlayer serverPlayer)) return;
        ItemStack result = ItemStack.EMPTY;
        var recipe = player.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftSlots, player.level());
        if (recipe.isPresent() && craftResult.setRecipeUsed(player.level(), serverPlayer, recipe.get())) {
            ItemStack assembled = recipe.get().assemble(craftSlots, player.level().registryAccess());
            if (assembled.isItemEnabled(player.level().enabledFeatures())) result = assembled;
        }
        // Vanilla CraftingMenu hardcodes result slot 0; this menu appends its result after the player slots.
        craftResult.setItem(0, result);
        setRemoteSlot(CRAFT_RESULT_SLOT, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), CRAFT_RESULT_SLOT, result));
    }
    @Override public void clicked(int slotId, int button, ClickType type, Player player) {
        if (closed || player != this.player || (!player.level().isClientSide && !stillValid(player))) return;
        if (slotId >= slots.size() || (slotId < 0 && slotId != -1 && slotId != -999)) return;
        super.clicked(slotId, button, type, player);
    }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != craftResult && super.canTakeItemForPickAll(stack, slot);
    }
    @Override public boolean stillValid(Player player) {
        if (blockEntity == null) return player.level().isClientSide;
        return !closed && player == this.player && player.containerMenu == this
                && player.level() == blockEntity.getLevel() && !player.isSpectator() && !blockEntity.isRemoved()
                && player.level().hasChunkAt(pos) && player.level().getBlockEntity(pos) == blockEntity
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    private NasBlockEntity nas() {
        NasBlockEntity offline = null;
        for (NasBlockEntity nas : nasAccess.cabinets()) {
            int slot = bay(nas);
            if (slot < 0) continue;
            if (nas.volume(slot) != null) return nas;
            if (offline == null) offline = nas;
        }
        return offline;
    }
    private int bay(NasBlockEntity nas) {
        int offline = -1;
        if (nas != null && !volume.isEmpty()) for (int i = 0; i < NasBlockEntity.BAYS; i++)
            if (volume.equals(String.valueOf(DiskItem.id(nas.disk(i))))) {
                if (nas.volume(i) != null) return i;
                if (offline < 0) offline = i;
            }
        return offline;
    }
    /** Resolve for every operation: callers never retain a removed disk's inventory handle. */
    private StorageAccess storage() {
        if (volume.isEmpty()) return blockEntity.inventory();
        NasBlockEntity nas = nas(); int bay = bay(nas);
        DiskSavedData disk = bay < 0 ? null : nas.volume(bay);
        return disk == null ? null : disk.inventory();
    }
    private void refreshMount() {
        refreshMount(true);
    }
    private void refreshMount(boolean forceTopology) {
        if (blockEntity == null) return;
        long now = player.level().getGameTime();
        if (forceTopology || now >= nextTopologyCheck) {
            nasAccess = CableStorageAccess.discover(player.level(), pos);
            nextTopologyCheck = now + 20;
        }
        NasBlockEntity nas = nas(); int bay = bay(nas);
        String stamp = volume.isEmpty() ? "local" : bay < 0 || nas.volume(bay) == null ? "offline:" + volume : nas.stamp(bay);
        if (!stamp.equals(mountStamp)) {
            exitSearch(true);
            mountStamp = stamp; session = MenuSession.next(); currentFolder = page = 0;
            querySeq = 0; located = -1;
        }
    }
    public CompoundTag snapshot() {
        refreshMount();
        return currentSnapshot();
    }
    private CompoundTag currentSnapshot() {
        TerminalPrograms.reconcile(blockEntity);
        StorageAccess storage = storage();
        if (storage != null && !storage.hasFolder(currentFolder)) {
            currentFolder = 0; page = 0;
            if (searching) { searchRoot = 0; searchReady = false; publishedSearchEntries = Set.of(); }
        }
        invalidateSearch(storage);
        CompoundTag view;
        if (storage == null) {
            view = new CompoundTag(); view.putInt("Current", 0); view.putInt("Pages", 1); view.putLong("Revision", -1); view.putInt("PageSize", pageSize);
            view.putString("Message", message.isEmpty() ? "disk_offline" : message);
        } else view = searching
                ? storage.searchView(searchRoot, searchRecursive, searchReady ? searchMatches : Set.of(), page, pageSize, message)
                : volume.isEmpty() ? TerminalPrograms.directoryView(blockEntity, currentFolder, page, pageSize, message)
                : storage.view(currentFolder, page, pageSize, message);
        page = view.getInt("Page");
        view.putBoolean("Available", storage != null); view.putLong("Session", session); view.putString("Volume", volume);
        view.putLong("ProgramRevision", blockEntity.programs().revision());
        view.putBoolean("ProgramsAvailable", volume.isEmpty() && !searching && !blockEntity.programs().isLocked()
                && !blockEntity.inventory().isLocked());
        view.putBoolean("Searching", searching); view.putBoolean("SearchReady", searchReady);
        view.putInt("SearchRoot", searchRoot); view.putBoolean("SearchRecursive", searchRecursive);
        view.putLong("SearchRevision", storage == null ? -1 : storage.searchRevision());
        view.putLong("QuerySeq", querySeq); view.putLong("ResultViewSeq", resultViewSeq);
        if (!searching) view.putInt("SearchMatches", 0);
        view.putInt("Located", located);
        ListTag volumes = new ListTag();
        CompoundTag local = new CompoundTag(); local.putString("Id", ""); local.putBoolean("Online", true); volumes.add(local);
        view.putInt("NasCount", nasAccess.cabinets().size()); view.putInt("WiredNasCount", nasAccess.wiredCabinets());
        view.putString("CableStorageStatus", nasAccess.wireStatus());
        Map<String, CompoundTag> disks = new LinkedHashMap<>();
        for (int n = 0; n < nasAccess.cabinets().size(); n++) {
          NasBlockEntity nas = nasAccess.cabinets().get(n);
          for (int i = 0; i < NasBlockEntity.BAYS; i++) {
            ItemStack item = nas.disk(i);
            if (item.isEmpty() || DiskItem.id(item) == null) continue;
            CompoundTag disk = new CompoundTag(); String id = DiskItem.id(item).toString();
            DiskSavedData data = nas.volume(i);
            disk.putString("Id", id); disk.putString("Name", data == null ? item.hasCustomHoverName() ? item.getHoverName().getString() : "" : data.name());
            disk.putString("Tier", ((DiskItem) item.getItem()).tier().id());
            disk.putInt("Bay", i + 1); disk.putBoolean("Online", data != null && !data.isLocked());
            disk.putInt("NasIndex", n + 1); disk.putInt("NasX", nas.getBlockPos().getX());
            disk.putInt("NasY", nas.getBlockPos().getY()); disk.putInt("NasZ", nas.getBlockPos().getZ());
            // A corrupt duplicate item must not shadow the drive's legitimate owner or create two UI identities.
            if (!disks.containsKey(id) || disk.getBoolean("Online")) disks.put(id, disk);
          }
        }
        disks.values().forEach(volumes::add);
        if (!volume.isEmpty() && !disks.containsKey(volume)) { CompoundTag missing = new CompoundTag(); missing.putString("Id", volume); missing.putBoolean("Online", false); volumes.add(missing); }
        view.put("Volumes", volumes);
        view.put("RemoteConfig", RemoteTransfers.configuration(blockEntity));
        view.put("RemoteTargets", transferTargets.copy());
        return view;
    }
    private void sync(boolean force) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) return;
        refreshMount(force); StorageAccess storage = storage();
        invalidateSearch(storage);
        boolean catalogSent = syncCatalog(serverPlayer, storage);
        long revision = storage == null ? -1 : storage.revision();
        long programRevision = blockEntity.programs().revision();
        long transferConfigRevision = blockEntity.transferConfig().revision();
        boolean programRefresh = !blockEntity.programs().files().isEmpty() && player.level().getGameTime() >= nextProgramRefresh;
        if (programRefresh) nextProgramRefresh = player.level().getGameTime() + 20;
        StringBuilder devices = new StringBuilder(nasAccess.wireStatus()).append(':').append(nasAccess.wiredCabinets());
        for (NasBlockEntity nas : nasAccess.cabinets()) devices.append('|').append(nas.getBlockPos().asLong())
                .append(':').append(nas.stamp(0)).append(':').append(nas.revision());
        String nasStamp = devices.toString();
        if (!force && !catalogSent && !programRefresh && programRevision == sentProgramRevision
                && transferConfigRevision == sentTransferConfigRevision
                && revision == sentRevision && session == sentSession && nasStamp.equals(sentNasStamp)) return;
        CompoundTag view = currentSnapshot();
        if (force || catalogSent || !view.equals(sentView)) {
            if (searching) {
                view.putLong("ResultViewSeq", ++resultViewSeq);
                Set<Integer> visible = new HashSet<>();
                if (searchReady) for (Tag row : view.getList("Entries", Tag.TAG_COMPOUND))
                    visible.add(((CompoundTag) row).getInt("Id"));
                publishedSearchEntries = Set.copyOf(visible); publishedRevision = revision;
            }
            StorageNetwork.snapshot(serverPlayer, containerId, view); sentView = view;
        }
        sentRevision = revision; sentSession = session; sentNasStamp = nasStamp;
        sentProgramRevision = blockEntity.programs().revision();
        sentTransferConfigRevision = transferConfigRevision;
        message = "";
    }
    @Override public void broadcastChanges() {
        if (blockEntity != null && stillValid(player)) cable.refresh(player.level(), pos, false);
        super.broadcastChanges();
        if (blockEntity != null && stillValid(player)) sync(false);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        // Player shift deposits still use the contextual protocol, including disk and mount session.
        if (closed || player != this.player || !stillValid(player)
                || index < CRAFT_RESULT_SLOT || index >= CRAFT_GRID_END) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem(), original = source.copy();
        if (index == CRAFT_RESULT_SLOT) {
            source.getItem().onCraftedBy(source, player.level(), player);
            if (!moveItemStackTo(source, 0, PLAYER_SLOT_COUNT, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(source, original);
        } else if (!moveItemStackTo(source, 0, PLAYER_SLOT_COUNT, false)) return ItemStack.EMPTY;
        if (source.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        if (source.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, source);
        if (index == CRAFT_RESULT_SLOT) player.drop(source, false);
        return original;
    }
    private void depositSlot(StorageAccess storage, int index) {
        if (index < 0 || index >= PLAYER_SLOT_COUNT) throw new IllegalArgumentException("missing_item");
        Slot slot = slots.get(index); ItemStack source = slot.getItem();
        int count = storage.insert(source, source.getCount(), currentFolder);
        if (count == 0) { message = "full"; return; }
        source.shrink(count); slot.setChanged(); message = "deposited";
    }
    public void handle(StorageNetwork.Request request) {
        if (blockEntity == null || request.menuId() != containerId || !stillValid(player)) return;
        if (!allowAction()) return;
        refreshMount();
        if (request.session() != session) { message = "stale"; sync(true); return; }
        StorageAccess storage = storage();
        if (searching) {
            if (request.action() == StorageNetwork.Action.OPEN || request.action() == StorageNetwork.Action.SELECT_VOLUME)
                exitSearch(true);
            else { message = "search_active"; sync(true); return; }
        }
        located = -1;
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
                int resized = (int) Math.max(StorageInventory.PAGE_SIZE, Math.min(StorageInventory.MAX_PAGE_SIZE, amount));
                if (resized != pageSize) { pageSize = resized; page = 0; }
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
                    case DELETE -> {
                        if (volume.isEmpty() && (blockEntity.programs().isLocked() || blockEntity.programs().hasFilesIn(currentFolder)))
                            throw new IllegalArgumentException(blockEntity.programs().isLocked() ? "production_storage_locked" : "not_empty");
                        currentFolder = storage.deleteFolder(currentFolder); page = 0; message = "deleted";
                    }
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

    public void handleTransfer(StorageNetwork.TransferRequest request) {
        if (blockEntity == null || request.menuId() != containerId || !stillValid(player) || !allowAction()) return;
        refreshMount();
        try {
            if (request.session() != session) throw new IllegalArgumentException("stale");
            if (searching) throw new IllegalArgumentException("search_active");
            CompoundTag config = RemoteTransfers.configuration(blockEntity);
            if (request.action() != StorageNetwork.TransferAction.REFRESH
                    && request.configRevision() != config.getLong("ConfigRevision")) throw new IllegalArgumentException("stale");
            if (request.action() == StorageNetwork.TransferAction.REFRESH) {
                transferTargets = RemoteTransfers.targets(blockEntity);
                message = "";
            } else if (request.action() == StorageNetwork.TransferAction.SEND) {
                StorageAccess storage = storage();
                if (storage == null) throw new IllegalArgumentException("disk_offline");
                if (request.revision() != storage.revision() || !request.volume().equals(volume)
                        || request.folder() != currentFolder || sentView == null
                        || sentView.getLong("Revision") != request.revision()
                        || sentView.getLong("Session") != session) throw new IllegalArgumentException("stale");
                boolean published = false;
                for (Tag row : sentView.getList("Entries", Tag.TAG_COMPOUND))
                    if (((CompoundTag) row).getInt("Id") == request.entry()) { published = true; break; }
                if (!published) throw new IllegalArgumentException("missing_item");
                boolean advertised = false;
                for (Tag row : transferTargets) {
                    CompoundTag target = (CompoundTag) row;
                    if (target.hasUUID("Id") && target.getUUID("Id").equals(request.target())
                            && target.getLong("Revision") == request.targetRevision()) { advertised = true; break; }
                }
                if (!advertised) throw new IllegalArgumentException("remote_target_changed");
                requireVisibleEntry(storage, request.entry());
                RemoteTransfers.send(blockEntity, volume, request.entry(), request.amount(), request.target(), request.targetRevision());
                message = "transfer_sent";
                transferTargets = RemoteTransfers.targets(blockEntity);
            } else {
                boolean bind = request.action() == StorageNetwork.TransferAction.BIND;
                if (bind) {
                    StorageAccess storage = storage();
                    if (storage == null) throw new IllegalArgumentException("disk_offline");
                    if (request.revision() != storage.revision() || !request.volume().equals(volume)
                            || request.folder() != currentFolder) throw new IllegalArgumentException("stale");
                }
                RemoteTransfers.configure(blockEntity,
                        request.action() == StorageNetwork.TransferAction.NAME ? request.name() : config.getString("Name"),
                        bind ? volume : config.getString("Volume"), bind ? currentFolder : config.getInt("Folder"),
                        request.action() == StorageNetwork.TransferAction.RECEIVING ? request.enabled() : config.getBoolean("Enabled"),
                        request.configRevision());
                message = "transfer_configured";
            }
        } catch (IllegalArgumentException rejected) { message = rejected.getMessage(); }
        sync(true);
    }

    public void handleFile(StorageNetwork.FileRequest request) {
        if (blockEntity == null || request.menuId() != containerId || !stillValid(player) || !allowAction()) return;
        refreshMount(); TerminalPrograms.reconcile(blockEntity);
        if (request.session() != session || request.revision() != blockEntity.inventory().revision()
                || request.programRevision() != blockEntity.programs().revision()) { message = "production_stale"; sync(true); return; }
        try {
            if (!volume.isEmpty() || searching) throw new IllegalArgumentException("production_local_only");
            if (blockEntity.programs().isLocked() || blockEntity.inventory().isLocked()) throw new IllegalArgumentException("production_storage_locked");
            ProgramFile file = blockEntity.programs().file(request.id());
            if (request.action() != StorageNetwork.FileAction.CREATE && (file == null || file.folder() != currentFolder))
                throw new IllegalArgumentException("production_missing_program");
            switch (request.action()) {
                case CREATE -> { blockEntity.programs().create(currentFolder, request.name()); page = 0; message = "created"; }
                case OPEN -> {
                    TerminalPrograms.open((ServerPlayer) player, blockEntity, file.id(), currentFolder, page, pageSize); return;
                }
                case RENAME -> { blockEntity.programs().rename(file.id(), request.name()); message = "renamed"; }
                case DELETE -> { blockEntity.programs().delete(file.id()); message = "deleted"; }
                case MOVE -> { blockEntity.programs().move(file.id(), request.target()); message = "moved"; }
                case COPY -> {
                    String name = request.name().isBlank() ? defaultProgramCopyName(file) : request.name();
                    blockEntity.programs().copy(file.id(), currentFolder, name); message = "created";
                }
            }
        } catch (IllegalArgumentException rejected) { message = rejected.getMessage(); }
        sync(true);
    }

    private String defaultProgramCopyName(ProgramFile source) {
        String original = source.name();
        boolean executable = original.length() >= 4 && original.regionMatches(true, original.length() - 4, ".exe", 0, 4);
        String extension = executable ? original.substring(original.length() - 4) : "";
        String base = executable ? original.substring(0, original.length() - 4) : original;
        for (int number = 2; ; number++) {
            String suffix = " (" + number + ")";
            int end = Math.min(base.length(), StorageInventory.MAX_NAME - suffix.length() - extension.length());
            if (end > 0 && end < base.length() && Character.isHighSurrogate(base.charAt(end - 1))) end--;
            String candidate = StorageInventory.validName(base.substring(0, end) + suffix + extension);
            boolean exists = blockEntity.programs().files().stream().anyMatch(file -> file.folder() == currentFolder
                    && file.name().equalsIgnoreCase(candidate));
            if (!exists) return candidate;
        }
    }

    private boolean allowAction() {
        long tick = player.level().getGameTime();
        if (tick != actionTick) { actionTick = tick; actionsThisTick = 0; }
        return ++actionsThisTick <= 10;
    }

    private void invalidateSearch(StorageAccess storage) {
        if (searching && (storage == null || storage.isLocked() || storage.searchRevision() != catalogRevision)) {
            searchReady = false; publishedSearchEntries = Set.of();
        }
    }

    /** Only a search session receives the metadata; structural changes are coalesced over four ticks. */
    private boolean syncCatalog(ServerPlayer target, StorageAccess storage) {
        if (!searching || storage == null || storage.isLocked() || storage.searchRevision() == catalogRevision) return false;
        long tick = player.level().getGameTime();
        boolean reset = catalogRevision < 0;
        if (!reset && tick - catalogSentTick < 4) return false;
        List<CompoundTag> catalog = storage.searchCatalog(), changed = new ArrayList<>();
        Map<Integer, CompoundTag> next = new LinkedHashMap<>();
        for (CompoundTag entry : catalog) {
            int id = entry.getInt("Id"); next.put(id, entry);
            if (reset || !entry.equals(sentCatalog.get(id))) changed.add(entry);
        }
        int[] removed = sentCatalog.keySet().stream().filter(id -> !next.containsKey(id)).mapToInt(Integer::intValue).toArray();
        List<CompoundTag> batches = SearchCatalogSupport.batches(changed, removed);
        long nextRevision = storage.searchRevision();
        for (int i = 0; i < batches.size(); i++)
            StorageNetwork.searchCatalog(target, new StorageNetwork.SearchCatalog(containerId, session, nextRevision,
                    i, reset && i == 0, i == batches.size() - 1, batches.get(i)));
        sentCatalog.clear(); sentCatalog.putAll(next);
        catalogRevision = nextRevision; catalogSentTick = tick;
        return true;
    }

    private void exitSearch(boolean restore) {
        if (searching && restore) { currentFolder = savedFolder; page = savedPage; pageSize = savedPageSize; }
        searching = searchReady = false;
        searchMatches = publishedSearchEntries = Set.of(); publishedRevision = -1;
        sentCatalog.clear(); catalogRevision = -1; catalogSentTick = -1;
    }

    private void requireSearchSource(StorageAccess storage, StorageNetwork.SearchRequest request) {
        if (!searchReady || request.querySeq() != querySeq || request.viewSeq() != resultViewSeq
                || request.catalogRevision() != catalogRevision || request.catalogRevision() != storage.searchRevision()
                || request.revision() != storage.revision() || request.revision() != publishedRevision)
            throw new IllegalArgumentException("stale");
        if (!publishedSearchEntries.contains(request.entryId()) || !searchMatches.contains(request.entryId())
                || !storage.inSearchScope(request.entryId(), searchRoot, searchRecursive))
            throw new IllegalArgumentException("missing_item");
    }

    public void handleSearch(StorageNetwork.SearchRequest request) {
        if (blockEntity == null || request.menuId() != containerId || !stillValid(player) || !allowAction()) return;
        refreshMount();
        if (request.session() != session) { message = "stale"; sync(true); return; }
        StorageAccess storage = storage(); invalidateSearch(storage); message = ""; located = -1;
        try {
            if (request.action() == StorageNetwork.SearchAction.EXIT) {
                // Text edits advance the client sequence before its debounced APPLY reaches us.
                if (searching && request.querySeq() < querySeq) throw new IllegalArgumentException("stale");
                exitSearch(true);
            } else {
                if (storage == null) throw new IllegalArgumentException("disk_offline");
                if (storage.isLocked()) throw new IllegalArgumentException("storage_locked");
                switch (request.action()) {
                    case START -> {
                        if (request.querySeq() < querySeq) throw new IllegalArgumentException("stale");
                        if (!searching) {
                            savedFolder = currentFolder; savedPage = page; savedPageSize = pageSize; searchRoot = currentFolder;
                        }
                        searching = true; searchReady = false; searchRecursive = request.recursive();
                        querySeq = request.querySeq(); page = 0; pageSize = searchPageSize(request.pageSize());
                        searchMatches = publishedSearchEntries = Set.of(); sentCatalog.clear(); catalogRevision = -1;
                    }
                    case APPLY -> {
                        if (!searching || request.querySeq() < querySeq || request.catalogRevision() != catalogRevision
                                || catalogRevision != storage.searchRevision()) throw new IllegalArgumentException("stale");
                        int[] requestedMatches = request.matches();
                        if (requestedMatches.length > 4096)
                            throw new IllegalArgumentException("missing_item");
                        Set<Integer> matches = new LinkedHashSet<>();
                        for (int id : requestedMatches) {
                            if (!storage.inSearchScope(id, searchRoot, request.recursive()))
                                throw new IllegalArgumentException("missing_item");
                            matches.add(id);
                        }
                        querySeq = request.querySeq(); searchRecursive = request.recursive(); searchMatches = Set.copyOf(matches);
                        page = 0; pageSize = searchPageSize(request.pageSize()); searchReady = true;
                    }
                    case PAGE, RESIZE -> {
                        if (!searching || !searchReady || request.querySeq() != querySeq
                                || request.catalogRevision() != catalogRevision) throw new IllegalArgumentException("stale");
                        if (request.action() == StorageNetwork.SearchAction.PAGE) page = Math.max(0, Math.min(4096, request.page()));
                        else { pageSize = searchPageSize(request.pageSize()); page = 0; }
                    }
                    case TAKE -> {
                        if (!searching) throw new IllegalArgumentException("stale");
                        requireSearchSource(storage, request);
                        message = StorageTransfers.withdraw(storage, player.getInventory(), request.entryId(), Math.max(0, request.amount())) > 0
                                ? "withdrawn" : "inventory_full";
                    }
                    case LOCATE -> {
                        if (!searching) throw new IllegalArgumentException("stale");
                        requireSearchSource(storage, request);
                        StorageInventory.Entry entry = storage.entry(request.entryId());
                        int normalSize = savedPageSize;
                        int targetPage = storage.locatePage(entry.id(), normalSize);
                        if (volume.isEmpty()) {
                            int offset = (int) blockEntity.inventory().folders().stream().filter(f -> f.parent() == entry.folder()).count()
                                    + (int) blockEntity.programs().files().stream().filter(f -> f.folder() == entry.folder()).count();
                            for (var candidate : blockEntity.inventory().entries()) {
                                if (candidate.folder() != entry.folder()) continue;
                                if (candidate.id() == entry.id()) break;
                                offset++;
                            }
                            targetPage = offset / normalSize;
                        }
                        exitSearch(false); currentFolder = entry.folder(); page = targetPage; pageSize = normalSize; located = entry.id();
                    }
                    default -> { }
                }
            }
        } catch (IllegalArgumentException failure) { message = failure.getMessage(); }
        sync(true); super.broadcastChanges();
    }

    private static int searchPageSize(int requested) { return Math.max(1, Math.min(StorageInventory.MAX_PAGE_SIZE, requested)); }

    @Override public void removed(Player player) {
        if (closed) return;
        closed = true; exitSearch(false); super.removed(player);
        if (!player.level().isClientSide) {
            clearContainer(player, craftSlots);
            craftResult.clearContent();
        }
    }
}
