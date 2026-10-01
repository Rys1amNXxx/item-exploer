package dev.itemexplorer.block;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.cable.CableNetwork;
import dev.itemexplorer.production.FurnaceProgram;
import dev.itemexplorer.production.MachineAdapter;
import dev.itemexplorer.production.MachineAdapters;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.storage.StorageRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.util.UUID;

/** A physical machine endpoint. Saved documents live on the terminal; this owns one immutable run snapshot. */
public final class ProductionPortBlockEntity extends BlockEntity {
    private static final String MACHINE_ID = "ItemExplorerProductionIdentity";
    private FurnaceProgram program;
    private UUID identity = UUID.randomUUID();
    private boolean identityNeedsSave = true;
    private int ownerProgram;
    private UUID jobRunIdentity;
    private CompoundTag adapterState = new CompoundTag();
    private boolean running, inFlight, interference;
    private int completed;
    private ItemStack expectedFuel = ItemStack.EMPTY;
    private String status = "production_unconfigured";
    private long revision;
    private Tag protectedData;
    private boolean archived;

    private record Connection(StorageBlockEntity terminal, String problem) {}

    public ProductionPortBlockEntity(BlockPos pos, BlockState state) { super(ModContent.PRODUCTION_ENTITY.get(), pos, state); }
    public long revision() { return revision; }
    public boolean isLocked() { return protectedData != null; }
    public boolean isRunning() { return running; }
    public UUID identity() { if (identityNeedsSave) { setChanged(); identityNeedsSave = false; } return identity; }
    public boolean ownsProgram(UUID terminal, int id) { return program != null && ownerProgram == id && program.terminal().equals(terminal); }
    public boolean ownsRun(UUID terminal, int id, UUID runIdentity) {
        return ownsProgram(terminal, id) && runIdentity != null && runIdentity.equals(jobRunIdentity);
    }
    private void changed() { revision++; setChanged(); }
    private void status(String next) { if (!status.equals(next)) { status = next; changed(); } }
    private static IllegalArgumentException failure(String key) { return new IllegalArgumentException(key); }
    private BlockEntity loaded(BlockPos pos) { return level != null && level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null; }
    private boolean online() { return level instanceof ServerLevel && !isRemoved() && loaded(worldPosition) == this; }
    public BlockPos machinePosition() { return worldPosition.relative(getBlockState().getValue(ProductionPortBlock.FACING).getOpposite()); }
    private MachineAdapter adapter() { return level != null && level.hasChunkAt(machinePosition()) ? MachineAdapters.forState(level.getBlockState(machinePosition())) : null; }
    public String machineType() { MachineAdapter adapter = adapter(); return adapter == null ? "" : adapter.kind(); }
    private BlockEntity machine() { MachineAdapter adapter = adapter(); return adapter == null ? null : adapter.find(level, machinePosition()); }
    private Connection connection() {
        if (!online()) return new Connection(null, "production_disconnected");
        var scan = CableNetwork.scan(level, worldPosition);
        if (scan.tooLarge()) return new Connection(null, "production_network_large");
        if (scan.unloaded()) return new Connection(null, "production_unloaded");
        StorageBlockEntity found = null;
        for (var endpoint : scan.endpoints().keySet()) {
            if (!(loaded(endpoint) instanceof StorageBlockEntity terminal) || terminal.isRemoved()) continue;
            if (found != null) return new Connection(null, "production_terminal_conflict");
            found = terminal;
        }
        return new Connection(found, found == null ? "production_disconnected" : "");
    }
    private StorageBlockEntity requireTerminal() {
        Connection connection = connection();
        if (!connection.problem.isEmpty()) throw failure(connection.problem);
        if (connection.terminal.inventory().isLocked() || connection.terminal.programs().isLocked()) throw failure("production_storage_locked");
        return connection.terminal;
    }
    private BlockEntity requireMachine() {
        BlockEntity machine = machine();
        if (machine == null) throw failure("production_machine_offline");
        for (Direction face : Direction.values()) {
            BlockPos p = machinePosition().relative(face);
            if (!level.hasChunkAt(p)) throw failure("production_unloaded");
            if (p.equals(worldPosition)) continue;
            BlockState state = level.getBlockState(p);
            if (state.is(ModContent.PRODUCTION_BLOCK.get()) && state.getValue(ProductionPortBlock.FACING) == face)
                throw failure("production_machine_conflict");
        }
        return machine;
    }
    private static UUID machineIdentity(BlockEntity machine) {
        CompoundTag data = machine.getPersistentData();
        if (!data.hasUUID(MACHINE_ID)) { data.putUUID(MACHINE_ID, UUID.randomUUID()); machine.setChanged(); }
        return data.getUUID(MACHINE_ID);
    }
    public String topologyStamp() {
        Connection c = connection(); BlockEntity machine = machine();
        return c.problem + ":" + (c.terminal == null ? "" : c.terminal.productionIdentity() + ":" + c.terminal.accessSession())
                + ":" + (machine == null ? "" : System.identityHashCode(machine));
    }
    private static boolean same(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b.isEmpty() : !b.isEmpty() && a.getCount() == b.getCount() && ItemStack.isSameItemSameTags(a, b);
    }
    private static void folders(StorageInventory inventory, int... ids) {
        for (int id : ids) if (!inventory.hasFolder(id)) throw failure("production_invalid_folder");
    }
    private void editable() {
        if (isLocked()) throw failure("production_storage_locked");
        if (running) throw failure("production_running");
    }
    /** Validate and capture a document definition without configuring or starting this interface. */
    public FurnaceProgram createDefinition(String name, int inputFolder, int fuelFolder, int outputFolder, int inputEntry, int fuelEntry) {
        if (isLocked()) throw failure("production_storage_locked");
        StorageBlockEntity terminal = requireTerminal(); BlockEntity machine = requireMachine();
        StorageInventory inventory = terminal.inventory(); folders(inventory, inputFolder, fuelFolder, outputFolder);
        var input = inventory.entry(inputEntry); var fuel = inventory.entry(fuelEntry);
        if (input == null || input.folder() != inputFolder || fuel == null || fuel.folder() != fuelFolder)
            throw failure("production_invalid_sample");
        MachineAdapter adapter = adapter(); var recipe = adapter.resolve(level, input.stack(), fuel.stack());
        return new FurnaceProgram(name, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry,
                input.stack(), fuel.stack(), recipe.result(), recipe.recipe(), 1, terminal.productionIdentity(), machineIdentity(machine), adapter.kind());
    }
    /** Compatibility for internal prototype callers. Even these definitions are now stored as terminal documents. */
    @Deprecated
    public void configure(String name, int inputFolder, int fuelFolder, int outputFolder, int inputEntry, int fuelEntry, int count) {
        editable(); StorageBlockEntity terminal = requireTerminal();
        FurnaceProgram configured = createDefinition(name, inputFolder, fuelFolder, outputFolder, inputEntry, fuelEntry).withCount(count);
        var previous = terminal.programs().file(ownerProgram);
        int id = previous != null && program != null && program.terminal().equals(terminal.productionIdentity())
                ? ownerProgram : terminal.programs().create(0, name);
        if (previous != null && id == ownerProgram) terminal.programs().rename(id, name);
        terminal.programs().configure(id, configured, worldPosition, identity());
        ownerProgram = id; jobRunIdentity = null; program = configured; completed = 0; interference = inFlight = false; expectedFuel = ItemStack.EMPTY;
        adapterState = adapter().freshState(requireMachine()); status("production_ready"); changed();
    }
    private StorageInventory boundInventory() {
        StorageBlockEntity terminal = requireTerminal();
        if (!program.terminal().equals(terminal.productionIdentity())) throw failure("production_terminal_changed");
        if (ownerProgram > 0) {
            var file = terminal.programs().file(ownerProgram);
            if (file == null || !file.active() || !worldPosition.equals(file.portPos()) || !identity().equals(file.portIdentity())
                    || jobRunIdentity == null || !jobRunIdentity.equals(file.runIdentity())) {
                // Cancellation can be issued while this chunk is unloaded. Retire the old snapshot
                // on reconnection without adopting or refunding anything left in the machine.
                running = inFlight = interference = false; expectedFuel = ItemStack.EMPTY; changed();
                throw failure("production_program_changed");
            }
        }
        folders(terminal.inventory(), program.inputFolder(), program.fuelFolder(), program.outputFolder());
        return terminal.inventory();
    }
    private BlockEntity boundMachine() {
        BlockEntity machine = requireMachine();
        if (!program.kind().equals(adapter().kind()) || !machine.getPersistentData().hasUUID(MACHINE_ID)
                || !program.furnace().equals(machine.getPersistentData().getUUID(MACHINE_ID))) throw failure("production_machine_changed");
        return machine;
    }
    private void verifyRecipe() {
        var current = adapter().resolve(level, program.input(), program.fuel());
        if (!program.recipe().equals(current.recipe()) || !same(program.result(), current.result())) throw failure("production_recipe_changed");
    }
    /** A launch is bound to the saved document and this exact port identity, never to coordinates alone. */
    public void startProgram(StorageBlockEntity terminal, int id, FurnaceProgram definition, int count) {
        editable();
        if (requireTerminal() != terminal) throw failure("production_terminal_changed");
        var file = terminal.programs().file(id);
        if (file == null) throw failure("production_missing_program");
        if (file.active()) throw failure("production_running");
        if (!file.configured()) throw failure("production_unconfigured");
        if (!worldPosition.equals(file.portPos()) || !identity().equals(file.portIdentity())) throw failure("production_machine_changed");
        if (definition == null || !file.definition().save().equals(definition.withName(file.name()).withCount(1).save()))
            throw failure("production_program_changed");
        FurnaceProgram snapshot = file.definition().withCount(count);
        if (!snapshot.terminal().equals(terminal.productionIdentity())) throw failure("production_terminal_changed");
        folders(terminal.inventory(), snapshot.inputFolder(), snapshot.fuelFolder(), snapshot.outputFolder());
        BlockEntity machine = requireMachine(); MachineAdapter adapter = adapter();
        if (!snapshot.kind().equals(adapter.kind()) || !snapshot.furnace().equals(machineIdentity(machine))) throw failure("production_machine_changed");
        var current = adapter.resolve(level, snapshot.input(), snapshot.fuel());
        if (!snapshot.recipe().equals(current.recipe()) || !same(snapshot.result(), current.result())) throw failure("production_recipe_changed");
        if (!adapter.empty(machine)) throw failure("production_machine_busy");
        CompoundTag state = adapter.freshState(machine); adapter.validateSavedState(state);
        terminal.programs().setActive(id, true);
        program = snapshot; ownerProgram = id; jobRunIdentity = terminal.programs().file(id).runIdentity(); adapterState = state;
        completed = 0; inFlight = interference = false; expectedFuel = ItemStack.EMPTY; running = true;
        status("production_running"); changed();
    }
    @Deprecated public void start() {
        if (program == null || ownerProgram <= 0) throw failure("production_unconfigured");
        startProgram(requireTerminal(), ownerProgram, program, program.count());
    }
    private void releaseOwner() {
        if (program == null || ownerProgram <= 0) return;
        Connection connection = connection(); StorageBlockEntity terminal = connection.terminal;
        if (terminal == null || !program.terminal().equals(terminal.productionIdentity()) || terminal.programs().isLocked() || terminal.inventory().isLocked()) return;
        var file = terminal.programs().file(ownerProgram);
        if (file != null && worldPosition.equals(file.portPos()) && identity().equals(file.portIdentity())
                && jobRunIdentity != null && jobRunIdentity.equals(file.runIdentity())) terminal.programs().setActive(ownerProgram, false);
    }
    public void cancelProgram(UUID terminal, int id) {
        if (!ownsProgram(terminal, id)) throw failure("production_program_changed");
        cancel();
    }
    /** Stop controlling the machine. In-flight ingredients and any reserve remain in the real machine. */
    public void cancel() {
        if (isLocked()) throw failure("production_storage_locked");
        if (!running) { releaseOwner(); return; }
        running = inFlight = interference = false; expectedFuel = ItemStack.EMPTY;
        releaseOwner(); status("production_cancelled"); changed();
    }
    public void onDetached() { if (!isLocked()) cancel(); archiveProtectedData(); }

