package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlock.Indicator;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionAppearanceTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port,
                           FurnaceBlockEntity furnace, BlockPos cable, int input, int fuel, int output, int file) {
        StorageInventory storage() { return terminal.inventory(); }
    }

    private static Fixture fixture(GameTestHelper h, int inputCount) {
        return fixture(h, inputCount, Direction.NORTH);
    }

    private static Fixture fixture(GameTestHelper h, int inputCount, Direction facing) {
        var level = h.getLevel();
        BlockPos machinePos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos portPos = machinePos.relative(facing), cable = portPos.relative(facing);
        BlockPos terminalPos = cable.relative(facing.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP);
        level.setBlockAndUpdate(machinePos, Blocks.FURNACE.defaultBlockState());
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, facing));
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        var furnace = (FurnaceBlockEntity) level.getBlockEntity(machinePos);
        var inventory = terminal.inventory();
        int input = inventory.createFolder(0, "原料"), fuel = inventory.createFolder(0, "燃料"), output = inventory.createFolder(0, "成品");
        inventory.insert(new ItemStack(Items.SAND, inputCount), inputCount, input);
        inventory.insert(new ItemStack(Items.COAL, 2), 2, fuel);
        int file = terminal.programs().create(0, "玻璃程序");
        terminal.programs().configure(file, port.createDefinition("玻璃程序", input, fuel, output,
                entry(inventory, input, Items.SAND), entry(inventory, fuel, Items.COAL)), portPos, port.identity());
        return new Fixture(terminal, port, furnace, cable, input, fuel, output, file);
    }

    private static void start(Fixture f, int count) {
        f.port.startProgram(f.terminal, f.file, f.terminal.programs().file(f.file).definition(), count);
    }

    private static void indicator(GameTestHelper h, Fixture f, Indicator expected) {
        var state = h.getLevel().getBlockState(f.port.getBlockPos());
        h.assertTrue(state.is(ModContent.PRODUCTION_BLOCK.get()), "Changing the indicator removed the production port");
        Indicator actual = state.getValue(ProductionPortBlock.STATUS);
        h.assertTrue(actual == expected, "Expected " + expected + " indicator, got " + actual
                + " for " + f.port.runtimeView().getString("Status"));
    }

    @GameTest(template = "empty")
    public static void shortageRecoveryAndRealCompletionDriveTheIndicator(GameTestHelper h) {
        Fixture f = fixture(h, 1);
        indicator(h, f, Indicator.IDLE);
        start(f, 2); f.port.workTick();
        indicator(h, f, Indicator.RUNNING);
        cook(f.furnace, f.port, 230);
        indicator(h, f, Indicator.WAITING);
        h.assertTrue(f.port.isRunning() && "production_missing_input".equals(f.port.runtimeView().getString("Status"))
                        && count(f.storage(), f.output, Items.GLASS) == 1,
                "An unfinished batch was not waiting for its missing second input");
        f.storage().insert(new ItemStack(Items.SAND), 1, f.input); f.port.workTick();
        indicator(h, f, Indicator.RUNNING);
        cook(f.furnace, f.port, 230);
        indicator(h, f, Indicator.COMPLETE);
        h.assertTrue(!f.port.isRunning() && !f.terminal.programs().isActive(f.file)
                        && count(f.storage(), f.output, Items.GLASS) == 2,
                "Completion indicator appeared before both real outputs were collected");
        var completedInventory = f.storage().save();
        h.runAfterDelay(19, () -> {
            f.port.workTick(); indicator(h, f, Indicator.COMPLETE);
        });
        h.runAfterDelay(20, () -> {
            f.port.workTick(); indicator(h, f, Indicator.IDLE);
            h.assertTrue(completedInventory.equals(f.storage().save()), "Expiring the completion light changed stored items");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void cancellingAnActiveBatchReturnsToIdleWithoutCompletion(GameTestHelper h) {
        Fixture f = fixture(h, 2); start(f, 2); f.port.workTick();
        indicator(h, f, Indicator.RUNNING);
        var before = f.storage().save();
        ItemStack input = f.furnace.getItem(0).copy(), fuel = f.furnace.getItem(1).copy();
        f.port.cancel(); f.port.workTick();
        indicator(h, f, Indicator.IDLE);
        h.assertTrue(!f.port.isRunning() && !f.terminal.programs().isActive(f.file)
                        && before.equals(f.storage().save()) && ItemStack.matches(input, f.furnace.getItem(0))
                        && ItemStack.matches(fuel, f.furnace.getItem(1)),
                "Cancelling for the idle light refunded, removed, or changed real machine contents");
        cook(f.furnace, f.port, 230);
        indicator(h, f, Indicator.IDLE);
        h.assertTrue(f.furnace.getItem(2).is(Items.GLASS) && count(f.storage(), f.output, Items.GLASS) == 0,
                "A cancelled indicator resumed collection or announced the abandoned output");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadingACompletedBatchDoesNotReplayItsCompletionLight(GameTestHelper h) {
        Fixture f = fixture(h, 1); start(f, 1); f.port.workTick(); cook(f.furnace, f.port, 230);
        indicator(h, f, Indicator.COMPLETE);
        var before = f.storage().save();
        f.port.load(f.port.saveWithoutMetadata()); f.port.workTick();
        indicator(h, f, Indicator.IDLE);
        h.assertTrue("production_complete".equals(f.port.runtimeView().getString("Status"))
                        && f.port.runtimeView().getInt("Completed") == 1 && before.equals(f.storage().save()),
                "Suppressing a restored completion flash erased progress or changed inventory");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void waitingAndInterferedRunsRestoreTheirActualIndicator(GameTestHelper h) {
        Fixture f = fixture(h, 1); start(f, 2); f.port.workTick(); cook(f.furnace, f.port, 230);
        indicator(h, f, Indicator.WAITING);
        f.port.load(f.port.saveWithoutMetadata()); f.port.workTick();
        indicator(h, f, Indicator.WAITING);
        f.storage().insert(new ItemStack(Items.SAND), 1, f.input); f.port.workTick();
        indicator(h, f, Indicator.RUNNING);
        f.furnace.removeItem(0, 1); f.port.workTick();
        indicator(h, f, Indicator.ERROR);
        h.assertTrue("production_interference".equals(f.port.runtimeView().getString("Status")),
                "The red indicator did not represent actual machine interference");
        var before = f.storage().save();
        f.port.load(f.port.saveWithoutMetadata()); f.port.workTick();
        indicator(h, f, Indicator.ERROR);
        h.assertTrue(before.equals(f.storage().save()) && f.port.isRunning(),
                "Restoring the fault indicator consumed more material or silently cancelled the run");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedPortAndTerminalDataShowErrorWithoutRewritingData(GameTestHelper h) {
        Fixture f = fixture(h, 2); start(f, 2); f.port.workTick();
        CompoundTag healthy = f.port.saveWithoutMetadata(), future = healthy.copy();
        future.getCompound("Production").putInt("Version", 99);
        var before = f.storage().save();
        f.port.load(future); f.port.workTick();
        indicator(h, f, Indicator.ERROR);
        h.assertTrue(f.port.isLocked() && future.get("Production").equals(f.port.saveWithoutMetadata().get("Production"))
                        && before.equals(f.storage().save()),
                "The protected-data light rewrote future port data or moved material");
        f.port.load(healthy);
        CompoundTag protectedStorage = ((CompoundTag) f.storage().save()).copy();
        protectedStorage.putInt("Version", 99); f.storage().load(protectedStorage); f.port.workTick();
        indicator(h, f, Indicator.ERROR);
        h.assertTrue(f.storage().isLocked() && protectedStorage.equals(f.storage().save())
                        && "production_storage_locked".equals(f.port.runtimeView().getString("Status")),
                "A protected terminal was not shown as a preserved data fault");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void losingAndRestoringTheCableChangesOnlyTheWaitingIndicator(GameTestHelper h) {
        Fixture f = fixture(h, 2); start(f, 2); f.port.workTick();
        var before = f.storage().save();
        h.getLevel().setBlockAndUpdate(f.cable, Blocks.AIR.defaultBlockState()); f.port.workTick();
        indicator(h, f, Indicator.WAITING);
        h.assertTrue("production_disconnected".equals(f.port.runtimeView().getString("Status"))
                        && f.port.isRunning() && f.terminal.programs().isActive(f.file),
                "A broken cable cancelled the job instead of displaying a waiting state");
        h.getLevel().setBlockAndUpdate(f.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState()); f.port.workTick();
        indicator(h, f, Indicator.RUNNING);
        h.assertTrue(before.equals(f.storage().save()), "Reconnecting the indicator transferred an extra input or fuel");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyFacingRetainsItsMachineAndRunAcrossAllIndicatorStates(GameTestHelper h) {
        for (Direction facing : Direction.values()) {
            Fixture f = fixture(h, 2, facing); start(f, 2); f.port.workTick();
            var before = f.storage().save();
            CompoundTag run = f.port.saveWithoutMetadata();
            for (Indicator next : Indicator.values()) {
                var state = h.getLevel().getBlockState(f.port.getBlockPos());
                h.getLevel().setBlockAndUpdate(f.port.getBlockPos(), state.setValue(ProductionPortBlock.STATUS, next));
                indicator(h, f, next);
                h.assertTrue(h.getLevel().getBlockEntity(f.port.getBlockPos()) == f.port
                                && h.getLevel().getBlockEntity(f.furnace.getBlockPos()) == f.furnace
                                && f.port.getBlockState().getValue(ProductionPortBlock.FACING) == facing
                                && f.port.isRunning() && f.terminal.programs().isActive(f.file)
                                && before.equals(f.storage().save()) && run.equals(f.port.saveWithoutMetadata()),
                        "Indicator " + next + " detached, rotated, or mutated a live " + facing + " interface");
            }
            f.port.cancel();
            h.getLevel().setBlockAndUpdate(f.port.getBlockPos(), Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(f.cable, Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(f.terminal.getBlockPos(), Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(f.furnace.getBlockPos(), Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }
}
