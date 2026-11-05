package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Binds the port to one folder of one drive. Choices stay local until Apply, because a binding starts moving
 * items at once; the green stripe marks what is in effect, the white highlight what is being chosen.
 */
public final class LogisticsPortScreen extends AbstractContainerScreen<LogisticsPortMenu> {
    private static final Set<String> SUCCESS_MESSAGES = Set.of("port_saved", "port_disconnected");
    // The same statuses that turn the block's status light red.
    private static final Set<String> FAULTS = Set.of("storage_locked", "invalid_folder", "disk_missing", "disk_mismatch",
            "disk_conflict", "disk_offline");
    private static final int LIST_Y = 60, LIST_HEIGHT = 106, DRIVE_X = 10, DRIVE_WIDTH = 92, DRIVE_ROW = 22;
    private static final int TREE_X = 108, TREE_WIDTH = 202;
    private record Drive(String id, String name, ItemStack icon) {}
    private final FolderTree tree = new FolderTree();
    private final GuiNotice notice = new GuiNotice();
    private List<Drive> drives = List.of();
    private Button inputButton, outputButton, recursiveButton, apply, disconnect;
    private boolean input = true, output = true, recursive;
    private int folder;
    private String selected = "";
    private long revision = -1, context = -1;
    private CompoundTag seen;

    public LogisticsPortScreen(LogisticsPortMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 320; imageHeight = 238; }
    private Component label(String key, Object... args) { return Component.translatable("gui.itemexplorer." + key, args); }
    private Button button(int x, int y, int width, Component text, Button.OnPress pressed) {
        return addRenderableWidget(Button.builder(text, pressed).bounds(leftPos + x, topPos + y, width, 18).build());
    }
    // Toggles carry no message: render() paints a check box and a left-aligned label the centered text would collide with.
    private Button toggle(int x, String key, Button.OnPress pressed) {
        Button button = button(x, 172, 96, Component.empty(), pressed);
        button.setTooltip(Tooltip.create(label(key)));
        return button;
    }
    @Override protected void init() {
        super.init();
        tree.place(leftPos + TREE_X, topPos + LIST_Y, TREE_WIDTH, LIST_HEIGHT / FolderTree.ROW);
        inputButton = toggle(10, "port_input_toggle", b -> input = !input);
        outputButton = toggle(112, "port_output_toggle", b -> output = !output);
        recursiveButton = toggle(214, "port_recursive_toggle", b -> recursive = !recursive);
        recursiveButton.setTooltip(Tooltip.create(label("port_recursive_hint")));
        disconnect = button(10, 210, 146, label("port_disconnect"), b -> send(StorageNetwork.PortAction.DISCONNECT, selected));
        apply = button(164, 210, 146, label("port_apply"), b -> send(StorageNetwork.PortAction.APPLY, selected));
        seen = null;
    }
    private void send(StorageNetwork.PortAction action, String volume) {
        CompoundTag view = menu.view(); if (!view.contains("Session")) return;
        StorageNetwork.request(new StorageNetwork.PortRequest(menu.containerId, view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"),
                action, volume, folder, input, output, recursive));
    }
    private boolean ready() { return menu.view().contains("Session") && !menu.view().getBoolean("Locked"); }

