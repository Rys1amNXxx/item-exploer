package dev.itemexplorer.client;

import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.production.ProcessingPattern;
import net.minecraft.Util;
import net.minecraft.core.Direction;
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
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;

/** A terminal program file editor; the selected production port only executes its definition. */
public final class ProductionPortScreen extends AbstractContainerScreen<ProductionPortMenu> {
    private record FolderRow(int id, String path) {}
    private record EntryRow(int id, int folder, ItemStack stack, long count, boolean smeltable, boolean fuel) {}
    private record MachineRow(long pos, String type, String label, boolean connected) {}
    private static final class MaterialRow {
        int folder, entry, amount;
        ItemStack stack;
        Direction face;
        MaterialRow(int folder, int entry, ItemStack stack, int amount) { this(folder, entry, stack, amount, null); }
        MaterialRow(int folder, int entry, ItemStack stack, int amount, Direction face) {
            this.folder = folder; this.entry = entry; this.stack = stack.copy(); this.amount = amount; this.face = face;
        }
        MaterialRow copy() { return new MaterialRow(folder, entry, stack, amount, face); }
    }
    private record Draft(String name, String count, int inputFolder, int fuelFolder, int outputFolder,
                         int inputEntry, int fuelEntry, List<MaterialRow> inputs, List<MaterialRow> outputs,
                         Direction inputFace, Direction outputFace) {}

    private record PickerOption(long id, String label, String detail, ItemStack stack) {}
    private record TotalRow(ItemStack stack, long amount, int folder, boolean estimated) {}
    private record PendingSave(long serial, long session, long context, long revision, long machine, CompoundTag expected, int runs, long deadline) {}
    private final List<FolderRow> folders = new ArrayList<>();
    private final List<EntryRow> entries = new ArrayList<>();
    private final List<MachineRow> machines = new ArrayList<>();
    private final List<MaterialRow> patternInputs = new ArrayList<>(), patternOutputs = new ArrayList<>();
    private final Map<Long, Draft> drafts = new HashMap<>();
    private final List<AbstractWidget> mainWidgets = new ArrayList<>();
    private final Button[] inputGrid = new Button[9], outputGrid = new Button[9], pickerButtons = new Button[6];
    private EditBox name, count, amount, search;
    private Button machineButton, recipeTab, connectionTab, runTab, changeMaterial, deleteMaterial, materialFolder;
    private Button inputFaceButton, outputFaceButton, materialSelector, materialFaceButton, save, runAction, cancel, back;
    private Button inputUp, inputDown, outputUp, outputDown, pickerBack, pickerPrevious, pickerNext;
    private int inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry, page, rowIndex, inputScroll, outputScroll, pickerOffset;
    private Direction inputFace = Direction.UP, outputFace = Direction.DOWN;
    private boolean outputTabSelected, updatingAmount, selectionPending, pickerOpen;
    private long context = -1, machinePos, noticeUntil, pickerSession, pickerContext, pickerMachine;
    private String savedSignature = "", notice = "";
    private CompoundTag seen;
    private PendingSave pendingSave;
    private Component pickerTitle = Component.empty();
    private List<PickerOption> pickerOptions = List.of(), pickerResults = List.of();
    private Consumer<PickerOption> pickerSelection;

