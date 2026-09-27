package dev.itemexplorer.client;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private static final int BG = 0xff1a2330, PANEL = 0xff243142, LINE = 0xff435369;
    private static final int TEXT = 0xffe9f0fa, MUTED = 0xffaabbd0, SELECTED = 0xff315579;
    private record Folder(int id, int parent, String name, int depth) {}
    private record Entry(int id, ItemStack stack, int count) {}
    private EditBox quantity, folderName;
    private Button rename, delete, up, previous, next, withdraw, move, deposit, modalOk, modalCancel;
    private int selected = -1, folderScroll, lastFolder = -1, dragCandidate = -1;
    private double pressX, pressY;
    private boolean dragging, choosingTarget, modal, renaming;
    private CompoundTag cachedView;
    private List<Folder> cachedFolders = List.of();
    private List<Entry> cachedEntries = List.of();

    public StorageScreen(StorageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = StorageMenu.WIDTH;
        imageHeight = StorageMenu.HEIGHT;
    }

    private Component label(String key) { return Component.translatable("gui.itemexplorer." + key); }
    private int current() { return menu.view().getInt("Current"); }

    private Button button(int x, int y, int width, String key, Button.OnPress action) {
        return addRenderableWidget(Button.builder(label(key), action).bounds(leftPos + x, topPos + y, width, 18).build());
    }

    @Override
    protected void init() {
        super.init();
        button(8, 22, 48, "new", b -> openModal(false));
        rename = button(60, 22, 42, "rename", b -> openModal(true));
        delete = button(106, 22, 42, "delete", b -> send(Action.DELETE, 0, 0, 0, ""));
        up = button(152, 22, 42, "up", b -> {
            Folder f = folders().stream().filter(v -> v.id == current()).findFirst().orElse(null);
            if (f != null && f.parent >= 0) open(f.parent);
        });
        previous = button(232, 22, 18, "previous", b -> changePage(-1));
        next = button(294, 22, 18, "next", b -> changePage(1));
        quantity = addRenderableWidget(new EditBox(font, leftPos + 35, topPos + 123, 39, 16, label("quantity")));
        quantity.setMaxLength(4);
        quantity.setFilter(s -> s.matches("[0-9]{0,4}"));
        quantity.setValue("64");
        withdraw = button(80, 122, 44, "withdraw", b -> send(Action.WITHDRAW, selected, 0, amount(), ""));
        move = button(128, 122, 64, "move", b -> choosingTarget = !choosingTarget);
        button(196, 122, 44, "all", b -> { Entry e = selectedEntry(); if (e != null) quantity.setValue(Integer.toString(e.count)); });
        deposit = button(248, 122, 64, "deposit", b -> send(Action.DEPOSIT_CURSOR, 0, 0, 0, ""));
        folderName = addRenderableWidget(new EditBox(font, leftPos + 52, topPos + 86, 216, 18, label("folder_name")));
        folderName.setMaxLength(StorageInventory.MAX_NAME);
        modalOk = button(132, 112, 64, "confirm", b -> submitModal());
        modalCancel = button(204, 112, 64, "cancel", b -> closeModal());
        closeModal();
    }

    private void send(Action action, int id, int target, int amount, String text) {
        if (!menu.view().contains("Revision")) return;
        StorageNetwork.request(new StorageNetwork.Request(menu.containerId, menu.view().getLong("Revision"), action, id, target, amount, text));
    }

    private void open(int folder) {
        if (choosingTarget && selected >= 0) {
            send(Action.MOVE, selected, folder, amount(), "");
            choosingTarget = false;
        } else {
            send(Action.OPEN, folder, 0, 0, "");
            selected = -1;
        }
    }

    private int amount() {
        try { return Math.min(StorageInventory.CAPACITY, Integer.parseInt(quantity.getValue())); }
        catch (NumberFormatException e) { return 0; }
    }

    private void changePage(int delta) {
        int page = menu.view().getInt("Page") + delta;
        if (page < 0 || page >= menu.view().getInt("Pages")) return;
        send(Action.PAGE, 0, 0, page, ""); selected = -1;
    }

    private List<Folder> folders() {
        refreshView();
        return cachedFolders;
    }

    private void refreshView() {
        if (cachedView == menu.view()) return;
        cachedView = menu.view();
        List<Folder> flat = new ArrayList<>();
        for (Tag tag : menu.view().getList("Folders", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) tag;
            int id = t.getInt("Id");
            flat.add(new Folder(id, t.getInt("Parent"), id == 0 ? label("root").getString() : t.getString("Name"), 0));
        }
        List<Folder> tree = new ArrayList<>();
        appendFolders(flat, tree, -1, 0);
        cachedFolders = tree;
        List<Entry> decoded = new ArrayList<>();
        for (Tag tag : menu.view().getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) tag;
            decoded.add(new Entry(e.getInt("Id"), ItemStack.of(e.getCompound("Stack")), e.getInt("Count")));
        }
        cachedEntries = decoded;
    }

    private void appendFolders(List<Folder> flat, List<Folder> tree, int parent, int depth) {
        if (depth > StorageInventory.MAX_DEPTH + 1) return;
        for (Folder f : flat) if (f.parent == parent) {
            tree.add(new Folder(f.id, f.parent, f.name, depth));
            appendFolders(flat, tree, f.id, depth + 1);
        }
    }

    private List<Entry> entries() {
        refreshView();
        return cachedEntries;
    }

    private Entry selectedEntry() { return entries().stream().filter(e -> e.id == selected).findFirst().orElse(null); }

    private boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + width && mouseY >= topPos + y && mouseY < topPos + y + height;
    }

    private Folder folderAt(double x, double y) {
        if (!inside(x, y, 8, 58, 96, 60)) return null;
        int index = folderScroll + ((int) y - topPos - 58) / 12;
        List<Folder> folders = folders();
        return index < folders.size() ? folders.get(index) : null;
    }

    private Entry entryAt(double x, double y) {
        if (!inside(x, y, 110, 58, 198, 60)) return null;
        int index = (((int) y - topPos - 58) / 30) * 3 + ((int) x - leftPos - 110) / 66;
        List<Entry> entries = entries();
        return index < entries.size() ? entries.get(index) : null;
    }

    private void openModal(boolean rename) {
        modal = true; renaming = rename; choosingTarget = false;
        Folder current = folders().stream().filter(f -> f.id == current()).findFirst().orElse(null);
        folderName.setValue(rename && current != null ? current.name : "");
        folderName.visible = modalOk.visible = modalCancel.visible = true;
        setFocused(folderName); folderName.setFocused(true); quantity.setFocused(false);
    }

    private void closeModal() {
        modal = false;
        folderName.visible = modalOk.visible = modalCancel.visible = false;
        folderName.setFocused(false);
        setFocused(null);
    }

    private void submitModal() {
        if (!folderName.getValue().isBlank()) {
            send(renaming ? Action.RENAME : Action.CREATE, 0, 0, 0, folderName.getValue());
            closeModal();
        }
    }

    @Override
    public void containerTick() { super.containerTick(); quantity.tick(); folderName.tick(); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (current() != lastFolder) { lastFolder = current(); selected = -1; choosingTarget = false; }
        if (selectedEntry() == null) selected = -1;
        rename.active = delete.active = up.active = current() != 0 && !modal;
        previous.active = menu.view().getInt("Page") > 0 && !modal;
        next.active = menu.view().getInt("Page") + 1 < menu.view().getInt("Pages") && !modal;
        withdraw.active = move.active = selected >= 0 && amount() > 0 && !modal;
        deposit.active = !menu.getCarried().isEmpty() && !modal;
        move.setMessage(label(choosingTarget ? "cancel_move" : "move"));
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (modal) {
            graphics.pose().pushPose(); graphics.pose().translate(0, 0, 300);
            graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xaa080c14);
            graphics.fill(leftPos + 40, topPos + 62, leftPos + 280, topPos + 140, LINE);
            graphics.fill(leftPos + 41, topPos + 63, leftPos + 279, topPos + 139, PANEL);
            graphics.drawString(font, label(renaming ? "rename" : "new"), leftPos + 52, topPos + 71, TEXT, false);
            folderName.render(graphics, mouseX, mouseY, partialTick);
            modalOk.render(graphics, mouseX, mouseY, partialTick);
            modalCancel.render(graphics, mouseX, mouseY, partialTick);
            graphics.pose().popPose();
        } else if (dragging && selectedEntry() != null) {
            graphics.pose().pushPose(); graphics.pose().translate(0, 0, 400);
            graphics.renderItem(selectedEntry().stack, mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
        } else {
            renderTooltip(graphics, mouseX, mouseY);
            Entry hovered = entryAt(mouseX, mouseY);
            if (hovered != null && menu.getCarried().isEmpty()) graphics.renderTooltip(font, hovered.stack, mouseX, mouseY);
            Folder folder = folderAt(mouseX, mouseY);
            if (folder != null) graphics.renderTooltip(font, Component.literal(folder.name), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    private void text(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), maxWidth), leftPos + x, topPos + y, color, false);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, LINE);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, BG);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 19, PANEL);
        g.renderItem(ModContent.STORAGE_ITEM.get().getDefaultInstance(), leftPos + 7, topPos + 2);
        text(g, label("title"), 28, 6, 155, TEXT);
        text(g, Component.literal(menu.view().getInt("Total") + " / " + StorageInventory.CAPACITY), 235, 6, 80, MUTED);
        g.drawCenteredString(font, (menu.view().getInt("Page") + 1) + "/" + Math.max(1, menu.view().getInt("Pages")), leftPos + 272, topPos + 27, MUTED);
        List<Folder> folders = folders();
        Folder currentFolder = folders.stream().filter(f -> f.id == current()).findFirst().orElse(null);
        String path = currentFolder == null ? label("root").getString() : currentFolder.name;
        Folder cursor = currentFolder;
        for (int i = 0; cursor != null && cursor.parent >= 0 && i < 10; i++) {
            int parent = cursor.parent;
            cursor = folders.stream().filter(f -> f.id == parent).findFirst().orElse(null);
            if (cursor != null) path = cursor.name + " / " + path;
        }
        text(g, Component.literal(path), 8, 45, 304, MUTED);
        g.fill(leftPos + 7, topPos + 57, leftPos + 105, topPos + 119, PANEL);
        folderScroll = Math.max(0, Math.min(folderScroll, folders.size() - 5));
        for (int i = 0; i < 5 && i + folderScroll < folders.size(); i++) {
            Folder f = folders.get(i + folderScroll);
            int y = 58 + i * 12;
            if (f.id == current() || (folderAt(mouseX, mouseY) == f)) g.fill(leftPos + 8, topPos + y, leftPos + 104, topPos + y + 12, SELECTED);
            if (folderAt(mouseX, mouseY) != null && folderAt(mouseX, mouseY).id == f.id && (dragging || choosingTarget)) {
                g.fill(leftPos + 8, topPos + y, leftPos + 104, topPos + y + 12, 0xff426b57);
            }
            int x = 12 + Math.min(f.depth, 6) * 6;
            g.fill(leftPos + x, topPos + y + 3, leftPos + x + 7, topPos + y + 9, 0xffd5ad54);
            text(g, Component.literal(f.name), x + 10, y + 2, 91 - x, TEXT);
        }
        if (folders.size() > 5) {
            int thumb = Math.max(5, 60 * 5 / folders.size());
            int y = 58 + (60 - thumb) * folderScroll / Math.max(1, folders.size() - 5);
            g.fill(leftPos + 103, topPos + y, leftPos + 105, topPos + y + thumb, MUTED);
        }
        List<Entry> entries = entries();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int x = 110 + (i % 3) * 66, y = 58 + (i / 3) * 30;
            g.fill(leftPos + x, topPos + y, leftPos + x + 64, topPos + y + 28, e.id == selected ? SELECTED : PANEL);
            g.renderItem(e.stack, leftPos + x + 4, topPos + y + 1);
            text(g, Component.literal("×" + e.count), x + 23, y + 6, 39, MUTED);
            text(g, e.stack.getHoverName(), x + 4, y + 19, 58, TEXT);
        }
        if (entries.isEmpty()) text(g, label("empty"), 122, 82, 174, MUTED);
        text(g, label("quantity"), 8, 127, 26, MUTED);
        text(g, label("inventory"), 79, 144, 164, MUTED);
        for (var slot : menu.slots) {
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, LINE);
            g.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, PANEL);
        }
        String status = menu.view().getString("Message");
        Component statusText = choosingTarget ? label("choose_target") : status.isEmpty() ? label("ready")
                : Component.translatable("message.itemexplorer." + status);
        g.drawWordWrap(font, statusText, leftPos + 8, topPos + 153, 63, MUTED);
        g.drawWordWrap(font, label("hint"), leftPos + 250, topPos + 154, 62, MUTED);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (modal) {
            if (modalOk.mouseClicked(mouseX, mouseY, button) || modalCancel.mouseClicked(mouseX, mouseY, button)) return true;
            folderName.mouseClicked(mouseX, mouseY, button); setFocused(folderName);
            return true;
        }
        Folder folder = folderAt(mouseX, mouseY);
        if (folder != null && button == 0) { open(folder.id); return true; }
        if (inside(mouseX, mouseY, 110, 58, 198, 60) && !menu.getCarried().isEmpty()) {
            send(Action.DEPOSIT_CURSOR, 0, 0, 0, ""); return true;
        }
        Entry entry = entryAt(mouseX, mouseY);
        if (entry != null) {
            selected = entry.id; quantity.setFocused(false); setFocused(null);
            if (button == 1 || hasShiftDown()) send(Action.WITHDRAW, selected, 0, button == 1 ? 1 : 64, "");
            else { dragCandidate = selected; pressX = mouseX; pressY = mouseY; }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
            Folder target = folderAt(x, y);
            if (target != null) send(Action.MOVE, dragCandidate, target.id, amount(), "");
        }
        dragging = false; dragCandidate = -1;
        return wasDragging || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (modal) return true;
        if (inside(x, y, 8, 58, 97, 60)) {
            folderScroll = Math.max(0, Math.min(Math.max(0, folders().size() - 5), folderScroll + (delta > 0 ? -1 : 1)));
            return true;
        }
        if (inside(x, y, 110, 58, 198, 60)) { changePage(delta > 0 ? -1 : 1); return true; }
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
        if (quantity.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
            quantity.keyPressed(key, scanCode, modifiers); return true;
        }
        if (choosingTarget && key == GLFW.GLFW_KEY_ESCAPE) { choosingTarget = false; return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
