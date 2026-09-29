package dev.itemexplorer.client;

import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

public final class LogisticsPortScreen extends AbstractContainerScreen<LogisticsPortMenu> {
    private record FolderRow(int id, String path) {}
    private final List<FolderRow> folders = new ArrayList<>();
    private final Button[] rows = new Button[4];
    private Button drive, inputButton, outputButton, recursiveButton, apply, disconnect, up, down;
    private boolean input = true, output = true, recursive;
    private int folder, scroll;
    private String selected = "", notice = "";
    private long revision = -1, context = -1, noticeUntil;
    private CompoundTag seen;

    public LogisticsPortScreen(LogisticsPortMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 320; imageHeight = 238; }
    private Component label(String key) { return Component.translatable("gui.itemexplorer." + key); }
    private Button button(int x, int y, int width, Component text, Button.OnPress pressed) {
        return addRenderableWidget(Button.builder(text, pressed).bounds(leftPos + x, topPos + y, width, 18).build());
    }
    @Override protected void init() {
        super.init();
        drive = button(10, 45, 300, label("port_choose_drive"), b -> cycleDrive());
        for (int i = 0; i < rows.length; i++) {
            int row = i;
            rows[i] = button(10, 81 + i * 18, 272, Component.empty(), b -> {
                if (scroll + row < folders.size()) folder = folders.get(scroll + row).id();
            });
        }
        up = button(286, 81, 24, Component.literal("▲"), b -> scroll = Math.max(0, scroll - 4));
        down = button(286, 135, 24, Component.literal("▼"), b -> scroll = Math.min(Math.max(0, folders.size() - 4), scroll + 4));
        inputButton = button(10, 156, 146, Component.empty(), b -> input = !input);
        outputButton = button(164, 156, 146, Component.empty(), b -> output = !output);
        recursiveButton = button(10, 178, 300, Component.empty(), b -> recursive = !recursive);
        recursiveButton.setTooltip(Tooltip.create(label("port_recursive_hint")));
        apply = button(164, 212, 146, label("port_apply"), b -> send(StorageNetwork.PortAction.APPLY, selected));
        disconnect = button(10, 212, 146, label("port_disconnect"), b -> send(StorageNetwork.PortAction.DISCONNECT, selected));
        seen = null;
    }
    private void send(StorageNetwork.PortAction action, String volume) {
        CompoundTag view = menu.view(); if (!view.contains("Session")) return;
        StorageNetwork.request(new StorageNetwork.PortRequest(menu.containerId, view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"),
                action, volume, folder, input, output, recursive));
    }
    private void cycleDrive() {
        ListTag volumes = menu.view().getList("Volumes", Tag.TAG_COMPOUND); if (volumes.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < volumes.size(); i++) if (volumes.getCompound(i).getString("Id").equals(selected)) index = i;
        send(StorageNetwork.PortAction.SELECT, volumes.getCompound((index + 1) % volumes.size()).getString("Id"));
    }
    private void appendFolders(ListTag nodes, int parent, String prefix, int depth) {
        if (depth > 8) return;
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag node = nodes.getCompound(i); if (node.getInt("Parent") != parent) continue;
            String path = prefix + node.getString("Name");
            folders.add(new FolderRow(node.getInt("Id"), path)); appendFolders(nodes, node.getInt("Id"), path + " / ", depth + 1);
        }
    }
    private void refresh() {
        CompoundTag view = menu.view();
        if (seen != view) {
            seen = view;
            if (revision != view.getLong("Revision") || context != view.getLong("Context") || !selected.equals(view.getString("Selected"))) {
                revision = view.getLong("Revision"); context = view.getLong("Context"); selected = view.getString("Selected");
                folder = selected.equals(view.getString("Volume")) ? view.getInt("Folder") : 0;
                boolean unconfigured = view.getString("Volume").isEmpty();
                input = unconfigured || view.getBoolean("Input"); output = unconfigured || view.getBoolean("Output");
                recursive = !unconfigured && view.getBoolean("Recursive"); scroll = 0;
            }
            folders.clear(); ListTag nodes = view.getList("Folders", Tag.TAG_COMPOUND);
            if (!nodes.isEmpty()) { folders.add(new FolderRow(0, label("port_root").getString())); appendFolders(nodes, 0, "", 1); }
            scroll = Math.min(scroll, Math.max(0, folders.size() - 4));
            if (!view.getString("Message").isEmpty()) { notice = view.getString("Message"); noticeUntil = Util.getMillis() + 4000; }
        }
        String name = label("offline_disk").getString();
        var volumes = view.getList("Volumes", Tag.TAG_COMPOUND);
        for (int i = 0; i < volumes.size(); i++) {
            CompoundTag disk = volumes.getCompound(i); if (!selected.equals(disk.getString("Id"))) continue;
            name = disk.getBoolean("Local") ? label("port_local").getString() : disk.getInt("Bay") + ". "
                    + (disk.getString("Name").isEmpty() ? Component.translatable("item.itemexplorer.disk_" + disk.getString("Tier")).getString() : disk.getString("Name"));
        }
        drive.setMessage(Component.literal(font.plainSubstrByWidth(name + "  ▸", 280))); drive.setTooltip(Tooltip.create(Component.literal(name)));
        boolean ready = view.contains("Session") && !view.getBoolean("Locked");
        drive.active = ready && !volumes.isEmpty();
        for (int i = 0; i < rows.length; i++) {
            boolean exists = scroll + i < folders.size(); rows[i].visible = exists; rows[i].active = ready && exists;
            if (exists) {
                FolderRow row = folders.get(scroll + i);
                rows[i].setMessage(Component.literal((row.id() == folder ? "▶ " : "   ") + font.plainSubstrByWidth(row.path(), 250)));
                rows[i].setTooltip(Tooltip.create(Component.literal(row.path())));
            }
        }
        up.active = scroll > 0; down.active = scroll + 4 < folders.size();
        inputButton.setMessage(Component.translatable("gui.itemexplorer.port_input", label(input ? "port_yes" : "port_no")));
        outputButton.setMessage(Component.translatable("gui.itemexplorer.port_output", label(output ? "port_yes" : "port_no")));
        recursiveButton.setMessage(Component.translatable("gui.itemexplorer.port_recursive", label(recursive ? "port_yes" : "port_no")));
        inputButton.active = outputButton.active = recursiveButton.active = ready;
        apply.active = ready && folders.stream().anyMatch(f -> f.id() == folder);
        disconnect.active = ready && !view.getString("Volume").isEmpty();
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= leftPos + 10 && x < leftPos + 310 && y >= topPos + 81 && y < topPos + 153) {
            scroll = Math.max(0, Math.min(Math.max(0, folders.size() - 4), scroll + (delta > 0 ? -1 : 1))); return true;
        }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh(); renderBackground(g); super.render(g, mouseX, mouseY, partialTick);
        if (mouseX >= leftPos + 10 && mouseX < leftPos + 310 && mouseY >= topPos + 25 && mouseY < topPos + 40)
            g.renderTooltip(font, Component.translatable("message.itemexplorer." + menu.view().getString("Status")), mouseX, mouseY);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff373737);
        g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffeeeeee);
        g.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xffc6c6c6);
        g.drawString(font, title, leftPos + 10, topPos + 10, 0xff303030, false);
        String status = Component.translatable("message.itemexplorer." + menu.view().getString("Status")).getString();
        g.drawString(font, font.plainSubstrByWidth(status, 296), leftPos + 10, topPos + 29, 0xff505050, false);
        g.drawString(font, label("port_folder"), leftPos + 10, topPos + 69, 0xff303030, false);
        String line = Util.getMillis() < noticeUntil ? Component.translatable("message.itemexplorer." + notice).getString() : label("port_apply_hint").getString();
        g.drawString(font, font.plainSubstrByWidth(line, 296), leftPos + 10, topPos + 201, 0xff505050, false);
    }
}
