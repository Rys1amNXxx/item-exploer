package dev.itemexplorer.client;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageSearch;
import dev.itemexplorer.station.StationConnection;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private static final int TEXT = 0xff303030, MUTED = 0xff505050;
    private static final ResourceLocation CRAFTING_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/container/crafting_table.png");
    private static final Set<String> QUIET_MESSAGES = Set.of("", "created", "renamed", "deleted", "moved",
            "deposited", "withdrawn", "no_change");
    private record Folder(int id, int parent, String name, int depth) {}
    private record Entry(int id, int folder, ItemStack stack, long count) {}
    private record CatalogEntry(int id, int folder, String item, String nameJson, String name) {}
    private record Volume(int id, String key, String name, String source) {}
    private List<Volume> volumes = List.of();
    private boolean awaitingVolume;
    private long volumeRequestedAt;
    private record ProgramFile(int id, int folder, String name, boolean configured, boolean active, String machine, String status) {}
    private record Tile(Folder folder, ProgramFile program, Entry entry) {}
    private record Crumb(int id, String name, String display, int x, int width) {}
    private StorageLayout layout;
    private EditBox quantity, folderName, searchQuery;
    private Button create, rename, delete, up, previous, next, withdraw, move, all, deposit, modalOk, modalCancel;
    private Button searchOpen, searchScope, searchExit, modalKind;
    private boolean searching, searchRecursive, catalogLoading, applyingSearch, awaitingSearchExit;
    private CompoundTag searchTransitionView;
    private long searchSession, querySeq, catalogRevision = -1, pendingCatalogRevision = -1, searchChangedAt;
    private long appliedQuerySeq = -1;
    private int expectedCatalogBatch, lastLocated = -1, doubleClickEntry = -1;
    private long doubleClickAt, searchRequestedAt;
    private Map<Integer, CatalogEntry> catalog = new LinkedHashMap<>(), pendingCatalog;
    private Language catalogLanguage;
    private int selected = -1, selectedProgram = -1, folderScroll, lastFolder = -1, dragCandidate = -1;
    private double pressX, pressY;
    private boolean dragging, choosingTarget, modal, renaming, programModal;
    private final Set<Integer> collapsed = new HashSet<>();
    private final Set<Integer> parents = new HashSet<>();
    private CompoundTag cachedView;
    private boolean cachedSearching, cachedSearchReady;
    private List<Folder> folders = List.of(), tree = List.of();
    private List<Entry> entries = List.of();
    private List<ProgramFile> programs = List.of();
    private List<Tile> tiles = List.of();
    private List<Crumb> crumbs = List.of();
    private int requestedPageSize = -1;
    private long resizeRequestedAt, errorUntil;
    private String error = "";

    public StorageScreen(StorageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    private Component label(String key) { return Component.translatable("gui.itemexplorer." + key); }
    private int current() { return menu.view().getInt("Current"); }

    private Button button(int x, int y, int width, String key, Button.OnPress action) {
        return addRenderableWidget(Button.builder(label(key), action).bounds(leftPos + x, topPos + y, width, 18).build());
    }

    @Override
    protected void init() {
        String savedQuantity = quantity == null ? "64" : quantity.getValue();
        String savedSearch = searchQuery == null ? "" : searchQuery.getValue();
        layout = StorageLayout.fit(width, height);
        imageWidth = layout.width(); imageHeight = layout.height();
        super.init();
        clearDraggingState();
        dragging = false; dragCandidate = -1; choosingTarget = false;
        menu.arrangeClientSlots(layout);
        create = button(8, 23, 48, "new", b -> openModal(false));
        rename = button(60, 23, 42, "rename", b -> openModal(true));
        delete = button(106, 23, 42, "delete", b -> {
            if (selectedProgram() != null) sendFile(StorageNetwork.FileAction.DELETE, selectedProgram, current(), "");
            else send(Action.DELETE, 0, 0, 0, "");
        });
        up = button(152, 23, 42, "up", b -> {
            Folder f = folder(current());
            if (f != null && f.parent >= 0) activateFolder(f.parent);
        });
        searchOpen = button(198, 23, 38, "search", b -> openSearch());
        searchOpen.setTooltip(Tooltip.create(label("search_hint")));
        searchQuery = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 24,
                layout.searchWidth(), 16, label("search_query")));
        searchQuery.setMaxLength(StorageSearch.MAX_QUERY);
        searchQuery.setHint(label("search_query"));
        searchQuery.setValue(savedSearch);
        searchQuery.setResponder(s -> invalidateSearch());
        searchScope = button(layout.searchScopeX(), 23, 46, "search_drive", b -> {
            searchRecursive = !searchRecursive;
            invalidateSearch();
        });
        searchExit = button(layout.searchExitX(), 23, 18, "search_close", b -> closeSearch());
        searchExit.setTooltip(Tooltip.create(label("search_exit_hint")));
        previous = button(imageWidth - 80, 23, 18, "previous", b -> changePage(-1));
        next = button(imageWidth - 26, 23, 18, "next", b -> changePage(1));
        int x = layout.controlsX(), y = layout.controlsY();
        quantity = addRenderableWidget(new EditBox(font, leftPos + x + 27, topPos + y + 1, 39, 16, label("quantity")));
        quantity.setMaxLength(19);
        quantity.setFilter(s -> s.matches("[0-9]{0,19}"));
        quantity.setValue(savedQuantity);
        withdraw = button(x + 72, y, 44, "withdraw", b -> {
            if (selectedProgram() != null) sendFile(StorageNetwork.FileAction.OPEN, selectedProgram, current(), "");
            else takeSelected(amount());
        });
        move = button(x + 120, y, 64, "move", b -> {
            if (searching) locateSelected(); else choosingTarget = !choosingTarget;
        });
        all = button(x + 188, y, 44, "all", b -> {
            if (selectedProgram() != null) { sendFile(StorageNetwork.FileAction.COPY, selectedProgram, current(), ""); return; }
            Entry e = selectedEntry(); if (e != null) quantity.setValue(Long.toString(e.count));
        });
        deposit = button(x + 240, y, 64, "deposit", b -> send(Action.DEPOSIT_CURSOR, 0, 0, 0, ""));
        int mx = layout.modalX(), my = layout.modalY();
        folderName = addRenderableWidget(new EditBox(font, leftPos + mx + 12, topPos + my + 26, 216, 18, label("folder_name")));
        folderName.setMaxLength(StorageInventory.MAX_NAME);
        modalOk = button(mx + 92, my + 52, 64, "confirm", b -> submitModal());
        modalCancel = button(mx + 164, my + 52, 64, "cancel", b -> closeModal());
        modalKind = button(mx + 92, my + 4, 136, "file_kind_folder", b -> {
            programModal = !programModal;
            modalKind.setMessage(label(programModal ? "file_kind_program" : "file_kind_folder"));
            if (folderName.getValue().isBlank() || folderName.getValue().equals(label("program_default_name").getString()))
                folderName.setValue(programModal ? label("program_default_name").getString() : "");
        });
        closeModal();
        cachedView = null;
        requestedPageSize = -1;
        updateSearchWidgets();
        if (searching) { setFocused(searchQuery); searchQuery.setFocused(true); }
    }

    private void send(Action action, int id, int target, long amount, String text) {
        if (!menu.view().contains("Revision") || awaitingVolume) return;
        if (searching && (action == Action.MOVE || action == Action.WITHDRAW
                || action == Action.DEPOSIT_CURSOR || action == Action.DEPOSIT_SLOT)) return;
        if (action == Action.MOVE && target < 0) { error = "cross_disk"; errorUntil = Util.getMillis() + 4500; return; }
        StorageNetwork.request(new StorageNetwork.Request(menu.containerId, menu.view().getLong("Revision"), action, id, target, amount, text, menu.view().getLong("Session")));
        if (action == Action.SELECT_VOLUME) { awaitingVolume = true; volumeRequestedAt = Util.getMillis(); }
        errorUntil = 0;
    }

    private void sendFile(StorageNetwork.FileAction action, int id, int target, String name) {
        CompoundTag view = menu.view();
        if (!view.getBoolean("ProgramsAvailable") || awaitingVolume || searching) return;
        if (target < 0) { error = "cross_disk"; errorUntil = Util.getMillis() + 4500; return; }
        StorageNetwork.request(new StorageNetwork.FileRequest(menu.containerId, view.getLong("Session"),
                view.getLong("Revision"), view.getLong("ProgramRevision"), action, id, target, name));
        errorUntil = 0;
    }

    private void openSearch() {
        if (modal || awaitingVolume || awaitingSearchExit || !menu.view().getBoolean("Available") || menu.view().getBoolean("Locked")) return;
        if (searching) { setFocused(searchQuery); searchQuery.setFocused(true); return; }
        searching = true; awaitingSearchExit = false; searchRecursive = false; searchSession = menu.view().getLong("Session");
        querySeq = Math.max(querySeq, menu.view().getLong("QuerySeq")) + 1;
        catalog.clear(); pendingCatalog = null; catalogRevision = pendingCatalogRevision = -1;
        catalogLoading = true; appliedQuerySeq = -1; lastLocated = -1;
        selected = selectedProgram = -1; choosingTarget = dragging = false; dragCandidate = -1;
        searchQuery.setValue(""); searchChangedAt = Util.getMillis();
        updateSearchWidgets(); setFocused(searchQuery); searchQuery.setFocused(true); quantity.setFocused(false);
        sendSearch(StorageNetwork.SearchAction.START, 0, layout.searchRows(), 0, 0, new int[0]);
        cachedView = null;
    }

    private void closeSearch() {
        if (!searching || awaitingSearchExit) return;
        sendSearch(StorageNetwork.SearchAction.EXIT, 0, layout.searchRows(), 0, 0, new int[0]);
        awaitSearchExit();
    }

    private void awaitSearchExit() {
        awaitingSearchExit = true; searchTransitionView = menu.view();
        searchRequestedAt = Util.getMillis();
        searchQuery.setFocused(false); setFocused(null);
    }

    private void resetSearch() {
        searching = false; catalogLoading = applyingSearch = awaitingSearchExit = false;
        catalog.clear(); pendingCatalog = null; catalogRevision = pendingCatalogRevision = -1;
        selected = selectedProgram = -1; choosingTarget = dragging = false; dragCandidate = -1;
        appliedQuerySeq = -1; doubleClickEntry = -1;
        if (searchQuery != null) { searchQuery.setFocused(false); updateSearchWidgets(); }
        setFocused(null);
    }

    private void invalidateSearch() {
        if (!searching) return;
        querySeq++; appliedQuerySeq = -1; applyingSearch = false; searchChangedAt = Util.getMillis();
        selected = -1; doubleClickEntry = -1; cachedView = null;
    }

    private void updateSearchWidgets() {
        create.visible = rename.visible = delete.visible = up.visible = searchOpen.visible = !searching;
        searchQuery.visible = searchScope.visible = searchExit.visible = searching;
        searchScope.setMessage(label(searchRecursive ? "search_tree" : "search_drive"));
        searchScope.setTooltip(Tooltip.create(label(searchRecursive ? "search_tree_hint" : "search_drive_hint")));
    }

    private boolean searchReady() {
        return searching && !awaitingSearchExit && !catalogLoading && appliedQuerySeq == querySeq
                && menu.view().getBoolean("Searching") && menu.view().getBoolean("SearchReady")
                && menu.view().getLong("Session") == searchSession
                && menu.view().getLong("QuerySeq") == querySeq
                && menu.view().getLong("SearchRevision") == catalogRevision;
    }

    private void sendSearch(StorageNetwork.SearchAction action, int page, int pageSize, int entryId, long amount, int[] matches) {
        if (!menu.view().contains("Session") || awaitingVolume) return;
        StorageNetwork.request(new StorageNetwork.SearchRequest(menu.containerId, searchSession, querySeq,
                Math.max(0, catalogRevision), menu.view().getLong("ResultViewSeq"), action,
                searchRecursive, page, pageSize, entryId, amount, menu.view().getLong("Revision"), matches));
        searchRequestedAt = Util.getMillis();
        errorUntil = 0;
    }

    private void applySearch(boolean immediate) {
        if (!searching) return;
        refreshView();
        if (!searching || awaitingSearchExit || catalogLoading || catalogRevision < 0 || appliedQuerySeq == querySeq
                || !menu.view().getBoolean("Searching") || menu.view().getLong("SearchRevision") != catalogRevision
                || menu.view().getLong("Session") != searchSession
                || !immediate && Util.getMillis() - searchChangedAt < 200) return;
        String query = searchQuery.getValue();
        int[] matches = catalog.values().stream().filter(this::inSearchScope)
                .filter(e -> StorageSearch.matches(query, e.name, e.item))
                .mapToInt(CatalogEntry::id).limit(StorageSearch.MAX_MATCHES).toArray();
        appliedQuerySeq = querySeq; applyingSearch = true;
        sendSearch(StorageNetwork.SearchAction.APPLY, 0, layout.searchRows(), 0, 0, matches);
    }

    private boolean inSearchScope(CatalogEntry entry) {
        if (!searchRecursive) return true;
        int root = menu.view().getInt("SearchRoot"), id = entry.folder;
        for (int i = 0; i <= StorageInventory.MAX_DEPTH; i++) {
            if (id == root) return true;
            Folder folder = folder(id);
            if (folder == null || folder.parent < 0) return false;
            id = folder.parent;
        }
        return false;
    }

    /** The catalog contains only immutable matching metadata; stacks and counts remain paged. */
    public void acceptSearchCatalog(StorageNetwork.SearchCatalog packet) {
        if (!searching || packet.menuId() != menu.containerId || packet.session() != searchSession
                || packet.session() != menu.view().getLong("Session") || packet.catalogRevision() < catalogRevision) return;
        if (packet.catalogRevision() != pendingCatalogRevision) {
            if (packet.batch() != 0 || packet.catalogRevision() <= catalogRevision
                    || pendingCatalogRevision > packet.catalogRevision()) return;
            pendingCatalogRevision = packet.catalogRevision(); expectedCatalogBatch = 0;
            pendingCatalog = packet.reset() ? new LinkedHashMap<>() : new LinkedHashMap<>(catalog);
            catalogLoading = true; invalidateSearch();
        }
        if (pendingCatalog == null || packet.batch() != expectedCatalogBatch) return;
        for (int id : packet.data().getIntArray("Removed")) pendingCatalog.remove(id);
        for (Tag tag : packet.data().getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag; int id = entry.getInt("Id");
            if (id < 0) continue;
            String item = entry.getString("Item"), json = entry.getString("Name");
            pendingCatalog.put(id, new CatalogEntry(id, entry.getInt("Folder"), item, json, ""));
        }
        expectedCatalogBatch++;
        if (pendingCatalog.size() > StorageSearch.MAX_MATCHES) return;
        if (packet.complete()) {
            catalog = pendingCatalog; pendingCatalog = null; catalogRevision = packet.catalogRevision();
            catalogLoading = false; reparseCatalog();
            searchChangedAt = Util.getMillis() - 200;
        }
    }

    private void reparseCatalog() {
        catalogLanguage = Language.getInstance();
        catalog.replaceAll((id, e) -> new CatalogEntry(id, e.folder, e.item, e.nameJson, StorageSearch.name(e.nameJson)));
    }

    private void takeSelected(long amount) {
        if (searching) {
            if (searchReady()) sendSearch(StorageNetwork.SearchAction.TAKE, menu.view().getInt("Page"),
                    layout.searchRows(), selected, amount, new int[0]);
        } else send(Action.WITHDRAW, selected, 0, amount, "");
    }

    private void locateSelected() {
        if (!searchReady() || selected < 0) return;
        sendSearch(StorageNetwork.SearchAction.LOCATE, menu.view().getInt("Page"), layout.searchRows(), selected, 0, new int[0]);
        awaitSearchExit();
    }

    private String entryPath(Entry entry) {
        List<String> names = new ArrayList<>(); Folder cursor = folder(entry.folder);
        for (int i = 0; cursor != null && i <= StorageInventory.MAX_DEPTH; i++) {
            names.add(cursor.name); cursor = folder(cursor.parent);
        }
        Collections.reverse(names);
        return String.join(" / ", names);
    }

    private void activateFolder(int id) {
        if (searching) {
            if (awaitingSearchExit) return;
            awaitSearchExit();
        }
        if (choosingTarget && (selected >= 0 || selectedProgram >= 0)) {
            if (selectedProgram >= 0) sendFile(StorageNetwork.FileAction.MOVE, selectedProgram, id, "");
            else send(Action.MOVE, selected, id, amount(), "");
            choosingTarget = false;
        } else if (id < 0) {
            volumes.stream().filter(v -> v.id == id).findFirst().ifPresent(v -> send(Action.SELECT_VOLUME, 0, 0, 0, v.key));
            selected = selectedProgram = -1;
        } else {
            send(Action.OPEN, id, 0, 0, "");
            selected = selectedProgram = -1;
        }
        quantity.setFocused(false); setFocused(null);
    }

    private long amount() {
        try { return Math.max(0, Long.parseLong(quantity.getValue())); }
        catch (NumberFormatException e) { return 0; }
    }

    private void changePage(int delta) {
        if (searching && !searchReady()) return;
        int page = menu.view().getInt("Page") + delta;
        if (page < 0 || page >= menu.view().getInt("Pages")) return;
        if (searching) sendSearch(StorageNetwork.SearchAction.PAGE, page, layout.searchRows(), 0, 0, new int[0]);
        else send(Action.PAGE, 0, 0, page, "");
        selected = selectedProgram = -1; choosingTarget = false; dragCandidate = -1; dragging = false;
    }

    private Folder folder(int id) { return folders.stream().filter(f -> f.id == id).findFirst().orElse(null); }
    private Entry selectedEntry() { return entries.stream().filter(e -> e.id == selected).findFirst().orElse(null); }
    private ProgramFile selectedProgram() { return programs.stream().filter(p -> p.id == selectedProgram).findFirst().orElse(null); }

    private void refreshView() {
        if (cachedView == menu.view() && cachedSearching == searching && cachedSearchReady == searchReady()) return;
        boolean sessionChanged = cachedView != null && cachedView.getLong("Session") != menu.view().getLong("Session");
        if (searching && menu.view().getLong("Session") != searchSession) resetSearch();
        if (awaitingSearchExit && menu.view() != searchTransitionView) {
            if (!menu.view().getBoolean("Searching")) resetSearch();
            else awaitingSearchExit = false;
        }
        if (sessionChanged) {
            selected = selectedProgram = -1; choosingTarget = dragging = false; dragCandidate = -1; collapsed.clear();
            if (modal) closeModal();
        }
        awaitingVolume = false;
        cachedView = menu.view();
        cachedSearching = searching; cachedSearchReady = searchReady();
        List<Volume> decodedVolumes = new ArrayList<>();
        String rootName = label("root").getString();
        for (Tag tag : cachedView.getList("Volumes", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) tag; String key = v.getString("Id");
            String name = key.isEmpty() ? label("root").getString() : v.getString("Name");
            if (name.isEmpty()) name = v.getString("Tier").isEmpty() ? label("offline_disk").getString()
                    : Component.translatable("item.itemexplorer.disk_" + v.getString("Tier")).getString() + " #" + v.getInt("Bay");
            String source = v.contains("NasIndex") ? Component.translatable("gui.itemexplorer.nas_disk_source",
                    v.getInt("NasX"), v.getInt("NasY"), v.getInt("NasZ"), v.getInt("Bay")).getString() : "";
            if (cachedView.getInt("NasCount") > 1 && v.contains("NasIndex"))
                name = Component.translatable("gui.itemexplorer.nas_disk_label", v.getInt("NasIndex"), v.getInt("Bay"), name).getString();
            if (!v.getBoolean("Online")) name += " (" + label("offline").getString() + ")";
            if (key.equals(cachedView.getString("Volume"))) rootName = name;
            decodedVolumes.add(new Volume(-10 - decodedVolumes.size(), key, name, source));
        }
        volumes = decodedVolumes;
        List<Folder> decodedFolders = new ArrayList<>();
        parents.clear();
        for (Tag tag : cachedView.getList("Folders", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) tag;
            int id = t.getInt("Id"), parent = t.getInt("Parent");
            decodedFolders.add(new Folder(id, parent, id == 0 ? rootName : t.getString("Name"), 0));
            parents.add(parent);
        }
        if (decodedFolders.isEmpty()) decodedFolders.add(new Folder(0, -1, rootName, 0));
        folders = decodedFolders;
        boolean folderChanged = sessionChanged || lastFolder != current();
        if (folderChanged) {
            lastFolder = current(); selected = selectedProgram = -1; choosingTarget = false;
            Folder cursor = folder(current());
            for (int i = 0; cursor != null && i <= StorageInventory.MAX_DEPTH; i++) {
                collapsed.remove(cursor.id);
                cursor = folder(cursor.parent);
            }
        }
        rebuildTree();
        if (folderChanged) {
            for (int i = 0; i < tree.size(); i++) if (tree.get(i).id == current()) {
                if (i < folderScroll) folderScroll = i;
                if (i >= folderScroll + layout.treeRows()) folderScroll = i - layout.treeRows() + 1;
            }
        }
        List<Entry> decoded = new ArrayList<>();
        List<Tile> page = new ArrayList<>();
        for (int id : searching || cachedView.getBoolean("Searching") ? new int[0] : cachedView.getIntArray("PageFolders")) {
            Folder f = folder(id);
            if (f != null) page.add(new Tile(f, null, null));
        }
        List<ProgramFile> decodedPrograms = new ArrayList<>();
        for (Tag tag : cachedView.getList("ProgramFiles", Tag.TAG_COMPOUND)) {
            if (searching || cachedView.getBoolean("Searching")) break;
            CompoundTag p = (CompoundTag) tag;
            ProgramFile program = new ProgramFile(p.getInt("Id"), p.getInt("Folder"), p.getString("Name"),
                    p.getBoolean("Configured"), p.getBoolean("Active"), p.getString("Machine"), p.getString("Status"));
            decodedPrograms.add(program); page.add(new Tile(null, program, null));
        }
        for (Tag tag : cachedView.getList("Entries", Tag.TAG_COMPOUND)) {
            if (searching ? !searchReady() : cachedView.getBoolean("Searching")) break;
            CompoundTag e = (CompoundTag) tag;
            Entry entry = new Entry(e.getInt("Id"), e.contains("Folder") ? e.getInt("Folder") : current(),
                    ItemStack.of(e.getCompound("Stack")), e.getLong("Count"));
            decoded.add(entry); page.add(new Tile(null, null, entry));
        }
        entries = decoded; programs = decodedPrograms; tiles = page;
        if (doubleClickEntry >= 0 && entries.stream().noneMatch(e -> e.id == doubleClickEntry)) doubleClickEntry = -1;
        if (!searching && !cachedView.getBoolean("Searching") && cachedView.contains("Located")) {
            int located = cachedView.getInt("Located");
            if (located >= 0 && located != lastLocated && entries.stream().anyMatch(e -> e.id == located)) {
                selected = located; lastLocated = located;
            }
        }
        if (searchReady()) applyingSearch = false;
        buildCrumbs();
        String message = cachedView.getString("Message");
        if (!QUIET_MESSAGES.contains(message)) { error = message; errorUntil = Util.getMillis() + 4500; }
    }

    private void rebuildTree() {
        List<Folder> visible = new ArrayList<>();
        if (volumes.isEmpty()) appendFolders(visible, -1, 0);
        for (Volume volume : volumes) {
            if (volume.key.equals(menu.view().getString("Volume"))) appendFolders(visible, -1, 0);
            else visible.add(new Folder(volume.id, -1, volume.name, 0));
        }
        tree = visible;
        folderScroll = Math.max(0, Math.min(folderScroll, tree.size() - layout.treeRows()));
    }

    private void appendFolders(List<Folder> result, int parent, int depth) {
        if (depth > StorageInventory.MAX_DEPTH) return;
        for (Folder f : folders) if (f.parent == parent) {
            result.add(new Folder(f.id, f.parent, f.name, depth));
            if (!collapsed.contains(f.id)) appendFolders(result, f.id, depth + 1);
        }
    }

    private void buildCrumbs() {
        List<Folder> chain = new ArrayList<>();
        Folder cursor = folder(current());
        for (int i = 0; cursor != null && i <= StorageInventory.MAX_DEPTH; i++) {
            chain.add(cursor); cursor = folder(cursor.parent);
        }
        Collections.reverse(chain);
        boolean shortened = false;
        int available = imageWidth - 16;
        while (chain.size() > 2 && crumbWidth(chain) + (shortened ? 24 : 0) > available) {
            chain.remove(1); shortened = true;
        }
        List<Crumb> result = new ArrayList<>();
        int x = 8;
        for (int i = 0; i < chain.size(); i++) {
            Folder f = chain.get(i);
            if (i == 1 && shortened) {
                result.add(new Crumb(-1, "...", "...", x, 18)); x += 30;
            }
            String display = fit(f.name, Math.min(90, imageWidth - 8 - x));
            int w = font.width(display);
            result.add(new Crumb(f.id, f.name, display, x, w)); x += w + 12;
        }
        crumbs = result;
    }

    private int crumbWidth(List<Folder> chain) {
        return chain.stream().mapToInt(f -> Math.min(90, font.width(f.name)) + 12).sum() - 12;
    }

    private String fit(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("..."))) + "...";
    }

    private boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= leftPos + left && x < leftPos + left + width && y >= topPos + top && y < topPos + top + height;
    }

    private Folder treeFolderAt(double x, double y) {
        if (!inside(x, y, 8, layout.browserY(), 96, layout.treeRows() * 12)) return null;
        int index = folderScroll + ((int) y - topPos - layout.browserY()) / 12;
        return index < tree.size() ? tree.get(index) : null;
    }

    private Tile tileAt(double x, double y) {
        if (searching) {
            if (!inside(x, y, layout.browserX(), layout.browserY(), layout.browserWidth(), layout.searchRows() * 30)) return null;
            int index = ((int) y - topPos - layout.browserY()) / 30;
            return index < tiles.size() ? tiles.get(index) : null;
        }
        if (!inside(x, y, layout.browserX(), layout.browserY(), layout.columns() * layout.cellWidth(), layout.rows() * 30)) return null;
        int col = ((int) x - leftPos - layout.browserX()) / layout.cellWidth();
        int row = ((int) y - topPos - layout.browserY()) / 30;
        int index = row * layout.columns() + col;
        return index < tiles.size() ? tiles.get(index) : null;
    }

    private Crumb crumbAt(double x, double y) {
        if (searching) return null;
        return crumbs.stream().filter(c -> c.id >= 0 && inside(x, y, c.x, 44, c.width, 12)).findFirst().orElse(null);
    }

    private Folder dropTargetAt(double x, double y) {
        Folder f = treeFolderAt(x, y);
        if (f != null) return f;
        Tile tile = tileAt(x, y);
        if (tile != null && tile.folder != null) return tile.folder;
        Crumb crumb = crumbAt(x, y);
        return crumb == null ? null : folder(crumb.id);
    }

    private void openModal(boolean rename) {
        modal = true; renaming = rename; choosingTarget = false; dragging = false; dragCandidate = -1;
        Folder current = folder(current());
        ProgramFile program = selectedProgram();
        programModal = rename && program != null;
        folderName.setValue(rename ? program != null ? program.name : current != null ? current.name : "" : "");
        folderName.visible = modalOk.visible = modalCancel.visible = true;
        modalKind.visible = !rename && menu.view().getBoolean("ProgramsAvailable");
        modalKind.setMessage(label("file_kind_folder"));
        setFocused(folderName); folderName.setFocused(true); quantity.setFocused(false);
    }

    private void closeModal() {
        modal = false;
        folderName.visible = modalOk.visible = modalCancel.visible = modalKind.visible = false;
        folderName.setFocused(false); setFocused(null);
    }

    private void submitModal() {
        if (!folderName.getValue().isBlank()) {
            if (programModal) sendFile(renaming ? StorageNetwork.FileAction.RENAME : StorageNetwork.FileAction.CREATE,
                    renaming ? selectedProgram : 0, current(), folderName.getValue());
            else send(renaming ? current() == 0 ? Action.RENAME_DISK : Action.RENAME : Action.CREATE, 0, 0, 0, folderName.getValue());
            closeModal();
        }
    }

    @Override
    public void containerTick() {
        super.containerTick(); quantity.tick(); folderName.tick(); searchQuery.tick();
        if (searching && !catalogLoading && catalogLanguage != Language.getInstance()) {
            reparseCatalog(); invalidateSearch();
        }
        if (searching && Util.getMillis() - searchRequestedAt > 3000) {
            if (awaitingSearchExit) {
                awaitingSearchExit = false; error = "stale"; errorUntil = Util.getMillis() + 4500;
            } else if (applyingSearch) { applyingSearch = false; appliedQuerySeq = -1; }
        }
        applySearch(false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshView();
        // A rate-limited selection may receive no reply. Keep the displayed old context and allow retry.
        if (awaitingVolume && Util.getMillis() - volumeRequestedAt > 3000) {
            awaitingVolume = false; error = "stale"; errorUntil = Util.getMillis() + 4500;
        }
        int desiredPageSize = searching ? layout.searchRows() : layout.pageSize();
        if (menu.view().contains("Revision") && (searching ? searchReady() : !menu.view().getBoolean("Searching"))
                && menu.view().getInt("PageSize") != desiredPageSize
                && (requestedPageSize != desiredPageSize || Util.getMillis() - resizeRequestedAt > 1000)) {
            requestedPageSize = desiredPageSize; resizeRequestedAt = Util.getMillis();
            if (searching) sendSearch(StorageNetwork.SearchAction.RESIZE, menu.view().getInt("Page"), desiredPageSize, 0, 0, new int[0]);
            else send(Action.RESIZE, 0, 0, desiredPageSize, "");
        }
        if (selectedEntry() == null) selected = -1;
        if (selectedProgram() == null) selectedProgram = -1;
        if (selected < 0 && selectedProgram < 0) choosingTarget = false;
        boolean fileSelected = selectedProgram >= 0;
        boolean writable = menu.view().getBoolean("Available") && !menu.view().getBoolean("Locked") && !awaitingVolume
                && !awaitingSearchExit && (searching ? searchReady() : !menu.view().getBoolean("Searching"));
        updateSearchWidgets();
        create.active = writable && !modal && !searching;
        rename.active = writable && (fileSelected || current() != 0 || !menu.view().getString("Volume").isEmpty()) && !modal && !searching;
        delete.active = writable && (fileSelected || current() != 0) && !modal && !searching
                && (!fileSelected || !selectedProgram().active);
        delete.setTooltip(fileSelected && selectedProgram().active ? Tooltip.create(label("program_delete_hint")) : null);
        searchOpen.active = writable && !modal;
        searchScope.active = searchExit.active = !awaitingSearchExit && !modal;
        up.active = current() != 0 && !modal;
        previous.active = menu.view().getInt("Page") > 0 && !modal && (!searching || searchReady());
        next.active = menu.view().getInt("Page") + 1 < menu.view().getInt("Pages") && !modal && (!searching || searchReady());
        withdraw.active = writable && (fileSelected || selected >= 0 && amount() > 0) && !modal;
        move.active = writable && (fileSelected || selected >= 0 && (searching || amount() > 0)) && !modal;
        all.active = writable && (fileSelected || selected >= 0) && !modal;
        quantity.setEditable(!fileSelected);
        withdraw.setMessage(label(fileSelected ? "program_open" : "withdraw"));
        all.setMessage(label(fileSelected ? "program_copy" : "all"));
        create.setMessage(label(menu.view().getBoolean("ProgramsAvailable") ? "file_new" : "new"));
        deposit.active = writable && !menu.getCarried().isEmpty() && !modal && !searching;
        deposit.setTooltip(searching ? Tooltip.create(label("search_deposit_hint")) : null);
        move.setMessage(label(searching ? "locate" : choosingTarget ? "cancel_move" : "move"));
        move.setTooltip(!searching && choosingTarget ? Tooltip.create(label("choose_target")) : null);
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        if (modal) {
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0x88000000);
            panel(g, layout.modalX(), layout.modalY(), 240, 80);
            text(g, label(renaming ? "rename" : "file_new"), layout.modalX() + 12, layout.modalY() + 11, modalKind.visible ? 72 : 216, TEXT);
            folderName.render(g, mouseX, mouseY, partialTick);
            modalOk.render(g, mouseX, mouseY, partialTick); modalCancel.render(g, mouseX, mouseY, partialTick);
            if (modalKind.visible) modalKind.render(g, mouseX, mouseY, partialTick);
            g.pose().popPose();
        } else {
            renderError(g);
            if (dragging && selectedEntry() != null) {
                g.pose().pushPose(); g.pose().translate(0, 0, 400);
                g.renderItem(selectedEntry().stack, mouseX - 8, mouseY - 8);
                g.pose().popPose();
            } else {
                renderTooltip(g, mouseX, mouseY);
                if (inside(mouseX, mouseY, 6, 5, 20, 16)) {
                    var lines = new ArrayList<Component>();
                    String wireState = menu.view().getString("CableStorageStatus");
                    lines.add(label("local_wire_" + (wireState.isEmpty() ? "checking" : wireState)));
                    lines.add(Component.translatable("gui.itemexplorer.local_nas_count", menu.view().getInt("NasCount"), menu.view().getInt("WiredNasCount")));
                    lines.add(Component.translatable(menu.cable().status().key()));
                    lines.add(Component.translatable("gui.itemexplorer.cable_counts", menu.cable().cables(), menu.cable().terminals()));
                    if (menu.cable().status() == StationConnection.Status.CONNECTED) {
                        var station = menu.cable().controller();
                        lines.add(Component.translatable("gui.itemexplorer.cable_station", station.getX(), station.getY(), station.getZ()));
                    }
                    g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
                }
                Tile tile = tileAt(mouseX, mouseY);
                if (tile != null && menu.getCarried().isEmpty()) {
                    if (tile.entry != null) {
                        var lines = new ArrayList<>(getTooltipFromItem(minecraft, tile.entry.stack));
                        lines.add(Component.translatable("gui.itemexplorer.exact_count", tile.entry.count));
                        if (searching) {
                            lines.add(Component.translatable("gui.itemexplorer.search_source", entryPath(tile.entry)));
                        }
                        g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
                    }
                    else if (tile.program != null) {
                        var lines = new ArrayList<Component>();
                        lines.add(Component.literal(tile.program.name));
                        lines.add(label("program_file"));
                        if (!tile.program.status.isEmpty()) lines.add(Component.translatable("message.itemexplorer." + tile.program.status));
                        else lines.add(label(tile.program.configured ? "program_configured" : "program_unconfigured"));
                        g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
                    } else g.renderTooltip(font, Component.literal(tile.folder.name), mouseX, mouseY);
                }
                Folder f = treeFolderAt(mouseX, mouseY);
                if (f != null) {
                    Volume drive = volumes.stream().filter(v -> v.id == f.id || f.id == 0 && v.key.equals(menu.view().getString("Volume"))).findFirst().orElse(null);
                    if (drive != null && !drive.source.isEmpty())
                        g.renderTooltip(font, List.of(Component.literal(f.name), Component.literal(drive.source)), java.util.Optional.empty(), mouseX, mouseY);
                    else g.renderTooltip(font, Component.literal(f.name), mouseX, mouseY);
                }
                Crumb crumb = crumbAt(mouseX, mouseY);
                if (crumb != null) g.renderTooltip(font, Component.literal(crumb.name), mouseX, mouseY);
                if (inside(mouseX, mouseY, imageWidth - 120, 5, 110, 15) && menu.view().getBoolean("Available"))
                    g.renderTooltip(font, Component.translatable("gui.itemexplorer.exact_capacity", menu.view().getLong("Total"), menu.view().getLong("Capacity")), mouseX, mouseY);
                if (quantity.isMouseOver(mouseX, mouseY) && font.width(quantity.getValue()) > quantity.getWidth() - 8)
                    g.renderTooltip(font, Component.literal(quantity.getValue()), mouseX, mouseY);
                if (searching && searchQuery.isMouseOver(mouseX, mouseY))
                    g.renderTooltip(font, label("search_matching_hint"), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {}

    private void text(GuiGraphics g, Component value, int x, int y, int maxWidth, int color) {
        g.drawString(font, fit(value.getString(), maxWidth), leftPos + x, topPos + y, color, false);
    }

    /** Pixel bevels follow the light/dark edges of vanilla inventory panels. */
    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        int left = leftPos + x, top = topPos + y;
        g.fill(left + 1, top, left + w - 1, top + h, 0xff373737);
        g.fill(left, top + 1, left + w, top + h - 1, 0xff373737);
        g.fill(left + 2, top + 2, left + w - 2, top + h - 2, 0xff555555);
        g.fill(left + 2, top + 2, left + w - 3, top + h - 3, 0xffffffff);
        g.fill(left + 4, top + 4, left + w - 4, top + h - 4, 0xffc6c6c6);
    }

    private void recess(GuiGraphics g, int x, int y, int w, int h) {
        int left = leftPos + x, top = topPos + y;
        g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xffffffff);
        g.fill(left - 1, top - 1, left + w, top + h, 0xff373737);
        g.fill(left, top, left + w, top + h, 0xff8b8b8b);
    }

    private void folderIcon(GuiGraphics g, int x, int y, boolean small) {
        int left = leftPos + x, top = topPos + y;
        int w = small ? 8 : 16, h = small ? 6 : 11;
        g.fill(left, top, left + w / 2, top + 3, 0xff725421);
        g.fill(left, top + 2, left + w, top + h + 2, 0xff725421);
        g.fill(left + 1, top + 1, left + w / 2 - 1, top + 3, 0xffffdf88);
        g.fill(left + 1, top + 3, left + w - 1, top + h + 1, 0xffd7ae54);
        g.fill(left + 1, top + 3, left + w - 1, top + 4, 0xffffdf88);
    }

    private void programIcon(GuiGraphics g, int x, int y, boolean active) {
        int left = leftPos + x, top = topPos + y;
        g.fill(left, top, left + 16, top + 14, 0xff304c72);
        g.fill(left + 1, top + 1, left + 15, top + 4, 0xff78a5d1);
        g.fill(left + 1, top + 5, left + 15, top + 13, 0xffe3ebf3);
        int color = active ? 0xff238543 : 0xff3d6393;
        g.fill(left + 5, top + 6, left + 7, top + 12, color);
        g.fill(left + 7, top + 7, left + 9, top + 11, color);
        g.fill(left + 9, top + 8, left + 11, top + 10, color);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        panel(g, 0, 0, imageWidth, imageHeight);
        g.renderItem(ModContent.STORAGE_ITEM.get().getDefaultInstance(), leftPos + 8, topPos + 5);
        // A compact wired-status badge preserves the existing title, capacity and search layout.
        boolean connected = menu.cable().status() == StationConnection.Status.CONNECTED || menu.view().getInt("NasCount") > 0;
        g.fill(leftPos + 20, topPos + 15, leftPos + 26, topPos + 21, 0xff303030);
        g.fill(leftPos + 21, topPos + 16, leftPos + 25, topPos + 20, connected ? 0xff55ac72 : 0xffb7784c);
        text(g, label("title"), 28, 9, imageWidth - 140, TEXT);
        String capacity = menu.view().getBoolean("Locked") ? label("locked").getString()
                : !menu.view().getBoolean("Available") ? label("offline").getString()
                : compact(menu.view().getLong("Total")) + " / " + compact(menu.view().getLong("Capacity"));
        text(g, Component.literal(capacity), imageWidth - 10 - font.width(capacity), 9, 110, MUTED);
        String page = (menu.view().getInt("Page") + 1) + "/" + Math.max(1, menu.view().getInt("Pages"));
        text(g, Component.literal(page), imageWidth - 44 - font.width(page) / 2, 28, 40, TEXT);
        if (searching) {
            Component status = searchReady() ? Component.translatable("gui.itemexplorer.search_summary",
                    label(searchRecursive ? "search_tree" : "search_drive"), menu.view().getInt("SearchMatches"))
                    : label("search_loading");
            text(g, status, 8, 46, imageWidth - 16, TEXT);
        } else {
            Crumb hoveredCrumb = crumbAt(mouseX, mouseY);
            for (int i = 0; i < crumbs.size(); i++) {
                Crumb crumb = crumbs.get(i);
                text(g, Component.literal(crumb.display), crumb.x, 46, crumb.width, TEXT);
                if (hoveredCrumb == crumb && crumb.id != current()) g.hLine(leftPos + crumb.x, leftPos + crumb.x + crumb.width - 1, topPos + 55, TEXT);
                if (i < crumbs.size() - 1) text(g, Component.literal("/"), crumb.x + crumb.width + 4, 46, 8, MUTED);
            }
        }
        recess(g, 8, layout.browserY(), 96, layout.browserHeight());
        recess(g, layout.browserX(), layout.browserY(), layout.browserWidth(), layout.browserHeight());
        Folder hoveredFolder = treeFolderAt(mouseX, mouseY);
        for (int i = 0; i < layout.treeRows() && i + folderScroll < tree.size(); i++) {
            Folder f = tree.get(i + folderScroll);
            int y = layout.browserY() + i * 12;
            boolean hover = hoveredFolder != null && hoveredFolder.id == f.id;
            if (f.id == current() || hover) g.fill(leftPos + 8, topPos + y, leftPos + 102, topPos + y + 12,
                    hover && (dragging || choosingTarget) ? 0xffb8c7a1 : f.id == current() ? 0xffd4d4d4 : 0xffb0b0b0);
            int x = 10 + Math.min(f.depth, 6) * 5;
            if (parents.contains(f.id)) text(g, Component.literal(collapsed.contains(f.id) ? ">" : "v"), x, y + 2, 6, TEXT);
            folderIcon(g, x + 8, y + 2, true);
            text(g, Component.literal(f.name), x + 20, y + 2, 80 - x, TEXT);
        }
        if (tree.size() > layout.treeRows()) {
            int thumb = Math.max(6, layout.browserHeight() * layout.treeRows() / tree.size());
            int y = layout.browserY() + (layout.browserHeight() - thumb) * folderScroll / (tree.size() - layout.treeRows());
            g.fill(leftPos + 102, topPos + y, leftPos + 104, topPos + y + thumb, 0xffdddddd);
        }
        Tile hoveredTile = tileAt(mouseX, mouseY);
        if (searching) for (int i = 0; i < entries.size() && i < layout.searchRows(); i++) {
            Entry entry = entries.get(i); int x = layout.browserX(), y = layout.browserY() + i * 30;
            int w = layout.browserWidth() - 2;
            boolean active = entry.id == selected;
            g.fill(leftPos + x + 1, topPos + y + 1, leftPos + x + w, topPos + y + 29,
                    active ? 0xffdadada : hoveredTile != null && hoveredTile.entry == entry ? 0xffc1c1c1 : 0xffaaaaaa);
            if (active) g.renderOutline(leftPos + x, topPos + y, w + 1, 30, 0xffffffff);
            g.renderItem(entry.stack, leftPos + x + 4, topPos + y + 2);
            String count = "×" + compact(entry.count); int countWidth = Math.min(64, font.width(count));
            text(g, entry.stack.getHoverName(), x + 24, y + 4, w - 30 - countWidth, TEXT);
            text(g, Component.literal(count), x + w - countWidth - 4, y + 4, countWidth, TEXT);
            text(g, Component.literal(entryPath(entry)), x + 24, y + 18, w - 28, MUTED);
        }
        else for (int i = 0; i < tiles.size() && i < layout.pageSize(); i++) {
            Tile tile = tiles.get(i);
            int x = layout.browserX() + (i % layout.columns()) * layout.cellWidth();
            int y = layout.browserY() + (i / layout.columns()) * 30;
            int w = layout.cellWidth() - 2;
            boolean active = tile.entry != null && tile.entry.id == selected || tile.program != null && tile.program.id == selectedProgram;
            int color = active ? 0xffdadada : hoveredTile == tile ? 0xffc1c1c1 : 0xffaaaaaa;
            if (tile.folder != null && hoveredTile == tile && (dragging || choosingTarget)) color = 0xffb8c7a1;
            g.fill(leftPos + x + 1, topPos + y + 1, leftPos + x + w, topPos + y + 29, color);
            if (active) g.renderOutline(leftPos + x, topPos + y, w + 1, 30, 0xffffffff);
            if (tile.folder != null) {
                folderIcon(g, x + 5, y + 3, false);
                text(g, Component.literal(tile.folder.name), x + 4, y + 20, w - 6, TEXT);
            } else if (tile.program != null) {
                programIcon(g, x + 5, y + 3, tile.program.active);
                text(g, label(tile.program.active ? "program_active_badge" : "program_file_badge"), x + 24, y + 6, w - 25, MUTED);
                text(g, Component.literal(tile.program.name), x + 4, y + 20, w - 6, TEXT);
            } else {
                Entry e = tile.entry;
                g.renderItem(e.stack, leftPos + x + 4, topPos + y + 2);
                text(g, Component.literal("×" + compact(e.count)), x + 23, y + 6, w - 24, TEXT);
                text(g, e.stack.getHoverName(), x + 4, y + 20, w - 6, TEXT);
            }
        }
        if (tiles.isEmpty()) text(g, label(searching ? searchReady() ? "search_empty" : "search_loading" : "empty"),
                layout.browserX() + 12, layout.browserY() + 12, layout.browserWidth() - 24, TEXT);
        text(g, label("quantity"), layout.controlsX(), layout.controlsY() + 5, 26, TEXT);
        text(g, label("crafting"), layout.craftingX(), layout.inventoryY() + 3, 104, TEXT);
        // Preserve the vanilla arrow spacing and larger result-slot frame relative to the 3x3 grid.
        g.blit(CRAFTING_TEXTURE, leftPos + layout.craftingX() + 60, topPos + layout.craftingResultY(),
                90, 35, 22, 15);
        g.blit(CRAFTING_TEXTURE, leftPos + layout.craftingResultX() - 5, topPos + layout.craftingResultY() - 5,
                119, 30, 26, 26);
        for (var slot : menu.slots)
            if (slot.index != StorageMenu.CRAFT_RESULT_SLOT) recess(g, slot.x, slot.y, 16, 16);
    }

    private void renderError(GuiGraphics g) {
        boolean locked = menu.view().getBoolean("Locked");
        boolean offline = menu.view().contains("Session") && !menu.view().getBoolean("Available");
        if (!locked && !offline && Util.getMillis() >= errorUntil) return;
        var lines = font.split(Component.translatable("message.itemexplorer." + (locked ? "storage_locked" : offline ? "disk_offline" : error)), imageWidth - 40);
        int h = lines.size() * font.lineHeight + 12;
        int x = 12, y = layout.controlsY() - h - 3;
        g.pose().pushPose(); g.pose().translate(0, 0, 250);
        panel(g, x, y, imageWidth - 24, h);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), leftPos + x + 8, topPos + y + 6 + i * font.lineHeight, 0xffa02020, false);
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (awaitingVolume || awaitingSearchExit) return true;
        if (modal) {
            if (modalOk.mouseClicked(x, y, button) || modalCancel.mouseClicked(x, y, button)
                    || modalKind.visible && modalKind.mouseClicked(x, y, button)) return true;
            folderName.mouseClicked(x, y, button); setFocused(folderName); return true;
        }
        Folder f = treeFolderAt(x, y);
        if (f != null && button == 0) {
            int arrowX = 10 + Math.min(f.depth, 6) * 5;
            if (parents.contains(f.id) && x < leftPos + arrowX + 7) {
                if (!collapsed.add(f.id)) collapsed.remove(f.id);
                rebuildTree();
            } else activateFolder(f.id);
            return true;
        }
        Crumb crumb = crumbAt(x, y);
        if (crumb != null && button == 0) { activateFolder(crumb.id); return true; }
        if (inside(x, y, layout.browserX(), layout.browserY(), layout.browserWidth(), layout.browserHeight()) && !menu.getCarried().isEmpty()) {
            if (!searching && !menu.view().getBoolean("Searching") && (button == 0 || button == 1)) send(Action.DEPOSIT_CURSOR, 0, 0, 0, "");
            return true;
        }
        Tile tile = tileAt(x, y);
        if (tile != null) {
            if (tile.folder != null) {
                if (button == 0) activateFolder(tile.folder.id);
            } else if (tile.program != null && (button == 0 || button == 1)) {
                selectedProgram = tile.program.id; selected = -1; choosingTarget = dragging = false; dragCandidate = -1;
                quantity.setFocused(false); searchQuery.setFocused(false); setFocused(null);
                if (button == 0) sendFile(StorageNetwork.FileAction.OPEN, selectedProgram, current(), "");
            } else if (button == 0 || button == 1) {
                selected = tile.entry.id; selectedProgram = -1; quantity.setFocused(false); setFocused(null);
                searchQuery.setFocused(false);
                if (button == 1 || hasShiftDown()) { doubleClickEntry = -1; takeSelected(button == 1 ? 1 : 64); }
                else if (searching) {
                    long now = Util.getMillis();
                    if (doubleClickEntry == selected && now - doubleClickAt < 250) { doubleClickEntry = -1; locateSelected(); }
                    else { doubleClickEntry = selected; doubleClickAt = now; }
                } else { dragCandidate = selected; pressX = x; pressY = y; }
            }
            return true;
        }
        boolean handled = super.mouseClicked(x, y, button);
        // The parent focuses the clicked button after its callback opens the modal.
        if (modal) { setFocused(folderName); folderName.setFocused(true); }
        else if (searching && (getFocused() == searchOpen || getFocused() == searchScope)) {
            setFocused(searchQuery); searchQuery.setFocused(true); quantity.setFocused(false);
        }
        return handled;
    }

    private static String compact(long count) {
        if (count < 10_000) return Long.toString(count);
        if (count < 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fK", count / 1000.0);
        if (count < 1_000_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", count / 1000000.0);
        return String.format(java.util.Locale.ROOT, "%.1fG", count / 1000000000.0);
    }

    @Override protected void slotClicked(net.minecraft.world.inventory.Slot slot, int slotId, int button, net.minecraft.world.inventory.ClickType type) {
        if (awaitingVolume) return;
        if (type == net.minecraft.world.inventory.ClickType.QUICK_MOVE && slot != null
                && slotId >= 0 && slotId < StorageMenu.PLAYER_SLOT_COUNT) {
            if (!searching && !menu.view().getBoolean("Searching")) send(Action.DEPOSIT_SLOT, slotId, 0, 0, "");
        } else super.slotClicked(slot, slotId, button, type);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (modal) return true;
        if (button == 0 && dragCandidate >= 0 && Math.hypot(x - pressX, y - pressY) > 4) { dragging = true; return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (modal) return true;
        boolean wasDragging = dragging;
        if (dragging && button == 0) {
            Folder target = dropTargetAt(x, y);
            if (target != null) send(Action.MOVE, dragCandidate, target.id, amount(), "");
        }
        dragging = false; dragCandidate = -1;
        return wasDragging || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (modal) return true;
        if (inside(x, y, 8, layout.browserY(), 97, layout.browserHeight())) {
            folderScroll = Math.max(0, Math.min(Math.max(0, tree.size() - layout.treeRows()), folderScroll + (delta > 0 ? -1 : 1)));
            return true;
        }
        if (inside(x, y, layout.browserX(), layout.browserY(), layout.browserWidth(), layout.browserHeight())) {
            if (!dragging) changePage(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (modal) {
            if (key == GLFW.GLFW_KEY_ESCAPE) closeModal();
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) submitModal();
            else folderName.keyPressed(key, scanCode, modifiers);
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && hasControlDown()) { openSearch(); return true; }
        if (searching && key == GLFW.GLFW_KEY_ESCAPE) { closeSearch(); return true; }
        if (searching && searchQuery.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) applySearch(true);
            else if (!awaitingSearchExit) searchQuery.keyPressed(key, scanCode, modifiers);
            return true;
        }
        if (quantity.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) { quantity.keyPressed(key, scanCode, modifiers); return true; }
        if (choosingTarget && key == GLFW.GLFW_KEY_ESCAPE) { choosingTarget = false; return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
