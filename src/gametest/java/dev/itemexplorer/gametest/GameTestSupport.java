package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.storage.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.common.util.FakePlayer;

import java.util.List;
import java.util.UUID;

/** Fixtures shared by the GameTest classes; scenario-specific setup stays with each test. */
final class GameTestSupport {
    /** Relative position used by tests that need a single terminal. */
    static final BlockPos TERMINAL = new BlockPos(2, 2, 2);

    record Opened(StorageBlockEntity entity, ServerPlayer player, StorageMenu menu) {}

    /** Everything a rejected operation must leave unchanged. */
    record Snapshot(Tag data, long total, long revision, long search, long transfers, List<CompoundTag> catalog) {
        static Snapshot of(StorageInventory inventory) {
            return new Snapshot(inventory.save(), inventory.total(), inventory.revision(),
                    inventory.searchRevision(), inventory.transferRevision(), inventory.searchCatalog());
        }
    }

    private GameTestSupport() {}

    /** A detached local inventory with no block entity to mark dirty. */
    static StorageInventory storage() {
        return new StorageInventory(() -> {});
    }

    /** Expects the action to fail with exactly this rejection reason. */
    static void rejected(GameTestHelper h, Runnable action, String reason) {
        try { action.run(); }
        catch (IllegalArgumentException e) {
            h.assertTrue(reason.equals(e.getMessage()), "Expected rejection " + reason + ", got " + e.getMessage());
            return;
        }
        h.fail("Expected rejection: " + reason);
    }

    static StorageBlockEntity terminal(GameTestHelper h) {
        h.setBlock(TERMINAL, ModContent.STORAGE_BLOCK.get());
        return (StorageBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(TERMINAL));
    }

    static ServerPlayer player(GameTestHelper h) {
        return player(h, false);
    }

    /** Disconnected players make vanilla drop menu contents instead of returning them to the inventory. */
    static ServerPlayer player(GameTestHelper h, boolean disconnected) {
        return new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "gametest")) {
            @Override public boolean hasDisconnected() { return disconnected; }
        };
    }

    /** A connected player standing on top of the absolute position. */
    static ServerPlayer player(GameTestHelper h, BlockPos pos) {
        return standOn(player(h), pos);
    }

    static ServerPlayer standOn(ServerPlayer player, BlockPos pos) {
        player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        return player;
    }

    /** Makes the terminal menu the player's open container, as the server does after a click. */
    static StorageMenu open(ServerPlayer player, StorageBlockEntity terminal) {
        return open(player, terminal, 1);
    }

    static StorageMenu open(ServerPlayer player, StorageBlockEntity terminal, int menuId) {
        StorageMenu menu = new StorageMenu(menuId, player.getInventory(), terminal.getBlockPos(), terminal);
        player.containerMenu = menu;
        return menu;
    }

    /** Opens the terminal for a new player standing on it. */
    static StorageMenu open(GameTestHelper h, StorageBlockEntity terminal) {
        return open(player(h, terminal.getBlockPos()), terminal);
    }

    static Opened openTerminal(GameTestHelper h) {
        return openTerminal(h, terminal(h));
    }

    static Opened openTerminal(GameTestHelper h, StorageBlockEntity terminal) {
        ServerPlayer player = player(h, terminal.getBlockPos());
        return new Opened(terminal, player, open(player, terminal));
    }

    /** A request carrying the revision and session the menu currently publishes. */
    static StorageNetwork.Request request(StorageMenu menu, Action action, int id, long amount, String name) {
        var view = menu.snapshot();
        return new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), action, id, 0, amount, name, view.getLong("Session"));
    }

    /** Count in the 36 main inventory slots, excluding armor and the offhand. */
    static int carried(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    /** Stored count directly inside the folder; child folders are not included. */
    static long count(StorageInventory inventory, int folder, Item item) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToLong(StorageInventory.Entry::count).sum();
    }

    /** Id of the first entry of the item directly inside the folder. */
    static int entry(StorageInventory inventory, int folder, Item item) {
        return inventory.entries().stream().filter(e -> e.folder() == folder && e.stack().is(item))
                .mapToInt(StorageInventory.Entry::id).findFirst().orElseThrow();
    }

    /** Uses vanilla furnace cooking, with deterministic service passes between its normal ticks. */
    static void cook(AbstractFurnaceBlockEntity furnace, ProductionPortBlockEntity port, int ticks) {
        for (int i = 0; i < ticks; i++) {
            AbstractFurnaceBlockEntity.serverTick(furnace.getLevel(), furnace.getBlockPos(), furnace.getBlockState(), furnace);
            port.workTick();
        }
    }
}