    private void refresh() {
        CompoundTag view = menu.view();
        if (seen == view) return;
        seen = view;
        List<Drive> nextDrives = new ArrayList<>();
        for (Tag tag : view.getList("Volumes", Tag.TAG_COMPOUND)) {
            CompoundTag disk = (CompoundTag) tag;
            if (disk.getBoolean("Local")) {
                nextDrives.add(new Drive(disk.getString("Id"), label("port_local").getString(), ModContent.STORAGE_ITEM.get().getDefaultInstance()));
                continue;
            }
            String tier = disk.getString("Tier");
            String name = disk.getString("Name").isEmpty() ? Component.translatable("item.itemexplorer.disk_" + tier).getString() : disk.getString("Name");
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ItemExplorer.MOD_ID, "disk_" + tier));
            nextDrives.add(new Drive(disk.getString("Id"), disk.getInt("Bay") + ". " + name,
                    item == null || item == Items.AIR ? ItemStack.EMPTY : item.getDefaultInstance()));
        }
        drives = nextDrives;
        // Folders describe the drive being browsed, which may differ from the bound one.
        List<FolderTree.Node> nodes = new ArrayList<>();
        boolean root = false;
        for (Tag tag : view.getList("Folders", Tag.TAG_COMPOUND)) {
            CompoundTag node = (CompoundTag) tag; int id = node.getInt("Id");
            root |= id == 0;
            nodes.add(new FolderTree.Node(id, id == 0 ? -1 : node.getInt("Parent"), id == 0 ? label("port_root").getString() : node.getString("Name")));
        }
        if (!nodes.isEmpty() && !root) nodes.add(0, new FolderTree.Node(0, -1, label("port_root").getString()));
        tree.setNodes(nodes);
        if (revision != view.getLong("Revision") || context != view.getLong("Context") || !selected.equals(view.getString("Selected"))) {
            revision = view.getLong("Revision"); context = view.getLong("Context"); selected = view.getString("Selected");
            folder = selected.equals(view.getString("Volume")) ? view.getInt("Folder") : 0;
            boolean unconfigured = view.getString("Volume").isEmpty();
            input = unconfigured || view.getBoolean("Input"); output = unconfigured || view.getBoolean("Output");
            recursive = !unconfigured && view.getBoolean("Recursive");
            tree.reveal(folder);
        }
        String message = view.getString("Message");
        if (!message.isEmpty()) notice.message(message,
                SUCCESS_MESSAGES.contains(message) ? VanillaGui.Tone.SUCCESS : VanillaGui.Tone.ERROR, 4000);
    }

    /** True while the choices on screen differ from the server's binding, including when nothing is bound yet. */
    private boolean dirty() {
        CompoundTag view = menu.view();
        return view.getString("Volume").isEmpty() || !selected.equals(view.getString("Volume")) || folder != view.getInt("Folder")
                || input != view.getBoolean("Input") || output != view.getBoolean("Output") || recursive != view.getBoolean("Recursive");
    }

    private Component binding() {
        CompoundTag view = menu.view();
        String volume = view.getString("Volume");
        if (volume.isEmpty()) return label("port_current_none");
        String where = drives.stream().filter(d -> d.id().equals(volume)).map(Drive::name).findFirst()
                .orElse(label("offline_disk").getString());
        String path = volume.equals(selected) ? tree.path(view.getInt("Folder")) : null;
        if (path != null) where += " / " + path;
        List<String> flags = new ArrayList<>();
        if (view.getBoolean("Input")) flags.add(label("port_flag_input").getString());
        if (view.getBoolean("Output")) flags.add(label("port_flag_output").getString());
        if (flags.isEmpty()) flags.add(label("port_flags_off").getString());
        else if (view.getBoolean("Recursive")) flags.add(label("port_flag_recursive").getString());
        return label("port_current", where, String.join(" · ", flags));
    }

    private static VanillaGui.Tone statusTone(String status) {
        if (status.equals("online")) return VanillaGui.Tone.SUCCESS;
        if (status.equals("port_full")) return VanillaGui.Tone.WARNING;
        return FAULTS.contains(status) ? VanillaGui.Tone.ERROR : VanillaGui.Tone.MUTED;
    }

    private Drive driveAt(double x, double y) {
        int left = leftPos + DRIVE_X, top = topPos + LIST_Y;
        if (x < left || x >= left + DRIVE_WIDTH || y < top || y >= top + LIST_HEIGHT) return null;
        int index = ((int) y - top) / DRIVE_ROW;
        return index < drives.size() ? drives.get(index) : null;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && ready()) {
            Drive drive = driveAt(x, y);
            if (drive != null) {
                if (!drive.id().equals(selected)) send(StorageNetwork.PortAction.SELECT, drive.id());
                return true;
            }
            int clicked = tree.click(x, y);
            if (clicked >= 0) folder = clicked;
            if (clicked != FolderTree.MISSED) return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        return tree.scroll(x, y, delta) || super.mouseScrolled(x, y, delta);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh();
        boolean ready = ready();
        inputButton.active = outputButton.active = recursiveButton.active = ready;
        apply.active = ready && tree.contains(folder) && dirty();
        disconnect.active = ready && !menu.view().getString("Volume").isEmpty();
        renderBackground(g); super.render(g, mouseX, mouseY, partialTick);
        toggleFace(g, inputButton, "port_input_toggle", input);
        toggleFace(g, outputButton, "port_output_toggle", output);
        toggleFace(g, recursiveButton, "port_recursive_toggle", recursive);
        if (mouseX >= leftPos + 10 && mouseX < leftPos + 310 && mouseY >= topPos + 20 && mouseY < topPos + 31)
            g.renderTooltip(font, Component.translatable("message.itemexplorer." + menu.view().getString("Status")), mouseX, mouseY);
        else if (mouseX >= leftPos + 10 && mouseX < leftPos + 310 && mouseY >= topPos + 33 && mouseY < topPos + 44)
            g.renderTooltip(font, binding(), mouseX, mouseY);
        Drive drive = driveAt(mouseX, mouseY);
        if (drive != null) g.renderTooltip(font, Component.literal(drive.name()), mouseX, mouseY);
        String path = tree.pathAt(mouseX, mouseY);
        if (path != null) g.renderTooltip(font, Component.literal(path), mouseX, mouseY);
    }

    private void toggleFace(GuiGraphics g, Button button, String key, boolean on) {
        int x = button.getX() + 6, y = button.getY() + 5;
        g.fill(x, y, x + 8, y + 8, 0xff303030);
        g.fill(x + 1, y + 1, x + 7, y + 7, button.active ? 0xffe0e0e0 : 0xffa0a0a0);
        if (on) g.fill(x + 2, y + 2, x + 6, y + 6, 0xff3c8d4f);
        g.drawString(font, VanillaGui.fit(font, label(key).getString(), button.getWidth() - 22), x + 12, y,
                button.active ? 0xffffffff : 0xffa0a0a0, true);
    }

    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        CompoundTag view = menu.view();
        VanillaGui.panel(g, leftPos, topPos, imageWidth, imageHeight);
        VanillaGui.panelString(g, font, VanillaGui.fit(font, title.getString(), 300), leftPos + 10, topPos + 8, VanillaGui.Tone.TEXT);
        String status = view.getString("Status");
        VanillaGui.Tone tone = statusTone(status);
        g.fill(leftPos + 10, topPos + 21, leftPos + 17, topPos + 28, 0xff303030);
        g.fill(leftPos + 11, topPos + 22, leftPos + 16, topPos + 27, VanillaGui.panelText(tone));
        VanillaGui.panelString(g, font, VanillaGui.fit(font, Component.translatable("message.itemexplorer." + status).getString(), 290),
                leftPos + 21, topPos + 21, tone);
        VanillaGui.panelString(g, font, VanillaGui.fit(font, binding().getString(), 300), leftPos + 10, topPos + 35, VanillaGui.Tone.TEXT);
        VanillaGui.panelString(g, font, label("port_drives"), leftPos + DRIVE_X, topPos + 49, VanillaGui.Tone.MUTED);
        VanillaGui.panelString(g, font, VanillaGui.fit(font, label("port_folder").getString(), TREE_WIDTH), leftPos + TREE_X, topPos + 49, VanillaGui.Tone.MUTED);

        boolean ready = ready();
        int left = leftPos + DRIVE_X, top = topPos + LIST_Y;
        VanillaGui.recess(g, left, top, DRIVE_WIDTH, LIST_HEIGHT);
        Drive hovered = ready ? driveAt(mouseX, mouseY) : null;
        String bound = view.getString("Volume");
        for (int i = 0; i < drives.size() && (i + 1) * DRIVE_ROW <= LIST_HEIGHT; i++) {
            Drive drive = drives.get(i); int y = top + i * DRIVE_ROW;
            int overlay = drive.id().equals(selected) ? 0x66ffffff : drive == hovered ? 0x33ffffff : 0;
            if (overlay != 0) g.fill(left, y, left + DRIVE_WIDTH, y + DRIVE_ROW, overlay);
            if (drive.id().equals(bound)) g.fill(left, y + 2, left + 2, y + DRIVE_ROW - 2, VanillaGui.recessText(VanillaGui.Tone.SUCCESS));
            if (!drive.icon().isEmpty()) g.renderItem(drive.icon(), left + 4, y + 3);
            VanillaGui.recessString(g, font, VanillaGui.fit(font, drive.name(), DRIVE_WIDTH - 26), left + 23, y + 7, 0xffeeeeee, overlay);
        }
        if (drives.isEmpty()) VanillaGui.recessString(g, font, VanillaGui.fit(font, label("port_no_drives").getString(), DRIVE_WIDTH - 8),
                left + 4, top + 6, 0xffd0d0d0);
        tree.render(g, font, folder, selected.equals(bound) ? view.getInt("Folder") : -1, mouseX, mouseY, ready);

        Component hint; VanillaGui.Tone hintTone;
        if (notice.active()) { hint = notice.text(); hintTone = notice.tone(); }
        else if (bound.isEmpty()) { hint = label("port_apply_hint"); hintTone = VanillaGui.Tone.MUTED; }
        else if (dirty()) { hint = label("port_unsaved"); hintTone = VanillaGui.Tone.WARNING; }
        else { hint = label("port_up_to_date"); hintTone = VanillaGui.Tone.MUTED; }
        VanillaGui.panelString(g, font, VanillaGui.fit(font, hint.getString(), 300), leftPos + 10, topPos + 196, hintTone);
    }
}
