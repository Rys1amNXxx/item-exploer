package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.compat.jei.TerminalRecipeTransferInfo;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.portable.PortableAccess;
import mezz.jei.common.transfer.BasicRecipeTransferHandlerServer;
import mezz.jei.common.transfer.TransferOperation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JeiTransferTests {
    private static final TerminalRecipeTransferInfo INFO = new TerminalRecipeTransferInfo(
            () -> mezz.jei.api.recipe.transfer.IRecipeTransferError.Type.USER_FACING);

    private static CraftingRecipe recipe(GameTestHelper h, String name) {
        return (CraftingRecipe) h.getLevel().getRecipeManager().byKey(new ResourceLocation("minecraft", name)).orElseThrow();
    }

    private static void transfer(ServerPlayer player, StorageMenu menu, CraftingRecipe recipe, List<TransferOperation> moves, boolean max) {
        BasicRecipeTransferHandlerServer.setItems(player, moves, INFO.getRecipeSlots(menu, recipe),
                INFO.getInventorySlots(menu, recipe), max, INFO.requireCompleteSets(menu, recipe));
    }

    private static int count(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static List<TransferOperation> ironBlockMoves() {
        return IntStream.range(0, 9).mapToObj(i -> new TransferOperation(0, StorageMenu.CRAFT_GRID_START + i)).toList();
    }

    @GameTest(template = "empty")
    public static void jeiFillsOneShapedRecipeWithoutCraftingOrTouchingStoredItems(GameTestHelper h) {
        var terminal = terminal(h); var player = player(h, terminal.getBlockPos()); var menu = open(player, terminal);
        menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT, 20));
        terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0); var before = terminal.inventory().save();
        var recipe = recipe(h, "iron_block");
        h.assertTrue(INFO.canHandle(menu, recipe) && INFO.getRecipeSlots(menu, recipe).size() == 9
                && INFO.getInventorySlots(menu, recipe).size() == 36, "JEI slot mapping is incorrect");
        transfer(player, menu, recipe, ironBlockMoves(), false);
        for (int i = 37; i < 46; i++) h.assertTrue(menu.getSlot(i).getItem().is(Items.IRON_INGOT)
                && menu.getSlot(i).getItem().getCount() == 1, "Shaped grid not filled exactly once");
        h.assertTrue(count(player, Items.IRON_INGOT) == 11 && count(player, Items.IRON_BLOCK) == 0
                && menu.getSlot(36).getItem().is(Items.IRON_BLOCK) && before.equals(terminal.inventory().save()),
                "Transfer crafted an output, lost materials or touched stored items");
        menu.clicked(36, 0, ClickType.PICKUP, player);
        h.assertTrue(menu.getCarried().is(Items.IRON_BLOCK), "Filled recipe cannot be crafted"); h.succeed();
    }

    @GameTest(template = "empty")
    public static void jeiMaxTransferUsesOnlyCompleteSetsAndLeavesTheRemainder(GameTestHelper h) {
        var terminal = terminal(h); var player = player(h, terminal.getBlockPos()); var menu = open(player, terminal);
        menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT, 20));
        transfer(player, menu, recipe(h, "iron_block"), ironBlockMoves(), true);
        for (int i = 37; i < 46; i++) h.assertTrue(menu.getSlot(i).getItem().getCount() == 2, "Max transfer split an incomplete set");
        h.assertTrue(count(player, Items.IRON_INGOT) == 2, "Remainder was lost");
        menu.clicked(36, 0, ClickType.QUICK_MOVE, player);
        h.assertTrue(count(player, Items.IRON_BLOCK) == 2 && count(player, Items.IRON_INGOT) == 2, "Shift crafting changed item totals");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void missingItemsDoNotWithdrawAPartialRecipe(GameTestHelper h) {
        var terminal = terminal(h); var player = player(h, terminal.getBlockPos()); var menu = open(player, terminal);
        menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT, 8)); menu.getSlot(37).set(new ItemStack(Items.DIAMOND, 3));
        transfer(player, menu, recipe(h, "iron_block"), ironBlockMoves(), false);
        h.assertTrue(count(player, Items.IRON_INGOT) == 8 && menu.getSlot(37).getItem().is(Items.DIAMOND)
                && menu.getSlot(37).getItem().getCount() == 3, "Failed transfer changed existing materials");
        for (int i = 38; i < 46; i++) h.assertTrue(menu.getSlot(i).getItem().isEmpty(), "Failed transfer left partial ingredients");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void shapelessTransferReturnsOldGridItemsAndPreservesTheirData(GameTestHelper h) {
        var terminal = terminal(h); var player = player(h, terminal.getBlockPos()); var menu = open(player, terminal);
        ItemStack old = new ItemStack(Items.DIAMOND, 7); old.setHoverName(net.minecraft.network.chat.Component.literal("keep me"));
        menu.getSlot(45).set(old.copy()); menu.getSlot(1).set(new ItemStack(Items.CORNFLOWER, 2));
        transfer(player, menu, recipe(h, "blue_dye_from_cornflower"), List.of(new TransferOperation(1, 37)), false);
        h.assertTrue(menu.getSlot(37).getItem().is(Items.CORNFLOWER) && menu.getSlot(45).getItem().isEmpty()
                && menu.getSlot(36).getItem().is(Items.BLUE_DYE) && count(player, Items.CORNFLOWER) == 1,
                "Shapeless transfer did not replace the old grid");
        h.assertTrue(player.getInventory().items.stream().anyMatch(stack -> ItemStack.matches(stack, old)), "Old grid stack lost its NBT/count");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void portableMainHandAndOffhandTransfersKeepTheActiveDevice(GameTestHelper h) {
        var terminal = terminal(h); terminal.setPortableEnabled(true);
        for (InteractionHand hand : InteractionHand.values()) {
            var player = player(h, terminal.getBlockPos()); player.getInventory().selected = 0;
            player.setItemInHand(hand, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
            h.assertTrue(PortableAccess.pair(player, hand, terminal).isEmpty(), "Pairing failed");
            var access = PortableAccess.openSession(player, hand);
            // Local backpack crafting also works while wireless storage is offline.
            var menu = new StorageMenu(2, player.getInventory(), terminal.getBlockPos(), null, access.session());
            player.containerMenu = menu;
            ItemStack device = player.getItemInHand(hand), saved = device.copy();
            var recipe = recipe(h, "blue_dye_from_cornflower");
            h.assertTrue(INFO.getInventorySlots(menu, recipe).size() == (hand == InteractionHand.MAIN_HAND ? 35 : 36),
                    "Active handheld was exposed as a transfer source/destination");
            menu.getSlot(0).set(new ItemStack(Items.CORNFLOWER, 2));
            transfer(player, menu, recipe, List.of(new TransferOperation(0, 37)), false);
            h.assertTrue(menu.getSlot(36).getItem().is(Items.BLUE_DYE) && player.getItemInHand(hand) == device
                    && ItemStack.matches(device, saved) && menu.stillValid(player), "Transfer changed the active portable terminal");
            menu.removed(player);
            h.assertTrue(count(player, Items.CORNFLOWER) == 2, "Closing failed to return uncrafted ingredients");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fullBackpackPreflightAccountsForEveryOldGridStackAndPreservesNbt(GameTestHelper h) {
        var terminal = terminal(h); var player = player(h, terminal.getBlockPos()); var menu = open(player, terminal);
        for (int i = 0; i < 36; i++) menu.getSlot(i).set(new ItemStack(Items.STONE, 64));
        menu.getSlot(0).set(new ItemStack(Items.CORNFLOWER, 64));
        ItemStack named = new ItemStack(Items.DIAMOND, 3); named.setHoverName(net.minecraft.network.chat.Component.literal("keep"));
        menu.getSlot(37).set(named.copy()); menu.getSlot(45).set(new ItemStack(Items.GOLD_INGOT));
        var recipe = recipe(h, "blue_dye_from_cornflower");
        h.assertTrue(!INFO.canHandle(menu, recipe) && INFO.getHandlingError(menu, recipe) != null, "Full backpack was accepted");
        menu.getSlot(1).set(ItemStack.EMPTY);
        h.assertTrue(!INFO.canHandle(menu, recipe), "One empty slot was promised to two different leftovers");
        menu.getSlot(2).set(new ItemStack(Items.DIAMOND, 61));
        h.assertTrue(!INFO.canHandle(menu, recipe), "Named diamonds were merged into ordinary diamonds");
        menu.getSlot(2).set(named.copyWithCount(61));
        h.assertTrue(INFO.canHandle(menu, recipe) && menu.getSlot(2).getItem().getCount() == 61
                && ItemStack.matches(menu.getSlot(37).getItem(), named) && menu.getSlot(1).getItem().isEmpty(),
                "Safe merging was rejected or preflight mutated actual items");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void forgedTransferCannotUseTheLockedPortableSlot(GameTestHelper h) {
        var terminal = terminal(h); terminal.setPortableEnabled(true); var player = player(h, terminal.getBlockPos());
        player.getInventory().selected = 0; player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
        h.assertTrue(PortableAccess.pair(player, InteractionHand.MAIN_HAND, terminal).isEmpty(), "Pairing failed");
        var menu = new StorageMenu(2, player.getInventory(), terminal.getBlockPos(), null,
                PortableAccess.openSession(player, InteractionHand.MAIN_HAND).session()); player.containerMenu = menu;
        ItemStack device = player.getMainHandItem();
        transfer(player, menu, recipe(h, "blue_dye_from_cornflower"), List.of(new TransferOperation(27, 37)), false);
        h.assertTrue(player.getMainHandItem() == device && menu.getSlot(37).getItem().isEmpty(), "JEI bypassed the handheld slot lock");
        h.succeed();
    }
}