    public ProductionPortScreen(ProductionPortMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 320; imageHeight = 238; }
    private Component label(String key, Object... args) { return Component.translatable("gui.itemexplorer.production_" + key, args); }
    private Button button(int x, int y, int width, Component text, Button.OnPress pressed) { return addRenderableWidget(Button.builder(text, pressed).bounds(leftPos + x, topPos + y, width, 18).build()); }
    @Override protected void init() {
        Draft resized = name == null ? null : captureDraft();
        super.init(); mainWidgets.clear(); pickerOpen = false;
        name = addRenderableWidget(new EditBox(font, leftPos + 43, topPos + 7, 235, 16, label("name"))); name.setMaxLength(24);
        back = button(290, 6, 20, label("back_symbol"), b -> onClose()); back.setTooltip(Tooltip.create(label("back")));
        machineButton = button(10, 27, 300, Component.empty(), b -> openMachinePicker());
        recipeTab = button(10, 49, 96, label("recipe_tab"), b -> selectPage(0));
        connectionTab = button(112, 49, 96, label("connection_tab"), b -> selectPage(1));
        runTab = button(214, 49, 96, label("run_tab"), b -> selectPage(2));
        for (int i = 0; i < 9; i++) {
            final int index = i;
            inputGrid[i] = addRenderableWidget(Button.builder(Component.empty(), b -> clickMaterial(false, index)).bounds(leftPos + 24 + i % 3 * 20, topPos + 86 + i / 3 * 20, 20, 20).build());
            outputGrid[i] = addRenderableWidget(Button.builder(Component.empty(), b -> clickMaterial(true, index)).bounds(leftPos + 236 + i % 3 * 20, topPos + 86 + i / 3 * 20, 20, 20).build());
        }
        amount = addRenderableWidget(new EditBox(font, leftPos + 61, topPos + 162, 37, 16, label("amount")));
        amount.setMaxLength(4); amount.setFilter(value -> value.matches("[0-9]{0,4}"));
        amount.setResponder(value -> { if (!updatingAmount && generic() && currentMaterial() != null) currentMaterial().amount = integer(value); });
        amount.setTooltip(Tooltip.create(label("amount_hint")));
        changeMaterial = button(106, 161, 92, label("change_material"), b -> openItemPicker());
        deleteMaterial = button(204, 161, 106, label("delete_material"), b -> deleteMaterial());
        materialFolder = button(10, 182, 300, Component.empty(), b -> openFolderPicker());
        inputFaceButton = button(10, 89, 147, Component.empty(), b -> openFacePicker(0));
        outputFaceButton = button(163, 89, 147, Component.empty(), b -> openFacePicker(1));
        materialSelector = button(10, 113, 300, Component.empty(), b -> openMaterialPicker());
        materialFaceButton = button(10, 137, 300, Component.empty(), b -> openFacePicker(2));
        count = addRenderableWidget(new EditBox(font, leftPos + 64, topPos + 73, 54, 16, label("count")));
        count.setMaxLength(4); count.setFilter(value -> value.matches("[0-9]{0,4}"));
        inputUp = button(140, 106, 16, Component.literal("↑"), b -> scrollTotals(false, -1));
        inputDown = button(140, 172, 16, Component.literal("↓"), b -> scrollTotals(false, 1));
        outputUp = button(294, 106, 16, Component.literal("↑"), b -> scrollTotals(true, -1));
        outputDown = button(294, 172, 16, Component.literal("↓"), b -> scrollTotals(true, 1));
        save = button(10, 214, 96, label("save"), b -> requestSave(0));
        runAction = button(112, 214, 198, label("save_test"), b -> requestSaveAndRun(page == 2 ? runCount() : 1));
        cancel = button(112, 214, 198, label("cancel"), b -> { pendingSave = null; send(StorageNetwork.ProductionAction.CANCEL, 1); });
        cancel.setTooltip(Tooltip.create(label("cancel_hint")));
        for (var child : children()) if (child instanceof AbstractWidget widget) mainWidgets.add(widget);
        search = addRenderableWidget(new EditBox(font, leftPos + 21, topPos + 40, 278, 16, label("search")));
        search.setMaxLength(64); search.setResponder(value -> filterPicker());
        for (int i = 0; i < pickerButtons.length; i++) { final int index = i; pickerButtons[i] = button(20, 63 + i * 22, 280, Component.empty(), b -> choosePickerResult(pickerOffset + index)); }
        pickerBack = button(20, 203, 136, label("picker_back"), b -> closePicker());
        pickerPrevious = button(164, 203, 64, Component.literal("‹"), b -> { pickerOffset = Math.max(0, pickerOffset - 6); refreshPicker(); });
        pickerNext = button(236, 203, 64, Component.literal("›"), b -> { pickerOffset = Math.min(Math.max(0, pickerResults.size() - 1), pickerOffset + 6); refreshPicker(); });
        if (resized != null) restoreDraft(resized); else { context = -1; savedSignature = ""; }
        seen = null; refresh();
    }
    private static int integer(String text) { try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { return 0; } }
    private int runCount() { return integer(count.getValue()); }
    private boolean editable() { return menu.view().contains("Session") && !menu.view().getBoolean("Locked") && !menu.view().getBoolean("Running") && !selectionPending && pendingSave == null; }
    public void selectPage(int next) { page = Math.max(0, Math.min(2, next)); inputScroll = outputScroll = 0; setFocused(null); refreshWidgets(); }
    public void selectMachine(long position) {
        if (!editable() || position == machinePos || machines.stream().noneMatch(machine -> machine.pos() == position)) return;
        drafts.put(machinePos, captureDraft()); machinePos = position; selectionPending = true; send(StorageNetwork.ProductionAction.SELECT_MACHINE, 1);
    }
    private void send(StorageNetwork.ProductionAction action, int runs) {
        CompoundTag view = menu.view(); if (!view.contains("Session")) return;
        StorageNetwork.request(new StorageNetwork.ProductionRequest(menu.containerId, view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"), action, name.getValue(), inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry, runs, machinePos));
    }
    public void requestSaveAndRun(int runs) {
        if (runs < 1 || runs > 4096 || !editable()) return;
        if (!dirty() && menu.view().getBoolean("Configured") && selectedMachine() != null && selectedMachine().connected()) { page = 2; send(StorageNetwork.ProductionAction.START, runs); return; }
        requestSave(runs);
    }
    private void requestSave(int runs) {
        if (!canSave()) return;
        CompoundTag view = menu.view();
        pendingSave = new PendingSave(view.getCompound("SaveReceipt").getLong("Serial"), view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"), machinePos, draftDocument(), runs, Util.getMillis() + 10_000);
        if (generic()) StorageNetwork.request(new StorageNetwork.ProcessingPatternRequest(menu.containerId, view.getLong("Session"), view.getLong("Context"), view.getLong("Revision"), name.getValue(), machinePos, inputFace, outputFace, requestRows(patternInputs), requestRows(patternOutputs)));
        else send(StorageNetwork.ProductionAction.SAVE, Math.max(1, runCount()));
        refreshWidgets();
    }
    private CompoundTag draftDocument() {
        CompoundTag tag = new CompoundTag(); tag.putString("Name", name.getValue().strip()); tag.putLong("Machine", machinePos);
        if (generic()) tag.put("Pattern", draftPattern().save());
        else { tag.putInt("InputFolder", inputFolder); tag.putInt("FuelFolder", fuelFolder); tag.putInt("OutputFolder", outputFolder); tag.putInt("InputEntry", inputEntry); tag.putInt("FuelEntry", fuelEntry); }
        return tag;
    }
    private static CompoundTag savedDocument(CompoundTag view) {
        CompoundTag tag = new CompoundTag(); tag.putString("Name", view.getString("Name")); tag.putLong("Machine", view.getLong("SavedMachinePos"));
        if (view.contains("Pattern", Tag.TAG_COMPOUND)) tag.put("Pattern", view.getCompound("Pattern").copy());
        else for (String key : new String[]{"InputFolder", "FuelFolder", "OutputFolder", "InputEntry", "FuelEntry"}) tag.putInt(key, view.getInt(key));
        return tag;
    }
    private boolean acknowledgeSave(CompoundTag view) {
        PendingSave pending = pendingSave; if (pending == null) return false;
        if (Util.getMillis() > pending.deadline() || view.getLong("Session") != pending.session() || view.getLong("Context") != pending.context() || view.getLong("MachinePos") != pending.machine()) { pendingSave = null; showNotice("production_save_queue_changed"); return false; }
        CompoundTag receipt = view.getCompound("SaveReceipt");
        if (receipt.getLong("Serial") <= pending.serial()) {
            if (view.getLong("Revision") != pending.revision()) { pendingSave = null; showNotice("production_save_queue_changed"); }
            return false;
        }
        pendingSave = null;
        boolean matching = receipt.getLong("RequestContext") == pending.context() && receipt.getLong("RequestRevision") == pending.revision() && receipt.getLong("MachinePos") == pending.machine();
        boolean success = matching && receipt.getBoolean("Success") && view.getLong("Revision") == receipt.getLong("Revision") && view.getLong("SavedMachinePos") == pending.machine()
                && view.getBoolean("Configured") && !view.getBoolean("Running") && !view.getBoolean("Locked") && view.getBoolean("MachineOnline")
                && selectedMachine() != null && selectedMachine().connected() && pending.expected().equals(savedDocument(view));
        if (!success) { showNotice(matching && !receipt.getBoolean("Success") ? receipt.getString("Message") : "production_save_queue_changed"); return false; }
        String runs = count.getValue(); loadSaved(view, true); count.setValue(runs); drafts.clear(); showNotice("production_saved");
        if (pending.runs() > 0) { page = 2; send(StorageNetwork.ProductionAction.START, pending.runs()); }
        return true;
    }

    private MachineRow selectedMachine() {
        return machines.stream().filter(machine -> machine.pos() == machinePos).findFirst().orElse(null);
    }
    private boolean brewing() {
        MachineRow machine = selectedMachine();
        return machine != null && machine.type().equals("brewing");
    }
    private boolean generic() {
        MachineRow machine = selectedMachine();
        if (machine != null && !machine.type().isEmpty()) return machine.type().equals("item_handler");
        CompoundTag view = menu.view();
        return view.getString("MachineType").equals("item_handler")
                || machinePos == view.getLong("SavedMachinePos") && view.contains("Pattern", Tag.TAG_COMPOUND);
    }
    private static List<MaterialRow> copyRows(List<MaterialRow> rows) { return rows.stream().map(MaterialRow::copy).toList(); }
    private Draft captureDraft() {
        return new Draft(name.getValue(), count.getValue(), inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry,
                copyRows(patternInputs), copyRows(patternOutputs), inputFace, outputFace);
    }
    private void restoreDraft(Draft draft) {
        name.setValue(draft.name()); count.setValue(draft.count());
        inputFolder = draft.inputFolder(); fuelFolder = draft.fuelFolder(); outputFolder = draft.outputFolder();
        inputEntry = draft.inputEntry(); fuelEntry = draft.fuelEntry();
        patternInputs.clear(); patternInputs.addAll(copyRows(draft.inputs()));
        patternOutputs.clear(); patternOutputs.addAll(copyRows(draft.outputs()));
        inputFace = draft.inputFace(); outputFace = draft.outputFace(); rowIndex = 0; showAmount();
    }
    private void loadSaved(CompoundTag view, boolean configuredMachine) {
        name.setValue(view.getString("Name")); count.setValue(Integer.toString(Math.max(1, view.getInt("Count"))));
        inputFolder = configuredMachine ? view.getInt("InputFolder") : 0;
        fuelFolder = configuredMachine ? view.getInt("FuelFolder") : 0;
        outputFolder = configuredMachine ? view.getInt("OutputFolder") : 0;
        inputEntry = configuredMachine ? view.getInt("InputEntry") : 0;
        fuelEntry = configuredMachine ? view.getInt("FuelEntry") : 0;
        patternInputs.clear(); patternOutputs.clear(); inputFace = Direction.UP; outputFace = Direction.DOWN;
        if (configuredMachine && view.contains("Pattern", Tag.TAG_COMPOUND)) {
            try {
                ProcessingPattern pattern = ProcessingPattern.load(view.getCompound("Pattern"));
                inputFace = pattern.inputFace(); outputFace = pattern.outputFace();
                loadMaterials(patternInputs, pattern.inputs()); loadMaterials(patternOutputs, pattern.outputs());
            } catch (IllegalArgumentException ignored) { }
        }


        rowIndex = 0; showAmount();
    }
    private void loadMaterials(List<MaterialRow> target, List<ProcessingPattern.Material> source) {
        for (int i = 0; i < source.size(); i++) {
            var material = source.get(i);
            target.add(new MaterialRow(material.folder(), -i - 1, material.stack(), material.stack().getCount(), material.face()));
        }
    }
    private void rebindSavedReferences(CompoundTag view) {
        ProcessingPattern pattern = null;
        try { if (view.contains("Pattern", Tag.TAG_COMPOUND)) pattern = ProcessingPattern.load(view.getCompound("Pattern")); }
        catch (IllegalArgumentException ignored) { }
        List<ProcessingPattern.Material> inputs = pattern == null ? List.of() : pattern.inputs();
        List<ProcessingPattern.Material> outputs = pattern == null ? List.of() : pattern.outputs();
        rebindRows(patternInputs, inputs, true); rebindRows(patternOutputs, outputs, false);
        for (Draft draft : drafts.values()) { rebindRows(draft.inputs(), inputs, true); rebindRows(draft.outputs(), outputs, false); }
    }
    private void rebindRows(List<MaterialRow> rows, List<ProcessingPattern.Material> previous, boolean input) {
        for (MaterialRow row : rows) {
            if (row.entry >= 0) continue;
            row.entry = 0;
            for (int i = 0; i < previous.size(); i++) if (ItemStack.isSameItemSameTags(previous.get(i).stack(), row.stack)) {
                row.entry = -i - 1; break;
            }
            if (row.entry == 0) row.entry = entries.stream()
                    .filter(entry -> (!input || entry.folder() == row.folder) && ItemStack.isSameItemSameTags(entry.stack(), row.stack))
                    .mapToInt(EntryRow::id).findFirst().orElse(0);
        }
    }
    private List<MaterialRow> currentRows() { return outputTabSelected ? patternOutputs : patternInputs; }
    private MaterialRow currentMaterial() {
        List<MaterialRow> rows = currentRows();
        return rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex) : null;
    }
    private void showAmount() {
        if (amount == null) return;
        updatingAmount = true; amount.setValue(Integer.toString(currentMaterial() == null ? 1 : currentMaterial().amount)); updatingAmount = false;
    }
    private static List<StorageNetwork.PatternRow> requestRows(List<MaterialRow> rows) {
        return rows.stream().map(row -> new StorageNetwork.PatternRow(row.folder, row.entry, row.amount, row.face == null ? -1 : row.face.ordinal())).toList();
    }
    private static List<ProcessingPattern.Material> patternMaterials(List<MaterialRow> rows) {
        return rows.stream().map(row -> {
            ItemStack stack = row.stack.copy(); stack.setCount(row.amount); return new ProcessingPattern.Material(row.folder, stack, row.face);
        }).toList();
    }
    private ProcessingPattern draftPattern() {
        return new ProcessingPattern(inputFace, outputFace, patternMaterials(patternInputs), patternMaterials(patternOutputs));
    }
    private boolean validMaterials(List<MaterialRow> rows) {
        return !rows.isEmpty() && rows.size() <= 9 && rows.stream().allMatch(row -> row.entry != 0 && !row.stack.isEmpty()
                && row.amount >= 1 && row.amount <= row.stack.getMaxStackSize() && row.amount <= 9999
                && folders.stream().anyMatch(folder -> folder.id() == row.folder));
    }
    private boolean validPattern() {
        if (!validMaterials(patternInputs) || !validMaterials(patternOutputs)) return false;
        if (patternProblem() != null) return false;
        try { draftPattern(); return true; } catch (IllegalArgumentException ignored) { return false; }
    }
    private Component patternProblem() {
        if (patternInputs.isEmpty() && patternOutputs.isEmpty()) return label("add_both_materials");
        if (patternInputs.isEmpty()) return label("add_inputs");
        if (patternOutputs.isEmpty()) return label("add_outputs");
        for (boolean output : new boolean[]{false, true}) for (MaterialRow row : output ? patternOutputs : patternInputs) {
            if (row.stack.isEmpty() || row.entry == 0) return label("select_missing_sample");
            if (row.amount < 1 || row.amount > row.stack.getMaxStackSize()) return label("fix_quantity", row.stack.getMaxStackSize());
            if (folders.stream().noneMatch(folder -> folder.id() == row.folder)) return label("select_missing_folder");
            if (row.entry > 0 && entries.stream().noneMatch(entry -> entry.id() == row.entry && (output || entry.folder() == row.folder)
                    && ItemStack.isSameItemSameTags(entry.stack(), row.stack))) return label("select_missing_sample");
        }
        for (MaterialRow input : patternInputs) for (MaterialRow output : patternOutputs)
            if (ProcessingPattern.matches(input.stack, output.stack)) return label("remove_input_output_overlap");
        return null;
    }
    private Component configurationProblem() {
        if (name.getValue().isBlank()) return label("enter_name");
        if (generic()) return patternProblem();
        if (selectedStack(false).isEmpty()) return label("select_vanilla_input");
        if (selectedStack(true).isEmpty()) return label(brewing() ? "select_vanilla_reagent" : "select_vanilla_fuel");
        if (folders.stream().noneMatch(folder -> folder.id() == outputFolder)) return label("select_missing_folder");
        if (dirty() && (selectedEntry(false) == null || selectedEntry(true) == null)) return label("select_missing_sample");
        return null;
    }
    private Component faceLabel(Direction face) { return label("face_" + face.getName()); }
    private List<EntryRow> candidates(boolean fuel) {
        int folder = fuel ? fuelFolder : inputFolder;
        return entries.stream().filter(entry -> entry.folder() == folder && (fuel ? entry.fuel() : entry.smeltable())).toList();
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
                + ":" + view.getLong("SavedMachinePos") + ":" + view.getBoolean("Configured") + ":" + view.getCompound("Pattern");
    }
    private boolean dirty() {
        CompoundTag view = menu.view();
        if (generic()) {
            if (!view.getBoolean("Configured") || !view.contains("Pattern", Tag.TAG_COMPOUND)
                    || !name.getValue().equals(view.getString("Name")) || machinePos != view.getLong("SavedMachinePos")) return true;
            try { return !draftPattern().save().equals(view.getCompound("Pattern")); }
            catch (IllegalArgumentException ignored) { return true; }
        }
        return !view.getBoolean("Configured") || !name.getValue().equals(view.getString("Name"))
                || inputFolder != view.getInt("InputFolder") || fuelFolder != view.getInt("FuelFolder")
                || outputFolder != view.getInt("OutputFolder") || inputEntry != view.getInt("InputEntry")
                || fuelEntry != view.getInt("FuelEntry")
                || machinePos != view.getLong(view.contains("SavedMachinePos") ? "SavedMachinePos" : "MachinePos");
    }

    public void selectMaterial(boolean output, int index) { outputTabSelected = output; rowIndex = Math.max(0, index); showAmount(); }
    private void clickMaterial(boolean output, int index) {
        if (pickerOpen) return;
        selectMaterial(output, generic() ? Math.min(index, (output ? patternOutputs : patternInputs).size()) : index);
        if (editable() && (generic() ? currentMaterial() == null : !output && selectedStack(rowIndex == 1).isEmpty())) openItemPicker();
        refreshWidgets();
    }
    private void deleteMaterial() { if (editable() && generic() && currentMaterial() != null) { currentRows().remove(rowIndex); rowIndex = Math.max(0, Math.min(rowIndex, currentRows().size() - 1)); showAmount(); } }
    public void selectItemEntry(int id) {
        if (!editable()) return; EntryRow entry = entries.stream().filter(row -> row.id() == id).findFirst().orElse(null); if (entry == null) return;
        if (generic()) {
            MaterialRow row = currentMaterial();
            if (row == null) { if (currentRows().size() >= 9) return; row = new MaterialRow(outputTabSelected ? 0 : entry.folder(), id, entry.stack(), 1); currentRows().add(row); rowIndex = currentRows().size() - 1; }
            else { row.entry = id; row.stack = entry.stack().copy(); if (!outputTabSelected) row.folder = entry.folder(); row.amount = Math.max(1, Math.min(row.amount, row.stack.getMaxStackSize())); }
        } else if (!outputTabSelected) {
            if (rowIndex == 1 && entry.fuel()) { fuelFolder = entry.folder(); fuelEntry = id; }
            else if (rowIndex == 0 && entry.smeltable()) { inputFolder = entry.folder(); inputEntry = id; }
        }
        showAmount();
    }
    public void selectFolder(int folder) {
        if (!editable() || folders.stream().noneMatch(row -> row.id() == folder)) return;
        if (generic()) {
            MaterialRow row = currentMaterial(); if (row == null) return; row.folder = folder;
            if (!outputTabSelected && row.entry >= 0) row.entry = entries.stream().filter(entry -> entry.folder() == folder && ItemStack.isSameItemSameTags(entry.stack(), row.stack)).mapToInt(EntryRow::id).findFirst().orElse(0);
        } else if (outputTabSelected) outputFolder = folder;
        else if (rowIndex == 1) { ItemStack sample = selectedStack(true); fuelFolder = folder; fuelEntry = entries.stream().filter(entry -> entry.folder() == folder && entry.fuel() && ItemStack.isSameItemSameTags(entry.stack(), sample)).mapToInt(EntryRow::id).findFirst().orElse(0); }
        else { ItemStack sample = selectedStack(false); inputFolder = folder; inputEntry = entries.stream().filter(entry -> entry.folder() == folder && entry.smeltable() && ItemStack.isSameItemSameTags(entry.stack(), sample)).mapToInt(EntryRow::id).findFirst().orElse(0); }
    }
    private void openMachinePicker() { openPicker(label("pick_machine"), machines.stream().map(machine -> new PickerOption(machine.pos(), machine.label(), label(machine.connected() ? "connected" : "offline").getString(), ItemStack.EMPTY)).toList(), option -> selectMachine(option.id())); }
    private void openItemPicker() {
        if (!editable() || !generic() && outputTabSelected) return;
        openPicker(label(generic() && outputTabSelected ? "pick_output" : "pick_input"), entries.stream().filter(entry -> generic() || (rowIndex == 1 ? entry.fuel() : entry.smeltable())).map(entry -> new PickerOption(entry.id(), entry.stack().getHoverName().getString(), folderName(entry.folder()) + " · " + entry.count(), entry.stack())).toList(), option -> selectItemEntry((int) option.id()));
    }
    private void openFolderPicker() { openPicker(label(outputTabSelected ? "pick_destination" : "pick_source"), folders.stream().map(folder -> new PickerOption(folder.id(), folder.path(), "", ItemStack.EMPTY)).toList(), option -> selectFolder((int) option.id())); }
    private void openFacePicker(int target) {
        List<PickerOption> options = new ArrayList<>();
        if (target == 2) options.add(new PickerOption(-1, label("default_face").getString(), label("inherits_face").getString(), ItemStack.EMPTY));
        for (Direction face : Direction.values()) options.add(new PickerOption(face.ordinal(), faceLabel(face).getString(), label("absolute_direction").getString(), ItemStack.EMPTY));
        openPicker(label("pick_face"), options, option -> { Direction face = option.id() < 0 ? null : Direction.values()[(int) option.id()]; if (target == 0) inputFace = face; else if (target == 1) outputFace = face; else if (currentMaterial() != null) currentMaterial().face = face; });
    }
    private void openMaterialPicker() {
        List<PickerOption> options = new ArrayList<>();
        if (!generic()) {
            options.add(new PickerOption(0, label(brewing() ? "base_potion" : "vanilla_input").getString(), folderName(inputFolder), selectedStack(false)));
            options.add(new PickerOption(1, label(brewing() ? "brewing_reagent" : "vanilla_fuel").getString(), folderName(fuelFolder), selectedStack(true)));
            options.add(new PickerOption(100, label("derived_output").getString(), folderName(outputFolder), derivedResult()));
        }
        for (int i = 0; i < patternInputs.size(); i++) options.add(new PickerOption(i, label("input_row", i + 1, patternInputs.size()).getString() + " · " + patternInputs.get(i).stack.getHoverName().getString(), folderName(patternInputs.get(i).folder), patternInputs.get(i).stack));
        for (int i = 0; i < patternOutputs.size(); i++) options.add(new PickerOption(100 + i, label("output_row", i + 1, patternOutputs.size()).getString() + " · " + patternOutputs.get(i).stack.getHoverName().getString(), folderName(patternOutputs.get(i).folder), patternOutputs.get(i).stack));
        openPicker(label("pick_material"), options, option -> selectMaterial(option.id() >= 100, (int) option.id() % 100));
    }
    private void openPicker(Component title, List<PickerOption> options, Consumer<PickerOption> selection) {
        if (!editable()) return;
        pickerTitle = title; pickerOptions = options; pickerSelection = selection; pickerOffset = 0;
        pickerSession = menu.view().getLong("Session"); pickerContext = menu.view().getLong("Context"); pickerMachine = machinePos;
        pickerOpen = true; search.setValue(""); filterPicker(); setFocused(search); search.setFocused(true); refreshWidgets();
    }
    private void filterPicker() { String query = search.getValue().strip().toLowerCase(Locale.ROOT); pickerResults = pickerOptions.stream().filter(option -> (option.label() + " " + option.detail()).toLowerCase(Locale.ROOT).contains(query)).toList(); pickerOffset = 0; refreshPicker(); }
    public void choosePickerResult(int index) {
        if (!pickerOpen || !editable() || index < 0 || index >= pickerResults.size()) return;
        if (menu.view().getLong("Session") != pickerSession || menu.view().getLong("Context") != pickerContext || machinePos != pickerMachine) { closePicker(); return; }
        PickerOption option = pickerResults.get(index); Consumer<PickerOption> selection = pickerSelection; closePicker(); selection.accept(option); refreshWidgets();
    }
    private void closePicker() { pickerOpen = false; search.setFocused(false); setFocused(null); refreshWidgets(); }
    private void refreshPicker() {
        if (search == null) return; search.visible = pickerOpen; search.setEditable(pickerOpen);
        for (int i = 0; i < pickerButtons.length; i++) if (pickerButtons[i] != null) {
            int index = pickerOffset + i; Button button = pickerButtons[i]; button.visible = pickerOpen && index < pickerResults.size(); button.active = button.visible;
            if (index < pickerResults.size()) {
                PickerOption option = pickerResults.get(index);
                int width = option.stack().isEmpty() ? 266 : 240;
                String caption = option.detail().isEmpty() ? font.plainSubstrByWidth(option.label(), width)
                        : font.plainSubstrByWidth(option.label(), width / 2) + " · " + font.plainSubstrByWidth(option.detail(), width / 2 - 12);
                button.setMessage(Component.literal((option.stack().isEmpty() ? "" : "    ") + caption));
                button.setTooltip(Tooltip.create(Component.literal(option.label() + (option.detail().isEmpty() ? "" : "\n" + option.detail()))));
            }
        }
        if (pickerBack != null) { pickerBack.visible = pickerPrevious.visible = pickerNext.visible = pickerOpen; pickerPrevious.active = pickerOffset > 0; pickerNext.active = pickerOffset + 6 < pickerResults.size(); }
    }
    private boolean canSave() { MachineRow machine = selectedMachine(); return editable() && machine != null && machine.connected() && !name.getValue().isBlank() && name.getValue().length() <= 24 && (generic() ? validPattern() : folders.stream().anyMatch(folder -> folder.id() == outputFolder) && selectedEntry(false) != null && selectedEntry(true) != null); }
    private ItemStack derivedResult() {
        CompoundTag view = menu.view();
        return machinePos == view.getLong("SavedMachinePos") && inputEntry == view.getInt("InputEntry") && fuelEntry == view.getInt("FuelEntry") && inputFolder == view.getInt("InputFolder") && fuelFolder == view.getInt("FuelFolder") ? ItemStack.of(view.getCompound("Result")) : ItemStack.EMPTY;
    }
    private void showNotice(String key) { notice = key == null || key.isEmpty() ? "production_save_queue_changed" : key; noticeUntil = Util.getMillis() + 4500; }
    private void refresh() {
        CompoundTag view = menu.view();
        if (seen != view) {
            seen = view; folders.clear(); entries.clear(); machines.clear();
            for (Tag tag : view.getList("Machines", Tag.TAG_COMPOUND)) { CompoundTag machine = (CompoundTag) tag; String machineName = Component.translatable(machine.getString("Label")).getString(); if (!machine.getString("Coordinates").isEmpty()) machineName += " @ " + machine.getString("Coordinates"); machines.add(new MachineRow(machine.getLong("Pos"), machine.getString("Type"), machineName, machine.getBoolean("Connected"))); }
            ListTag nodes = view.getList("Folders", Tag.TAG_COMPOUND); if (!nodes.isEmpty()) { folders.add(new FolderRow(0, label("root").getString())); appendFolders(nodes, 0, "", 1, new HashSet<>()); }
            for (Tag tag : view.getList("Entries", Tag.TAG_COMPOUND)) { CompoundTag entry = (CompoundTag) tag; entries.add(new EntryRow(entry.getInt("Id"), entry.getInt("Folder"), ItemStack.of(entry.getCompound("Stack")), entry.getLong("Count"), entry.getBoolean("Smeltable"), entry.getBoolean("Fuel"))); }
            if (view.contains("Session")) {
                String nextSignature = signature(view); boolean initial = context == -1;
                boolean selected = selectionPending && (context != view.getLong("Context") || !view.getString("Message").isEmpty());
                boolean saved = acknowledgeSave(view);
                if (!initial && !saved && !savedSignature.equals(nextSignature)) rebindSavedReferences(view);
                if (initial || selected) { machinePos = view.getLong("MachinePos"); Draft draft = selected ? drafts.get(machinePos) : null; if (draft != null) restoreDraft(draft); else loadSaved(view, view.getBoolean("Configured") && machinePos == view.getLong("SavedMachinePos")); if (selected) { selectionPending = false; showNotice("production_machine_selected"); } }
                else if (!saved && !savedSignature.equals(nextSignature)) showNotice("production_document_updated");
                context = view.getLong("Context"); savedSignature = nextSignature;
            }
            if (!view.getString("Message").isEmpty()) showNotice(view.getString("Message"));
        } else acknowledgeSave(view);
        if (pickerOpen && (!editable() || view.getLong("Session") != pickerSession || view.getLong("Context") != pickerContext || machinePos != pickerMachine)) closePicker();
        refreshWidgets();
    }
    private void textButton(Button button, Component text, int width) { button.setMessage(Component.literal(font.plainSubstrByWidth(text.getString(), width))); button.setTooltip(Tooltip.create(text)); }
    private void refreshWidgets() {
        if (save == null || search == null) return;
        boolean edit = editable(), running = menu.view().getBoolean("Running"), generic = generic();
        for (AbstractWidget widget : mainWidgets) { widget.visible = !pickerOpen; widget.active = edit; }
        recipeTab.active = page != 0; connectionTab.active = page != 1; runTab.active = page != 2; back.active = true;
        MachineRow machine = selectedMachine(); textButton(machineButton, machine == null ? label("choose_machine") : label("machine", machine.label()), 282); machineButton.active = edit && !machines.isEmpty();
        name.setEditable(edit); count.setEditable(edit); amount.setEditable(edit && generic); count.setTooltip(Tooltip.create(label(generic ? "generic_count_hint" : brewing() ? "brewing_count_hint" : "count_hint")));
        boolean recipe = !pickerOpen && page == 0, connection = !pickerOpen && page == 1, run = !pickerOpen && page == 2;
        for (int i = 0; i < 9; i++) {
            inputGrid[i].visible = recipe && (generic || i < 2); outputGrid[i].visible = recipe && (generic || i == 0); inputGrid[i].active = outputGrid[i].active = !selectionPending && pendingSave == null;
            inputGrid[i].setX(leftPos + (generic ? 24 + i % 3 * 20 : 44)); inputGrid[i].setY(topPos + (generic ? 86 + i / 3 * 20 : 86 + i * 36));
            outputGrid[i].setX(leftPos + (generic ? 236 + i % 3 * 20 : 256)); outputGrid[i].setY(topPos + (generic ? 86 + i / 3 * 20 : 104));
        }
        boolean hasMaterial = generic ? currentMaterial() != null : true;
        amount.visible = changeMaterial.visible = deleteMaterial.visible = recipe;
        if (!generic && !outputTabSelected && rowIndex == 1 && !brewing()) amount.visible = false;
        if (!generic) {
            updatingAmount = true;
            amount.setValue(Integer.toString(outputTabSelected && !derivedResult().isEmpty() ? derivedResult().getCount() : 1));
            updatingAmount = false;
        }
        materialFolder.visible = recipe || connection;
        materialFolder.setY(topPos + (connection && !generic ? 113 : 182));
        amount.active = edit && generic && hasMaterial; amount.setEditable(amount.active); changeMaterial.active = edit && (generic || !outputTabSelected); deleteMaterial.active = edit && generic && hasMaterial; materialFolder.active = edit && hasMaterial && !folders.isEmpty();
        int folder = generic ? currentMaterial() == null ? 0 : currentMaterial().folder : outputTabSelected ? outputFolder : rowIndex == 1 ? fuelFolder : inputFolder;
        textButton(materialFolder, label(outputTabSelected ? "output_folder" : !generic && rowIndex == 1 ? brewing() ? "reagent_folder" : "fuel_folder" : "input_folder", folderName(folder)), 284);
        inputFaceButton.visible = outputFaceButton.visible = materialFaceButton.visible = connection && generic;
        materialSelector.visible = connection;
        materialSelector.setY(topPos + (generic ? 113 : 89));
        textButton(inputFaceButton, label("input_face", faceLabel(inputFace)), 137); textButton(outputFaceButton, label("output_face", faceLabel(outputFace)), 137);
        inputFaceButton.setTooltip(Tooltip.create(label("connection_note"))); outputFaceButton.setTooltip(Tooltip.create(label("connection_note")));
        textButton(materialSelector, selectedMaterialLabel(), 284); textButton(materialFaceButton, label("material_face", currentMaterial() == null || currentMaterial().face == null ? label("default_face") : faceLabel(currentMaterial().face)), 284);
        materialSelector.active = edit && (!generic || !patternInputs.isEmpty() || !patternOutputs.isEmpty()); materialFaceButton.active = edit && currentMaterial() != null;
        count.visible = run && !running; inputUp.visible = inputDown.visible = outputUp.visible = outputDown.visible = run;
        inputScroll = Math.min(inputScroll, Math.max(0, totals(false).size() - 4)); outputScroll = Math.min(outputScroll, Math.max(0, totals(true).size() - 4));
        inputUp.active = inputScroll > 0; inputDown.active = inputScroll + 4 < totals(false).size(); outputUp.active = outputScroll > 0; outputDown.active = outputScroll + 4 < totals(true).size();
        save.visible = runAction.visible = !pickerOpen && !running; cancel.visible = !pickerOpen && running; save.active = canSave();
        boolean cleanRunnable = edit && machine != null && machine.connected() && menu.view().getBoolean("Configured") && !dirty();
        runAction.active = (canSave() || cleanRunnable) && (page != 2 || runCount() >= 1 && runCount() <= 4096); runAction.setMessage(label(page == 2 ? "save_run" : "save_test")); runAction.setTooltip(Tooltip.create(label("save_run_hint")));
        cancel.active = running && !menu.view().getBoolean("Locked");
        if (!amount.visible) amount.setFocused(false); if (!count.visible) count.setFocused(false); refreshPicker();
    }

    private Component selectedMaterialLabel() {
        if (generic()) { MaterialRow row = currentMaterial(); return row == null ? label(outputTabSelected ? "empty_output" : "empty_input") : label(row.entry == 0 ? "missing_material_sample" : outputTabSelected ? "selected_output" : "selected_input", rowIndex + 1, row.stack.getHoverName()); }
        return outputTabSelected ? label("derived_output") : label(rowIndex == 1 ? brewing() ? "brewing_reagent" : "vanilla_fuel" : brewing() ? "base_potion" : "vanilla_input");
    }
    private List<TotalRow> totals(boolean output) {
        CompoundTag view = menu.view();
        if (view.getBoolean("Running")) {
            int runs = Math.max(1, view.getInt("Count")); List<TotalRow> result = new ArrayList<>();
            if (view.contains("Pattern", Tag.TAG_COMPOUND)) {
                try {
                    ProcessingPattern pattern = ProcessingPattern.load(view.getCompound("Pattern"));
                    for (var material : output ? pattern.outputs() : pattern.inputs()) result.add(new TotalRow(material.stack(), (long) material.stack().getCount() * runs, material.folder(), false));
                } catch (IllegalArgumentException ignored) { }
            } else if (output) {
                ItemStack stack = ItemStack.of(view.getCompound("Result"));
                if (!stack.isEmpty()) result.add(new TotalRow(stack, (long) stack.getCount() * runs, view.getInt("OutputFolder"), false));
            } else {
                boolean brewing = "brewing".equals(view.getString("Kind"));
                ItemStack input = ItemStack.of(view.getCompound("Input")), fuel = ItemStack.of(view.getCompound("Fuel"));
                if (!input.isEmpty()) result.add(new TotalRow(input, runs, view.getInt("InputFolder"), false));
                if (!fuel.isEmpty()) result.add(new TotalRow(fuel, brewing ? runs : 0, view.getInt("FuelFolder"), !brewing));
            }
            return result;
        }
        int runs = Math.max(0, runCount()); List<TotalRow> result = new ArrayList<>();
        if (generic()) for (MaterialRow material : output ? patternOutputs : patternInputs) result.add(new TotalRow(material.stack, (long) material.amount * runs, material.folder, false));
        else if (output) { ItemStack stack = derivedResult(); if (!stack.isEmpty()) result.add(new TotalRow(stack, (long) stack.getCount() * runs, outputFolder, false)); }
        else { ItemStack input = selectedStack(false), fuel = selectedStack(true); if (!input.isEmpty()) result.add(new TotalRow(input, runs, inputFolder, false)); if (!fuel.isEmpty()) result.add(new TotalRow(fuel, brewing() ? runs : 0, fuelFolder, !brewing())); }
        return result;
    }
    public List<String> runSummary(boolean output) { return totals(output).stream().map(row -> row.stack().getHoverName().getString() + " × " + (row.estimated() ? label("as_needed").getString() : row.amount())).toList(); }
    private void scrollTotals(boolean output, int delta) { if (output) outputScroll = Math.max(0, Math.min(Math.max(0, totals(true).size() - 4), outputScroll + delta)); else inputScroll = Math.max(0, Math.min(Math.max(0, totals(false).size() - 4), inputScroll + delta)); }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (pickerOpen) { pickerOffset = Math.max(0, Math.min(Math.max(0, pickerResults.size() - 6), pickerOffset + (delta < 0 ? 1 : -1))); refreshPicker(); return true; }
        if (page == 2 && y >= topPos + 101 && y < topPos + 191) { scrollTotals(x >= leftPos + 160, delta < 0 ? 1 : -1); return true; } return super.mouseScrolled(x, y, delta);
    }
    @Override protected void containerTick() { super.containerTick(); name.tick(); count.tick(); amount.tick(); search.tick(); refresh(); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) { if (pickerOpen) closePicker(); else onClose(); return true; }
        for (EditBox edit : List.of(name, count, amount, search)) if (edit.visible && edit.isFocused()) { if (edit.keyPressed(key, scan, modifiers)) return true; if (minecraft.options.keyInventory.matches(key, scan)) return true; }
        if (pickerOpen && minecraft.options.keyInventory.matches(key, scan)) { closePicker(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { if (pickerOpen) { closePicker(); return; } pendingSave = null; if (menu.view().contains("Session")) send(StorageNetwork.ProductionAction.BACK, 1); else super.onClose(); }
    @Override public void removed() { pendingSave = null; super.removed(); }
    private Component status() {
        if (pendingSave != null) return label(pendingSave.runs() > 0 ? "saving_before_run" : "saving");
        if (selectionPending) return label("switching_machine");
        if (menu.view().getBoolean("Running")) return label("progress", menu.view().getInt("Completed"), Math.max(1, menu.view().getInt("Count"))).copy().append(" · ").append(Component.translatable("message.itemexplorer." + menu.view().getString("Status")));
        if (Util.getMillis() < noticeUntil) return Component.translatable("message.itemexplorer." + notice);
        String state = menu.view().getString("Status");
        if (!state.isEmpty() && !Set.of("production_ready", "production_unconfigured", "production_complete", "production_cancelled").contains(state))
            return Component.translatable("message.itemexplorer." + state);
        if (selectedMachine() == null || !selectedMachine().connected() || !menu.view().getBoolean("MachineOnline"))
            return Component.translatable("message.itemexplorer.production_machine_offline");
        Component problem = configurationProblem();
        if (problem != null) return problem;
        if (page == 2 && (runCount() < 1 || runCount() > 4096)) return label("fix_batch_count");
        if (!dirty() && state.equals("production_complete")) return Component.translatable("message.itemexplorer.production_complete");
        return label(dirty() ? "unsaved_changes" : "saved_changes");
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    private void line(GuiGraphics g, Component text, int x, int y, int width) { g.drawString(font, font.plainSubstrByWidth(text.getString(), width), leftPos + x, topPos + y, VanillaGui.panelText(0xff303030), VanillaGui.panelShadow()); }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        VanillaGui.panel(g, leftPos, topPos, imageWidth, imageHeight); line(g, label("name"), 10, 11, 31);
        if (page == 0) {
            line(g, label(generic() ? "inputs" : "vanilla_input", patternInputs.size()), 24, 74, 125); line(g, label(generic() ? "outputs" : "derived_output", patternOutputs.size()), 214, 74, 96);
            line(g, Component.literal("→"), 153, 112, 20); line(g, label("per_batch"), 131, 97, 64);
            if (!generic()) { line(g, label(brewing() ? "base_potion" : "vanilla_input"), 70, 91, 70); line(g, label(brewing() ? "brewing_reagent" : "vanilla_fuel"), 70, 127, 90); if (derivedResult().isEmpty()) line(g, label("derive_after_save"), 199, 129, 109); }
            line(g, selectedMaterialLabel(), 10, 150, 300);
            boolean fuel = !generic() && !outputTabSelected && rowIndex == 1 && !brewing();
            line(g, label(fuel ? "fuel_usage" : "amount"), 10, 166, 49);
            if (fuel) line(g, label("as_needed"), 61, 166, 40);
        } else if (page == 1) {
            line(g, label(generic() ? "absolute_direction" : "native_folders"), 10, 75, 300);
            if (generic()) line(g, label("connection_short_note"), 10, 164, 300);
            else g.drawWordWrap(font, label("vanilla_connection_note"), leftPos + 10, topPos + 140, 300, VanillaGui.panelText(0xff505050));
        } else {
            boolean running = menu.view().getBoolean("Running");
            line(g, label("batches"), 10, 77, 49);
            if (running) line(g, label("active_batches", Math.max(1, menu.view().getInt("Count"))), 64, 77, 246);
            line(g, label("total_inputs"), 10, 95, 140); line(g, label("total_outputs"), 164, 95, 146); renderTotals(g, false); renderTotals(g, true);
            if (!running && !generic() && derivedResult().isEmpty()) line(g, label("derive_after_save"), 164, 113, 125);
            boolean pattern = running ? menu.view().contains("Pattern", Tag.TAG_COMPOUND) : generic();
            boolean brewing = running ? "brewing".equals(menu.view().getString("Kind")) : brewing();
            line(g, label(pattern ? "batch_note" : brewing ? "brewing_supplies_note" : "fuel_estimate_note"), 10, 193, 300);
        }
        line(g, status(), 10, 203, 300);
        if (pickerOpen) { g.fill(leftPos, topPos, leftPos + 320, topPos + 238, 0xa0000000); VanillaGui.panel(g, leftPos + 10, topPos + 13, 300, 216); line(g, pickerTitle, 21, 24, 278); if (pickerResults.isEmpty()) line(g, label("no_matches"), 24, 80, 270); }
    }
    private void renderTotals(GuiGraphics g, boolean output) {
        List<TotalRow> rows = totals(output); int scroll = output ? outputScroll : inputScroll, x = output ? 164 : 10;
        for (int i = 0; i < 4 && scroll + i < rows.size(); i++) { TotalRow row = rows.get(scroll + i); int y = 108 + i * 20; g.renderItem(row.stack(), leftPos + x, topPos + y); String amount = row.estimated() ? label("as_needed").getString() : "×" + row.amount(); line(g, Component.literal(font.plainSubstrByWidth(row.stack().getHoverName().getString(), Math.max(24, 105 - font.width(amount))) + " " + amount), x + 20, y + 4, 106); }
    }
    private ItemStack gridStack(boolean output, int index) { if (!generic()) return output ? derivedResult() : selectedStack(index == 1); List<MaterialRow> rows = output ? patternOutputs : patternInputs; return index >= rows.size() ? ItemStack.EMPTY : rows.get(index).stack.copyWithCount(Math.max(1, rows.get(index).amount)); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh(); renderBackground(g); super.render(g, mouseX, mouseY, partialTick);
        if (pickerOpen) { for (int i = 0; i < 6 && pickerOffset + i < pickerResults.size(); i++) { ItemStack stack = pickerResults.get(pickerOffset + i).stack(); if (!stack.isEmpty()) g.renderItem(stack, leftPos + 24, topPos + 64 + i * 22); } return; }
        if (page == 0) for (boolean output : new boolean[]{false, true}) {
            Button[] grid = output ? outputGrid : inputGrid;
            for (int i = 0; i < grid.length; i++) if (grid[i].visible) {
                Button button = grid[i]; int x = button.getX(), y = button.getY(); VanillaGui.slot(g, x + 1, y + 1); ItemStack stack = gridStack(output, i);
                if (!stack.isEmpty()) { g.renderItem(stack, x + 2, y + 2); g.renderItemDecorations(font, stack, x + 2, y + 2); } else if (generic() || !output) g.drawString(font, "+", x + 7, y + 6, 0xff777777, false);
                if (outputTabSelected == output && rowIndex == i) { g.fill(x, y, x + 20, y + 1, 0xffffff80); g.fill(x, y + 19, x + 20, y + 20, 0xffffff80); g.fill(x, y, x + 1, y + 20, 0xffffff80); g.fill(x + 19, y, x + 20, y + 20, 0xffffff80); }
                if (button.isMouseOver(mouseX, mouseY)) { if (!stack.isEmpty()) g.renderTooltip(font, stack, mouseX, mouseY); else g.renderTooltip(font, label(!generic() && output ? "derive_after_save" : "click_add_material"), mouseX, mouseY); }
            }
        }
        if (page == 2) for (boolean output : new boolean[]{false, true}) { int x = output ? 164 : 10, scroll = output ? outputScroll : inputScroll; List<TotalRow> rows = totals(output); for (int i = 0; i < 4 && scroll + i < rows.size(); i++) if (mouseX >= leftPos + x && mouseX < leftPos + x + 126 && mouseY >= topPos + 108 + i * 20 && mouseY < topPos + 128 + i * 20) g.renderTooltip(font, Component.literal(runSummary(output).get(scroll + i) + "\n" + folderName(rows.get(scroll + i).folder())), mouseX, mouseY); }
        if (page == 0 && mouseX >= leftPos + 10 && mouseX < leftPos + 310 && mouseY >= topPos + 149 && mouseY < topPos + 160) g.renderTooltip(font, selectedMaterialLabel(), mouseX, mouseY);
        if (mouseX >= leftPos + 10 && mouseX < leftPos + 310 && mouseY >= topPos + 202 && mouseY < topPos + 213) g.renderTooltip(font, status(), mouseX, mouseY);
    }
}
