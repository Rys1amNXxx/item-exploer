package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.production.ProcessingPattern;
import dev.itemexplorer.production.ProcessingPattern.Material;
import dev.itemexplorer.production.ProcessingPattern.Role;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import java.util.List;

import static dev.itemexplorer.gametest.GameTestSupport.*;

/** Real level/network integration; external processing itself is represented by moving chest contents. */
@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdvancedProcessingEndpointTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity main,
                           ProductionPortBlockEntity remote, ChestBlockEntity input, ChestBlockEntity output,
                           BlockPos bridge, int source, int products) {
        StorageInventory inventory() { return terminal.inventory(); }
        ProcessingPattern pattern() {
            return new ProcessingPattern(Direction.UP, Direction.DOWN,
                    List.of(material(source, Items.SAND, 2, null, 0, Role.STANDARD)),
                    List.of(material(products, Items.GLASS, 1, remote, 2, Role.STANDARD)));
        }
    }

    /** Simulation accepts a whole batch, but the first real service can accept only one item. */
    private static final class BudgetChest extends ChestBlockEntity {
        private int budget = 1;
        private final LazyOptional<IItemHandler> exposed = LazyOptional.of(() -> new InvWrapper(this) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (simulate || stack.isEmpty()) return super.insertItem(slot, stack, simulate);
                int offered = Math.min(stack.getCount(), budget);
                if (offered == 0) return stack.copy();
                ItemStack remainder = super.insertItem(slot, stack.copyWithCount(offered), false);
                int accepted = offered - remainder.getCount();
                budget -= accepted;
                return accepted == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
            }
        });

        private BudgetChest(BlockPos position, BlockState state) { super(position, state); }
        @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            if (capability == ForgeCapabilities.ITEM_HANDLER) return isRemoved() ? LazyOptional.empty() : exposed.cast();
            return super.getCapability(capability, side);
        }
        @Override public void invalidateCaps() {
            super.invalidateCaps();
            if (exposed != null) exposed.invalidate();
        }
    }

    private static Material material(int folder, Item item, int count, ProductionPortBlockEntity endpoint,
                                     int slot, Role role) {
        return new Material(folder, new ItemStack(item, count), null,
                endpoint == null ? null : endpoint.processingEndpoint(), slot, role);
    }

    private static Fixture fixture(GameTestHelper h) { return fixture(h, false); }

    private static Fixture fixture(GameTestHelper h, boolean limitedInput) {
        var level = h.getLevel();
        BlockPos inputPos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos outputPos = h.absolutePos(new BlockPos(5, 2, 2));
        BlockPos terminalPos = h.absolutePos(new BlockPos(1, 2, 0));
        level.setBlockAndUpdate(inputPos, Blocks.CHEST.defaultBlockState());
        if (limitedInput) {
            level.removeBlockEntity(inputPos);
            level.setBlockEntity(new BudgetChest(inputPos, level.getBlockState(inputPos)));
        }
        level.setBlockAndUpdate(outputPos, Blocks.CHEST.defaultBlockState());
        for (BlockPos position : List.of(inputPos.north(), outputPos.north()))
            level.setBlockAndUpdate(position, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                    .setValue(ProductionPortBlock.FACING, Direction.NORTH));
        for (int x = 2; x <= 5; x++)
            level.setBlockAndUpdate(h.absolutePos(new BlockPos(x, 2, 0)), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var main = (ProductionPortBlockEntity) level.getBlockEntity(inputPos.north());
        var remote = (ProductionPortBlockEntity) level.getBlockEntity(outputPos.north());
        var input = (ChestBlockEntity) level.getBlockEntity(inputPos);
        var output = (ChestBlockEntity) level.getBlockEntity(outputPos);
        int source = terminal.inventory().createFolder(0, "原料");
        int products = terminal.inventory().createFolder(0, "成品");
        terminal.inventory().insert(new ItemStack(Items.SAND, 8), 8, source);
        return new Fixture(terminal, main, remote, input, output,
                h.absolutePos(new BlockPos(4, 2, 0)), source, products);
    }

    private static int configure(Fixture f, ProductionPortBlockEntity owner, String name, ProcessingPattern pattern) {
        int file = f.terminal.programs().create(0, name);
        f.terminal.programs().configure(file, owner.createProcessingDefinition(name, pattern), owner.getBlockPos(), owner.identity());
        return file;
    }

    private static void start(Fixture f, ProductionPortBlockEntity owner, int file, int batches) {
        owner.startProgram(f.terminal, file, f.terminal.programs().file(file).definition(), batches);
    }

    private static void service(Fixture f) { for (int i = 0; i < 4; i++) f.main.workTick(); }

    private static void rejects(GameTestHelper h, Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, message);
    }

    private static void finishBatch(Fixture f) {
        f.input.setItem(0, ItemStack.EMPTY);
        f.output.setItem(2, new ItemStack(Items.GLASS));
        f.main.workTick();
    }

    @GameTest(template = "empty")
    public static void separateInputAndOutputEndpointsUseOnlyTheSelectedSlots(GameTestHelper h) {
        Fixture f = fixture(h);
        f.input.setItem(7, new ItemStack(Items.DIAMOND));
        f.output.setItem(8, new ItemStack(Items.EMERALD));
        int file = configure(f, f.main, "分离进出料", f.pattern());
        start(f, f.main, file, 1); service(f);
        h.assertTrue(f.input.getItem(0).is(Items.SAND) && f.input.getItem(0).getCount() == 2
                        && count(f.inventory(), f.source, Items.SAND) == 6 && f.output.getItem(0).isEmpty(),
                "Input was not delivered to the selected slot on the input endpoint");
        finishBatch(f); service(f);
        h.assertTrue(!f.main.isRunning() && f.main.runtimeView().getInt("Completed") == 1
                        && count(f.inventory(), f.products, Items.GLASS) == 1 && f.output.getItem(2).isEmpty()
                        && f.input.getItem(7).is(Items.DIAMOND) && f.output.getItem(8).is(Items.EMERALD),
                "Separate output collection touched resident items or failed to finish the batch");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void disconnectedOutputEndpointStopsReservationUntilTheCableReturns(GameTestHelper h) {
        Fixture f = fixture(h); int file = configure(f, f.main, "断线等待", f.pattern());
        start(f, f.main, file, 1);
        h.getLevel().setBlockAndUpdate(f.bridge, Blocks.AIR.defaultBlockState());
        var before = f.inventory().save(); service(f);
        h.assertTrue(before.equals(f.inventory().save()) && f.input.isEmpty() && f.output.isEmpty(),
                "An unreachable output endpoint allowed input reservation or insertion");
        h.getLevel().setBlockAndUpdate(f.bridge, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState()); service(f);
        h.assertTrue(f.input.getItem(0).getCount() == 2 && count(f.inventory(), f.source, Items.SAND) == 6,
                "Reconnecting the original endpoint did not resume exactly one batch");
        finishBatch(f); service(f);
        h.assertTrue(!f.main.isRunning() && count(f.inventory(), f.products, Items.GLASS) == 1,
                "The reconnected multi-endpoint job did not complete");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingTheOutputPortCannotAdoptItsReplacementInventory(GameTestHelper h) {
        Fixture f = fixture(h); int file = configure(f, f.main, "端口身份", f.pattern());
        start(f, f.main, file, 1); service(f);
        BlockPos position = f.remote.getBlockPos();
        h.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(position, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, Direction.NORTH));
        f.input.setItem(0, ItemStack.EMPTY); f.output.setItem(2, new ItemStack(Items.GLASS));
        var before = f.inventory().save(); service(f);
        h.assertTrue(before.equals(f.inventory().save()) && f.output.getItem(2).getCount() == 1
                        && f.main.runtimeView().getInt("Completed") == 0,
                "A saved endpoint adopted a different port placed at the same coordinates");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingTheOutputMachineCannotAdoptItsReplacementInventory(GameTestHelper h) {
        Fixture f = fixture(h); int file = configure(f, f.main, "机器身份", f.pattern());
        start(f, f.main, file, 1); service(f);
        BlockPos position = f.output.getBlockPos();
        h.getLevel().removeBlockEntity(position);
        ChestBlockEntity replacement = new ChestBlockEntity(position, h.getLevel().getBlockState(position));
        h.getLevel().setBlockEntity(replacement); replacement.setItem(2, new ItemStack(Items.GLASS));
        f.input.setItem(0, ItemStack.EMPTY); var before = f.inventory().save(); service(f);
        h.assertTrue(before.equals(f.inventory().save()) && replacement.getItem(2).getCount() == 1
                        && f.main.runtimeView().getInt("Completed") == 0,
                "A saved endpoint adopted a different machine behind the same port");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void participatingEndpointRejectsAnotherTaskAndIsReleasedAfterCancel(GameTestHelper h) {
        Fixture f = fixture(h); int first = configure(f, f.main, "占用远端", f.pattern());
        f.inventory().insert(new ItemStack(Items.IRON_INGOT, 2), 2, f.source);
        ProcessingPattern local = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(f.source, Items.IRON_INGOT, 1, null, 0, Role.STANDARD)),
                List.of(material(f.products, Items.GOLD_INGOT, 1, null, 2, Role.STANDARD)));
        int second = configure(f, f.remote, "远端自己的任务", local);
        start(f, f.main, first, 1); var before = f.inventory().save();
        rejects(h, () -> start(f, f.remote, second, 1), "A participating endpoint allowed a competing task to start");
        h.assertTrue(before.equals(f.inventory().save()) && !f.terminal.programs().isActive(second),
                "Rejected endpoint contention changed stock or activated the competing file");
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(first));
        start(f, f.remote, second, 1); f.remote.workTick();
        h.assertTrue(f.remote.isRunning() && f.output.getItem(0).is(Items.IRON_INGOT)
                        && count(f.inventory(), f.source, Items.IRON_INGOT) == 1,
                "Cancelling the owner did not release its participating endpoint");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void savedEndpointOwnershipCannotBeStolenBeforeTheOwnerTicks(GameTestHelper h) {
        Fixture f = fixture(h); int first = configure(f, f.main, "保存占用", f.pattern());
        f.inventory().insert(new ItemStack(Items.IRON_INGOT), 1, f.source);
        ProcessingPattern local = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(f.source, Items.IRON_INGOT, 1, null, 0, Role.STANDARD)),
                List.of(material(f.products, Items.GOLD_INGOT, 1, null, 2, Role.STANDARD)));
        int second = configure(f, f.remote, "重载后竞争", local);
        start(f, f.main, first, 1);
        CompoundTag terminal = f.terminal.saveWithoutMetadata(), main = f.main.saveWithoutMetadata(), remote = f.remote.saveWithoutMetadata();
        f.terminal.load(terminal); f.main.load(main); f.remote.load(remote);
        var before = f.inventory().save();
        rejects(h, () -> start(f, f.remote, second, 1), "Reload forgot a participating endpoint's active owner");
        h.assertTrue(before.equals(f.inventory().save()) && !f.main.isLocked() && !f.remote.isLocked(),
                "Reloading endpoint ownership damaged storage or rejected valid saved runtime state");
        service(f); finishBatch(f); service(f);
        h.assertTrue(!f.main.isRunning() && count(f.inventory(), f.products, Items.GLASS) == 1,
                "The original multi-endpoint owner could not resume after reload");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationAfterRemoteDisconnectionReturnsOnlyUnsentInput(GameTestHelper h) {
        Fixture f = fixture(h, true); int file = configure(f, f.main, "部分投料取消", f.pattern());
        start(f, f.main, file, 1); f.main.workTick();
        h.assertTrue(f.input.getItem(0).getCount() == 1 && count(f.inventory(), f.source, Items.SAND) == 6,
                "Fixture did not retain the unsent remainder of a partially inserted batch");
        h.getLevel().setBlockAndUpdate(f.bridge, Blocks.AIR.defaultBlockState());
        TerminalPrograms.cancel(f.terminal, f.terminal.programs().file(file)); service(f);
        h.assertTrue(!f.main.isRunning() && !f.terminal.programs().isActive(file)
                        && f.input.getItem(0).getCount() == 1 && count(f.inventory(), f.source, Items.SAND) == 7,
                "Cancelling a disconnected remote endpoint lost the unsent remainder or refunded committed input");
        f.main.load(f.main.saveWithoutMetadata());
        h.getLevel().setBlockAndUpdate(f.bridge, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState()); service(f);
        h.assertTrue(!f.main.isLocked() && f.input.getItem(0).getCount() == 1
                        && count(f.inventory(), f.source, Items.SAND) == 7,
                "Reconnection/reload resent or refunded the cancelled multi-endpoint batch");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void retainedToolsSuppliesAndOptionalOutputsWorkAcrossEndpoints(GameTestHelper h) {
        Fixture f = fixture(h);
        f.input.setItem(3, new ItemStack(Items.IRON_PICKAXE));
        f.input.setItem(1, new ItemStack(Items.COAL, 2));
        f.inventory().insert(new ItemStack(Items.COAL, 6), 6, f.source);
        ProcessingPattern pattern = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(f.source, Items.SAND, 2, null, 0, Role.STANDARD),
                        material(f.source, Items.IRON_PICKAXE, 1, null, 3, Role.RETAINED),
                        material(f.source, Items.COAL, 4, null, 1, Role.SUPPLY)),
                List.of(material(f.products, Items.GLASS, 1, f.remote, 2, Role.STANDARD),
                        material(f.products, Items.IRON_NUGGET, 1, f.remote, 3, Role.OPTIONAL)));
        int file = configure(f, f.main, "工具与补给", pattern);
        start(f, f.main, file, 2); f.main.workTick();
        h.assertTrue(f.input.getItem(3).is(Items.IRON_PICKAXE) && f.input.getItem(1).getCount() == 4
                        && count(f.inventory(), f.source, Items.COAL) == 4 && f.input.getItem(0).getCount() == 2,
                "Retained tools were withdrawn or supplies were charged as a full batch instead of only the deficit");
        f.input.setItem(1, new ItemStack(Items.COAL, 3));
        finishBatch(f);
        h.assertTrue(f.main.runtimeView().getInt("Completed") == 1,
                "An absent optional output prevented the first required output from completing");
        f.main.workTick();
        h.assertTrue(f.input.getItem(1).getCount() == 4 && count(f.inventory(), f.source, Items.COAL) == 3
                        && f.input.getItem(3).is(Items.IRON_PICKAXE) && f.input.getItem(0).getCount() == 2,
                "The next batch did not refill only the missing supply or preserve the retained tool");
        f.output.setItem(3, new ItemStack(Items.IRON_NUGGET)); finishBatch(f); service(f);
        h.assertTrue(!f.main.isRunning() && f.main.runtimeView().getInt("Completed") == 2
                        && count(f.inventory(), f.products, Items.GLASS) == 2
                        && count(f.inventory(), f.products, Items.IRON_NUGGET) == 1
                        && f.input.getItem(3).is(Items.IRON_PICKAXE),
                "Multi-endpoint optional output collection or retained-tool completion was incorrect");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void missingRetainedToolPreventsAllSourceWithdrawal(GameTestHelper h) {
        Fixture f = fixture(h);
        ProcessingPattern pattern = new ProcessingPattern(Direction.UP, Direction.DOWN,
                List.of(material(f.source, Items.SAND, 2, null, 0, Role.STANDARD),
                        material(f.source, Items.IRON_PICKAXE, 1, null, 3, Role.RETAINED)),
                f.pattern().outputs());
        f.input.setItem(3, new ItemStack(Items.IRON_PICKAXE));
        int file = configure(f, f.main, "工具缺失", pattern);
        start(f, f.main, file, 1); f.input.setItem(3, ItemStack.EMPTY);
        var before = f.inventory().save(); service(f);
        h.assertTrue(before.equals(f.inventory().save()) && f.input.getItem(0).isEmpty(),
                "A missing retained tool allowed other source materials to be withdrawn");
        f.input.setItem(3, new ItemStack(Items.IRON_PICKAXE)); service(f);
        h.assertTrue(f.input.getItem(0).getCount() == 2 && count(f.inventory(), f.source, Items.SAND) == 6,
                "Restoring the required retained tool did not resume the batch");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void multiEndpointOwnershipDoesNotBlockAnUnrelatedVanillaFurnace(GameTestHelper h) {
        Fixture f = fixture(h); var level = h.getLevel();
        BlockPos furnacePos = h.absolutePos(new BlockPos(5, 2, 4)), portPos = furnacePos.west();
        level.setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, Direction.WEST));
        for (int x = 1; x <= 3; x++)
            level.setBlockAndUpdate(h.absolutePos(new BlockPos(x, 2, 4)), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        for (int z = 1; z <= 3; z++)
            level.setBlockAndUpdate(h.absolutePos(new BlockPos(1, 2, z)), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnacePos);
        var furnacePort = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        f.inventory().insert(new ItemStack(Items.COAL), 1, f.source);
        int generic = configure(f, f.main, "并行外部加工", f.pattern());
        int vanilla = f.terminal.programs().create(0, "独立原版熔炉");
        var definition = furnacePort.createDefinition("独立原版熔炉", f.source, f.source, f.products,
                entry(f.inventory(), f.source, Items.SAND), entry(f.inventory(), f.source, Items.COAL));
        f.terminal.programs().configure(vanilla, definition, portPos, furnacePort.identity());
        start(f, f.main, generic, 1); service(f);
        start(f, furnacePort, vanilla, 1); furnacePort.workTick();
        h.assertTrue(f.main.isRunning() && furnacePort.isRunning() && furnace.getItem(0).is(Items.SAND),
                "A generic task reserved the whole network instead of only its participating endpoints");
        cook(furnace, furnacePort, 240); finishBatch(f); service(f);
        h.assertTrue(!f.main.isRunning() && !furnacePort.isRunning()
                        && count(f.inventory(), f.products, Items.GLASS) == 2
                        && count(f.inventory(), f.source, Items.SAND) == 5,
                "The vanilla adapter or concurrent multi-endpoint task changed its material totals");
        h.succeed();
    }
}
