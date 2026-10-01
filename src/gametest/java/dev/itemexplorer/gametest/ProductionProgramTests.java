package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionProgramTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port,
                           FurnaceBlockEntity furnace, BlockPos cable, int input, int fuel, int output) {
        StorageInventory storage() { return terminal.inventory(); }
    }

    private static Fixture fixture(GameTestHelper h, int inputCount, Item fuelItem, int fuelCount, int runs) {
        var level = h.getLevel();
        BlockPos furnacePos = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos portPos = furnacePos.north(), cable = portPos.north(), terminalPos = cable.west();
        level.setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        var furnace = (FurnaceBlockEntity) level.getBlockEntity(furnacePos);
        StorageInventory storage = terminal.inventory();
        int input = storage.createFolder(0, "原料"), fuel = storage.createFolder(0, "燃料"), output = storage.createFolder(0, "成品");
        storage.insert(new ItemStack(Items.SAND, inputCount), inputCount, input);
        storage.insert(new ItemStack(fuelItem, fuelCount), fuelCount, fuel);
        port.configure("玻璃生产", input, fuel, output, entry(storage, input, Items.SAND), entry(storage, fuel, fuelItem), runs);
        return new Fixture(terminal, port, furnace, cable, input, fuel, output);
    }

    private static int entry(StorageInventory storage, int folder, Item item) {
        return storage.entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToInt(StorageInventory.Entry::id).findFirst().orElseThrow();
    }

    private static long count(StorageInventory storage, int folder, Item item) {
        return storage.entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToLong(StorageInventory.Entry::count).sum();
    }

    /** Uses vanilla furnace cooking, with deterministic service passes between its normal ticks. */
    private static void cook(Fixture f, int ticks) {
        for (int i = 0; i < ticks; i++) {
            AbstractFurnaceBlockEntity.serverTick(f.furnace.getLevel(), f.furnace.getBlockPos(), f.furnace.getBlockState(), f.furnace);
            f.port.workTick();
        }
    }

    private static void status(GameTestHelper h, Fixture f, String expected) {
        String actual = f.port.view().getString("Status");
        h.assertTrue(expected.equals(actual), "Expected " + expected + ", got " + actual);
    }

    @GameTest(template = "empty", timeoutTicks = 520)
    public static void naturalWorldTicksRunTwoSmeltsAndKeepFolderBindings(GameTestHelper h) {
        Fixture f = fixture(h, 3, Items.COAL, 2, 2);
        f.storage().renameFolder(f.input, "重命名原料");
        f.storage().renameFolder(f.output, "重命名成品");
        f.port.start();
        h.succeedWhen(() -> {
            h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 2, "The real server ticker has not delivered both glasses");
            h.assertTrue(count(f.storage(), f.input, Items.SAND) == 1, "Wrong raw-material total");
            h.assertTrue(count(f.storage(), f.fuel, Items.COAL) == 1, "Fuel was treated as one coal per operation");
            h.assertTrue(f.furnace.isEmpty() && f.port.view().getInt("Completed") == 2, "Finished job retained inventory or lost progress");
            status(h, f, "production_complete");
        });
    }

    @GameTest(template = "empty")
    public static void repeatedStartCannotCommitTheSameBatchTwice(GameTestHelper h) {
        Fixture f = fixture(h, 4, Items.COAL, 2, 2);
        f.port.start(); f.port.workTick();
        var before = f.storage().save();
        ItemStack input = f.furnace.getItem(0).copy(), fuel = f.furnace.getItem(1).copy();
        try { f.port.start(); } catch (IllegalArgumentException expected) { /* A duplicate request may explicitly reject. */ }
        h.assertTrue(before.equals(f.storage().save()) && ItemStack.matches(input, f.furnace.getItem(0))
                && ItemStack.matches(fuel, f.furnace.getItem(1)), "Repeated start withdrew a second batch");
        cook(f, 440);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 2 && count(f.storage(), f.input, Items.SAND) == 2,
                "Duplicate start changed the requested production quantity");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lavaFuelReturnsItsBucketToTheFuelFolder(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.LAVA_BUCKET, 1, 2);
        f.port.start(); f.port.workTick(); cook(f, 440);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 2 && count(f.storage(), f.fuel, Items.BUCKET) == 1
                && count(f.storage(), f.fuel, Items.LAVA_BUCKET) == 0 && f.furnace.isEmpty(), "Lava fuel/container accounting is wrong");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void missingFuelWaitsWithoutBorrowingFromOtherFolders(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 1);
        f.storage().take(entry(f.storage(), f.fuel, Items.COAL), 1);
        int other = f.storage().createFolder(0, "私人燃料");
        f.storage().insert(new ItemStack(Items.COAL, 3), 3, other);
        f.port.start(); f.port.workTick();
        status(h, f, "production_missing_fuel");
        h.assertTrue(count(f.storage(), other, Items.COAL) == 3 && f.furnace.getItem(1).isEmpty(), "Job used another folder's coal");
        // Restocking produces a new entry ID: the persisted selection must match its item sample.
        f.storage().insert(new ItemStack(Items.COAL), 1, f.fuel);
        f.port.workTick(); cook(f, 240);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 1 && count(f.storage(), other, Items.COAL) == 3,
                "Restocking did not resume safely with a new entry ID"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void missingInputDoesNotUseChildrenOrSiblingDirectories(GameTestHelper h) {
        Fixture f = fixture(h, 1, Items.COAL, 1, 1);
        f.storage().take(entry(f.storage(), f.input, Items.SAND), 1);
        int child = f.storage().createFolder(f.input, "子目录");
        f.storage().insert(new ItemStack(Items.SAND, 3), 3, child);
        f.storage().insert(new ItemStack(Items.SAND, 4), 4, 0);
        f.port.start(); f.port.workTick();
        status(h, f, "production_missing_input");
        h.assertTrue(count(f.storage(), child, Items.SAND) == 3 && count(f.storage(), 0, Items.SAND) == 4
                && f.furnace.getItem(0).isEmpty(), "Program's exact folder boundary leaked");
        f.storage().insert(new ItemStack(Items.SAND), 1, f.input);
        f.port.workTick(); cook(f, 240);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 1, "Restocking raw material did not resume the task"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void aFullOutputVolumeKeepsTheRealProductInsideTheFurnace(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 2);
        f.port.start(); f.port.workTick();
        while (f.storage().total() < f.storage().capacity())
            f.storage().insert(new ItemStack(Items.STONE, 64), 64, 0);
        cook(f, 240);
        status(h, f, "production_output_full");
        h.assertTrue(f.furnace.getItem(2).is(Items.GLASS) && f.furnace.getItem(2).getCount() == 1
                && count(f.storage(), f.input, Items.SAND) == 1 && count(f.storage(), f.output, Items.GLASS) == 0,
                "Full output lost the product or fed another input");
        f.storage().take(entry(f.storage(), 0, Items.STONE), 8);
        f.port.workTick(); cook(f, 240);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 2 && f.furnace.isEmpty(), "Making room did not recover the held product");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void brokenCableLetsVanillaCookingFinishButStopsTransfers(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 2);
        f.port.start(); f.port.workTick();
        h.getLevel().setBlockAndUpdate(f.cable, Blocks.AIR.defaultBlockState());
        cook(f, 240);
        status(h, f, "production_disconnected");
        h.assertTrue(f.furnace.getItem(2).is(Items.GLASS) && count(f.storage(), f.input, Items.SAND) == 1
                && count(f.storage(), f.output, Items.GLASS) == 0, "Disconnected program moved items");
        h.getLevel().setBlockAndUpdate(f.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        f.port.workTick(); cook(f, 240);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 2 && f.furnace.isEmpty(), "Wire repair lost track of in-flight material");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cancelLeavesCommittedInputForTheRealFurnaceToFinish(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 2);
        f.port.start(); f.port.workTick();
        var before = f.storage().save();
        f.port.cancel(); cook(f, 240);
        h.assertTrue(before.equals(f.storage().save()) && f.furnace.getItem(2).is(Items.GLASS)
                && !f.port.view().getBoolean("Active"), "Cancel refunded committed input or recovered an unowned product");
        boolean rejected = false;
        try { f.port.start(); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected && f.furnace.getItem(2).is(Items.GLASS), "Starting over silently adopted leftovers from a cancelled task");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingAFurnaceAtTheSamePositionCannotResumeItsOldJob(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 2);
        f.port.start(); f.port.workTick();
        BlockPos pos = f.furnace.getBlockPos();
        // Keep the mounting block in place: actual block removal correctly detaches the interface.
        h.getLevel().removeBlockEntity(pos);
        var replacement = new FurnaceBlockEntity(pos, h.getLevel().getBlockState(pos));
        h.getLevel().setBlockEntity(replacement);
        replacement.setItem(2, new ItemStack(Items.GLASS));
        f.port.workTick();
        status(h, f, "production_machine_changed");
        h.assertTrue(count(f.storage(), f.input, Items.SAND) == 1 && count(f.storage(), f.output, Items.GLASS) == 0
                && replacement.getItem(2).is(Items.GLASS), "Same-position replacement inherited the old task"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingATerminalCannotRedirectAnExistingProgram(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 1);
        var identity = f.terminal.productionIdentity();
        BlockPos pos = f.terminal.getBlockPos();
        h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(pos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var replacement = (StorageBlockEntity) h.getLevel().getBlockEntity(pos);
        h.assertTrue(!identity.equals(replacement.productionIdentity()), "New terminal reused the old persistent identity");
        // Matching folder numbers are insufficient authorization to use replacement storage.
        int input = replacement.inventory().createFolder(0, "原料");
        int fuel = replacement.inventory().createFolder(0, "燃料");
        replacement.inventory().createFolder(0, "成品");
        replacement.inventory().insert(new ItemStack(Items.SAND, 2), 2, input);
        replacement.inventory().insert(new ItemStack(Items.COAL), 1, fuel);
        try { f.port.start(); } catch (IllegalArgumentException expected) { /* May reject at start or on the service pass. */ }
        f.port.workTick();
        h.assertTrue(replacement.inventory().total() == 3 && f.furnace.isEmpty(), "Task silently used a replacement terminal");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void normalMidflightSaveLoadDoesNotRepeatInputOrOutput(GameTestHelper h) {
        Fixture f = fixture(h, 3, Items.COAL, 1, 3);
        f.port.start(); f.port.workTick(); cook(f, 93);
        var identity = f.terminal.productionIdentity();
        CompoundTag terminal = f.terminal.saveWithoutMetadata(), furnace = f.furnace.saveWithoutMetadata(), port = f.port.saveWithoutMetadata();
        f.terminal.load(terminal); f.furnace.load(furnace); f.port.load(port);
        h.assertTrue(identity.equals(f.terminal.productionIdentity()), "Normal load changed stable terminal identity");
        cook(f, 560);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 3 && count(f.storage(), f.input, Items.SAND) == 0
                && f.furnace.isEmpty() && f.port.view().getInt("Completed") == 3, "Normal reload duplicated or lost work");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void unexpectedMachineItemsLatchUntilCancellation(GameTestHelper h) {
        Fixture f = fixture(h, 3, Items.COAL, 1, 2);
        f.port.start(); f.port.workTick();
        f.furnace.setItem(2, new ItemStack(Items.DIAMOND));
        f.port.workTick();
        var afterFault = f.storage().save();
        f.furnace.setItem(2, ItemStack.EMPTY);
        cook(f, 440);
        h.assertTrue(afterFault.equals(f.storage().save()) && f.furnace.getItem(2).is(Items.GLASS)
                && f.port.view().getInt("Completed") == 0, "Clearing unexpected inventory silently resumed a faulted program");
        f.port.cancel(); h.assertTrue(!f.port.view().getBoolean("Active"), "Faulted job cannot be cancelled"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void deletingABoundOutputFolderCannotRedirectProductsToRoot(GameTestHelper h) {
        Fixture f = fixture(h, 1, Items.COAL, 1, 1);
        f.port.start(); f.port.workTick();
        f.storage().deleteFolder(f.output);
        cook(f, 240);
        h.assertTrue(f.furnace.getItem(2).is(Items.GLASS) && count(f.storage(), 0, Items.GLASS) == 0
                && f.port.view().getInt("Completed") == 0, "Missing folder caused silent output redirection/loss");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidSavedProgramRemainsProtectedAndInert(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 1);
        CompoundTag saved = f.port.saveWithoutMetadata();
        saved.getCompound("Production").putInt("Version", 99);
        f.port.load(saved);
        f.port.workTick();
        h.assertTrue(saved.get("Production").equals(f.port.saveWithoutMetadata().get("Production"))
                && f.furnace.isEmpty() && f.storage().total() == 3, "Unsupported data was discarded or executed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void twoInterfacesCannotOperateTheSameFurnace(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 1);
        BlockPos second = f.furnace.getBlockPos().east();
        h.getLevel().setBlockAndUpdate(second, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, Direction.EAST));
        try { f.port.start(); } catch (IllegalArgumentException expected) { /* Conflict may reject before activating. */ }
        f.port.workTick();
        h.assertTrue(f.storage().total() == 3 && f.furnace.isEmpty(), "Competing interfaces fed the same furnace");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void configurationRejectsOutOfRangeCountsAndWrongSourceFolders(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 1);
        int sand = entry(f.storage(), f.input, Items.SAND), coal = entry(f.storage(), f.fuel, Items.COAL);
        Runnable[] invalid = {
                () -> f.port.configure("玻璃", f.input, f.fuel, f.output, sand, coal, 0),
                () -> f.port.configure("玻璃", f.input, f.fuel, f.output, sand, coal, 4097),
                () -> f.port.configure("玻璃", f.output, f.fuel, f.output, sand, coal, 1),
                () -> f.port.configure("玻璃", f.input, f.output, f.output, sand, coal, 1)
        };
        for (Runnable attempt : invalid) {
            boolean rejected = false;
            try { attempt.run(); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "Invalid configuration was accepted");
        }
        h.assertTrue(f.storage().total() == 3 && f.furnace.isEmpty(), "Configuring a program moved items");
        f.port.start(); f.port.workTick(); cook(f, 240);
        h.assertTrue(count(f.storage(), f.output, Items.GLASS) == 1, "Invalid edits destroyed the previous valid program");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void wetSpongeRetainsLavaBucketUntilVanillaMakesWater(GameTestHelper h) {
        Fixture f = fixture(h, 1, Items.LAVA_BUCKET, 1, 1);
        f.storage().take(entry(f.storage(), f.input, Items.SAND), 1);
        f.storage().insert(new ItemStack(Items.WET_SPONGE), 1, f.input);
        f.port.configure("海绵烘干", f.input, f.fuel, f.output,
                entry(f.storage(), f.input, Items.WET_SPONGE), entry(f.storage(), f.fuel, Items.LAVA_BUCKET), 1);
        f.port.start(); f.port.workTick(); cook(f, 240);
        h.assertTrue(count(f.storage(), f.output, Items.SPONGE) == 1 && count(f.storage(), f.fuel, Items.WATER_BUCKET) == 1
                && count(f.storage(), f.fuel, Items.BUCKET) == 0 && f.furnace.isEmpty(),
                "Collecting the fuel container too soon changed vanilla wet-sponge behavior");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void removingTheFuelContainerIsDetectedBeforeFurtherTransfers(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.LAVA_BUCKET, 1, 2);
        f.port.start(); f.port.workTick();
        AbstractFurnaceBlockEntity.serverTick(f.furnace.getLevel(), f.furnace.getBlockPos(), f.furnace.getBlockState(), f.furnace);
        h.assertTrue(f.furnace.getItem(1).is(Items.BUCKET), "Vanilla did not create the expected lava container");
        f.furnace.setItem(1, ItemStack.EMPTY);
        f.port.workTick();
        status(h, f, "production_interference");
        cook(f, 440);
        h.assertTrue(count(f.storage(), f.input, Items.SAND) == 1 && count(f.storage(), f.output, Items.GLASS) == 0
                && f.furnace.getItem(2).is(Items.GLASS), "Missing container did not halt future transfers");
        h.succeed();
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos pos) {
        ServerPlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "production-test")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        return player;
    }

    private static StorageNetwork.ProductionRequest request(ProductionPortMenu menu, CompoundTag view,
                                                            StorageNetwork.ProductionAction action) {
        return new StorageNetwork.ProductionRequest(menu.containerId, view.getLong("Session"), view.getLong("Context"),
                view.getLong("Revision"), action, view.getString("Name"), view.getInt("InputFolder"), view.getInt("FuelFolder"),
                view.getInt("OutputFolder"), view.getInt("InputEntry"), view.getInt("FuelEntry"), view.getInt("Count"), view.getLong("MachinePos"));
    }

    @GameTest(template = "empty")
    public static void menuReplaysAndOutOfRangePlayersCannotStartJobs(GameTestHelper h) {
        Fixture f = fixture(h, 2, Items.COAL, 1, 1);
        ServerPlayer player = player(h, f.terminal.getBlockPos());
        int fileId = f.terminal.programs().files().get(0).id();
        var menu = new ProductionPortMenu(9, player.getInventory(), f.terminal.getBlockPos(), f.terminal, fileId, 0, 0);
        player.containerMenu = menu;
        var stale = request(menu, menu.snapshot(), StorageNetwork.ProductionAction.START);
        f.port.configure("新程序", f.input, f.fuel, f.output,
                entry(f.storage(), f.input, Items.SAND), entry(f.storage(), f.fuel, Items.COAL), 1);
        menu.handle(stale);
        h.assertTrue(!f.port.view().getBoolean("Active"), "Old configuration revision started a newer program");
        var fresh = request(menu, menu.snapshot(), StorageNetwork.ProductionAction.START);
        player.setPos(f.terminal.getBlockPos().getX() + 10, f.terminal.getBlockPos().getY(), f.terminal.getBlockPos().getZ());
        menu.handle(fresh);
        h.assertTrue(!f.port.view().getBoolean("Active"), "Out-of-range player started production");
        player.setPos(f.terminal.getBlockPos().getX() + .5, f.terminal.getBlockPos().getY() + .5, f.terminal.getBlockPos().getZ() + .5);
        menu.handle(fresh);
        h.assertTrue(f.port.view().getBoolean("Active"), "Current in-range request did not start");
        f.port.workTick();
        var before = f.storage().save();
        menu.handle(fresh);
        h.assertTrue(before.equals(f.storage().save()), "Replayed START duplicated a material withdrawal");
        var cancel = request(menu, menu.snapshot(), StorageNetwork.ProductionAction.CANCEL);
        menu.removed(player); menu.handle(cancel);
        h.assertTrue(f.port.view().getBoolean("Active"), "Closed menu accepted cancellation");
        h.succeed();
    }
}
