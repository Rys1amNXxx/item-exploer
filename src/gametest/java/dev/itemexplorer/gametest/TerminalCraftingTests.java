package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerminalCraftingTests {
    private static StorageBlockEntity terminal(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, ModContent.STORAGE_BLOCK.get());
        return (StorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(local));
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos pos) {
        return player(helper, pos, false);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos pos, boolean disconnected) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "craft-test")) {
            @Override public boolean hasDisconnected() { return disconnected; }
        };
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        return player;
    }

    private static StorageMenu open(ServerPlayer player, StorageBlockEntity terminal) {
        StorageMenu menu = new StorageMenu(1, player.getInventory(), terminal.getBlockPos(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    private static void ingredient(StorageMenu menu, int index, Item item, int count) {
        menu.getSlot(StorageMenu.CRAFT_GRID_START + index).set(new ItemStack(item, count));
    }

    private static void ironBlockRecipe(StorageMenu menu, int count) {
        for (int i = 0; i < 9; i++) ingredient(menu, i, Items.IRON_INGOT, count);
    }

    private static int inventoryCount(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int gridCount(StorageMenu menu, Item item) {
        int count = 0;
        for (int i = StorageMenu.CRAFT_GRID_START; i < StorageMenu.CRAFT_GRID_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void assertEmptyGrid(GameTestHelper helper, StorageMenu menu) {
        for (int i = StorageMenu.CRAFT_GRID_START; i < StorageMenu.CRAFT_GRID_END; i++)
            helper.assertTrue(menu.getSlot(i).getItem().isEmpty(), "Crafting input remained in slot " + i);
    }

    private static void fillInventory(ServerPlayer player) {
        for (int i = 0; i < StorageMenu.PLAYER_SLOT_COUNT; i++)
            player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
    }

    @GameTest(template = "empty")
    public static void shapedRecipeConsumesNineInputsOnlyWhenTaken(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        ironBlockRecipe(menu, 1);
        helper.assertTrue(menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.IRON_BLOCK)
                && gridCount(menu, Items.IRON_INGOT) == 9, "The 3x3 recipe did not produce a non-consuming preview");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.IRON_BLOCK) && menu.getCarried().getCount() == 1,
                "Taking the output did not put one iron block on the cursor");
        assertEmptyGrid(helper, menu);
        helper.assertTrue(menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().isEmpty() && terminal.inventory().total() == 0,
                "Taking a result retained a preview or changed terminal storage");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().getCount() == 1, "An empty result duplicated the crafted item");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawnItemsCanBePlacedAndCraftedManually(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        terminal.inventory().insert(new ItemStack(Items.CORNFLOWER), 1, 0);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        int id = terminal.inventory().entries().get(0).id();
        menu.handle(new StorageNetwork.Request(1, terminal.inventory().revision(), StorageNetwork.Action.WITHDRAW,
                id, 0, 1, "").withSession(menu.session()));
        helper.assertTrue(player.getInventory().getItem(0).is(Items.CORNFLOWER) && terminal.inventory().total() == 0,
                "Withdrawing the ingredient failed");
        menu.clicked(27, 0, ClickType.PICKUP, player);
        menu.clicked(StorageMenu.CRAFT_GRID_END - 1, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.BLUE_DYE),
                "The shapeless recipe failed in the bottom-right input slot");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.BLUE_DYE) && menu.getCarried().getCount() == 1
                && inventoryCount(player, Items.CORNFLOWER) == 0, "Manual crafting did not consume the withdrawn ingredient");
        assertEmptyGrid(helper, menu);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shiftClickRepeatsCraftingIntoPlayerInventory(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        ironBlockRecipe(menu, 3);
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 3 && menu.getCarried().isEmpty()
                && terminal.inventory().total() == 0, "Shift-craft failed to repeat or deposited the output into terminal storage");
        assertEmptyGrid(helper, menu);
        helper.assertTrue(menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().isEmpty(), "Shift-craft retained an obsolete result");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cakeCraftReturnsEachMilkBucket(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        for (int i = 0; i < 3; i++) ingredient(menu, i, Items.MILK_BUCKET, 1);
        ingredient(menu, 3, Items.SUGAR, 1);
        ingredient(menu, 4, Items.EGG, 1);
        ingredient(menu, 5, Items.SUGAR, 1);
        for (int i = 6; i < 9; i++) ingredient(menu, i, Items.WHEAT, 1);
        helper.assertTrue(menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.CAKE), "Cake recipe was not matched");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.CAKE) && menu.getCarried().getCount() == 1,
                "Cake output was not taken");
        helper.assertTrue(gridCount(menu, Items.BUCKET) == 3 && gridCount(menu, Items.MILK_BUCKET) == 0,
                "Crafting lost or duplicated milk-bucket remainders");
        for (int i = 3; i < 9; i++)
            helper.assertTrue(menu.getSlot(StorageMenu.CRAFT_GRID_START + i).getItem().isEmpty(), "Cake ingredients were not consumed");
        menu.removed(player);
        helper.assertTrue(inventoryCount(player, Items.BUCKET) == 3 && inventoryCount(player, Items.CAKE) == 1,
                "Closing lost the crafted cake or its bucket remainders");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void resultRejectsPlacementAndInputShiftReturnsToInventory(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        ironBlockRecipe(menu, 1);
        menu.setCarried(new ItemStack(Items.STONE, 7));
        helper.assertTrue(!menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).mayPlace(menu.getCarried()), "Result accepts inserted items");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.STONE) && menu.getCarried().getCount() == 7
                && gridCount(menu, Items.IRON_INGOT) == 9, "Placing onto the result consumed or replaced items");
        menu.setCarried(ItemStack.EMPTY);
        menu.clicked(StorageMenu.CRAFT_GRID_START, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_INGOT) == 1 && gridCount(menu, Items.IRON_INGOT) == 8
                && menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().isEmpty() && terminal.inventory().total() == 0,
                "Shift-clicking an input did not return it to the player and invalidate the recipe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryShiftCraftDoesNotConsumeIngredients(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        fillInventory(player);
        ironBlockRecipe(menu, 2);
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.STONE) == 36 * 64 && inventoryCount(player, Items.IRON_BLOCK) == 0
                && gridCount(menu, Items.IRON_INGOT) == 18 && menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.IRON_BLOCK),
                "Shift-crafting into a full inventory consumed ingredients or changed the inventory");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(terminal.getBlockPos()).inflate(3)).isEmpty(),
                "Failed shift-crafting unexpectedly dropped an item");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shiftCraftStopsWhenTheLastInventorySpaceIsUsed(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        fillInventory(player);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_BLOCK, 63));
        ironBlockRecipe(menu, 2);
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_BLOCK) == 64 && gridCount(menu, Items.IRON_INGOT) == 9
                && menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.IRON_BLOCK),
                "Shift-crafting consumed more ingredients than fit into the remaining inventory space");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void closingReturnsInputsOnceWithoutGrantingThePreview(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        ironBlockRecipe(menu, 1);
        menu.setCarried(new ItemStack(Items.DIAMOND, 2));
        menu.removed(player);
        menu.removed(player);
        helper.assertTrue(inventoryCount(player, Items.IRON_INGOT) == 9 && inventoryCount(player, Items.DIAMOND) == 2
                && inventoryCount(player, Items.IRON_BLOCK) == 0 && menu.getCarried().isEmpty(),
                "Closing lost or duplicated inputs/cursor, or granted the uncrafted output preview");
        assertEmptyGrid(helper, menu);
        helper.assertTrue(menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().isEmpty(), "Closed menu retained a result preview");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        menu.clicked(StorageMenu.CRAFT_GRID_START, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.IRON_INGOT) == 9 && menu.getCarried().isEmpty(),
                "Late clicks on the closed crafting menu transferred items");
        StorageMenu reopened = open(player, terminal);
        assertEmptyGrid(helper, reopened);
        helper.assertTrue(reopened.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().isEmpty(), "Reopening retained another menu's recipe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryAndDisconnectDropInputsExactlyOnce(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        for (boolean disconnected : new boolean[]{false, true}) {
            ServerPlayer player = player(helper, terminal.getBlockPos(), disconnected);
            StorageMenu menu = open(player, terminal);
            if (!disconnected) fillInventory(player);
            ironBlockRecipe(menu, 1);
            menu.removed(player);
            menu.removed(player);
            assertEmptyGrid(helper, menu);
            helper.assertTrue(inventoryCount(player, Items.IRON_INGOT) == 0 && inventoryCount(player, Items.IRON_BLOCK) == 0,
                    "Disconnect/full-inventory close retained ingredients or granted a result");
        }
        var dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(terminal.getBlockPos()).inflate(3));
        int ingots = dropped.stream().filter(entity -> entity.getItem().is(Items.IRON_INGOT)).mapToInt(entity -> entity.getItem().getCount()).sum();
        int blocks = dropped.stream().filter(entity -> entity.getItem().is(Items.IRON_BLOCK)).mapToInt(entity -> entity.getItem().getCount()).sum();
        helper.assertTrue(ingots == 18 && blocks == 0 && terminal.inventory().total() == 0,
                "Close must drop exactly 18 input ingots and no result blocks, got " + ingots + " ingots and " + blocks + " blocks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void simultaneousViewersHaveIndependentCraftingGrids(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer first = player(helper, terminal.getBlockPos()), second = player(helper, terminal.getBlockPos());
        StorageMenu a = open(first, terminal), b = open(second, terminal);
        ironBlockRecipe(a, 1);
        ingredient(b, 4, Items.CORNFLOWER, 2);
        a.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, first);
        helper.assertTrue(b.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.BLUE_DYE)
                && gridCount(b, Items.CORNFLOWER) == 2, "Crafting in one menu mutated another player's grid");
        a.removed(first);
        helper.assertTrue(inventoryCount(first, Items.IRON_BLOCK) == 1 && gridCount(b, Items.CORNFLOWER) == 2,
                "Closing one viewer returned or consumed another viewer's ingredients");
        b.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE, second);
        helper.assertTrue(inventoryCount(second, Items.BLUE_DYE) == 2 && inventoryCount(second, Items.IRON_BLOCK) == 0
                && terminal.inventory().total() == 0, "Separate menus leaked crafting items between viewers or into storage");
        assertEmptyGrid(helper, b);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void forgedStorageDepositsCannotConsumeCraftingSlots(GameTestHelper helper) {
        StorageBlockEntity terminal = terminal(helper);
        ServerPlayer player = player(helper, terminal.getBlockPos());
        StorageMenu menu = open(player, terminal);
        ironBlockRecipe(menu, 1);
        for (int index : new int[]{StorageMenu.CRAFT_RESULT_SLOT, StorageMenu.CRAFT_GRID_START, StorageMenu.CRAFT_GRID_END - 1})
            menu.handle(new StorageNetwork.Request(1, terminal.inventory().revision(), StorageNetwork.Action.DEPOSIT_SLOT,
                    index, 0, 0, "").withSession(menu.session()));
        helper.assertTrue(terminal.inventory().total() == 0 && gridCount(menu, Items.IRON_INGOT) == 9
                && menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).getItem().is(Items.IRON_BLOCK),
                "A forged deposit inserted the recipe preview or consumed crafting inputs");
        menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.IRON_BLOCK) && menu.getCarried().getCount() == 1,
                "Rejected forged deposits invalidated the legitimate recipe");
        assertEmptyGrid(helper, menu);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void responsiveCraftingSlotsRetainTheirContainersAndRules(GameTestHelper helper) {
        var player = helper.makeMockPlayer();
        StorageMenu menu = new StorageMenu(1, player.getInventory(), BlockPos.ZERO, null);
        var resultContainer = menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT).container;
        var gridContainer = menu.getSlot(StorageMenu.CRAFT_GRID_START).container;
        ingredient(menu, 8, Items.CORNFLOWER, 3);
        int[][] viewports = {{320, 240}, {360, 240}, {480, 270}, {720, 415}, {1920, 1080}};
        for (int[] viewport : viewports) {
            StorageLayout layout = StorageLayout.fit(viewport[0], viewport[1]);
            menu.arrangeClientSlots(layout);
            var result = menu.getSlot(StorageMenu.CRAFT_RESULT_SLOT);
            helper.assertTrue(result instanceof ResultSlot && result.container == resultContainer
                    && result.index == StorageMenu.CRAFT_RESULT_SLOT && result.getContainerSlot() == 0
                    && !result.mayPlace(new ItemStack(Items.STONE)), "Resize replaced the specialized result slot or its backing container");
            for (int index = StorageMenu.CRAFT_GRID_START; index < StorageMenu.CRAFT_GRID_END; index++) {
                var slot = menu.getSlot(index);
                helper.assertTrue(slot.index == index && slot.container == gridContainer
                        && slot.getContainerSlot() == index - StorageMenu.CRAFT_GRID_START,
                        "Resize changed a crafting input's menu index or backing slot");
            }
            for (var slot : menu.slots)
                helper.assertTrue(slot.x >= 0 && slot.y >= 0 && slot.x + 16 <= layout.width() && slot.y + 16 <= layout.height(),
                        "Resize placed slot " + slot.index + " outside the terminal window");
            helper.assertTrue(gridCount(menu, Items.CORNFLOWER) == 3, "Resize changed the crafting grid contents");
        }
        helper.succeed();
    }
}
