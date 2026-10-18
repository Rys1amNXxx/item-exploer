package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.portable.PortableAccess;
import dev.itemexplorer.station.BaseStationStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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

/** Real client widgets, packets and rendered Chinese UI in a guarded disposable world. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class StationChunkClientProbe {
    private static final BlockPos CONTROLLER = new BlockPos(80, 200, 79), MODULE = CONTROLLER.south().west(),
            TERMINAL = CONTROLLER.south(3).east(3);
    private enum Stage { START, OVERVIEW, GUIDE, GUIDE_SHOT, EMPTY, EMPTY_SHOT, INSTALLED, ENABLED, ACTIVE_SHOT,
        MINIMUM, MINIMUM_SHOT, DISABLED, REENABLED, PREVIEW, WORLD_SHOT, FAR, PORTABLE, FINISH }
    private static Stage stage = Stage.START;
    private static Path root;
    private static boolean initialized, setupQueued, done;
    private static volatile boolean fixtureReady, verified;
    private static volatile Throwable serverFailure;
    private static int ticks, stageSince;
    private static long started;
    private static Screen renderedScreen;
    private static CompoundTag renderedView;
    private static String pendingScreenshot, completedScreenshot;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.chunkClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty("itemexplorer.chunkClientProbe")).toAbsolutePath().normalize();
                check(root.getFileName().toString().equals("chunk-loading-client")
                        && root.getParent().getFileName().toString().equals("work"), "Refusing unguarded probe directory");
                Files.createDirectories(root);
                check(root.toRealPath().equals(mc.gameDirectory.toPath().toRealPath()), "Client directory differs from isolated root");
                Files.writeString(root.resolve("report.txt"), "Station chunk maintenance real client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.options.hideGui = false;
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                log("START pid=" + ProcessHandle.current().pid());
            }
            ticks++;
            if (ticks > 3000 || System.currentTimeMillis() - started > 150_000)
                throw new IllegalStateException("Timed out at " + stage + " after " + ticks + " ticks");
            if (serverFailure != null) throw new IllegalStateException("Server probe failed", serverFailure);
            if (!setupQueued && mc.getSingleplayerServer() != null && mc.player != null) {
                setupQueued = true; server(mc, () -> prepare(mc));
            }
            if (!fixtureReady || mc.player == null || mc.level == null || ticks - stageSince < 4) return;
            switch (stage) {
                case START -> {
                    check(mc.options.languageCode.equals("zh_cn"), "Chinese options not loaded");
                    server(mc, () -> NetworkHooks.openScreen(player(mc), station(mc.getSingleplayerServer().overworld()), CONTROLLER));
                    advance(Stage.OVERVIEW);
                }
                case OVERVIEW -> {
                    if (!stationReady(mc)) return;
                    click(mc.screen, "toggle"); advance(Stage.GUIDE);
                }
                case GUIDE -> {
                    if (!stationReady(mc)) return;
                    screenshot("chunk-building-guide-zh.png"); advance(Stage.GUIDE_SHOT);
                }
                case GUIDE_SHOT -> {
                    if (!captured("chunk-building-guide-zh.png")) return;
                    click(mc.screen, "chunks"); advance(Stage.EMPTY);
                }
                case EMPTY -> {
                    if (!stationReady(mc)) return;
                    check(!button(mc.screen, "chunkSwitch").active, "Missing module allowed activation");
                    check(!menu(mc).chunkLoadingView().getBoolean("Enabled"), "Station opted in without input");
                    screenshot("chunk-missing-module-zh.png"); advance(Stage.EMPTY_SHOT);
                }
                case EMPTY_SHOT -> {
                    if (!captured("chunk-missing-module-zh.png")) return;
                    server(mc, () -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(MODULE,
                            ModContent.BASE_STATION_CHUNK_LOADER_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, Direction.WEST)));
                    advance(Stage.INSTALLED);
                }
                case INSTALLED -> {
                    if (!stationReady(mc) || menu(mc).chunkLoadingView().getInt("ModuleCount") != 1) return;
                    click(mc.screen, "chunkSwitch"); advance(Stage.ENABLED);
                }
                case ENABLED -> {
                    if (!stationReady(mc) || !menu(mc).chunkLoadingView().getBoolean("Active")) return;
                    check(!menu(mc).networkOnline(), "Chunk activation implicitly enabled wireless");
                    screenshot("chunk-active-zh.png"); advance(Stage.ACTIVE_SHOT);
                }
                case ACTIVE_SHOT -> {
                    if (!captured("chunk-active-zh.png")) return;
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480); mc.resizeDisplay(); advance(Stage.MINIMUM);
                }
                case MINIMUM -> {
                    if (!stationReady(mc) || mc.screen.width != 320 || mc.screen.height != 240) return;
                    for (var child : mc.screen.children()) if (child instanceof AbstractWidget widget && widget.visible)
                        check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget.getWidth() <= mc.screen.width
                                && widget.getY() + widget.getHeight() <= mc.screen.height, "Visible widget leaves minimum viewport");
                    screenshot("chunk-minimum-zh.png"); advance(Stage.MINIMUM_SHOT);
                }
                case MINIMUM_SHOT -> {
                    if (!captured("chunk-minimum-zh.png")) return;
                    click(mc.screen, "chunkSwitch"); advance(Stage.DISABLED);
                }
                case DISABLED -> {
                    if (!stationReady(mc) || menu(mc).chunkLoadingView().getBoolean("Enabled")) return;
                    check(!menu(mc).chunkLoadingView().getBoolean("Active"), "Disable did not release tickets");
                    click(mc.screen, "chunkSwitch"); advance(Stage.REENABLED);
                }
                case REENABLED -> {
                    if (!stationReady(mc) || !menu(mc).chunkLoadingView().getBoolean("Active")) return;
                    click(mc.screen, "preview"); check(StationChunkPreview.isVisible(CONTROLLER), "Preview not enabled");
                    mc.player.closeContainer();
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 720); mc.resizeDisplay();
                    server(mc, () -> {
                        var p = player(mc); p.getAbilities().flying = true; p.onUpdateAbilities();
                        p.teleportTo(mc.getSingleplayerServer().overworld(), 88, 248, 120, 180, 55);
                    }); advance(Stage.PREVIEW);
                }
                case PREVIEW -> {
                    if (mc.screen != null || ticks - stageSince < 20) return;
                    screenshot("chunk-world-boundary.png"); advance(Stage.WORLD_SHOT);
                }
                case WORLD_SHOT -> {
                    if (!captured("chunk-world-boundary.png")) return;
                    log("PASS actual controller packets, opt-in, network independence, release/re-enable, minimum viewport and preview");
                    server(mc, () -> {
                        var level = mc.getSingleplayerServer().overworld(); var p = player(mc);
                        station(level).setNetworkOnline(true);
                        p.teleportTo(level, TERMINAL.getX() + .5, TERMINAL.getY(), TERMINAL.getZ() - 1.5, 0, 0);
                        var stack = p.getMainHandItem(); p.setShiftKeyDown(true);
                        var context = new UseOnContext(p, InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.NORTH, TERMINAL, false));
                        check(stack.getItem().onItemUseFirst(stack, context).consumesAction(), "Pair action failed");
                        p.setShiftKeyDown(false); check(PortableAccess.binding(stack) != null, "No pairing stored");
                        p.teleportTo(level, 600.5, 100, 600.5, 0, 0);
                    }); advance(Stage.FAR);
                }
                case FAR -> {
                    // Wait beyond the normal player view/simulation radius before opening.
                    if (ticks - stageSince < 240) return;
                    server(mc, () -> {
                        var p = player(mc); var level = mc.getSingleplayerServer().overworld();
                        check(p.distanceToSqr(Vec3.atCenterOf(TERMINAL)) > 250_000, "Player not remote");
                        check(level.hasChunkAt(TERMINAL) && station(level).chunkLoadingActive(), "Station did not stay loaded");
                        check(p.getMainHandItem().getItem().use(level, p, InteractionHand.MAIN_HAND).getResult().consumesAction(), "Portable use failed far away");
                    }); advance(Stage.PORTABLE);
                }
                case PORTABLE -> {
                    if (!(mc.screen instanceof StorageScreen) || renderedScreen != mc.screen
                            || !(mc.player.containerMenu instanceof StorageMenu storage) || renderedView != storage.view()
                            || !storage.view().getBoolean("Available")) return;
                    check(storage.isPortable() && storage.view().getLong("Total") == 64, "Remote storage contents differ");
                    screenshot("chunk-far-portable-zh.png");
                    server(mc, () -> { station(mc.getSingleplayerServer().overworld()).setChunkLoadingEnabled(false); verified = true; });
                    advance(Stage.FINISH);
                }
                case FINISH -> {
                    if (!verified || !captured("chunk-far-portable-zh.png")) return;
                    log("PASS portable access over 700 blocks away after waiting 240 client ticks; screenshots=6");
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8); done = true; mc.stop();
                }
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void prepare(Minecraft mc) throws Exception {
        var server = mc.getSingleplayerServer();
        check(server.getWorldPath(LevelResource.ROOT).toRealPath().equals(root.resolve("saves/Base Station Probe").toRealPath()), "Refusing outside isolated world");
        var level = server.overworld();
        for (int x = 77; x <= 94; x++) for (int z = 77; z <= 90; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.SMOOTH_STONE.defaultBlockState());
            for (int y = 200; y <= 206; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        for (var part : BaseStationStructure.requirements(CONTROLLER, Direction.NORTH)) {
            var state = BaseStationStructure.block(part.part()).defaultBlockState();
            if (part.facing() != null) state = state.setValue(BaseStationControllerBlock.FACING, part.facing());
            level.setBlockAndUpdate(part.pos(), state);
        }
        for (int x = 0; x <= 2; x++) level.setBlockAndUpdate(CONTROLLER.south(3).east(x), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        station(level).refreshStructure();
        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        terminal.setPortableEnabled(true);
        var player = player(mc); player.setGameMode(GameType.CREATIVE); player.getInventory().clearContent(); player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
        player.teleportTo(level, 80.5, 200, 77.5, 0, 0);
        level.setDayTime(6000); level.setWeatherParameters(6000, 0, false, false); fixtureReady = true;
    }
    private static BaseStationBlockEntity station(ServerLevel level) { return (BaseStationBlockEntity) level.getBlockEntity(CONTROLLER); }
    private static ServerPlayer player(Minecraft mc) { return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()); }
    private interface ServerAction { void run() throws Exception; }
    private static void server(Minecraft mc, ServerAction action) {
        mc.getSingleplayerServer().execute(() -> { try { action.run(); } catch (Throwable failure) { serverFailure = failure; } });
    }
    private static BaseStationMenu menu(Minecraft mc) { return (BaseStationMenu) mc.player.containerMenu; }
    private static boolean stationReady(Minecraft mc) {
        return mc.screen instanceof BaseStationScreen && renderedScreen == mc.screen && mc.player.containerMenu instanceof BaseStationMenu
                && renderedView == menu(mc).networkView() && menu(mc).networkView().contains("Session") && menu(mc).ready();
    }
    private static Button button(Screen screen, String name) throws Exception {
        Field field = screen.getClass().getDeclaredField(name); field.setAccessible(true); return (Button) field.get(screen);
    }
    private static void click(Screen screen, String name) throws Exception {
        Button button = button(screen, name); check(button.active && button.visible, "Unavailable control " + name);
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
    }
    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty("itemexplorer.chunkClientProbe") == null || done) return;
        renderedScreen = event.getScreen();
        if (renderedScreen instanceof BaseStationScreen screen) renderedView = screen.getMenu().networkView();
        if (renderedScreen instanceof StorageScreen screen) renderedView = screen.getMenu().view();
        if (pendingScreenshot != null) { event.getGuiGraphics().flush(); capture(); }
    }
    @SubscribeEvent public static void worldRendered(TickEvent.RenderTickEvent event) {
        if (System.getProperty("itemexplorer.chunkClientProbe") != null && !done && event.phase == TickEvent.Phase.END
                && Minecraft.getInstance().screen == null && pendingScreenshot != null) capture();
    }
    private static void capture() {
        try {
            try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(root.resolve(pendingScreenshot).toFile()); }
            completedScreenshot = pendingScreenshot; pendingScreenshot = null; log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(Minecraft.getInstance(), failure); }
    }
    private static void screenshot(String name) { pendingScreenshot = name; completedScreenshot = null; }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(Stage next) { stage = next; stageSince = ticks; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String message) throws Exception { Files.writeString(root.resolve("report.txt"), message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); log("FAIL stage=" + stage + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL " + stage + " " + failure + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
