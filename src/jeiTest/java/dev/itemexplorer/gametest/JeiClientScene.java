package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.client.StorageScreen;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.portable.PortableAccess;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;
import org.lwjgl.glfw.GLFW;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Real JEI UI, standard transfer packets and server inventories in an isolated world. */
@JeiPlugin
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class JeiClientScene implements IModPlugin {
    private static final String PROPERTY = "itemexplorer.jeiClientProbe";
    private static final BlockPos TERMINAL = new BlockPos(80, 82, 80);
    private static IJeiRuntime runtime;
    private static Path root;
    private static boolean queued, done;
    private static volatile boolean ready, prepared, verified;
    private static volatile Throwable serverFailure;
    private static long started;
    private static int stage, frames, stageFrame;
    private static Screen rendered;
    private static String pendingShot, capturedShot;

    @Override public ResourceLocation getPluginUid() { return new ResourceLocation(ItemExplorer.MOD_ID, "jei_acceptance"); }
    @Override public void onRuntimeAvailable(IJeiRuntime available) { runtime = available; }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty(PROPERTY) == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (root == null) {
                root = Path.of(System.getProperty(PROPERTY)).toAbsolutePath().normalize(); Files.createDirectories(root);
                Files.writeString(root.resolve("report.txt"), "JEI 15.20 terminal acceptance\n", StandardCharsets.UTF_8);
                started = System.currentTimeMillis(); mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2);
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
            }
            if (System.currentTimeMillis() - started > 180_000) throw new IllegalStateException("Timed out at stage " + stage);
            if (serverFailure != null) throw new IllegalStateException("Server assertion failed", serverFailure);
            if (!queued && mc.player != null && mc.getSingleplayerServer() != null) setup(mc);
            if (!ready || runtime == null || mc.player == null || frames <= stageFrame || rendered != mc.screen) return;
            StorageMenu menu = mc.player.containerMenu instanceof StorageMenu storage ? storage : null;
            if (menu == null) return;
            switch (stage) {
                case 0 -> {
                    if (!(mc.screen instanceof StorageScreen) || !menu.view().contains("Session")) return;
                    show(mc, "iron_block"); advance(1);
                }
                case 1 -> { check(transferButton(mc).active, "JEI + is unavailable for a complete shaped recipe"); shot("jei-fixed-recipe.png"); advance(2); }
                case 2 -> { if (captured("jei-fixed-recipe.png")) { clickTransfer(mc); advance(3); } }
                case 3 -> {
                    // Result previews arrive while JEI is still filling the grid. Wait for the
                    // ordinary slot synchronization too, rather than inspecting a partial update.
                    if (!(mc.screen instanceof StorageScreen) || !menu.getSlot(36).getItem().is(Items.IRON_BLOCK)
                            || gridCount(menu, Items.IRON_INGOT) != 9 || count(mc, Items.IRON_INGOT) != 9
                            || count(mc, Items.DIAMOND) != 3) return;
                    for (int i = 37; i < 46; i++) check(menu.getSlot(i).getItem().is(Items.IRON_INGOT)
                            && menu.getSlot(i).getItem().getCount() == 1, "JEI filled the wrong grid/quantity");
                    check(count(mc, Items.IRON_INGOT) == 9 && count(mc, Items.DIAMOND) == 3 && count(mc, Items.IRON_BLOCK) == 0,
                            "Transfer lost old grid items or crafted automatically");
                    log("PASS actual JEI + -> packet -> fixed terminal: one shaped set, old grid returned, result preview only");
                    shot("jei-fixed-filled.png"); advance(4);
                }
                case 4 -> {
                    if (!captured("jei-fixed-filled.png")) return;
                    clickSlot(mc, menu, 36); advance(5);
                }
                case 5 -> {
                    if (!menu.getCarried().is(Items.IRON_BLOCK)) return;
                    int empty = menu.craftingInventorySlots().stream().filter(slot -> !slot.hasItem())
                            .mapToInt(slot -> slot.index).findFirst().orElseThrow();
                    clickSlot(mc, menu, empty); advance(6);
                }
                case 6 -> {
                    if (!menu.getCarried().isEmpty() || count(mc, Items.IRON_BLOCK) != 1) return;
                    show(mc, "cake"); advance(7);
                }
                case 7 -> {
                    AbstractWidget button = transferButton(mc);
                    check(button.visible && !button.active, "Missing ingredients did not disable JEI +");
                    shot("jei-missing-materials.png"); advance(8);
                }
                case 8 -> {
                    if (!captured("jei-missing-materials.png")) return;
                    mc.screen.onClose(); prepareFullInventory(mc); advance(9);
                }
                case 9 -> {
                    if (!prepared || !menu.getSlot(45).getItem().is(Items.GOLD_INGOT) || count(mc, Items.STONE) != 35 * 64) return;
                    show(mc, "blue_dye_from_cornflower"); advance(10);
                }
                case 10 -> {
                    AbstractWidget button = transferButton(mc);
                    check(button.visible && !button.active, "JEI allowed grid replacement without room to return old materials");
                    shot("jei-full-inventory.png"); advance(11);
                }
                case 11 -> {
                    if (!captured("jei-full-inventory.png")) return;
                    log("PASS missing-material and full-backpack transfers are rejected by the real JEI UI");
                    mc.screen.onClose(); openPortable(mc); advance(12);
                }
                case 12 -> {
                    if (!(mc.screen instanceof StorageScreen) || !menu.isPortable() || !menu.view().contains("Session")
                            || count(mc, Items.CORNFLOWER) != 2) return;
                    check(menu.craftingInventorySlots().size() == 35, "Client portable slot was not excluded");
                    show(mc, "blue_dye_from_cornflower"); advance(13);
                }
                case 13 -> { check(transferButton(mc).active, "Portable JEI + is unavailable"); clickTransfer(mc); advance(14); }
                case 14 -> {
                    if (!(mc.screen instanceof StorageScreen) || !menu.getSlot(36).getItem().is(Items.BLUE_DYE)
                            || gridCount(menu, Items.CORNFLOWER) != 1 || count(mc, Items.CORNFLOWER) != 1) return;
                    check(mc.player.getMainHandItem().is(ModContent.PORTABLE_TERMINAL_ITEM.get())
                            && count(mc, Items.CORNFLOWER) == 1 && gridCount(menu, Items.CORNFLOWER) == 1,
                            "Portable transfer lost its device or used wrong quantities");
                    shot("jei-portable-filled.png"); verifyServer(mc); advance(15);
                }
                case 15 -> {
                    if (!verified || !captured("jei-portable-filled.png")) return;
                    log("PASS portable JEI +, authoritative server inventory, locked device, offline backpack crafting, storage untouched");
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8); done = true; mc.stop();
                }
                default -> throw new IllegalStateException("Unknown stage");
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void setup(Minecraft mc) {
        queued = true; var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                check(server.getWorldPath(LevelResource.ROOT).toRealPath().equals(root.resolve("saves/JEI Probe").toRealPath()), "Unsafe fixture path");
                var level = server.overworld(); var player = server.getPlayerList().getPlayer(id);
                level.getChunkAt(TERMINAL);
                level.setBlockAndUpdate(TERMINAL.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(TERMINAL, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(76, 80, 76, 84, 88, 84)).forEach(net.minecraft.world.entity.item.ItemEntity::discard);
                var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
                player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
                player.teleportTo(level, 80.5, 82, 78.5, 0, 0); level.setDayTime(6000);
                player.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 18));
                player.getInventory().setItem(10, new ItemStack(Items.CORNFLOWER, 2));
                NetworkHooks.openScreen(player, terminal, TERMINAL);
                ((StorageMenu) player.containerMenu).getSlot(45).set(new ItemStack(Items.DIAMOND, 3));
                ready = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void prepareFullInventory(Minecraft mc) {
        var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(id); var menu = (StorageMenu) player.containerMenu;
                check(player.getInventory().items.stream().filter(s -> s.is(Items.IRON_BLOCK)).mapToInt(ItemStack::getCount).sum() == 1,
                        "Server did not craft exactly one iron block");
                for (int i = 0; i < 36; i++) menu.getSlot(i).set(new ItemStack(Items.STONE, 64));
                menu.getSlot(0).set(new ItemStack(Items.CORNFLOWER, 64));
                menu.getSlot(37).set(new ItemStack(Items.DIAMOND)); menu.getSlot(45).set(new ItemStack(Items.GOLD_INGOT));
                menu.broadcastChanges(); prepared = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void openPortable(Minecraft mc) {
        var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(id); player.closeContainer(); player.getInventory().clearContent();
                var terminal = (StorageBlockEntity) server.overworld().getBlockEntity(TERMINAL); terminal.setPortableEnabled(true);
                player.getInventory().selected = 0; player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
                player.getInventory().setItem(9, new ItemStack(Items.CORNFLOWER, 2));
                check(PortableAccess.pair(player, InteractionHand.MAIN_HAND, terminal).isEmpty(), "Portable pairing failed");
                var session = PortableAccess.openSession(player, InteractionHand.MAIN_HAND).session();
                NetworkHooks.openScreen(player, new SimpleMenuProvider((menuId, inventory, owner) ->
                        new StorageMenu(menuId, inventory, TERMINAL, null, session), Component.literal("JEI 便携终端")),
                        data -> { data.writeBlockPos(TERMINAL); data.writeBoolean(true); data.writeVarInt(session.slot()); });
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void verifyServer(Minecraft mc) {
        var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(id); var menu = (StorageMenu) player.containerMenu;
                check(menu.isPortable() && menu.stillValid(player) && gridCount(menu, Items.CORNFLOWER) == 1
                        && menu.getSlot(36).getItem().is(Items.BLUE_DYE), "Server transfer result differs");
                var terminal = (StorageBlockEntity) server.overworld().getBlockEntity(TERMINAL);
                check(terminal.inventory().total() == 64, "JEI touched terminal storage"); verified = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void show(Minecraft mc, String name) {
        CraftingRecipe recipe = (CraftingRecipe) mc.level.getRecipeManager().byKey(new ResourceLocation("minecraft", name)).orElseThrow();
        var category = runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING);
        runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
    }
    private static AbstractWidget transferButton(Minecraft mc) throws Exception {
        check(mc.screen instanceof RecipesGui, "JEI recipe screen is missing");
        Object layouts = field(mc.screen, "layouts");
        var rows = (List<?>) field(layouts, "recipeLayoutsWithButtons"); check(rows.size() == 1, "Expected one JEI recipe");
        var row = (RecipeLayoutWithButtons<?>) rows.get(0);
        return (AbstractWidget) field(row.transferButton(), "button");
    }
    private static void clickTransfer(Minecraft mc) throws Exception {
        var button = transferButton(mc); check(button.active && button.visible, "JEI transfer unavailable");
        Screen screen = mc.screen; double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
    }
    private static void clickSlot(Minecraft mc, StorageMenu menu, int index) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, index, 0, ClickType.PICKUP, mc.player);
    }
    private static int count(Minecraft mc, Item item) {
        return mc.player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static int gridCount(StorageMenu menu, Item item) {
        return menu.slots.subList(StorageMenu.CRAFT_GRID_START, StorageMenu.CRAFT_GRID_END).stream()
                .map(slot -> slot.getItem()).filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty(PROPERTY) == null || done || root == null) return;
        frames++; rendered = event.getScreen();
        if (pendingShot == null) return;
        try {
            event.getGuiGraphics().flush();
            try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(root.resolve(pendingShot).toFile()); }
            capturedShot = pendingShot; pendingShot = null; log("SCREENSHOT " + capturedShot);
        } catch (Throwable failure) { fail(Minecraft.getInstance(), failure); }
    }
    private static void shot(String name) { pendingShot = name; capturedShot = null; }
    private static boolean captured(String name) { return name.equals(capturedShot); }
    private static void advance(int next) { stage = next; stageFrame = frames; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String text) throws Exception { Files.writeString(root.resolve("report.txt"), text + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); log("FAIL stage=" + stage + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL\n", StandardCharsets.UTF_8);
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(root.resolve("failed.png").toFile()); }
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
