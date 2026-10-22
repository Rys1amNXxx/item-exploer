package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.production.ProcessingPattern;
import mekanism.api.RelativeSide;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.machine.TileEntityCombiner;
import mekanism.common.tile.machine.TileEntityEnergizedSmelter;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** Opt-in tests: real Mekanism recipes and tickers, with energy supplied by the fixture. */
@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RealModProductionTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port,
                           TileEntityConfigurableMachine machine, int input, int output) {}

    private static Fixture fixture(GameTestHelper h, String machineId) {
        var level = h.getLevel(); BlockPos machinePos = h.absolutePos(new BlockPos(2, 2, 2));
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(machineId));
        h.assertTrue(block != null, "Compatibility fixture machine is not registered");
        level.setBlockAndUpdate(machinePos, block.defaultBlockState());
        var machine = (TileEntityConfigurableMachine) level.getBlockEntity(machinePos);
        var config = machine.getConfig().getConfig(TransmissionType.ITEM);
        config.setEjecting(false);
        config.setDataType(DataType.INPUT, RelativeSide.TOP);
        config.setDataType(DataType.OUTPUT, RelativeSide.BOTTOM);
        machine.getConfig().sideChanged(TransmissionType.ITEM, RelativeSide.TOP);
        machine.getConfig().sideChanged(TransmissionType.ITEM, RelativeSide.BOTTOM);
        BlockPos portPos = machinePos.north(), cable = portPos.north(), terminalPos = cable.west();
        var portState = ModContent.PRODUCTION_BLOCK.get().defaultBlockState().setValue(ProductionPortBlock.FACING, Direction.NORTH);
        h.assertTrue(portState.canSurvive(level, portPos), "Real mod machine rejected interface placement");
        level.setBlockAndUpdate(portPos, portState);
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        int input = terminal.inventory().createFolder(0, "Inputs"), output = terminal.inventory().createFolder(0, "Outputs");
        h.assertTrue("item_handler".equals(port.machineType()), "Real mod machine did not use generic access");
        return new Fixture(terminal, port, machine, input, output);
    }

    private static void start(Fixture f, ProcessingPattern pattern, int batches) {
        int id = f.terminal.programs().create(0, "Real machine");
        var definition = f.port.createProcessingDefinition("Real machine", pattern);
        f.terminal.programs().configure(id, definition, f.port.getBlockPos(), f.port.identity());
        f.port.startProgram(f.terminal, id, definition, batches);
    }

    private static long count(Fixture f, int folder, Item item) {
        return f.terminal.inventory().entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToLong(e -> e.count()).sum();
    }

    @GameTest(template = "empty", timeoutTicks = 650)
    public static void mekanismEnergizedSmelterCompletesTwoBatchesThroughSidedHandlers(GameTestHelper h) {
        Fixture f = fixture(h, "mekanism:energized_smelter");
        var machine = (TileEntityEnergizedSmelter) f.machine;
        f.terminal.inventory().insert(new ItemStack(Items.SAND, 2), 2, f.input);
        start(f, new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(new ProcessingPattern.Material(f.input, new ItemStack(Items.SAND))),
                List.of(new ProcessingPattern.Material(f.output, new ItemStack(Items.GLASS)))), 2);
        h.succeedWhen(() -> {
            machine.getEnergyContainer().setEnergy(machine.getEnergyContainer().getMaxEnergy());
            h.assertTrue(count(f, f.output, Items.GLASS) == 2, "Real smelter still working: " + f.port.runtimeView().getString("Status"));
            h.assertTrue(count(f, f.input, Items.SAND) == 0 && !f.port.isRunning()
                    && f.port.runtimeView().getInt("Completed") == 2, "Real smelter batch accounting failed");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 650)
    public static void mekanismCombinerConsumesTwoIngredientsOnDifferentFaces(GameTestHelper h) {
        Fixture f = fixture(h, "mekanism:combiner");
        var machine = (TileEntityCombiner) f.machine;
        var side = RelativeSide.LEFT;
        machine.getConfig().getConfig(TransmissionType.ITEM).setDataType(DataType.EXTRA, side);
        machine.getConfig().sideChanged(TransmissionType.ITEM, side);
        Direction extraFace = side.getDirection(machine.getDirection());
        f.terminal.inventory().insert(new ItemStack(Items.BLUE_DYE, 2), 2, f.input);
        f.terminal.inventory().insert(new ItemStack(Items.RED_DYE, 2), 2, f.input);
        start(f, new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(new ProcessingPattern.Material(f.input, new ItemStack(Items.BLUE_DYE)),
                        new ProcessingPattern.Material(f.input, new ItemStack(Items.RED_DYE), extraFace)),
                List.of(new ProcessingPattern.Material(f.output, new ItemStack(Items.PURPLE_DYE, 4)))), 2);
        h.succeedWhen(() -> {
            machine.getEnergyContainer().setEnergy(machine.getEnergyContainer().getMaxEnergy());
            h.assertTrue(count(f, f.output, Items.PURPLE_DYE) == 8, "Real combiner still working: " + f.port.runtimeView().getString("Status"));
            h.assertTrue(count(f, f.input, Items.BLUE_DYE) == 0 && count(f, f.input, Items.RED_DYE) == 0
                    && f.port.runtimeView().getInt("Completed") == 2 && !f.port.isRunning(), "Real combiner batch accounting failed");
        });
    }
}
