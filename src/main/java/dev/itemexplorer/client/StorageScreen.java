package dev.itemexplorer.client;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private static final int TEXT = 0xff303030, MUTED = 0xff505050;
    private static final Set<String> QUIET_MESSAGES = Set.of("", "created", "renamed", "deleted", "moved",
            "deposited", "withdrawn", "no_change");
    private record Folder(int id, int parent, String name, int depth) {}
    private record Entry(int id, ItemStack stack, long count) {}
    private record Volume(int id, String key, String name) {}
    private List<Volume> volumes = List.of();
    private boolean awaitingVolume;
    private long volumeRequestedAt;
    private record Tile(Folder folder, Entry entry) {}
    private record Crumb(int id, String name, String display, int x, int width) {}
    private StorageLayout layout;
    private EditBox quantity, folderName;
    private Button create, rename, delete, up, previous, next, withdraw, move, all, deposit, modalOk, modalCancel;
    private int selected = -1, folderScroll, lastFolder = -1, dragCandidate = -1;
    private double pressX, pressY;
    private boolean dragging, choosingTarget, modal, renaming;
    private final Set<Integer> collapsed = new HashSet<>();
    private final Set<Integer> parents = new HashSet<>();
    private CompoundTag cachedView;
    private List<Folder> folders = List.of(), tree = List.of();
    private List<Entry> entries = List.of();
    private List<Tile> tiles = List.of();
    private List<Crumb> crumbs = List.of();
    private int requestedPageSize = -1;
    private long resizeRequestedAt, errorUntil;
    private String error = "";
    private boolean moveTooltipTarget;

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
        layout = StorageLayout.fit(width, height);
        imageWidth = layout.width(); imageHeight = layout.height();
        super.init();
        clearDraggingState();
        dragging = false; dragCandidate = -1; choosingTarget = false;
        menu.arrangeClientSlots(layout.inventoryX(), layout.inventoryY());
        create = button(8, 23, 48, "new", b -> openModal(false));
        rename = button(60, 23, 42, "rename", b -> openModal(true));
        delete = button(106, 23, 42, "delete", b -> send(Action.DELETE, 0, 0, 0, ""));
        up = button(152, 23, 42, "up", b -> {
            Folder f = folder(current());
            if (f != null && f.parent >= 0) activateFolder(f.parent);
        });
        previous = button(imageWidth - 80, 23, 18, "previous", b -> changePage(-1));
        next = button(imageWidth - 26, 23, 18, "next", b -> changePage(1));
        int x = layout.controlsX(), y = layout.controlsY();
        quantity = addRenderableWidget(new EditBox(font, leftPos + x + 27, topPos + y + 1, 39, 16, label("quantity")));
        quantity.setMaxLength(19);
        quantity.setFilter(s -> s.matches("[0-9]{0,19}"));
        quantity.setValue(savedQuantity);
        withdraw = button(x + 72, y, 44, "withdraw", b -> send(Action.WITHDRAW, selected, 0, amount(), ""));
        move = button(x + 120, y, 64, "move", b -> choosingTarget = !choosingTarget);
        all = button(x + 188, y, 44, "all", b -> {
            Entry e = selectedEntry(); if (e != null) quantity.setValue(Long.toString(e.count));
        });
        deposit = button(x + 240, y, 64, "deposit", b -> send(Action.DEPOSIT_CURSOR, 0, 0, 0, ""));
        withdraw.setTooltip(Tooltip.create(label("withdraw_hint")));
        move.setTooltip(Tooltip.create(label("move_hint")));
        moveTooltipTarget = false;
        all.setTooltip(Tooltip.create(label("all_hint")));
        deposit.setTooltip(Tooltip.create(label("deposit_hint")));
        int mx = layout.modalX(), my = layout.modalY();
        folderName = addRenderableWidget(new EditBox(font, leftPos + mx + 12, topPos + my + 26, 216, 18, label("folder_name")));
        folderName.setMaxLength(StorageInventory.MAX_NAME);
        modalOk = button(mx + 92, my + 52, 64, "confirm", b -> submitModal());
        modalCancel = button(mx + 164, my + 52, 64, "cancel", b -> closeModal());
        closeModal();
        cachedView = null;
        requestedPageSize = -1;
    }

    private void send(Action action, int id, int target, long amount, String text) {
        if (!menu.view().contains("Revision") || awaitingVolume) return;
        if (action == Action.MOVE && target < 0) { error = "cross_disk"; errorUntil = Util.getMillis() + 4500; return; }
        StorageNetwork.request(new StorageNetwork.Request(menu.containerId, menu.view().getLong("Revision"), action, id, target, amount, text, menu.view().getLong("Session")));
        if (action == Action.SELECT_VOLUME) { awaitingVolume = true; volumeRequestedAt = Util.getMillis(); }
        errorUntil = 0;
    }

    private void activateFolder(int id) {
        if (choosingTarget && selected >= 0) {
            send(Action.MOVE, selected, id, amount(), "");
            choosingTarget = false;
        } else if (id < 0) {
            volumes.stream().filter(v -> v.id == id).findFirst().ifPresent(v -> send(Action.SELECT_VOLUME, 0, 0, 0, v.key));
            selected = -1;
        } else {
            send(Action.OPEN, id, 0, 0, "");
            selected = -1;
        }
        quantity.setFocused(false); setFocused(null);
    }

    private long amount() {
        try { return Math.max(0, Long.parseLong(quantity.getValue())); }
        catch (NumberFormatException e) { return 0; }
    }

    private void changePage(int delta) {
        int page = menu.view().getInt("Page") + delta;
        if (page < 0 || page >= menu.view().getInt("Pages")) return;
        send(Action.PAGE, 0, 0, page, "");
        selected = -1; choosingTarget = false; dragCandidate = -1; dragging = false;
    }

    private Folder folder(int id) { return folders.stream().filter(f -> f.id == id).findFirst().orElse(null); }
    private Entry selectedEntry() { return entries.stream().filter(e -> e.id == selected).findFirst().orElse(null); }

    private void refreshView() {
        if (cachedView == menu.view()) return;
        boolean sessionChanged = cachedView == null || cachedView.getLong("Session") != menu.view().getLong("Session");
        if (sessionChanged) {
            selected = -1; choosingTarget = dragging = false; dragCandidate = -1; collapsed.clear();
            if (modal) closeModal();
        }
        awaitingVolume = false;
        cachedView = menu.view();
        List<Volume> decodedVolumes = new ArrayList<>();
        String rootName = label("root").getString();
        for (Tag tag : cachedView.getList("Volumes", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) tag; String key = v.getString("Id");
            String name = key.isEmpty() ? label("root").getString() : v.getString("Name");
            if (name.isEmpty()) name = v.getString("Tier").isEmpty() ? label("offline_disk").getString()
                    : Component.translatable("item.itemexplorer.disk_" + v.getString("Tier")).getString() + " #" + v.getInt("Bay");
            if (!v.getBoolean("Online")) name += " (" + label("offline").getString() + ")";
            if (key.equals(cachedView.getString("Volume"))) rootName = name;
            decodedVolumes.add(new Volume(-10 - decodedVolumes.size(), key, name));
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
            lastFolder = current(); selected = -1; choosingTarget = false;
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
        for (int id : cachedView.getIntArray("PageFolders")) {
            Folder f = folder(id);
            if (f != null) page.add(new Tile(f, null));
        }
        for (Tag tag : cachedView.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) tag;
            Entry entry = new Entry(e.getInt("Id"), ItemStack.of(e.getCompound("Stack")), e.getLong("Count"));
            decoded.add(entry); page.add(new Tile(null, entry));
        }
        entries = decoded; tiles = page;
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
        if (!inside(x, y, layout.browserX(), layout.browserY(), layout.columns() * layout.cellWidth(), layout.rows() * 30)) return null;
        int col = ((int) x - leftPos - layout.browserX()) / layout.cellWidth();
        int row = ((int) y - topPos - layout.browserY()) / 30;
        int index = row * layout.columns() + col;
        return index < tiles.size() ? tiles.get(index) : null;
    }

    private Crumb crumbAt(double x, double y) {
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
        folderName.setValue(rename && current != null ? current.name : "");
        folderName.visible = modalOk.visible = modalCancel.visible = true;
        setFocused(folderName); folderName.setFocused(true); quantity.setFocused(false);
    }

    private void closeModal() {
        modal = false;
        folderName.visible = modalOk.visible = modalCancel.visible = false;
        folderName.setFocused(false); setFocused(null);
    }

    private void submitModal() {
        if (!folderName.getValue().isBlank()) {
            send(renaming ? current() == 0 ? Action.RENAME_DISK : Action.RENAME : Action.CREATE, 0, 0, 0, folderName.getValue());
            closeModal();
        }
    }

    @Override
    public void containerTick() { super.containerTick(); quantity.tick(); folderName.tick(); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshView();
        // A rate-limited selection may receive no reply. Keep the displayed old context and allow retry.
        if (awaitingVolume && Util.getMillis() - volumeRequestedAt > 3000) {
            awaitingVolume = false; error = "stale"; errorUntil = Util.getMillis() + 4500;
        }
        if (menu.view().contains("Revision") && menu.view().getInt("PageSize") != layout.pageSize()
                && (requestedPageSize != layout.pageSize() || Util.getMillis() - resizeRequestedAt > 1000)) {
            requestedPageSize = layout.pageSize(); resizeRequestedAt = Util.getMillis();
            send(Action.RESIZE, 0, 0, layout.pageSize(), "");
        }
        if (selectedEntry() == null) { selected = -1; choosingTarget = false; }
        boolean writable = menu.view().getBoolean("Available") && !menu.view().getBoolean("Locked") && !awaitingVolume;
        create.active = writable && !modal;
        rename.active = writable && (current() != 0 || !menu.view().getString("Volume").isEmpty()) && !modal;
        delete.active = writable && current() != 0 && !modal;
        up.active = current() != 0 && !modal;
        previous.active = menu.view().getInt("Page") > 0 && !modal;
        next.active = menu.view().getInt("Page") + 1 < menu.view().getInt("Pages") && !modal;
        withdraw.active = move.active = writable && selected >= 0 && amount() > 0 && !modal;
        all.active = writable && selected >= 0 && !modal;
        deposit.active = writable && !menu.getCarried().isEmpty() && !modal;
        move.setMessage(label(choosingTarget ? "cancel_move" : "move"));
        if (moveTooltipTarget != choosingTarget) {
            moveTooltipTarget = choosingTarget;
            move.setTooltip(Tooltip.create(label(choosingTarget ? "choose_target" : "move_hint")));
        }
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        if (modal) {
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0x88000000);
            panel(g, layout.modalX(), layout.modalY(), 240, 80);
            text(g, label(renaming ? "rename" : "new"), layout.modalX() + 12, layout.modalY() + 11, 216, TEXT);
            folderName.render(g, mouseX, mouseY, partialTick);
            modalOk.render(g, mouseX, mouseY, partialTick); modalCancel.render(g, mouseX, mouseY, partialTick);
            g.pose().popPose();
        } else {
            renderError(g);
            if (dragging && selectedEntry() != null) {
                g.pose().pushPose(); g.pose().translate(0, 0, 400);
                g.renderItem(selectedEntry().stack, mouseX - 8, mouseY - 8);
                g.pose().popPose();
            } else {
                renderTooltip(g, mouseX, mouseY);
                Tile tile = tileAt(mouseX, mouseY);
                if (tile != null && menu.getCarried().isEmpty()) {
                    if (tile.entry != null) {
                        var lines = new ArrayList<>(getTooltipFromItem(minecraft, tile.entry.stack));
                        lines.add(Component.translatable("gui.itemexplorer.exact_count", tile.entry.count));
                        g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
                    }
                    else g.renderTooltip(font, Component.literal(tile.folder.name), mouseX, mouseY);
                }
                Folder f = treeFolderAt(mouseX, mouseY);
                if (f != null) g.renderTooltip(font, Component.literal(f.name), mouseX, mouseY);
                Crumb crumb = crumbAt(mouseX, mouseY);
                if (crumb != null) g.renderTooltip(font, Component.literal(crumb.name), mouseX, mouseY);
                if (inside(mouseX, mouseY, imageWidth - 120, 5, 110, 15) && menu.view().getBoolean("Available"))
                    g.renderTooltip(font, Component.translatable("gui.itemexplorer.exact_capacity", menu.view().getLong("Total"), menu.view().getLong("Capacity")), mouseX, mouseY);
                if (quantity.isMouseOver(mouseX, mouseY)) g.renderTooltip(font, Component.literal(quantity.getValue()), mouseX, mouseY);
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

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        panel(g, 0, 0, imageWidth, imageHeight);
        g.renderItem(ModContent.STORAGE_ITEM.get().getDefaultInstance(), leftPos + 8, topPos + 5);
        text(g, label("title"), 28, 9, imageWidth - 140, TEXT);
        String capacity = menu.view().getBoolean("Locked") ? label("locked").getString()
                : !menu.view().getBoolean("Available") ? label("offline").getString()
                : compact(menu.view().getLong("Total")) + " / " + compact(menu.view().getLong("Capacity"));
        text(g, Component.literal(capacity), imageWidth - 10 - font.width(capacity), 9, 110, MUTED);
        String page = (menu.view().getInt("Page") + 1) + "/" + Math.max(1, menu.view().getInt("Pages"));
        text(g, Component.literal(page), imageWidth - 44 - font.width(page) / 2, 28, 40, TEXT);
        Crumb hoveredCrumb = crumbAt(mouseX, mouseY);
        for (int i = 0; i < crumbs.size(); i++) {
            Crumb crumb = crumbs.get(i);
            text(g, Component.literal(crumb.display), crumb.x, 46, crumb.width, TEXT);
            if (hoveredCrumb == crumb && crumb.id != current()) g.hLine(leftPos + crumb.x, leftPos + crumb.x + crumb.width - 1, topPos + 55, TEXT);
            if (i < crumbs.size() - 1) text(g, Component.literal("/"), crumb.x + crumb.width + 4, 46, 8, MUTED);
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
        for (int i = 0; i < tiles.size() && i < layout.pageSize(); i++) {
            Tile tile = tiles.get(i);
            int x = layout.browserX() + (i % layout.columns()) * layout.cellWidth();
            int y = layout.browserY() + (i / layout.columns()) * 30;
            int w = layout.cellWidth() - 2;
            boolean active = tile.entry != null && tile.entry.id == selected;
            int color = active ? 0xffdadada : hoveredTile == tile ? 0xffc1c1c1 : 0xffaaaaaa;
            if (tile.folder != null && hoveredTile == tile && (dragging || choosingTarget)) color = 0xffb8c7a1;
            g.fill(leftPos + x + 1, topPos + y + 1, leftPos + x + w, topPos + y + 29, color);
            if (active) g.renderOutline(leftPos + x, topPos + y, w + 1, 30, 0xffffffff);
            if (tile.folder != null) {
                folderIcon(g, x + 5, y + 3, false);
                text(g, Component.literal(tile.folder.name), x + 4, y + 20, w - 6, TEXT);
            } else {
                Entry e = tile.entry;
                g.renderItem(e.stack, leftPos + x + 4, topPos + y + 2);
                text(g, Component.literal("×" + compact(e.count)), x + 23, y + 6, w - 24, TEXT);
                text(g, e.stack.getHoverName(), x + 4, y + 20, w - 6, TEXT);
            }
        }
        if (tiles.isEmpty()) text(g, label("empty"), layout.browserX() + 12, layout.browserY() + 12, layout.browserWidth() - 24, TEXT);
        text(g, label("quantity"), layout.controlsX(), layout.controlsY() + 5, 26, TEXT);
        for (var slot : menu.slots) recess(g, slot.x, slot.y, 16, 16);
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
        if (awaitingVolume) return true;
        if (modal) {
            if (modalOk.mouseClicked(x, y, button) || modalCancel.mouseClicked(x, y, button)) return true;
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
            if (button == 0 || button == 1) send(Action.DEPOSIT_CURSOR, 0, 0, 0, "");
            return true;
        }
        Tile tile = tileAt(x, y);
        if (tile != null) {
            if (tile.folder != null) {
                if (button == 0) activateFolder(tile.folder.id);
            } else if (button == 0 || button == 1) {
                selected = tile.entry.id; quantity.setFocused(false); setFocused(null);
                if (button == 1 || hasShiftDown()) send(Action.WITHDRAW, selected, 0, button == 1 ? 1 : 64, "");
                else { dragCandidate = selected; pressX = x; pressY = y; }
            }
            return true;
        }
        boolean handled = super.mouseClicked(x, y, button);
        // The parent focuses the clicked button after its callback opens the modal.
        if (modal) { setFocused(folderName); folderName.setFocused(true); }
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
        if (type == net.minecraft.world.inventory.ClickType.QUICK_MOVE && slot != null) {
            send(Action.DEPOSIT_SLOT, slotId, 0, 0, "");
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
        if (quantity.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) { quantity.keyPressed(key, scanCode, modifiers); return true; }
        if (choosingTarget && key == GLFW.GLFW_KEY_ESCAPE) { choosingTarget = false; return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
