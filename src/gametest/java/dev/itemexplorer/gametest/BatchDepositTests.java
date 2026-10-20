package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork.Action;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static dev.itemexplorer.gametest.GameTestSupport.*;

/** Vanilla Shift + double-click from the backpack, sent as one request for all matching slots. */
@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BatchDepositTests {
    private static long slots(int... menuSlots) {
        long mask = 0;
        for (int slot : menuSlots) mask |= 1L << slot;
        return mask;
    }

    private static void put(StorageMenu menu, int menuSlot, ItemStack stack) {
        menu.slots.get(menuSlot).set(stack);
    }

    @GameTest(template = "empty")
    public static void batchFollowsTheFirstShiftClickWithinTheSameView(GameTestHelper h) {
        Opened o = openTerminal(h); var menu = o.menu(); var storage = o.entity().inventory();
        for (int slot : new int[]{0, 5, 30}) put(menu, slot, new ItemStack(Items.IRON_INGOT, 64));
        put(menu, 6, new ItemStack(Items.DIRT, 64));
        // Both requests come from the view the client had before either deposit, as they do within one double-click.
        var first = request(menu, Action.DEPOSIT_SLOT, 0, 0, "");
        var separate = request(menu, Action.DEPOSIT_SLOT, 5, 0, "");
        var batch = request(menu, Action.DEPOSIT_SLOTS, 0, slots(0, 5, 30), "");
        menu.handle(first); menu.handle(separate);
        h.assertTrue(storage.total() == 64, "A second separate deposit from the old view should still be stale");
        menu.handle(batch);
        h.assertTrue(count(storage, 0, Items.IRON_INGOT) == 192 && carried(o.player(), Items.IRON_INGOT) == 0,
                "Batch did not deposit every listed matching slot after the first deposit changed the revision");
        h.assertTrue(storage.total() == 192 && carried(o.player(), Items.DIRT) == 64, "Batch touched an unlisted slot");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fullStorageDepositsWhatFitsAndKeepsTheRest(GameTestHelper h) {
        Opened o = openTerminal(h); var menu = o.menu(); var storage = o.entity().inventory();
        int room = 100, filler = (int) storage.capacity() - room;
        storage.insert(new ItemStack(Items.STONE, filler), filler, 0);
        for (int slot = 0; slot < 3; slot++) put(menu, slot, new ItemStack(Items.IRON_INGOT, 64));
        menu.handle(request(menu, Action.DEPOSIT_SLOTS, 0, slots(0, 1, 2), ""));
        h.assertTrue(storage.total() == storage.capacity() && count(storage, 0, Items.IRON_INGOT) == room
                        && carried(o.player(), Items.IRON_INGOT) == 3 * 64 - room,
                "Batch deposit lost, duplicated or refused items that fit");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidMasksAndOldSessionsChangeNothing(GameTestHelper h) {
        Opened o = openTerminal(h); var menu = o.menu(); var storage = o.entity().inventory();
        put(menu, 0, new ItemStack(Items.IRON_INGOT, 64));
        menu.getSlot(StorageMenu.CRAFT_GRID_START).set(new ItemStack(Items.IRON_INGOT, 8));
        menu.handle(request(menu, Action.DEPOSIT_SLOTS, 0, 0, ""));
        menu.handle(request(menu, Action.DEPOSIT_SLOTS, 0, slots(0, StorageMenu.CRAFT_GRID_START), ""));
        menu.handle(request(menu, Action.DEPOSIT_SLOTS, 0, slots(StorageMenu.PLAYER_SLOT_COUNT), ""));
        var forged = request(menu, Action.DEPOSIT_SLOTS, 0, slots(0), "");
        menu.handle(forged.withSession(forged.session() + 1));
        h.assertTrue(storage.total() == 0 && carried(o.player(), Items.IRON_INGOT) == 64
                        && menu.getSlot(StorageMenu.CRAFT_GRID_START).getItem().getCount() == 8,
                "An empty, out-of-range or old-session batch moved items");
        h.succeed();
    }
}
