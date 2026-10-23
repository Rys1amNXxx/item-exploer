package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.production.FurnaceProgram;
import dev.itemexplorer.production.ItemHandlerAccess;
import dev.itemexplorer.production.ProcessingBatch;
import dev.itemexplorer.production.ProcessingPattern;
import dev.itemexplorer.production.ProcessingPattern.Material;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RangedWrapper;

import java.util.List;

import static dev.itemexplorer.gametest.GameTestSupport.*;

/** Exercises the public production API against a sided capability in an actual server level. */
@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GenericProductionTests {
    /**
     * A chest supplies a registered block/entity type, but its vanilla inventory is not exposed.
     * The simulated machine accepts sand and iron from above and exposes products only below.
     * Its adjustable real insertion budget models capacity changing after simulation.
     */
    private static final class CapabilityMachine extends ChestBlockEntity {
        private int acceptanceBudget = Integer.MAX_VALUE;
        private int acceptedSand, acceptedIron;
        private boolean available = true;
        private boolean splitInputs;
        private final ItemStackHandler inputs = new ItemStackHandler(2) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return slot == 0 ? stack.is(Items.SAND) : stack.is(Items.IRON_INGOT);
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (stack.isEmpty()) return ItemStack.EMPTY;
                int offered = simulate ? stack.getCount() : Math.min(stack.getCount(), acceptanceBudget);
                if (offered == 0) return stack.copy();
                ItemStack limited = stack.copy(); limited.setCount(offered);
                int accepted = offered - super.insertItem(slot, limited, simulate).getCount();
                if (!simulate) {
                    acceptanceBudget -= accepted;
                    if (stack.is(Items.SAND)) acceptedSand += accepted;
                    if (stack.is(Items.IRON_INGOT)) acceptedIron += accepted;
                    setChanged();
                }
                ItemStack remainder = stack.copy(); remainder.shrink(accepted); return remainder;
            }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }
        };
        private final ItemStackHandler outputs = new ItemStackHandler(2) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack.copy(); }
        };
        private LazyOptional<IItemHandler> inputCapability = LazyOptional.of(() -> inputs);
        private LazyOptional<IItemHandler> outputCapability = LazyOptional.of(() -> outputs);
        private final LazyOptional<IItemHandler> sandCapability = LazyOptional.of(() -> new RangedWrapper(inputs, 0, 1));
        private final LazyOptional<IItemHandler> ironCapability = LazyOptional.of(() -> new RangedWrapper(inputs, 1, 2));

        private CapabilityMachine(BlockPos position, BlockState state) { super(position, state); }
        @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            if (capability == ForgeCapabilities.ITEM_HANDLER) {
                if (!available || isRemoved()) return LazyOptional.empty();
                if (side == Direction.UP) return (splitInputs ? sandCapability : inputCapability).cast();
                if (side == Direction.NORTH && splitInputs) return ironCapability.cast();
                if (side == Direction.DOWN) return outputCapability.cast();
                return LazyOptional.empty();
            }
            return super.getCapability(capability, side);
        }
        @Override public void invalidateCaps() {
            super.invalidateCaps();
            if (inputCapability != null) inputCapability.invalidate();
            if (outputCapability != null) outputCapability.invalidate();
            if (sandCapability != null) sandCapability.invalidate();
            if (ironCapability != null) ironCapability.invalidate();
        }
        private void available(boolean next) {
            available = next;
            inputCapability.invalidate(); outputCapability.invalidate();
            if (next) {
                inputCapability = LazyOptional.of(() -> inputs);
                outputCapability = LazyOptional.of(() -> outputs);
            }
        }
        private void consumeInputs() {
            inputs.setStackInSlot(0, ItemStack.EMPTY); inputs.setStackInSlot(1, ItemStack.EMPTY); setChanged();
        }
        private void produce(int glass, int nuggets) {
            outputs.setStackInSlot(0, glass == 0 ? ItemStack.EMPTY : new ItemStack(Items.GLASS, glass));
            outputs.setStackInSlot(1, nuggets == 0 ? ItemStack.EMPTY : new ItemStack(Items.IRON_NUGGET, nuggets));
            setChanged();
        }
        @Override protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.put("TestInputs", inputs.serializeNBT()); tag.put("TestOutputs", outputs.serializeNBT());
            tag.putInt("TestAcceptedSand", acceptedSand); tag.putInt("TestAcceptedIron", acceptedIron);
        }
        @Override public void load(CompoundTag tag) {
            super.load(tag);
            inputs.deserializeNBT(tag.getCompound("TestInputs")); outputs.deserializeNBT(tag.getCompound("TestOutputs"));
            acceptedSand = tag.getInt("TestAcceptedSand"); acceptedIron = tag.getInt("TestAcceptedIron");
        }
    }

    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port, CapabilityMachine machine,
                           BlockPos cable, int file, int sand, int iron, int glass, int nuggets) {
        StorageInventory storage() { return terminal.inventory(); }
        ProcessingPattern pattern() {
            return new ProcessingPattern(Direction.UP, Direction.DOWN,
                    List.of(material(sand, Items.SAND, 2), material(iron, Items.IRON_INGOT, 3)),
                    List.of(material(glass, Items.GLASS, 3), material(nuggets, Items.IRON_NUGGET, 2)));
        }
    }

    private static Material material(int folder, Item item, int count) { return new Material(folder, new ItemStack(item, count)); }
    private static Fixture fixture(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos machinePos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos portPos = machinePos.north(), cable = portPos.north(), terminalPos = cable.west();
        level.setBlockAndUpdate(machinePos, Blocks.CHEST.defaultBlockState());
        level.removeBlockEntity(machinePos);
        CapabilityMachine machine = new CapabilityMachine(machinePos, level.getBlockState(machinePos));
        level.setBlockEntity(machine);
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        var inventory = terminal.inventory();
        int sand = inventory.createFolder(0, "砂料"), iron = inventory.createFolder(0, "铁料");
        int glass = inventory.createFolder(0, "玻璃"), nuggets = inventory.createFolder(0, "副产物");
        inventory.insert(new ItemStack(Items.SAND, 8), 8, sand);
        inventory.insert(new ItemStack(Items.IRON_INGOT, 12), 12, iron);
        int file = terminal.programs().create(0, "通用加工");
        Fixture fixture = new Fixture(terminal, port, machine, cable, file, sand, iron, glass, nuggets);
        terminal.programs().configure(file, port.createProcessingDefinition("通用加工", fixture.pattern()), portPos, port.identity());
        h.assertTrue("item_handler".equals(port.machineType()), "Sided capability was not recognized as a generic machine");
        return fixture;
    }
    private static void launch(Fixture f, int batches) {
        f.port.startProgram(f.terminal, f.file, f.terminal.programs().file(f.file).definition(), batches);
        f.port.workTick();
    }
    private static void service(Fixture f) { for (int i = 0; i < 5; i++) f.port.workTick(); }
    private static void status(GameTestHelper h, Fixture f, String expected) {
        h.assertTrue(expected.equals(f.port.runtimeView().getString("Status")),
                "Expected " + expected + ", got " + f.port.runtimeView().getString("Status"));
    }
    private static void accepted(GameTestHelper h, Fixture f, int sand, int iron) {
        h.assertTrue(f.machine.acceptedSand == sand && f.machine.acceptedIron == iron,
                "Machine accepted " + f.machine.acceptedSand + " sand and " + f.machine.acceptedIron + " iron");
    }
    private static void invalid(GameTestHelper h, Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid processing pattern was accepted");
    }

    @GameTest(template = "empty")
    public static void missingSecondIngredientNeverCommitsHalfABatchOrBorrowsOtherFolders(GameTestHelper h) {
        Fixture f = fixture(h);
        f.storage().take(entry(f.storage(), f.iron, Items.IRON_INGOT), 12);
        int child = f.storage().createFolder(f.iron, "私人铁料");
        f.storage().insert(new ItemStack(Items.IRON_INGOT, 3), 3, child);
        f.storage().insert(new ItemStack(Items.IRON_INGOT, 4), 4, 0);
        var before = f.storage().save();
        launch(f, 1); service(f);
        accepted(h, f, 0, 0);
        h.assertTrue(before.equals(f.storage().save()), "Insufficient batch reserved materials or borrowed another folder");
        status(h, f, "production_missing_input");
        f.storage().insert(new ItemStack(Items.IRON_INGOT, 3), 3, f.iron); service(f);
        accepted(h, f, 2, 3);
        h.assertTrue(count(f.storage(), child, Items.IRON_INGOT) == 3 && count(f.storage(), 0, Items.IRON_INGOT) == 4,
                "Restocking crossed an exact source folder boundary");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void disappearingInputsCanWaitForInternalProcessingWithoutBeingReinserted(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, 1); accepted(h, f, 2, 3);
        f.machine.consumeInputs(); service(f);
        accepted(h, f, 2, 3);
        h.assertTrue(f.port.isRunning() && f.port.runtimeView().getInt("Completed") == 0,
                "Invisible in-flight ingredients faulted or completed the task");
        status(h, f, "production_waiting_output");
        f.machine.produce(3, 2); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 3 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2,
                "Real output was not routed to its individual destination folders");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void allOutputRowsAndQuantitiesAreRequiredBeforeTheNextBatch(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, 2); f.machine.consumeInputs();
        f.machine.produce(1, 0); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 1 && f.port.runtimeView().getInt("Completed") == 0,
                "A partial output prematurely completed a batch");
        accepted(h, f, 2, 3);
        f.machine.produce(0, 2); service(f); accepted(h, f, 2, 3);
        f.machine.produce(2, 0); service(f);
        h.assertTrue(f.port.runtimeView().getInt("Completed") == 1, "Completed output quantities did not advance exactly one batch");
        accepted(h, f, 4, 6);
        f.machine.consumeInputs(); f.machine.produce(3, 2); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 6 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 4
                && count(f.storage(), f.sand, Items.SAND) == 4 && count(f.storage(), f.iron, Items.IRON_INGOT) == 6
                && f.port.runtimeView().getInt("Completed") == 2, "Multi-batch material totals are inconsistent");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void aPartialRealInsertionRetriesOnlyTheUnacceptedRemainder(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.acceptanceBudget = 1; launch(f, 1);
        accepted(h, f, 1, 0); var reserved = f.storage().save(); service(f);
        accepted(h, f, 1, 0);
        h.assertTrue(reserved.equals(f.storage().save()), "Retrying blocked insertion withdrew another batch");
        f.machine.acceptanceBudget = 20; service(f); accepted(h, f, 2, 3);
        f.machine.consumeInputs(); f.machine.produce(3, 2); service(f);
        h.assertTrue(count(f.storage(), f.sand, Items.SAND) == 6 && count(f.storage(), f.iron, Items.IRON_INGOT) == 9,
                "Partial insertion changed the total batch cost");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedInsertionBudgetAcrossFacesKeepsOriginalMaterialOrder(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.splitInputs = true;
        ProcessingPattern pattern = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(new Material(f.sand, new ItemStack(Items.SAND, 2), Direction.UP),
                        new Material(f.iron, new ItemStack(Items.IRON_INGOT, 3), Direction.NORTH)), f.pattern().outputs());
        f.terminal.programs().configure(f.file, f.port.createProcessingDefinition("多面限量投料", pattern),
                f.port.getBlockPos(), f.port.identity());
        f.machine.acceptanceBudget = 1; launch(f, 1);
        accepted(h, f, 1, 0);
        h.assertTrue(f.machine.inputs.getStackInSlot(0).getCount() == 1 && f.machine.inputs.getStackInSlot(1).isEmpty(),
                "A later material row consumed the shared insertion budget before the first row");
        var reserved = f.storage().save();
        service(f); accepted(h, f, 1, 0);
        h.assertTrue(reserved.equals(f.storage().save()) && f.port.runtimeView().getInt("Completed") == 0,
                "Blocked sided-handler retries withdrew another batch or advanced completion");
        f.machine.acceptanceBudget = 20; service(f); accepted(h, f, 2, 3);
        f.machine.consumeInputs(); f.machine.produce(3, 2); service(f);
        h.assertTrue(count(f.storage(), f.sand, Items.SAND) == 6 && count(f.storage(), f.iron, Items.IRON_INGOT) == 9
                && count(f.storage(), f.glass, Items.GLASS) == 3 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2
                && f.port.runtimeView().getInt("Completed") == 1,
                "Restoring the shared sided-handler budget lost or duplicated batch materials");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationReturnsOnlyUninsertedMaterials(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.acceptanceBudget = 1; launch(f, 1);
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(f.file)); service(f);
        h.assertTrue(!f.port.isRunning() && !f.terminal.programs().isActive(f.file)
                && count(f.storage(), f.sand, Items.SAND) == 7 && count(f.storage(), f.iron, Items.IRON_INGOT) == 12
                && f.machine.inputs.getStackInSlot(0).getCount() == 1,
                "Cancellation lost staged items or refunded the already committed input");
        f.machine.acceptanceBudget = 20; service(f); accepted(h, f, 1, 0);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void disconnectedCancellationPersistsUninsertedMaterialsUntilReconnected(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.acceptanceBudget = 1; launch(f, 1);
        h.getLevel().setBlockAndUpdate(f.cable, Blocks.AIR.defaultBlockState());
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(f.file));
        CompoundTag saved = f.port.saveWithoutMetadata(); f.port.load(saved); service(f);
        h.assertTrue(count(f.storage(), f.sand, Items.SAND) == 6 && count(f.storage(), f.iron, Items.IRON_INGOT) == 9,
                "Disconnected cancellation moved materials without the network");
        h.getLevel().setBlockAndUpdate(f.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState()); service(f);
        h.assertTrue(count(f.storage(), f.sand, Items.SAND) == 7 && count(f.storage(), f.iron, Items.IRON_INGOT) == 12,
                "Reload/reconnection lost or duplicated the cancelled batch remainder");
        accepted(h, f, 1, 0); h.succeed();
    }

    @GameTest(template = "empty")
    public static void fullStorageKeepsCancelledRemaindersUntilSpaceIsAvailable(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.acceptanceBudget = 1; launch(f, 1);
        while (f.storage().total() < f.storage().capacity()) f.storage().insert(new ItemStack(Items.STONE, 64), 64, 0);
        f.port.cancel(); service(f);
        h.assertTrue(!f.port.isRunning() && f.storage().total() == f.storage().capacity(), "Cancellation overfilled storage");
        CompoundTag saved = f.port.saveWithoutMetadata(); f.port.load(saved);
        f.storage().take(entry(f.storage(), 0, Items.STONE), 4); service(f);
        h.assertTrue(count(f.storage(), f.sand, Items.SAND) == 7 && count(f.storage(), f.iron, Items.IRON_INGOT) == 12
                && f.storage().total() == f.storage().capacity(), "Full-storage cancellation discarded staged materials");
        accepted(h, f, 1, 0); h.succeed();
    }

    @GameTest(template = "empty")
    public static void fullOutputStorageNeverLosesProductOrStartsAnotherBatch(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, 2); f.machine.consumeInputs();
        while (f.storage().total() < f.storage().capacity()) f.storage().insert(new ItemStack(Items.STONE, 64), 64, 0);
        f.machine.produce(3, 2); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 0 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 0
                && f.port.runtimeView().getInt("Completed") == 0, "Full storage claimed a product it could not hold");
        accepted(h, f, 2, 3);
        f.storage().take(entry(f.storage(), 0, Items.STONE), 5); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 3 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2,
                "Making output space did not recover the held products");
        accepted(h, f, 4, 6); h.succeed();
    }

    @GameTest(template = "empty")
    public static void savingDuringPartialInsertionDoesNotRepeatACommittedInput(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.acceptanceBudget = 1; launch(f, 1);
        CompoundTag terminal = f.terminal.saveWithoutMetadata(), machine = f.machine.saveWithoutMetadata(), port = f.port.saveWithoutMetadata();
        f.terminal.load(terminal); f.machine.load(machine); f.port.load(port);
        f.machine.acceptanceBudget = 20; service(f); accepted(h, f, 2, 3);
        f.machine.consumeInputs(); f.machine.produce(3, 2); service(f);
        h.assertTrue(!f.port.isLocked() && count(f.storage(), f.sand, Items.SAND) == 6 && count(f.storage(), f.iron, Items.IRON_INGOT) == 9
                && count(f.storage(), f.glass, Items.GLASS) == 3 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2,
                "Partial-insertion save/load changed owned material totals");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void savingAfterOneOutputPreservesTheRemainingOutputRequirements(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, 1); f.machine.consumeInputs(); f.machine.produce(1, 2); service(f);
        CompoundTag terminal = f.terminal.saveWithoutMetadata(), machine = f.machine.saveWithoutMetadata(), port = f.port.saveWithoutMetadata();
        f.terminal.load(terminal); f.machine.load(machine); f.port.load(port); service(f);
        accepted(h, f, 2, 3);
        h.assertTrue(f.port.runtimeView().getInt("Completed") == 0, "Reload completed a partially collected batch");
        f.machine.produce(2, 0); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 3 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2,
                "Reload lost progress or recollected an already delivered output");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void savingAfterFullyDeliveringATaggedOutputKeepsTheOtherRowsRunnable(GameTestHelper h) {
        Fixture f = fixture(h);
        ItemStack taggedGlass = new ItemStack(Items.GLASS, 3);
        taggedGlass.getOrCreateTag().putString("MachineGrade", "precision");
        ProcessingPattern pattern = new ProcessingPattern(Direction.UP, Direction.DOWN, f.pattern().inputs(),
                List.of(new Material(f.glass, taggedGlass), material(f.nuggets, Items.IRON_NUGGET, 2)));
        f.terminal.programs().configure(f.file, f.port.createProcessingDefinition("带标签产物", pattern),
                f.port.getBlockPos(), f.port.identity());
        launch(f, 1); f.machine.consumeInputs(); f.machine.outputs.setStackInSlot(0, taggedGlass.copy()); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 3 && f.port.runtimeView().getInt("Completed") == 0,
                "The tagged output row was not delivered independently of the pending byproduct");
        CompoundTag terminal = f.terminal.saveWithoutMetadata(), machine = f.machine.saveWithoutMetadata(), port = f.port.saveWithoutMetadata();
        f.terminal.load(terminal); f.machine.load(machine); f.port.load(port); service(f);
        h.assertTrue(!f.port.isLocked() && f.port.isRunning(),
                "Reload treated a fully delivered tagged output's empty buffer as corrupt data");
        f.machine.produce(0, 2); service(f);
        var delivered = f.storage().entry(entry(f.storage(), f.glass, Items.GLASS));
        h.assertTrue(delivered.count() == 3 && taggedGlass.getTag().equals(delivered.stack().getTag())
                && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2,
                "Reload changed tagged output data, duplicated delivery, or lost the remaining byproduct");
        accepted(h, f, 2, 3); status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingMachineAtSameCoordinatesCannotAdoptItsOutputs(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, 1); f.machine.consumeInputs();
        BlockPos position = f.machine.getBlockPos(); h.getLevel().removeBlockEntity(position);
        CapabilityMachine replacement = new CapabilityMachine(position, h.getLevel().getBlockState(position));
        h.getLevel().setBlockEntity(replacement); replacement.produce(3, 2); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 0 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 0
                && replacement.outputs.getStackInSlot(0).getCount() == 3 && replacement.outputs.getStackInSlot(1).getCount() == 2,
                "Old job adopted a replacement machine's inventory");
        status(h, f, "production_machine_changed"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidatedCapabilitiesAreNotCachedAcrossServicePasses(GameTestHelper h) {
        Fixture f = fixture(h); launch(f, 1); f.machine.consumeInputs(); f.machine.produce(3, 2);
        f.machine.available(false); var before = f.storage().save(); service(f);
        h.assertTrue(before.equals(f.storage().save()) && f.machine.outputs.getStackInSlot(0).getCount() == 3,
                "An invalidated capability was still used for extraction");
        f.machine.available(true); service(f);
        h.assertTrue(count(f.storage(), f.glass, Items.GLASS) == 3 && count(f.storage(), f.nuggets, Items.IRON_NUGGET) == 2,
                "Restored capability could not resume the owned batch");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void absentSidesAndMissingFoldersCannotConfigureAProgram(GameTestHelper h) {
        Fixture f = fixture(h); var before = f.storage().save();
        invalid(h, () -> f.port.createProcessingDefinition("错误面", new ProcessingPattern(Direction.NORTH, Direction.DOWN,
                f.pattern().inputs(), f.pattern().outputs())));
        invalid(h, () -> f.port.createProcessingDefinition("错误目录", new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(9999, Items.SAND, 2)), f.pattern().outputs())));
        h.assertTrue(before.equals(f.storage().save()) && !f.terminal.programs().isActive(f.file),
                "Invalid configuration changed stored materials or activated the file");
        launch(f, 1); accepted(h, f, 2, 3); h.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedAndAmbiguousPatternsAreRejectedWithoutChangingTheValidFile(GameTestHelper h) {
        Fixture f = fixture(h); var original = f.terminal.programs().file(f.file).definition().save();
        List<Material> input = f.pattern().inputs(), output = f.pattern().outputs();
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN, List.of(), output));
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN, input, List.of()));
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(-1, Items.SAND, 2)), output));
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(f.sand, Items.SAND, 65)), output));
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(f.sand, Items.SAND, 0)), output));
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN,
                input, List.of(material(f.glass, Items.SAND, 1))));
        List<Material> tooMany = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> { ItemStack stack = new ItemStack(Items.SAND); stack.getOrCreateTag().putInt("Variant", i); return new Material(f.sand, stack); })
                .toList();
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN, tooMany, output));
        ItemStack oversized = new ItemStack(Items.SAND); oversized.getOrCreateTag().putString("Payload", "x".repeat(StorageInventory.MAX_ITEM_BYTES + 1));
        invalid(h, () -> new ProcessingPattern(Direction.UP, Direction.DOWN, List.of(new Material(f.sand, oversized)), output));
        h.assertTrue(original.equals(f.terminal.programs().file(f.file).definition().save()), "Rejected patterns altered the valid saved file");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void programRoundTripPreservesSidesRowsCountsAndItemTags(GameTestHelper h) {
        Fixture f = fixture(h);
        ItemStack glass = new ItemStack(Items.GLASS, 3); glass.getOrCreateTag().putString("Variant", "precise");
        ProcessingPattern pattern = new ProcessingPattern(Direction.UP, Direction.DOWN, f.pattern().inputs(),
                List.of(new Material(f.glass, glass), material(f.nuggets, Items.IRON_NUGGET, 2)));
        FurnaceProgram definition = f.port.createProcessingDefinition("标签样板", pattern);
        CompoundTag saved = definition.save(); FurnaceProgram loaded = FurnaceProgram.load(saved);
        glass.setCount(1); glass.getOrCreateTag().putString("Variant", "changed");
        h.assertTrue(loaded.generic() && saved.equals(loaded.save()) && saved.equals(definition.save())
                && loaded.pattern().inputFace() == Direction.UP && loaded.pattern().outputFace() == Direction.DOWN
                && loaded.pattern().outputs().get(0).stack().getCount() == 3
                && "precise".equals(loaded.pattern().outputs().get(0).stack().getTag().getString("Variant")),
                "Program round-trip or source mutation changed the immutable pattern");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void productionInterfacesMountOnEverySideOfChestsAndBarrelsButNotStone(GameTestHelper h) {
        BlockPos machine = h.absolutePos(new BlockPos(2, 2, 2));
        for (var block : List.of(Blocks.CHEST, Blocks.BARREL, Blocks.STONE)) {
            h.getLevel().setBlockAndUpdate(machine, block.defaultBlockState());
            for (Direction side : Direction.values()) {
                var state = ModContent.PRODUCTION_BLOCK.get().defaultBlockState().setValue(ProductionPortBlock.FACING, side);
                h.assertTrue(state.canSurvive(h.getLevel(), machine.relative(side)) == (block != Blocks.STONE),
                        "Incorrect mount result for " + block + " on " + side);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingMachineCapabilitiesDoesNotDetachTheInterface(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.acceptanceBudget = 1; launch(f, 1); f.machine.available(false);
        var portState = h.getLevel().getBlockState(f.port.getBlockPos());
        var after = portState.updateShape(Direction.SOUTH, f.machine.getBlockState(), h.getLevel(),
                f.port.getBlockPos(), f.machine.getBlockPos());
        h.assertTrue(after.is(ModContent.PRODUCTION_BLOCK.get()) && h.getLevel().getBlockEntity(f.port.getBlockPos()) == f.port,
                "Temporarily disabled capabilities removed the interface and its owned materials");
        f.machine.available(true); f.port.cancel(); service(f);
        h.assertTrue(count(f.storage(), f.sand, Items.SAND) == 7 && count(f.storage(), f.iron, Items.IRON_INGOT) == 12,
                "Temporarily disabled capabilities lost uninserted materials"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void storageAndLogisticsInterfacesCannotBeUsedAsProcessingMachines(GameTestHelper h) {
        BlockPos position = h.absolutePos(new BlockPos(2, 2, 2));
        var logistics = new LogisticsPortBlockEntity(position, ModContent.LOGISTICS_BLOCK.get().defaultBlockState());
        var terminal = new StorageBlockEntity(position, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var nas = new NasBlockEntity(position, ModContent.NAS_BLOCK.get().defaultBlockState());
        for (var blockEntity : List.of(logistics, terminal, nas)) {
            h.assertTrue(!ItemHandlerAccess.hasHandler(blockEntity), "A storage-network endpoint was accepted as an external machine");
            for (Direction side : Direction.values()) h.assertTrue(ItemHandlerAccess.handler(blockEntity, side) == null,
                    "Sided access bypassed storage-network loop prevention");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void combinedInsertionSimulationDoesNotPromiseOneSlotToTwoMaterials(GameTestHelper h) {
        var machine = new ItemStackHandler(1);
        h.assertTrue(!ProcessingBatch.canInsertBatch(machine, List.of(new ItemStack(Items.SAND), new ItemStack(Items.IRON_INGOT))),
                "Different ingredients were both promised the same empty machine slot");
        h.assertTrue(!ProcessingBatch.canInsertBatch(machine, List.of(new ItemStack(Items.SAND, 40), new ItemStack(Items.SAND, 40))),
                "Repeated ingredients overbooked shared slot capacity");
        h.assertTrue(ProcessingBatch.canInsertBatch(machine, List.of(new ItemStack(Items.SAND, 30), new ItemStack(Items.SAND, 34)))
                && machine.getStackInSlot(0).isEmpty(), "Exact shared-slot capacity should simulate without mutation");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void flexibleAndRestrictedSlotsAcceptEitherMaterialRowOrder(GameTestHelper h) {
        for (boolean flexibleFirst : List.of(true, false)) {
            var machine = new ItemStackHandler(2) {
                @Override public int getSlotLimit(int slot) { return 1; }
                @Override public boolean isItemValid(int slot, ItemStack stack) {
                    return stack.is(Items.SAND) || (slot == 0 && stack.is(Items.IRON_INGOT));
                }
            };
            List<Material> materials = flexibleFirst
                    ? List.of(material(0, Items.SAND, 1), material(0, Items.IRON_INGOT, 1))
                    : List.of(material(0, Items.IRON_INGOT, 1), material(0, Items.SAND, 1));
            var inventory = storage();
            inventory.insert(new ItemStack(Items.SAND), 1, 0);
            inventory.insert(new ItemStack(Items.IRON_INGOT), 1, 0);
            var before = inventory.save();
            var pattern = new ProcessingPattern(Direction.UP, Direction.DOWN, materials,
                    List.of(material(0, Items.GLASS, 1)));
            var batch = new ProcessingBatch(pattern, () -> {});

            h.assertTrue(ProcessingBatch.canInsertBatch(machine, materials.stream().map(Material::stack).toList())
                    && ProcessingBatch.empty(machine) && before.equals(inventory.save()),
                    "A valid mixed-slot allocation was rejected or simulation mutated inventories: " + flexibleFirst);
            batch.reserve(inventory, machine);
            h.assertTrue(batch.staged() && batch.hasOwned() && inventory.total() == 0 && ProcessingBatch.empty(machine),
                    "Reservation did not retain the complete batch before inserting it");
            batch.insert(machine);
            h.assertTrue(batch.sentAll() && !batch.hasOwned()
                    && machine.getStackInSlot(0).is(Items.IRON_INGOT) && machine.getStackInSlot(0).getCount() == 1
                    && machine.getStackInSlot(1).is(Items.SAND) && machine.getStackInSlot(1).getCount() == 1,
                    "Real insertion did not preserve the feasible mixed-slot allocation: " + flexibleFirst);
            CompoundTag inserted = batch.save();
            batch.insert(machine);
            batch.returnOwned(inventory);
            h.assertTrue(inserted.equals(batch.save()) && inventory.total() == 0,
                    "Repeating insertion or cancellation duplicated an already inserted mixed-slot ingredient");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void equalCandidateCountsCanReassignAnEarlierSlotChoice(GameTestHelper h) {
        var machine = new ItemStackHandler(4) {
            @Override public int getSlotLimit(int slot) { return 1; }
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                return switch (slot) {
                    case 0 -> stack.is(Items.SAND) || stack.is(Items.GOLD_INGOT);
                    case 1 -> stack.is(Items.SAND) || stack.is(Items.IRON_INGOT);
                    default -> stack.is(Items.GOLD_INGOT) || stack.is(Items.DIAMOND);
                };
            }
        };
        // Every slot accepts two material types. Taking the first row's gold for slot 0
        // strands iron; a successful assignment must move gold to slot 2 or 3.
        List<Material> materials = List.of(material(0, Items.GOLD_INGOT, 1), material(0, Items.SAND, 1),
                material(0, Items.IRON_INGOT, 1), material(0, Items.DIAMOND, 1));
        var inventory = storage();
        for (Material material : materials) inventory.insert(material.stack(), 1, 0);
        var pattern = new ProcessingPattern(Direction.UP, Direction.DOWN, materials, List.of(material(0, Items.GLASS, 1)));
        var batch = new ProcessingBatch(pattern, () -> {});

        h.assertTrue(ProcessingBatch.canInsertBatch(machine, materials.stream().map(Material::stack).toList())
                && ProcessingBatch.empty(machine), "A feasible assignment requiring reassignment was rejected");
        batch.reserve(inventory, machine);
        batch.insert(machine);
        ItemStack first = machine.getStackInSlot(0), second = machine.getStackInSlot(1);
        ItemStack third = machine.getStackInSlot(2), fourth = machine.getStackInSlot(3);
        h.assertTrue(batch.sentAll() && !batch.hasOwned() && inventory.total() == 0
                && first.is(Items.SAND) && second.is(Items.IRON_INGOT)
                && first.getCount() == 1 && second.getCount() == 1 && third.getCount() == 1 && fourth.getCount() == 1
                && ((third.is(Items.GOLD_INGOT) && fourth.is(Items.DIAMOND))
                    || (third.is(Items.DIAMOND) && fourth.is(Items.GOLD_INGOT))),
                "Equal candidate counts left a feasible ingredient stranded or changed the batch totals");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyThreeByThreeSlotAcceptanceMatrixMatchesAnIndependentAssignmentOracle(GameTestHelper h) {
        List<Item> types = List.of(Items.SAND, Items.IRON_INGOT, Items.GOLD_INGOT);
        List<Material> materials = types.stream().map(item -> material(0, item, 1)).toList();
        List<ItemStack> offered = materials.stream().map(Material::stack).toList();
        int[][] assignments = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        var pattern = new ProcessingPattern(Direction.UP, Direction.DOWN, materials, List.of(material(0, Items.GLASS, 1)));
        for (int mask = 0; mask < 512; mask++) {
            final int acceptance = mask;
            var machine = new ItemStackHandler(3) {
                @Override public int getSlotLimit(int slot) { return 1; }
                @Override public boolean isItemValid(int slot, ItemStack stack) {
                    int material = types.indexOf(stack.getItem());
                    return material >= 0 && (acceptance & (1 << (material * 3 + slot))) != 0;
                }
            };
            boolean possible = false;
            for (int[] assignment : assignments) {
                boolean valid = true;
                for (int material = 0; material < 3; material++)
                    valid &= (acceptance & (1 << (material * 3 + assignment[material]))) != 0;
                possible |= valid;
            }
            h.assertTrue(ProcessingBatch.canInsertBatch(machine, offered) == possible && ProcessingBatch.empty(machine),
                    "Slot feasibility differs from the six-permutation oracle or simulation mutated matrix " + mask);
            if (!possible) continue;

            var inventory = storage();
            for (Material material : materials) inventory.insert(material.stack(), 1, 0);
            var batch = new ProcessingBatch(pattern, () -> {});
            batch.reserve(inventory, machine);
            batch.insert(machine);
            int[] counts = new int[types.size()];
            for (int slot = 0; slot < machine.getSlots(); slot++) {
                ItemStack actual = machine.getStackInSlot(slot);
                int material = types.indexOf(actual.getItem());
                h.assertTrue(material >= 0 && actual.getCount() == 1 && machine.isItemValid(slot, actual),
                        "Real insertion chose an invalid slot or quantity for matrix " + mask);
                counts[material] += actual.getCount();
            }
            h.assertTrue(batch.sentAll() && !batch.hasOwned() && inventory.total() == 0
                    && counts[0] == 1 && counts[1] == 1 && counts[2] == 1,
                    "A feasible matrix stranded an ingredient or changed total material counts: " + mask);
            CompoundTag inserted = batch.save(), machineContents = machine.serializeNBT();
            batch.insert(machine);
            batch.returnOwned(inventory);
            h.assertTrue(inserted.equals(batch.save()) && machineContents.equals(machine.serializeNBT()) && inventory.total() == 0,
                    "A repeated service pass duplicated or refunded committed ingredients for matrix " + mask);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aliasedSidedSlotsKeepFeedingPartialCapacityUntilTheBatchCompletes(GameTestHelper h) {
        var input = new ItemStackHandler(1);
        var output = new ItemStackHandler(1);
        java.util.Map<Direction, IItemHandler> handlers = java.util.Map.of(
                Direction.UP, new RangedWrapper(input, 0, 1),
                Direction.NORTH, new RangedWrapper(input, 0, 1),
                Direction.SOUTH, new RangedWrapper(input, 0, 1),
                Direction.DOWN, output);
        var pattern = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(new Material(0, new ItemStack(Items.SAND, 64), Direction.UP),
                        new Material(0, new ItemStack(Items.SAND, 64), Direction.NORTH),
                        new Material(0, new ItemStack(Items.SAND, 64), Direction.SOUTH)),
                List.of(material(0, Items.GLASS, 64)));
        var inventory = storage();
        for (int row = 0; row < 3; row++) inventory.insert(new ItemStack(Items.SAND, 64), 64, 0);
        var batch = new ProcessingBatch(pattern, () -> {});
        batch.reserve(inventory, handlers::get);
        h.assertTrue(batch.staged() && inventory.total() == 0 && input.getStackInSlot(0).isEmpty(),
                "Distinct sided wrappers should reserve their materials without inserting them");

        int consumed = 0;
        for (int pass = 0; pass < 16 && !batch.complete(); pass++) {
            batch.insert(handlers::get);
            if (pass < 2) h.assertTrue(input.getStackInSlot(0).getCount() == 64,
                    "The shared slot was not filled despite having partial capacity on pass " + pass);
            // This machine consumes three sand per operation. A full slot leaves one sand,
            // so the next service pass must accept 63 of a still-buffered 64-item row.
            int amount = input.getStackInSlot(0).getCount() / 3 * 3;
            ItemStack taken = input.extractItem(0, amount, false);
            h.assertTrue(taken.getCount() == amount && (taken.isEmpty() || taken.is(Items.SAND)),
                    "The fixture consumed material that was not actually present");
            consumed += taken.getCount();
            if (amount > 0) h.assertTrue(output.insertItem(0, new ItemStack(Items.GLASS, amount / 3), false).isEmpty(),
                    "The fixture could not store its actual product");
            batch.collect(handlers::get);
            batch.deliver(inventory);
            h.assertTrue(count(inventory, 0, Items.SAND) == 0 && count(inventory, 0, Items.GLASS) == consumed / 3,
                    "Aliased-face retries withdrew extra material or changed delivered product counts");
        }
        h.assertTrue(batch.complete() && batch.sentAll() && !batch.hasOwned() && consumed == 192
                && count(inventory, 0, Items.GLASS) == 64 && inventory.total() == 64
                && input.getStackInSlot(0).isEmpty() && output.getStackInSlot(0).isEmpty(),
                "Aliased sided inventories deadlocked on partial space or failed the 192-to-64 material balance");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateInputRowsCheckTheirCombinedStockBeforeReservation(GameTestHelper h) {
        var inventory = storage(); inventory.insert(new ItemStack(Items.SAND, 3), 3, 0);
        var pattern = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(0, Items.SAND, 2), material(0, Items.SAND, 2)), List.of(material(0, Items.GLASS, 1)));
        var before = inventory.save(); var batch = new ProcessingBatch(pattern, () -> {});
        rejected(h, () -> batch.reserve(inventory, new ItemStackHandler(1)), "production_missing_input");
        h.assertTrue(before.equals(inventory.save()) && !batch.hasOwned(), "Insufficient combined stock partially reserved a batch");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void combinedOutputRoomIsCheckedBeforeAnyInputIsReserved(GameTestHelper h) {
        var inventory = storage(); inventory.insert(new ItemStack(Items.SAND), 1, 0);
        while (inventory.total() < inventory.capacity() - 2) inventory.insert(new ItemStack(Items.STONE, 64),
                (int) Math.min(64, inventory.capacity() - 2 - inventory.total()), 0);
        var pattern = new ProcessingPattern(Direction.UP, Direction.DOWN, List.of(material(0, Items.SAND, 1)),
                List.of(material(0, Items.GLASS, 2), material(0, Items.IRON_NUGGET, 2)));
        var before = inventory.save(); var batch = new ProcessingBatch(pattern, () -> {});
        rejected(h, () -> batch.reserve(inventory, new ItemStackHandler(1)), "production_output_full");
        h.assertTrue(before.equals(inventory.save()) && !batch.hasOwned(), "Individual output checks overbooked total destination capacity");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eachIngredientCanSelectItsOwnMachineInputFace(GameTestHelper h) {
        Fixture f = fixture(h); f.machine.splitInputs = true;
        ProcessingPattern pattern = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(new Material(f.sand, new ItemStack(Items.SAND, 2), Direction.UP),
                        new Material(f.iron, new ItemStack(Items.IRON_INGOT, 3), Direction.NORTH)), f.pattern().outputs());
        var definition = f.port.createProcessingDefinition("多面加工", pattern);
        f.terminal.programs().configure(f.file, definition, f.port.getBlockPos(), f.port.identity());
        launch(f, 1); accepted(h, f, 2, 3);
        h.assertTrue(FurnaceProgram.load(definition.save()).pattern().save().equals(pattern.save()),
                "Save/load lost per-material side overrides");
        f.machine.consumeInputs(); f.machine.produce(3, 2); service(f);
        status(h, f, "production_complete"); h.succeed();
    }
}
