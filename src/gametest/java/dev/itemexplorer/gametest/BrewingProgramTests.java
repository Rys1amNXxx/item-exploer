package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
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
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BrewingProgramTests {
    private record Fixture(StorageBlockEntity terminal, ProductionPortBlockEntity port, BrewingStandBlockEntity stand,
                           BlockPos cable, int input, int auxiliary, int output, ItemStack base, ItemStack reagent, ItemStack result) {
        StorageInventory storage() { return terminal.inventory(); }
    }

    private static Fixture fixture(GameTestHelper h, ItemStack base, ItemStack reagent, ItemStack result,
                                   int bottles, int reagents, int blazePowder, int runs) {
        var level = h.getLevel();
        BlockPos machine = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos portPos = machine.north(), cable = portPos.north(), terminalPos = cable.west();
        level.setBlockAndUpdate(machine, Blocks.BREWING_STAND.defaultBlockState());
        level.setBlockAndUpdate(portPos, ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                .setValue(ProductionPortBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(terminalPos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        var port = (ProductionPortBlockEntity) level.getBlockEntity(portPos);
        var stand = (BrewingStandBlockEntity) level.getBlockEntity(machine);
        var storage = terminal.inventory();
        int input = storage.createFolder(0, "药水原料"), auxiliary = storage.createFolder(0, "药材与烈焰粉");
        int output = storage.createFolder(0, "药水成品");
        put(storage, base, bottles, input);
        put(storage, reagent, reagents, auxiliary);
        put(storage, new ItemStack(Items.BLAZE_POWDER), blazePowder, auxiliary);
        port.configure("自动酿造", input, auxiliary, output, entry(storage, input, base), entry(storage, auxiliary, reagent), runs);
        return new Fixture(terminal, port, stand, cable, input, auxiliary, output, base, reagent, result);
    }

    private static Fixture water(GameTestHelper h, int bottles, int reagents, int blazePowder, int runs) {
        return fixture(h, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER), new ItemStack(Items.NETHER_WART),
                PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD), bottles, reagents, blazePowder, runs);
    }
    private static int entry(StorageInventory inventory, int folder, ItemStack sample) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && ItemStack.isSameItemSameTags(e.stack(), sample))
                .mapToInt(StorageInventory.Entry::id).findFirst().orElseThrow();
    }
    private static void put(StorageInventory inventory, ItemStack sample, int count, int folder) {
        for (int remaining = count; remaining > 0;) {
            int batch = Math.min(remaining, sample.getMaxStackSize());
            int inserted = inventory.insert(sample.copyWithCount(batch), batch, folder);
            if (inserted != batch) throw new IllegalStateException("Cannot prepare brewing fixture");
            remaining -= batch;
        }
    }
    private static long count(StorageInventory inventory, int folder, ItemStack sample) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && ItemStack.isSameItemSameTags(e.stack(), sample))
                .mapToLong(StorageInventory.Entry::count).sum();
    }
    private static void brew(Fixture fixture, int ticks) {
        for (int i = 0; i < ticks; i++) {
            BrewingStandBlockEntity.serverTick(fixture.stand.getLevel(), fixture.stand.getBlockPos(), fixture.stand.getBlockState(), fixture.stand);
            fixture.port.workTick();
        }
    }
    private static void status(GameTestHelper h, Fixture fixture, String expected) {
        h.assertTrue(expected.equals(fixture.port.view().getString("Status")), "Expected " + expected + ", got " + fixture.port.view().getString("Status"));
    }

    @GameTest(template = "empty", timeoutTicks = 940)
    public static void naturalTicksBrewTwoBottlesUsingOneBlazePowder(GameTestHelper h) {
        Fixture f = water(h, 3, 3, 1, 2);
        f.port.start();
        h.succeedWhen(() -> {
            h.assertTrue(count(f.storage(), f.output, f.result) == 2, "Both real brews have not completed");
            h.assertTrue(count(f.storage(), f.input, f.base) == 1 && count(f.storage(), f.auxiliary, f.reagent) == 1,
                    "Bottle or reagent accounting changed");
            h.assertTrue(count(f.storage(), f.auxiliary, new ItemStack(Items.BLAZE_POWDER)) == 0
                    && f.stand.saveWithoutMetadata().getByte("Fuel") == 18, "Blaze fuel was not reused across brews");
            h.assertTrue(f.stand.isEmpty() && f.port.view().getInt("Completed") == 2, "Finished brew left inventory or lost progress");
            status(h, f, "production_complete");
        });
    }

    @GameTest(template = "empty")
    public static void missingBlazePowderWaitsWithoutBorrowingFromOtherFolders(GameTestHelper h) {
        Fixture f = water(h, 1, 1, 0, 1);
        int other = f.storage().createFolder(0, "别处的烈焰粉");
        ItemStack powder = new ItemStack(Items.BLAZE_POWDER);
        put(f.storage(), powder, 2, other);
        f.port.start(); f.port.workTick();
        status(h, f, "production_missing_blaze_powder");
        h.assertTrue(f.stand.isEmpty() && count(f.storage(), f.input, f.base) == 1
                && count(f.storage(), other, powder) == 2, "Missing blaze committed input or used another directory");
        f.storage().insert(powder, 1, f.auxiliary);
        f.port.workTick(); brew(f, 410);
        h.assertTrue(count(f.storage(), f.output, f.result) == 1 && count(f.storage(), other, powder) == 2,
                "Restocking the designated blaze directory did not resume");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dragonBreathReturnsItsBottleToTheAuxiliaryDirectory(GameTestHelper h) {
        Fixture f = fixture(h, PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.WATER),
                new ItemStack(Items.DRAGON_BREATH), PotionUtils.setPotion(new ItemStack(Items.LINGERING_POTION), Potions.WATER),
                1, 1, 1, 1);
        f.port.start(); f.port.workTick(); brew(f, 410);
        h.assertTrue(count(f.storage(), f.output, f.result) == 1
                && count(f.storage(), f.auxiliary, new ItemStack(Items.GLASS_BOTTLE)) == 1
                && count(f.storage(), f.auxiliary, f.reagent) == 0 && f.stand.isEmpty(), "Dragon breath bottle was lost or duplicated");
        status(h, f, "production_complete"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void potionSamplesKeepTheirExactPotionNbt(GameTestHelper h) {
        Fixture f = water(h, 1, 1, 1, 1);
        f.storage().take(entry(f.storage(), f.input, f.base), 1);
        ItemStack otherPotion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        put(f.storage(), otherPotion, 3, f.input);
        f.port.start(); f.port.workTick();
        status(h, f, "production_missing_input");
        h.assertTrue(f.stand.isEmpty() && count(f.storage(), f.input, otherPotion) == 3, "Brewing substituted a different potion NBT");
        f.storage().insert(f.base, 1, f.input);
        f.port.workTick(); brew(f, 410);
        h.assertTrue(count(f.storage(), f.output, f.result) == 1 && count(f.storage(), f.input, otherPotion) == 3,
                "Exact water-bottle restock was not respected");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void disconnectionAndReloadPreserveTheRealInFlightBrew(GameTestHelper h) {
        Fixture f = water(h, 2, 2, 1, 2);
        f.port.start(); f.port.workTick(); brew(f, 130);
        CompoundTag portData = f.port.saveWithoutMetadata(), standData = f.stand.saveWithoutMetadata();
        f.port.load(portData);
        BlockPos standPos = f.stand.getBlockPos();
        var reloadedStand = new BrewingStandBlockEntity(standPos, f.stand.getBlockState());
        reloadedStand.load(standData);
        h.getLevel().removeBlockEntity(standPos);
        h.getLevel().setBlockEntity(reloadedStand);
        f = new Fixture(f.terminal, f.port, reloadedStand, f.cable, f.input, f.auxiliary, f.output, f.base, f.reagent, f.result);
        h.getLevel().setBlockAndUpdate(f.cable, Blocks.AIR.defaultBlockState());
        // Vanilla does not save its cached ingredient field; a reconstructed stand may restart its
        // timer. The program must wait for the actual new result instead of synthesizing one.
        brew(f, 410);
        status(h, f, "production_disconnected");
        h.assertTrue(ItemStack.matches(f.result, f.stand.getItem(0)) && count(f.storage(), f.output, f.result) == 0,
                "Disconnected machine did not retain its real result");
        h.getLevel().setBlockAndUpdate(f.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        f.port.workTick(); brew(f, 410);
        h.assertTrue(count(f.storage(), f.output, f.result) == 2 && count(f.storage(), f.input, f.base) == 0
                && f.port.view().getInt("Completed") == 2, "Reload duplicated or lost brewing material");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void extraBottleLatchesInterferenceAfterItIsRemoved(GameTestHelper h) {
        Fixture f = water(h, 2, 2, 1, 2);
        f.port.start(); f.port.workTick();
        f.stand.setItem(1, f.base.copy()); f.port.workTick();
        status(h, f, "production_interference");
        var before = f.storage().save();
        f.stand.setItem(1, ItemStack.EMPTY); brew(f, 410);
        h.assertTrue(before.equals(f.storage().save()) && f.port.view().getInt("Completed") == 0,
                "Removing an unowned bottle resumed a faulted brew");
        status(h, f, "production_interference"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void removingUnburnedPowderLatchesInterference(GameTestHelper h) {
        Fixture f = water(h, 1, 1, 1, 1);
        f.port.start(); f.port.workTick();
        h.assertTrue(f.stand.getItem(4).is(Items.BLAZE_POWDER), "Fuel was not committed to the real blaze slot");
        f.stand.setItem(4, ItemStack.EMPTY); f.port.workTick();
        status(h, f, "production_interference");
        var before = f.storage().save();
        f.stand.setItem(4, new ItemStack(Items.BLAZE_POWDER)); brew(f, 410);
        h.assertTrue(before.equals(f.storage().save()) && f.port.view().getInt("Completed") == 0,
                "Replacing stolen blaze powder silently resumed transfers");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void removingTheReagentBeforeTheBottleTransformsCannotRefillIt(GameTestHelper h) {
        Fixture f = water(h, 2, 2, 1, 2);
        f.port.start(); f.port.workTick(); brew(f, 30);
        ItemStack removed = f.stand.removeItem(3, 1);
        h.assertTrue(removed.is(Items.NETHER_WART), "Expected committed brewing reagent");
        var before = f.storage().save();
        f.port.workTick();
        status(h, f, "production_interference");
        f.stand.setItem(3, removed); brew(f, 410);
        h.assertTrue(before.equals(f.storage().save()) && f.port.view().getInt("Completed") == 0,
                "Stolen reagent was treated as vanilla consumption and refilled or resumed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void blazePowderReagentRequiresSeparateFuelAndIngredient(GameTestHelper h) {
        ItemStack base = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        ItemStack powder = new ItemStack(Items.BLAZE_POWDER);
        ItemStack result = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH);
        Fixture f = fixture(h, base, powder, result, 1, 1, 0, 1);
        f.port.start(); f.port.workTick();
        status(h, f, "production_missing_fuel");
        h.assertTrue(f.stand.isEmpty() && count(f.storage(), f.auxiliary, powder) == 1,
                "Single powder was partially spent before both roles were available");
        f.storage().insert(powder, 1, f.auxiliary);
        f.port.workTick(); brew(f, 410);
        h.assertTrue(count(f.storage(), f.output, result) == 1 && count(f.storage(), f.auxiliary, powder) == 0
                && f.stand.saveWithoutMetadata().getByte("Fuel") == 19, "Blaze ingredient was confused with brewing fuel");
        h.succeed();
    }
}