    private void inventoryFault() { interference = true; changed(); throw failure("production_interference"); }
    private void verifyMachineInventory(BlockEntity machine) {
        MachineAdapter adapter = adapter(); CompoundTag before = adapterState.copy();
        try { adapter.validateExtraSlots(machine, adapterState); }
        finally { if (!before.equals(adapterState)) changed(); }
        ItemStack input = adapter.input(machine, program), output = adapter.output(machine, program), fuel = adapter.fuel(machine);
        if (inFlight) {
            if (!(same(input, program.input()) && output.isEmpty())
                    && !(input.isEmpty() && same(output, program.result()))) inventoryFault();
        } else if (!input.isEmpty() || !output.isEmpty()) inventoryFault();
        if (same(expectedFuel, fuel)) return;
        ItemStack remainder = program.fuel().getCraftingRemainingItem();
        boolean spongeWater = program.input().is(Items.WET_SPONGE) && remainder.is(Items.BUCKET)
                && input.isEmpty() && !output.isEmpty() && fuel.is(Items.WATER_BUCKET) && fuel.getCount() == 1;
        if ((same(expectedFuel, program.fuel()) && (same(fuel, remainder) || spongeWater))
                || (same(expectedFuel, remainder) && spongeWater)) {
            // A brewing reagent is consumed together with the transformed bottle; unlike furnace
            // fuel, it cannot legitimately disappear at the beginning of the operation.
            if ("brewing".equals(program.kind()) && same(expectedFuel, program.fuel()) && output.isEmpty()) inventoryFault();
            expectedFuel = fuel.copy(); changed(); return;
        }
        inventoryFault();
    }
    private static StorageInventory.Entry find(StorageInventory inventory, int folder, ItemStack sample) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && ItemStack.isSameItemSameTags(e.stack(), sample)).findFirst().orElse(null);
    }
    private static boolean room(StorageInventory inventory, int folder, ItemStack stack) {
        try { return inventory.insert(stack, stack.getCount(), folder, true) == stack.getCount(); }
        catch (IllegalArgumentException full) {
            if ("entry_limit".equals(full.getMessage()) || "item_too_large".equals(full.getMessage()) || "nested_device".equals(full.getMessage())) return false;
            throw full;
        }
    }
    private void collect(StorageInventory inventory, BlockEntity machine, boolean output, int folder, String fullReason) {
        MachineAdapter adapter = adapter(); ItemStack stack = (output ? adapter.output(machine, program) : adapter.fuel(machine)).copy();
        if (!room(inventory, folder, stack)) throw failure(fullReason);
        int inserted = inventory.insert(stack, stack.getCount(), folder, false);
        if (output) adapter.removeOutput(machine, inserted); else adapter.removeFuel(machine, inserted);
        machine.setChanged();
    }
    private void prepare(MachineAdapter adapter, BlockEntity machine, StorageInventory inventory) {
        CompoundTag before = adapterState.copy();
        try { adapter.prepare(machine, inventory, program, adapterState); }
        finally { if (!before.equals(adapterState)) changed(); }
    }
    public void workTick() {
        if (!online() || !running || isLocked() || program == null) return;
        if (interference) { status("production_interference"); return; }
        try {
            StorageInventory inventory = boundInventory(); BlockEntity machine = boundMachine(); verifyRecipe();
            MachineAdapter adapter = adapter(); verifyMachineInventory(machine);
            if (!expectedFuel.isEmpty() && !same(expectedFuel, program.fuel())
                    && (!inFlight || !adapter.output(machine, program).isEmpty() || !adapter.burning(machine))) {
                collect(inventory, machine, false, program.fuelFolder(), "production_container_full");
                expectedFuel = ItemStack.EMPTY; changed();
            }
            if (inFlight && !adapter.output(machine, program).isEmpty()) {
                collect(inventory, machine, true, program.outputFolder(), "production_output_full");
                inFlight = false; completed++; changed();
            }
            if (completed >= program.count()) {
                running = false; releaseOwner(); status("production_complete"); changed(); return;
            }
            if (!room(inventory, program.outputFolder(), program.result())) throw failure("production_output_full");
            if (!inFlight) {
                var input = find(inventory, program.inputFolder(), program.input());
                if (input == null) throw failure("production_missing_input");
                if (adapter.needsFuel(machine) && find(inventory, program.fuelFolder(), program.fuel()) == null)
                    throw failure("production_missing_fuel");
                prepare(adapter, machine, inventory);
                ItemStack taken = inventory.take(input.id(), 1, false);
                adapter.setInput(machine, taken); machine.setChanged(); inFlight = true; changed();
            }
            if (adapter.needsFuel(machine)) {
                var fuel = find(inventory, program.fuelFolder(), program.fuel());
                if (fuel == null) throw failure("production_missing_fuel");
                prepare(adapter, machine, inventory);
                fuel = find(inventory, program.fuelFolder(), program.fuel());
                if (fuel == null) throw failure("production_missing_fuel");
                expectedFuel = inventory.take(fuel.id(), 1, false);
                adapter.setFuel(machine, expectedFuel.copy()); machine.setChanged(); changed();
            }
            status("production_running");
        } catch (IllegalArgumentException waiting) {
            String reason = waiting.getMessage();
            if ("production_interference".equals(reason) && !interference) { interference = true; changed(); }
            status(reason != null && reason.startsWith("production_") ? reason : "production_storage_locked");
        }
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, ProductionPortBlockEntity port) {
        if ((level.getGameTime() + pos.asLong()) % 5 == 0) port.workTick();
    }
    public CompoundTag runtimeView() {
        CompoundTag view = program == null ? new CompoundTag() : program.save();
        view.putLong("Revision", revision); view.putBoolean("Configured", program != null); view.putBoolean("Running", running);
        view.putBoolean("Active", running); view.putBoolean("Locked", isLocked()); view.putInt("Completed", completed);
        view.putInt("ProgramId", ownerProgram); view.putUUID("PortIdentity", identity()); view.putString("MachineType", machineType());
        if (jobRunIdentity != null) view.putUUID("RunIdentity", jobRunIdentity);
        view.putString("Status", isLocked() ? "production_storage_locked" : status);
        if (program == null) view.putInt("Count", 1);
        return view;
    }
    public CompoundTag view() {
        CompoundTag view = runtimeView(); ListTag folders = new ListTag(), entries = new ListTag();
        Connection c = connection(); MachineAdapter adapter = adapter();
        if (c.terminal != null && !c.terminal.inventory().isLocked()) {
            for (var folder : c.terminal.inventory().folders()) {
                CompoundTag node = new CompoundTag(); node.putInt("Id", folder.id()); node.putInt("Parent", folder.parent());
                node.putString("Name", folder.name()); folders.add(node);
            }
            for (var entry : c.terminal.inventory().entries()) {
                CompoundTag node = new CompoundTag(); node.putInt("Id", entry.id()); node.putInt("Folder", entry.folder());
                node.put("Stack", entry.stack().save(new CompoundTag())); node.putLong("Count", entry.count());
                node.putBoolean("Smeltable", adapter != null && adapter.acceptsInput(level, entry.stack()));
                node.putBoolean("Fuel", adapter != null && adapter.acceptsFuel(entry.stack())); entries.add(node);
            }
        }
        view.put("Folders", folders); view.put("Entries", entries);
        if (!running && !isLocked() && !c.problem.isEmpty()) view.putString("Status", c.problem);
        return view;
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag); tag.putUUID("PortIdentity", identity);
        if (isLocked()) { tag.put("Production", protectedData.copy()); return; }
        CompoundTag data = new CompoundTag(); data.putInt("Version", 2);
        if (program != null) data.put("Program", program.save());
        data.putInt("OwnerProgram", ownerProgram); data.put("AdapterState", adapterState.copy());
        if (jobRunIdentity != null) data.putUUID("RunIdentity", jobRunIdentity);
        data.putBoolean("Running", running); data.putBoolean("InFlight", inFlight); data.putBoolean("Interference", interference);
        data.putInt("Completed", completed); data.put("ExpectedFuel", expectedFuel.save(new CompoundTag()));
        tag.put("Production", data);
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag); program = null; running = inFlight = interference = false; completed = 0; ownerProgram = 0; jobRunIdentity = null;
        identity = tag.hasUUID("PortIdentity") ? tag.getUUID("PortIdentity") : UUID.randomUUID(); identityNeedsSave = !tag.hasUUID("PortIdentity");
        adapterState = new CompoundTag(); expectedFuel = ItemStack.EMPTY; protectedData = null; archived = false; revision++;
        status = "production_unconfigured";
        if (!tag.contains("Production")) return;
        Tag raw = tag.get("Production");
        try {
            if (!(raw instanceof CompoundTag data)) throw new IllegalArgumentException();
            FurnaceProgram.require(data, "Version", Tag.TAG_INT);
            int version = data.getInt("Version"); if (version != 1 && version != 2) throw new IllegalArgumentException();
            for (String field : new String[]{"Running", "InFlight", "Interference"}) {
                FurnaceProgram.require(data, field, Tag.TAG_BYTE);
                if (data.getByte(field) != 0 && data.getByte(field) != 1) throw new IllegalArgumentException();
            }
            FurnaceProgram.require(data, "Completed", Tag.TAG_INT);
            if (data.contains("Program")) { FurnaceProgram.require(data, "Program", Tag.TAG_COMPOUND); program = FurnaceProgram.load(data.getCompound("Program")); }
            running = data.getBoolean("Running"); inFlight = data.getBoolean("InFlight"); interference = data.getBoolean("Interference");
            completed = data.getInt("Completed"); expectedFuel = FurnaceProgram.stack(data, "ExpectedFuel", true);
            if (version == 2) {
                FurnaceProgram.require(data, "OwnerProgram", Tag.TAG_INT); ownerProgram = data.getInt("OwnerProgram");
                if (data.contains("RunIdentity")) {
                    if (!data.hasUUID("RunIdentity")) throw new IllegalArgumentException();
                    jobRunIdentity = data.getUUID("RunIdentity");
                }
                FurnaceProgram.require(data, "AdapterState", Tag.TAG_COMPOUND); adapterState = data.getCompound("AdapterState").copy();
                if (ownerProgram < 0 || (program == null && ownerProgram != 0) || adapterState.sizeInBytes() > 4096) throw new IllegalArgumentException();
                if (ownerProgram > 0 && running && jobRunIdentity == null) throw new IllegalArgumentException();
                if (program != null && running) MachineAdapters.byKind(program.kind()).validateSavedState(adapterState);
            }
            if (completed < 0 || (program == null && (running || inFlight || interference || completed != 0 || !expectedFuel.isEmpty()))
                    || (program != null && completed > program.count()) || (!running && (inFlight || interference))
                    || (inFlight && completed >= program.count())) throw new IllegalArgumentException();
            if (!expectedFuel.isEmpty() && program != null && !same(expectedFuel, program.fuel())
                    && !same(expectedFuel, program.fuel().getCraftingRemainingItem())
                    && !(program.input().is(Items.WET_SPONGE) && expectedFuel.is(Items.WATER_BUCKET) && expectedFuel.getCount() == 1)) throw new IllegalArgumentException();
            // Prototype jobs had no terminal document and therefore no place in the new UI to
            // inspect or cancel them. Retire their control state, leaving every real machine slot
            // and its vanilla cooking progress intact for the player to recover.
            if (ownerProgram == 0 && program != null) {
                running = inFlight = interference = false; expectedFuel = ItemStack.EMPTY;
                status = "production_legacy_stopped"; return;
            }
            status = program == null ? "production_unconfigured" : running ? "production_running"
                    : completed == program.count() ? "production_complete" : "production_ready";
        } catch (RuntimeException invalid) {
            protectedData = raw.copy(); program = null; running = false; status = "production_storage_locked";
        }
    }
    @Override public void onLoad() { super.onLoad(); archiveProtectedData(); }
    private void archiveProtectedData() {
        if (!isLocked() || archived || !(level instanceof ServerLevel server)) return;
        try {
            StorageRecovery.archive(server.getServer().getWorldPath(LevelResource.ROOT).resolve("itemexplorer-recovery"), protectedData,
                    level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), "production_program");
            archived = true;
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Cannot archive production interface at {}", worldPosition, failure); }
    }
}
