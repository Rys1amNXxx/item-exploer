package dev.itemexplorer.client;

import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A terminal program file editor; the selected production port only executes its definition. */
public final class ProductionPortScreen extends AbstractContainerScreen<ProductionPortMenu> {
    private record FolderRow(int id, String path) {}
    private record EntryRow(int id, int folder, ItemStack stack, long count, boolean smeltable, boolean fuel) {}
    private record MachineRow(long pos, String type, String label, boolean connected) {}
    private final List<FolderRow> folders = new ArrayList<>();
    private final List<EntryRow> entries = new ArrayList<>();
    private final List<MachineRow> machines = new ArrayList<>();
    private EditBox name, count;
    private Button inputFolderButton, fuelFolderButton, outputFolderButton, ingredientButton, fuelButton, machineButton, save, start, cancel, back;
    private int inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry;
    private long context = -1, noticeUntil, machinePos;
    private String savedSignature = "", notice = "";
    private CompoundTag seen;

    public ProductionPortScreen(ProductionPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 320; imageHeight = 238;
    }
    private Component label(String key, Object... args) {
        return Component.translatable("gui.itemexplorer.production_" + key, args);
    }
    private Button button(int x, int y, int width, Component text, Button.OnPress pressed) {
        return addRenderableWidget(Button.builder(text, pressed).bounds(leftPos + x, topPos + y, width, 18).build());
    }
    @Override protected void init() {
        super.init();
        back = addRenderableWidget(Button.builder(label("back_symbol"), b -> onClose())
                .bounds(leftPos + 290, topPos + 3, 20, 16).build());
        back.setTooltip(Tooltip.create(label("back")));
        machineButton = button(10, 20, 300, Component.empty(), b -> cycleMachine());
        name = addRenderableWidget(new EditBox(font, leftPos + 43, topPos + 39, 157, 16, label("name")));
        name.setMaxLength(24);
        count = addRenderableWidget(new EditBox(font, leftPos + 254, topPos + 39, 55, 16, label("count")));
        count.setMaxLength(4); count.setFilter(value -> value.matches("[0-9]{0,4}"));
        count.setTooltip(Tooltip.create(label("count_hint")));
        inputFolderButton = button(10, 60, 300, Component.empty(), b -> cycleFolder(0));
        ingredientButton = button(32, 80, 278, Component.empty(), b -> inputEntry = nextEntry(false));
        fuelFolderButton = button(10, 100, 300, Component.empty(), b -> cycleFolder(1));
        fuelButton = button(32, 120, 278, Component.empty(), b -> fuelEntry = nextEntry(true));
        outputFolderButton = button(10, 140, 300, Component.empty(), b -> cycleFolder(2));
        save = button(10, 212, 96, label("save"), b -> send(StorageNetwork.ProductionAction.SAVE));
        start = button(112, 212, 96, label("start"), b -> send(StorageNetwork.ProductionAction.START));
        cancel = button(214, 212, 96, label("cancel"), b -> send(StorageNetwork.ProductionAction.CANCEL));
        cancel.setTooltip(Tooltip.create(label("cancel_hint")));
        seen = null; savedSignature = ""; context = -1;
    }
    private int runCount() {
        try { return Integer.parseInt(count.getValue()); } catch (NumberFormatException ignored) { return -1; }
    }
    private void send(StorageNetwork.ProductionAction action) {
        CompoundTag view = menu.view(); if (!view.contains("Session")) return;
        StorageNetwork.request(new StorageNetwork.ProductionRequest(menu.containerId, view.getLong("Session"),
                view.getLong("Context"), view.getLong("Revision"), action, name.getValue(), inputFolder, fuelFolder,
                outputFolder, inputEntry, fuelEntry, runCount(), machinePos));
    }
    private MachineRow selectedMachine() {
        return machines.stream().filter(machine -> machine.pos() == machinePos).findFirst().orElse(null);
    }
    private boolean brewing() {
        MachineRow machine = selectedMachine();
        return machine != null && machine.type().equals("brewing");
    }
    private void cycleMachine() {
        if (machines.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < machines.size(); i++) if (machines.get(i).pos() == machinePos) index = i;
        machinePos = machines.get(Math.floorMod(index + (hasShiftDown() ? -1 : 1), machines.size())).pos();
        send(StorageNetwork.ProductionAction.SELECT_MACHINE);
    }
    private void cycleFolder(int type) {
        if (folders.isEmpty()) return;
        int selected = type == 0 ? inputFolder : type == 1 ? fuelFolder : outputFolder, index = -1;
        for (int i = 0; i < folders.size(); i++) if (folders.get(i).id() == selected) index = i;
        int next = folders.get(Math.floorMod(index + (hasShiftDown() ? -1 : 1), folders.size())).id();
        if (type == 0) { inputFolder = next; inputEntry = firstEntry(false); }
        else if (type == 1) { fuelFolder = next; fuelEntry = firstEntry(true); }
        else outputFolder = next;
    }
    private List<EntryRow> candidates(boolean fuel) {
        int folder = fuel ? fuelFolder : inputFolder;
        return entries.stream().filter(entry -> entry.folder() == folder && (fuel ? entry.fuel() : entry.smeltable())).toList();
    }
    private int firstEntry(boolean fuel) {
        List<EntryRow> choices = candidates(fuel); return choices.isEmpty() ? 0 : choices.get(0).id();
    }
    private int nextEntry(boolean fuel) {
        List<EntryRow> choices = candidates(fuel); if (choices.isEmpty()) return 0;
        int selected = fuel ? fuelEntry : inputEntry, index = -1;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).id() == selected) index = i;
        return choices.get(Math.floorMod(index + (hasShiftDown() ? -1 : 1), choices.size())).id();
    }
    private EntryRow selectedEntry(boolean fuel) {
        int id = fuel ? fuelEntry : inputEntry;
        return candidates(fuel).stream().filter(entry -> entry.id() == id).findFirst().orElse(null);
    }
    private ItemStack selectedStack(boolean fuel) {
        EntryRow entry = selectedEntry(fuel); if (entry != null) return entry.stack();
        CompoundTag view = menu.view();
        String prefix = fuel ? "Fuel" : "Input";
        if ((fuel ? fuelEntry : inputEntry) == view.getInt(prefix + "Entry")
                && (fuel ? fuelFolder : inputFolder) == view.getInt(prefix + "Folder"))
            return ItemStack.of(view.getCompound(prefix));
        return ItemStack.EMPTY;
    }
    private void appendFolders(ListTag nodes, int parent, String prefix, int depth, Set<Integer> visited) {
        if (depth > 8) return;
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag node = nodes.getCompound(i); int id = node.getInt("Id");
            if (id == 0 || node.getInt("Parent") != parent || !visited.add(id)) continue;
            String path = prefix + node.getString("Name");
            folders.add(new FolderRow(id, path)); appendFolders(nodes, id, path + " / ", depth + 1, visited);
        }
    }
    private String folderName(int id) {
        if (id == 0) return label("root").getString();
        return folders.stream().filter(row -> row.id() == id).map(FolderRow::path).findFirst()
                .orElseGet(() -> label("missing_folder", id).getString());
    }
    private static String signature(CompoundTag view) {
        return view.getString("Name") + "\u0000" + view.getInt("InputFolder") + ":" + view.getInt("FuelFolder") + ":"
                + view.getInt("OutputFolder") + ":" + view.getInt("InputEntry") + ":" + view.getInt("FuelEntry")
                + ":" + view.getLong("MachinePos") + ":" + view.getBoolean("Configured");
    }
    private boolean dirty() {
        CompoundTag view = menu.view();
        return !view.getBoolean("Configured") || !name.getValue().equals(view.getString("Name"))
                || inputFolder != view.getInt("InputFolder") || fuelFolder != view.getInt("FuelFolder")
                || outputFolder != view.getInt("OutputFolder") || inputEntry != view.getInt("InputEntry")
                || fuelEntry != view.getInt("FuelEntry")
                || machinePos != view.getLong(view.contains("SavedMachinePos") ? "SavedMachinePos" : "MachinePos");
    }
    private void cycleLabel(Button button, Component text, int maxWidth) {
        button.setMessage(Component.literal(font.plainSubstrByWidth(text.getString(), maxWidth) + "  ▸"));
        button.setTooltip(Tooltip.create(text.copy().append("\n").append(label("cycle_hint"))));
    }
    private void sampleLabel(Button button, boolean fuel) {
        EntryRow entry = selectedEntry(fuel); ItemStack stack = selectedStack(fuel);
        Component selection = stack.isEmpty() ? label(brewing() ? fuel ? "choose_reagent" : "choose_potion" : fuel ? "choose_fuel" : "choose_input")
                : label("sample", stack.getHoverName(), entry == null ? label("saved_sample") : Component.literal(Long.toString(entry.count())));
        cycleLabel(button, selection, 254);
    }
    private void refresh() {
        CompoundTag view = menu.view();
        if (seen != view) {
            seen = view; folders.clear(); entries.clear(); machines.clear();
            for (Tag tag : view.getList("Machines", Tag.TAG_COMPOUND)) {
                CompoundTag machine = (CompoundTag) tag;
                String machineName = Component.translatable(machine.getString("Label")).getString();
                if (!machine.getString("Coordinates").isEmpty()) machineName += " @ " + machine.getString("Coordinates");
                machines.add(new MachineRow(machine.getLong("Pos"), machine.getString("Type"), machineName, machine.getBoolean("Connected")));
            }
            ListTag nodes = view.getList("Folders", Tag.TAG_COMPOUND);
            if (!nodes.isEmpty()) { folders.add(new FolderRow(0, label("root").getString())); appendFolders(nodes, 0, "", 1, new HashSet<>()); }
            ListTag samples = view.getList("Entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < samples.size(); i++) {
                CompoundTag entry = samples.getCompound(i);
                entries.add(new EntryRow(entry.getInt("Id"), entry.getInt("Folder"), ItemStack.of(entry.getCompound("Stack")),
                        entry.getLong("Count"), entry.getBoolean("Smeltable"), entry.getBoolean("Fuel")));
            }
            String signature = signature(view);
            if (context != view.getLong("Context") || !savedSignature.equals(signature)) {
                context = view.getLong("Context"); savedSignature = signature;
                name.setValue(view.getString("Name")); count.setValue(Integer.toString(Math.max(1, view.getInt("Count"))));
                inputFolder = view.getInt("InputFolder"); fuelFolder = view.getInt("FuelFolder"); outputFolder = view.getInt("OutputFolder");
                inputEntry = view.getInt("InputEntry"); fuelEntry = view.getInt("FuelEntry");
                machinePos = view.getLong("MachinePos");
            }
            if (!view.getBoolean("Configured")) {
                if (inputEntry <= 0) inputEntry = firstEntry(false);
                if (fuelEntry <= 0) fuelEntry = firstEntry(true);
            }
            if (!view.getString("Message").isEmpty()) { notice = view.getString("Message"); noticeUntil = Util.getMillis() + 4500; }
        }
        MachineRow machine = selectedMachine();
        Component machineLabel = machine == null ? label("choose_machine") : label("machine", machine.label());
        cycleLabel(machineButton, machineLabel, 276);
        machineButton.setTooltip(Tooltip.create(machineLabel.copy().append("\n").append(label("machine_hint"))));
        cycleLabel(inputFolderButton, label(brewing() ? "potion_folder" : "input_folder", folderName(inputFolder)), 276);
        cycleLabel(fuelFolderButton, label(brewing() ? "reagent_folder" : "fuel_folder", folderName(fuelFolder)), 276);
        if (brewing()) fuelFolderButton.setTooltip(Tooltip.create(label("reagent_folder_hint")));
        cycleLabel(outputFolderButton, label("output_folder", folderName(outputFolder)), 276);
        sampleLabel(ingredientButton, false); sampleLabel(fuelButton, true);
        boolean ready = view.contains("Session") && !view.getBoolean("Locked"), running = view.getBoolean("Running");
        boolean editable = ready && !running;
        machineButton.active = editable && !machines.isEmpty();
        name.setEditable(editable); count.setEditable(editable);
        count.setTooltip(Tooltip.create(label(brewing() ? "brewing_count_hint" : "count_hint")));
        inputFolderButton.active = fuelFolderButton.active = outputFolderButton.active = editable && !folders.isEmpty();
        ingredientButton.active = editable && !candidates(false).isEmpty(); fuelButton.active = editable && !candidates(true).isEmpty();
        boolean folderExists = folders.stream().anyMatch(folder -> folder.id() == outputFolder);
        save.active = editable && machine != null && !name.getValue().isBlank() && name.getValue().length() <= 24
                && folderExists && selectedEntry(false) != null && selectedEntry(true) != null;
        start.active = editable && machine != null && machine.connected() && view.getBoolean("Configured") && !dirty()
                && runCount() >= 1 && runCount() <= 4096;
        start.setTooltip(Tooltip.create(label(dirty() ? "save_first" : "start_hint")));
        cancel.active = ready && running;
    }
    @Override protected void containerTick() { super.containerTick(); name.tick(); count.tick(); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) { onClose(); return true; }
        // Typing inventory's key into either field must not close the program editor.
        if (key != 256 && (name.isFocused() || count.isFocused())) {
            if (name.isFocused() && name.keyPressed(key, scan, modifiers)) return true;
            if (count.isFocused() && count.keyPressed(key, scan, modifiers)) return true;
            if (minecraft.options.keyInventory.matches(key, scan)) return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() {
        if (menu.view().contains("Session")) send(StorageNetwork.ProductionAction.BACK);
        else super.onClose();
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh(); renderBackground(g); super.render(g, mouseX, mouseY, partialTick);
        int x = mouseX - leftPos, y = mouseY - topPos;
        if (x >= 10 && x < 310 && y >= 182 && y < 194) g.renderTooltip(font, status(), mouseX, mouseY);
        if (x >= 10 && x < 310 && y >= 196 && y < 210) g.renderTooltip(font, hint(), mouseX, mouseY);
        if (x >= 10 && x < 28) {
            ItemStack stack = y >= 80 && y < 98 ? selectedStack(false) : y >= 120 && y < 138 ? selectedStack(true)
                    : y >= 162 && y < 180 ? ItemStack.of(menu.view().getCompound("Result")) : ItemStack.EMPTY;
            if (!stack.isEmpty()) g.renderTooltip(font, stack, mouseX, mouseY);
        }
    }
    private Component status() {
        String key = menu.view().getString("Status");
        return key.isEmpty() ? label("loading") : Component.translatable("message.itemexplorer." + key);
    }
    private Component hint() {
        return Util.getMillis() < noticeUntil ? Component.translatable("message.itemexplorer." + notice)
                : label(menu.view().getBoolean("Running") ? "running_hint" : dirty() ? "save_first" : "ready_hint");
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    private void icon(GuiGraphics g, ItemStack stack, int y) {
        g.fill(leftPos + 10, topPos + y, leftPos + 28, topPos + y + 18, 0xff8b8b8b);
        if (!stack.isEmpty()) g.renderItem(stack, leftPos + 11, topPos + y + 1);
    }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff373737);
        g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffeeeeee);
        g.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xffc6c6c6);
        g.drawString(font, font.plainSubstrByWidth(title.getString(), 272), leftPos + 10, topPos + 9, 0xff303030, false);
        g.drawString(font, label("name"), leftPos + 10, topPos + 43, 0xff303030, false);
        g.drawString(font, label("count"), leftPos + 207, topPos + 43, 0xff303030, false);
        icon(g, selectedStack(false), 80); icon(g, selectedStack(true), 120);
        ItemStack result = ItemStack.of(menu.view().getCompound("Result")); icon(g, result, 162);
        String output = result.isEmpty() ? label("no_recipe").getString() : label("result", result.getHoverName()).getString();
        g.drawString(font, font.plainSubstrByWidth(output, 167), leftPos + 32, topPos + 167, 0xff303030, false);
        Component progress = label("progress", menu.view().getInt("Completed"), Math.max(1, menu.view().getInt("Count")));
        g.drawString(font, progress, leftPos + 310 - font.width(progress), topPos + 167, 0xff303030, false);
        g.drawString(font, font.plainSubstrByWidth(status().getString(), 300), leftPos + 10, topPos + 184, 0xff505050, false);
        g.drawString(font, font.plainSubstrByWidth(hint().getString(), 300), leftPos + 10, topPos + 199, 0xff505050, false);
    }
}
