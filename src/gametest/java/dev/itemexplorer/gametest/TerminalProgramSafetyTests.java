package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.production.TerminalPrograms;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerminalProgramSafetyTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity first, ProductionPortBlockEntity second,
                           FurnaceBlockEntity furnace, FurnaceBlockEntity otherFurnace, BlockPos cable,
                           int file, int input, int fuel, int output) {
        StorageInventory storage() { return terminal.inventory(); }
    }
    private static Fixture fixture(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos firstPos = h.absolutePos(new BlockPos(2, 2, 3)), secondPos = h.absolutePos(new BlockPos(4, 2, 3));
        for (BlockPos machine : new BlockPos[]{firstPos, secondPos}) {
            level.setBlockAndUpdate(machine, Blocks.FURNACE.defaultBlockState());
            level.setBlockAndUpdate(machine.north(), ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                    .setValue(ProductionPortBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(machine.north(2), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        }
        level.setBlockAndUpdate(h.absolutePos(new BlockPos(3, 2, 1)), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        BlockPos terminalPos = h.absolutePos(new BlockPos(3, 2, 0));
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var first = (ProductionPortBlockEntity) level.getBlockEntity(firstPos.north());
        var second = (ProductionPortBlockEntity) level.getBlockEntity(secondPos.north());
        var inventory = terminal.inventory();
        int input = inventory.createFolder(0, "原料"), fuel = inventory.createFolder(0, "燃料"), output = inventory.createFolder(0, "成品");
        inventory.insert(new ItemStack(Items.SAND, 6), 6, input);
        inventory.insert(new ItemStack(Items.COAL, 3), 3, fuel);
        int file = terminal.programs().create(0, "玻璃程序");
        terminal.programs().configure(file, first.createDefinition("玻璃程序", input, fuel, output,
                entry(inventory, input, Items.SAND), entry(inventory, fuel, Items.COAL)), first.getBlockPos(), first.identity());
        return new Fixture(terminal, first, second, (FurnaceBlockEntity) level.getBlockEntity(firstPos),
                (FurnaceBlockEntity) level.getBlockEntity(secondPos), firstPos.north(2), file, input, fuel, output);
    }
    private static int entry(StorageInventory inventory, int folder, Item item) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToInt(StorageInventory.Entry::id).findFirst().orElseThrow();
    }
    private static long count(StorageInventory inventory, int folder, Item item) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToLong(StorageInventory.Entry::count).sum();
    }
    private static void cook(FurnaceBlockEntity furnace, ProductionPortBlockEntity port, int ticks) {
        for (int i = 0; i < ticks; i++) {
            AbstractFurnaceBlockEntity.serverTick(furnace.getLevel(), furnace.getBlockPos(), furnace.getBlockState(), furnace);
            port.workTick();
        }
    }
    private static void launch(Fixture f, ProductionPortBlockEntity port, int count) {
        port.startProgram(f.terminal, f.file, f.terminal.programs().file(f.file).definition(), count);
        port.workTick();
    }

    @GameTest(template = "empty")
    public static void terminalCanCancelAProgramWhileItsDataCableIsBroken(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, f.first, 2);
        h.getLevel().setBlockAndUpdate(f.cable, Blocks.AIR.defaultBlockState());
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(f.file));
        h.assertTrue(!f.terminal.programs().isActive(f.file) && !f.first.isRunning(), "Disconnected cancel did not revoke both owners");
        var before = f.storage().save();
        cook(f.furnace, f.first, 230);
        h.getLevel().setBlockAndUpdate(f.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        f.first.workTick();
        h.assertTrue(before.equals(f.storage().save()) && f.furnace.getItem(2).is(Items.GLASS),
                "Reconnecting a cancelled interface moved its abandoned real product");
        boolean rejected = false;
        try { launch(f, f.first, 1); } catch (IllegalArgumentException busy) { rejected = true; }
        h.assertTrue(rejected && !f.terminal.programs().isActive(f.file), "New launch adopted cancelled machine inventory");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aReturningOldPortCannotUseAFileReboundToAnotherPort(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, f.first, 2);
        CompoundTag oldRuntime = f.first.saveWithoutMetadata();
        BlockPos oldPosition = f.first.getBlockPos();
        h.getLevel().removeBlockEntity(oldPosition);
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(f.file));
        f.terminal.programs().configure(f.file, f.second.createDefinition("玻璃程序", f.input, f.fuel, f.output,
                entry(f.storage(), f.input, Items.SAND), entry(f.storage(), f.fuel, Items.COAL)), f.second.getBlockPos(), f.second.identity());
        launch(f, f.second, 1);
        var before = f.storage().save();
        var returning = new ProductionPortBlockEntity(oldPosition, h.getLevel().getBlockState(oldPosition));
        returning.load(oldRuntime); h.getLevel().setBlockEntity(returning);
        returning.workTick();
        h.assertTrue(!returning.isRunning() && before.equals(f.storage().save()) && f.terminal.programs().isActive(f.file),
                "Returning runtime consumed materials or revoked the replacement port's reservation");
        cook(f.furnace, returning, 230); cook(f.otherFurnace, f.second, 230);
        h.assertTrue(f.furnace.getItem(2).is(Items.GLASS) && count(f.storage(), f.output, Items.GLASS) == 1,
                "Rebound file collected the old port's product or lost its new product");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void staleRuntimeCannotConsumeUnderANewerLaunchOfTheSameFile(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, f.first, 2);
        CompoundTag staleRuntime = f.first.saveWithoutMetadata();
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(f.file));
        // A player clears the cancelled input and fuel before intentionally launching the file again.
        ItemStack recoveredInput = f.furnace.removeItem(0, 64), recoveredFuel = f.furnace.removeItem(1, 64);
        f.storage().insert(recoveredInput, recoveredInput.getCount(), f.input);
        f.storage().insert(recoveredFuel, recoveredFuel.getCount(), f.fuel);
        launch(f, f.first, 1);
        var before = f.storage().save();
        f.first.load(staleRuntime);
        TerminalPrograms.reconcile(f.terminal);
        h.assertTrue(f.terminal.programs().isActive(f.file), "Reconciliation revoked a newer launch while an old runtime was present");
        f.first.workTick();
        TerminalPrograms.reconcile(f.terminal);
        h.assertTrue(!f.first.isRunning() && before.equals(f.storage().save()) && f.terminal.programs().isActive(f.file),
                "Old saved launch reused or revoked the newer active reservation");
        cook(f.furnace, f.first, 230);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 0 && f.furnace.getItem(2).is(Items.GLASS),
                "A stale runtime collected the newer launch's product");
        h.succeed();
    }
}
