package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.production.ProgramLibrary;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerminalProgramTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port,
                           AbstractFurnaceBlockEntity machine, int input, int fuel, int output, int file) {}
    private static Fixture fixture(GameTestHelper h, Block block, Item inputItem) {
        var level = h.getLevel(); BlockPos machinePos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos portPos = machinePos.north(), cable = portPos.north(), terminalPos = cable.west();
        level.setBlockAndUpdate(machinePos, block.defaultBlockState());
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState().setValue(ProductionPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        var machine = (AbstractFurnaceBlockEntity) level.getBlockEntity(machinePos);
        var storage = terminal.inventory(); int input = storage.createFolder(0, "原料"), fuel = storage.createFolder(0, "辅助材料"), output = storage.createFolder(0, "成品");
        storage.insert(new ItemStack(inputItem, 3), 3, input); storage.insert(new ItemStack(Items.COAL, 2), 2, fuel);
        int inId = storage.entries().stream().filter(e -> e.folder() == input).findFirst().orElseThrow().id();
        int fuelId = storage.entries().stream().filter(e -> e.folder() == fuel).findFirst().orElseThrow().id();
        int file = terminal.programs().create(0, "自动程序");
        var definition = port.createDefinition("自动程序", input, fuel, output, inId, fuelId);
        terminal.programs().configure(file, definition, portPos, port.identity());
        return new Fixture(terminal, port, machine, input, fuel, output, file);
    }
    private static void start(Fixture f, int count) { f.port.startProgram(f.terminal, f.file, f.terminal.programs().file(f.file).definition(), count); }
    private static void cook(Fixture f, int ticks) {
        for (int i = 0; i < ticks; i++) {
            AbstractFurnaceBlockEntity.serverTick(f.machine.getLevel(), f.machine.getBlockPos(), f.machine.getBlockState(), f.machine);
            f.port.workTick();
        }
    }
    private static long count(Fixture f, int folder, Item item) {
        return f.terminal.inventory().entries().stream().filter(e -> e.folder() == folder && e.stack().is(item)).mapToLong(StorageInventory.Entry::count).sum();
    }
    @GameTest(template = "empty")
    public static void documentMoveCopyAndReloadKeepDefinitionsOutOfItemCounts(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.FURNACE, Items.SAND); var library = f.terminal.programs();
        int target = f.terminal.inventory().createFolder(0, "程序目录"); long total = f.terminal.inventory().total();
        library.move(f.file, target); library.rename(f.file, "玻璃程序");
        int copy = library.copy(f.file, 0, "玻璃程序副本");
        CompoundTag saved = f.terminal.saveWithoutMetadata(); f.terminal.load(saved);
        h.assertTrue(library.file(f.file).folder() == target && library.file(f.file).name().equals("玻璃程序")
                && library.file(f.file).definition().inputFolder() == f.input, "Moving a document changed its stable folder references");
        h.assertTrue(copy != f.file && library.file(copy).definition().count() == 1 && !library.file(copy).active()
                && f.terminal.inventory().total() == total, "Copy created items or copied an active run");
        library.delete(copy); int fresh = library.create(0, "新程序");
        h.assertTrue(fresh > copy, "Deleted document identity was reused");
        start(f, 2); f.port.workTick(); cook(f, 430);
        h.assertTrue(count(f, f.output, Items.GLASS) == 2 && library.file(f.file).definition().count() == 1,
                "Runtime quantity mutated the saved definition or moved path broke execution"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void activeDocumentsRejectRetargetingAndDuplicateLaunch(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.FURNACE, Items.SAND); var library = f.terminal.programs(); start(f, 2); f.port.workTick();
        var saved = library.save(); var material = f.terminal.inventory().save();
        Runnable[] edits = {() -> library.delete(f.file), () -> library.move(f.file, f.output), () -> library.rename(f.file, "其他程序"),
                () -> library.configure(f.file, library.file(f.file).definition(), f.port.getBlockPos(), f.port.identity()), () -> start(f, 2)};
        for (Runnable edit : edits) {
            boolean rejected = false; try { edit.run(); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "An active file accepted a conflicting mutation");
        }
        h.assertTrue(saved.equals(library.save()) && material.equals(f.terminal.inventory().save()), "Rejected edit changed file or items");
        f.port.cancelProgram(f.terminal.productionIdentity(), f.file);
        h.assertTrue(!library.file(f.file).active() && f.machine.getItem(0).is(Items.SAND), "Cancellation refunded input or retained file lock"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void persistedCancellationRetiresAnOldRuntimeWithoutTransfers(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.FURNACE, Items.SAND); start(f, 2); f.port.workTick();
        CompoundTag staleRuntime = f.port.saveWithoutMetadata(); f.terminal.programs().setActive(f.file, false);
        f.port.load(staleRuntime); var before = f.terminal.inventory().save(); cook(f, 240);
        h.assertTrue(!f.port.isRunning() && before.equals(f.terminal.inventory().save()) && f.machine.getItem(2).is(Items.GLASS),
                "An old runtime resumed a cancelled reservation or lost the real output"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void corruptProgramLibraryPreservesRawDataAndNeverLocksMaterialInventory(GameTestHelper h) {
        StorageInventory inventory = new StorageInventory(() -> {}); ProgramLibrary library = new ProgramLibrary(inventory, () -> {});
        library.create(0, "程序"); CompoundTag broken = (CompoundTag) library.save(); broken.putInt("Version", 99);
        library.load(broken);
        h.assertTrue(library.isLocked() && library.files().isEmpty() && broken.equals(library.save()) && !inventory.isLocked(),
                "Invalid program metadata was rewritten or material storage was corrupted");
        boolean rejected = false; try { library.create(0, "覆盖程序"); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Protected program data accepted overwrite");
        CompoundTag orphan = new CompoundTag(); orphan.putInt("Version", 1); orphan.putInt("NextId", 2);
        var files = broken.getList("Files", Tag.TAG_COMPOUND).copy(); files.getCompound(0).putInt("Folder", 1000); orphan.put("Files", files);
        library.load(orphan); h.assertTrue(library.isLocked() && orphan.equals(library.save()), "Orphan file folder was silently changed"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void blastFurnaceRunsItsOwnRecipeAndTime(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.BLAST_FURNACE, Items.RAW_IRON); start(f, 2); f.port.workTick(); cook(f, 230);
        h.assertTrue(count(f, f.output, Items.IRON_INGOT) == 2 && !f.port.isRunning() && count(f, f.fuel, Items.COAL) == 1,
                "Blast furnace did not use its actual cooking ticker and reusable fuel"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void smokerRunsItsOwnRecipeAndTime(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.SMOKER, Items.BEEF); start(f, 2); f.port.workTick(); cook(f, 230);
        h.assertTrue(count(f, f.output, Items.COOKED_BEEF) == 2 && !f.port.isRunning() && count(f, f.fuel, Items.COAL) == 1,
                "Smoker did not use its actual cooking ticker and reusable fuel"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void copiedDefinitionCannotShareAnOccupiedInterface(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.FURNACE, Items.SAND); start(f, 2);
        int copied = f.terminal.programs().copy(f.file, 0, "副本"); boolean rejected = false;
        try { f.port.startProgram(f.terminal, copied, f.terminal.programs().file(copied).definition(), 1); }
        catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected && !f.terminal.programs().file(copied).active() && f.terminal.programs().file(f.file).active(),
                "Copied document displaced an existing machine task"); h.succeed();
    }
    @GameTest(template = "empty")
    public static void upgradingPrototypeStopsHiddenJobAndLeavesRealMachineContents(GameTestHelper h) {
        Fixture f = fixture(h, Blocks.FURNACE, Items.SAND); start(f, 2); f.port.workTick();
        CompoundTag prototype = f.port.saveWithoutMetadata(), data = prototype.getCompound("Production");
        data.putInt("Version", 1); data.remove("OwnerProgram"); data.remove("RunIdentity"); data.remove("AdapterState");
        var before = f.terminal.inventory().save(); f.port.load(prototype); cook(f, 240);
        h.assertTrue(!f.port.isRunning() && f.port.runtimeView().getString("Status").equals("production_legacy_stopped")
                && before.equals(f.terminal.inventory().save()) && f.machine.getItem(2).is(Items.GLASS),
                "Prototype upgrade continued an invisible job or changed real ingredients/output");
        CompoundTag upgraded = f.port.saveWithoutMetadata(); f.port.load(upgraded);
        h.assertTrue(!f.port.isRunning() && f.machine.getItem(2).is(Items.GLASS), "Reload resumed a retired prototype job"); h.succeed();
    }
}
