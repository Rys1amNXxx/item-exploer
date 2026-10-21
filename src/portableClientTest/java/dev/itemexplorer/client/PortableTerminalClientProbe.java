package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.portable.PortableAccess;
import dev.itemexplorer.station.StationConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
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

/** Actual rendering and network transport in one guarded disposable world; no OS input. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class PortableTerminalClientProbe {
    private static final BlockPos CENTER = new BlockPos(80, 82, 80), TERMINAL = CENTER.south(2).east(3), NAS = TERMINAL.south();
    private enum Stage { START, PHYSICAL, REMOTE, SETTINGS, ENABLED, SETTINGS_SHOT, PORTABLE,
        NORMAL_SHOT, SEARCH, SELECTED, TAKE, SEARCH_SHOT, NAS, NAS_READY, MINIMUM, MINIMUM_SHOT,
        OFFLINE, OFFLINE_SHOT, RECONNECT, REVOKE_PHYSICAL, REVOKE_REMOTE, REVOKE_SETTINGS,
        REVOKED, VERIFIED, MODEL, HAND_SETUP, HAND_RIGHT, HAND_LEFT, HAND_THIRD, FINISH }
    private static Stage stage = Stage.START;
    private static Path root;
    private static boolean initialized, setupQueued, done;
    private static volatile boolean fixtureReady, offlineReady, verified, handReady;
    private static volatile Throwable serverFailure;
    private static volatile String volume;
    private static int ticks, stageSince, nasEntry;
    private static long started, staleSession, staleRevision;
    private static String pendingScreenshot, completedScreenshot;
    private static Screen renderedScreen;
    private static CompoundTag renderedView;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.portableClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty("itemexplorer.portableClientProbe")).toAbsolutePath().normalize();
                check(root.getFileName().toString().equals("portable-terminal-client")
                        && root.getParent().getFileName().toString().equals("work"), "Refusing unguarded probe directory");
                Files.createDirectories(root);
                check(root.toRealPath().equals(mc.gameDirectory.toPath().toRealPath()), "Client directory differs from isolated root");
                Files.writeString(root.resolve("report.txt"), "Portable terminal real client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.options.hideGui = false;
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                log("START pid=" + ProcessHandle.current().pid() + " language=" + mc.options.languageCode);
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
                    check(mc.options.languageCode.equals("zh_cn"), "Real zh_cn options were not loaded");
                    openPhysical(mc); advance(Stage.PHYSICAL);
                }
                case PHYSICAL, REVOKE_PHYSICAL -> {
                    if (!storageReady(mc, true)) return;
                    check(!menu(mc).isPortable(), "Physical menu unexpectedly portable");
                    click(mc.screen, "remoteOpen"); advance(stage == Stage.PHYSICAL ? Stage.REMOTE : Stage.REVOKE_REMOTE);
                }
                case REMOTE, REVOKE_REMOTE -> {
                    if (!(mc.screen instanceof RemoteTransferScreen) || renderedScreen != mc.screen
                            || (Boolean) field(mc.screen, "pending")) return;
                    check((Boolean) field(mc.screen, "settingsPage"), "No selected item should open receiver settings");
                    click(mc.screen, "portableAccess"); advance(stage == Stage.REMOTE ? Stage.SETTINGS : Stage.REVOKE_SETTINGS);
                }
                case SETTINGS -> {
                    if (!(mc.screen instanceof PortableAccessScreen) || renderedScreen != mc.screen) return;
                    check(!menu(mc).view().getBoolean("PortableAccessEnabled"), "Fresh terminal already enabled");
                    click(mc.screen, "enabled"); advance(Stage.ENABLED);
                }
                case ENABLED -> {
                    if (!menu(mc).view().getBoolean("PortableAccessEnabled") || (Boolean) field(mc.screen, "pending")) return;
                    screenshot("portable-access-settings-zh.png"); advance(Stage.SETTINGS_SHOT);
                }
                case SETTINGS_SHOT -> {
                    if (!captured("portable-access-settings-zh.png")) return;
                    log("PASS physical portable access enabled through actual controls and server packets");
                    server(mc, () -> pairAndOpen(mc)); advance(Stage.PORTABLE);
                }
                case PORTABLE -> {
                    if (!storageReady(mc, true) || !menu(mc).isPortable()) return;
                    check(menu(mc).view().getBoolean("PortableConnected") && menu(mc).view().getInt("NasCount") == 1,
                            "Portable view missing connection or attached NAS");
                    check(!((Button) field(mc.screen, "remoteOpen")).visible, "Portable menu exposes remote settings");
                    check(!menu(mc).slots.get(27).mayPickup(mc.player), "Active handheld hotbar slot is not locked");
                    screenshot("portable-zh-normal.png"); advance(Stage.NORMAL_SHOT);
                }
                case NORMAL_SHOT -> {
                    if (!captured("portable-zh-normal.png")) return;
                    click(mc.screen, "searchOpen"); replaceText(mc.screen, "searchQuery", "铁锭"); advance(Stage.SEARCH);
                }
                case SEARCH -> {
                    if (!storageReady(mc, true) || !menu(mc).view().getBoolean("Searching")
                            || !menu(mc).view().getBoolean("SearchReady") || menu(mc).view().getInt("SearchMatches") != 1) return;
                    firstTile((StorageScreen) mc.screen); advance(Stage.SELECTED);
                }
                case SELECTED -> {
                    if (!storageReady(mc, true) || !((Button) field(mc.screen, "withdraw")).active) return;
                    replaceText(mc.screen, "quantity", "8"); click(mc.screen, "withdraw"); advance(Stage.TAKE);
                }
                case TAKE -> {
                    if (!storageReady(mc, true) || mc.player.getInventory().countItem(Items.IRON_INGOT) != 8) return;
                    check(menu(mc).view().getLong("Total") == 216, "Search withdrawal did not remove exactly eight iron ingots");
                    screenshot("portable-search-zh.png"); advance(Stage.SEARCH_SHOT);
                }
                case SEARCH_SHOT -> {
                    if (!captured("portable-search-zh.png")) return;
                    log("PASS remote item use, Chinese search, quantity withdrawal and held-item slot lock");
                    click(mc.screen, "searchExit"); advance(Stage.NAS);
                }
                case NAS -> {
                    if (!storageReady(mc, true) || menu(mc).view().getBoolean("Searching")) return;
                    request(mc, StorageNetwork.Action.SELECT_VOLUME, 0, 0, volume); advance(Stage.NAS_READY);
                }
                case NAS_READY -> {
                    if (!storageReady(mc, true) || !menu(mc).view().getString("Volume").equals(volume)) return;
                    check(menu(mc).view().getLong("Total") == 128, "NAS contents did not resolve remotely");
                    nasEntry = menu(mc).view().getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getInt("Id");
                    click(mc.screen, "defaultVolume");
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480); mc.resizeDisplay(); advance(Stage.MINIMUM);
                }
                case MINIMUM -> {
                    if (!storageReady(mc, true) || mc.screen.width != 320 || mc.screen.height != 240
                            || !menu(mc).view().getString("DefaultVolume").equals(volume)) return;
                    for (var child : mc.screen.children()) if (child instanceof AbstractWidget widget && widget.visible)
                        check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget.getWidth() <= mc.screen.width
                                && widget.getY() + widget.getHeight() <= mc.screen.height, "Visible widget leaves minimum viewport");
                    screenshot("portable-zh-minimum.png"); advance(Stage.MINIMUM_SHOT);
                }
                case MINIMUM_SHOT -> {
                    if (!captured("portable-zh-minimum.png")) return;
                    staleSession = menu(mc).view().getLong("Session"); staleRevision = menu(mc).view().getLong("Revision");
                    server(mc, () -> { station(mc.getSingleplayerServer().overworld()).setNetworkOnline(false); offlineReady = true; });
                    advance(Stage.OFFLINE);
                }
                case OFFLINE -> {
                    if (!offlineReady || !storageReady(mc, false) || menu(mc).view().getBoolean("Available")) return;
                    check(menu(mc).view().getString("PortableStatus").equals("gui.itemexplorer.portable_station_offline"),
                            "Unexpected disconnect reason: " + menu(mc).view().getString("PortableStatus"));
                    StorageNetwork.request(new StorageNetwork.Request(menu(mc).containerId, staleRevision,
                            StorageNetwork.Action.WITHDRAW, nasEntry, 0, 1, "", staleSession));
                    screenshot("portable-offline-zh.png"); advance(Stage.OFFLINE_SHOT);
                }
                case OFFLINE_SHOT -> {
                    if (!captured("portable-offline-zh.png") || ticks - stageSince < 12) return;
                    check(mc.player.getInventory().countItem(Items.REDSTONE) == 0, "Offline stale packet withdrew NAS contents");
                    server(mc, () -> station(mc.getSingleplayerServer().overworld()).setNetworkOnline(true)); advance(Stage.RECONNECT);
                }
                case RECONNECT -> {
                    if (!storageReady(mc, true)) return;
                    check(menu(mc).view().getString("Volume").equals(volume) && menu(mc).view().getLong("Total") == 128,
                            "Reconnect redirected the selected drive or consumed contents");
                    log("PASS NAS access, independent handheld default, minimum viewport, retained offline UI and stale packet rejection");
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 720); mc.resizeDisplay();
                    openPhysical(mc); advance(Stage.REVOKE_PHYSICAL);
                }
                case REVOKE_SETTINGS -> {
                    if (!(mc.screen instanceof PortableAccessScreen) || renderedScreen != mc.screen) return;
                    check(menu(mc).view().getInt("PortablePairings") == 1, "Paired device count differs from one");
                    click(mc.screen, "revoke"); click(mc.screen, "revoke"); advance(Stage.REVOKED);
                }
                case REVOKED -> {
                    if (menu(mc).view().getInt("PortablePairings") != 0 || (Boolean) field(mc.screen, "pending")) return;
                    server(mc, () -> {
                        ServerPlayer player = player(mc); ServerLevel level = mc.getSingleplayerServer().overworld();
                        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                        check(PortableAccess.openSession(player, InteractionHand.MAIN_HAND).status().equals("portable_revoked"), "Revoked handheld opened");
                        check(terminal.inventory().total() == 216 && player.getInventory().countItem(Items.IRON_INGOT) == 8,
                                "Local items were not conserved");
                        check(((NasBlockEntity) level.getBlockEntity(NAS)).volume(0).inventory().total() == 128,
                                "NAS items changed during offline request");
                        check(terminal.defaultVolume().isEmpty(), "Handheld preference changed fixed-terminal default");
                        verified = true;
                    }); advance(Stage.VERIFIED);
                }
                case VERIFIED -> {
                    if (!verified) return;
                    log("PASS revoke through actual two-click control; revoked device denied; server quantity conservation verified");
                    mc.setScreen(new ItemPreview()); advance(Stage.MODEL);
                }
                case MODEL -> {
                    if (!(mc.screen instanceof ItemPreview) || renderedScreen != mc.screen) return;
                    screenshot("portable-item-model.png"); advance(Stage.HAND_SETUP);
                }
                case HAND_SETUP -> {
                    if (!captured("portable-item-model.png")) return;
                    mc.setScreen(null); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.bobView().set(false); mc.options.fov().set(70); mc.player.setMainArm(HumanoidArm.RIGHT);
                    server(mc, () -> {
                        var player = player(mc); player.closeContainer();
                        player.teleportTo(mc.getSingleplayerServer().overworld(), 86.5, 82, 87.5, 135, 5);
                        player.setDeltaMovement(Vec3.ZERO); player.getInventory().selected = 0;
                        player.getInventory().setItem(0, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
                        player.inventoryMenu.broadcastChanges(); handReady = true;
                    });
                    advance(Stage.HAND_RIGHT);
                }
                case HAND_RIGHT -> {
                    if (!handReady || mc.screen != null || ticks - stageSince < 16) return;
                    screenshot("portable-hand-right.png"); advance(Stage.HAND_LEFT);
                }
                case HAND_LEFT -> {
                    if (!captured("portable-hand-right.png")) return;
                    mc.player.setMainArm(HumanoidArm.LEFT);
                    if (ticks - stageSince < 16) return;
                    screenshot("portable-hand-left.png"); advance(Stage.HAND_THIRD);
                }
                case HAND_THIRD -> {
                    if (!captured("portable-hand-left.png")) return;
                    mc.player.setMainArm(HumanoidArm.RIGHT); mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    if (ticks - stageSince < 16) return;
                    screenshot("portable-hand-third.png"); advance(Stage.FINISH);
                }
                case FINISH -> {
                    if (!captured("portable-hand-third.png")) return;
                    log("PASS first-person right/left hands and third-person model captured at FOV 70");
                    log("PASS all checks; screenshots=9; actual client/server packets; no OS input; ticks=" + ticks);
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8); done = true; mc.stop();
                }
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void prepare(Minecraft mc) throws Exception {
        var server = mc.getSingleplayerServer();
        check(server.getWorldPath(LevelResource.ROOT).toRealPath().equals(root.resolve("saves/Base Station Probe").toRealPath()),
                "Refusing to modify a world outside exact isolated Base Station Probe path");
        var level = server.overworld();
        for (int x = 77; x <= 94; x++) for (int z = 77; z <= 90; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.SMOOTH_STONE.defaultBlockState());
            for (int y = 82; y <= 88; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(CENTER.offset(x, 0, z), ModContent.BASE_STATION_CASING_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(CENTER.north(), ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState().setValue(BaseStationControllerBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(CENTER.south(), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, Direction.SOUTH));
        for (Direction side : List.of(Direction.EAST, Direction.WEST))
            level.setBlockAndUpdate(CENTER.relative(side), ModContent.BASE_STATION_MODULE_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, side));
        for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(CENTER.above(y), ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL)
            level.setBlockAndUpdate(CENTER.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState().setValue(BaseStationPartBlock.FACING, side));
        level.setBlockAndUpdate(CENTER.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        for (int x = 0; x <= 2; x++) level.setBlockAndUpdate(CENTER.south(2).east(x), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(NAS, ModContent.NAS_BLOCK.get().defaultBlockState());
        station(level).refreshStructure(); station(level).setNetworkOnline(true);
        check(StationConnection.inspect(level, TERMINAL, false).status() == StationConnection.Status.CONNECTED, "Fixture station disconnected");
        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        terminal.transferConfig().configure("主基地", "", 0, false, terminal.transferConfig().revision());
        terminal.inventory().createFolder(0, "建造材料"); terminal.inventory().createFolder(0, "探索收获");
        terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        terminal.inventory().insert(new ItemStack(Items.COBBLESTONE, 64), 64, 0);
        terminal.inventory().insert(new ItemStack(Items.OAK_PLANKS, 64), 64, 0);
        terminal.inventory().insert(new ItemStack(Items.GLASS, 32), 32, 0);
        var nas = (NasBlockEntity) level.getBlockEntity(NAS);
        nas.install(0, new ItemStack(ModContent.DISK_64K.get())); nas.rename(0, "建筑备料");
        for (int i = 0; i < 2; i++) nas.volume(0).inventory().insert(new ItemStack(Items.REDSTONE, 64), 64, 0);
        volume = nas.volume(0).id().toString();
        level.getEntitiesOfClass(ItemEntity.class, new AABB(76, 80, 76, 96, 90, 92)).forEach(ItemEntity::discard);
        var player = player(mc); player.setGameMode(GameType.CREATIVE); player.getInventory().clearContent(); player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
        level.setDayTime(6000); level.setWeatherParameters(6000, 0, false, false); fixtureReady = true;
    }

    private static void pairAndOpen(Minecraft mc) {
        var player = player(mc); player.closeContainer(); player.setShiftKeyDown(true);
        var stack = player.getMainHandItem();
        var context = new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.NORTH, TERMINAL, false));
        check(stack.getItem().onItemUseFirst(stack, context).consumesAction(), "Sneak-use did not handle pairing");
        player.setShiftKeyDown(false); check(PortableAccess.binding(stack) != null, "Actual item pairing did not persist a binding");
        player.teleportTo(mc.getSingleplayerServer().overworld(), TERMINAL.getX() + 48.5, 84, TERMINAL.getZ() + .5, 0, 0);
        check(player.distanceToSqr(Vec3.atCenterOf(TERMINAL)) > 64, "Probe did not move beyond physical terminal range");
        check(stack.getItem().use(player.level(), player, InteractionHand.MAIN_HAND).getResult().consumesAction(), "Actual item use failed");
        check(player.containerMenu instanceof StorageMenu menu && menu.isPortable(), "Item use did not open portable StorageMenu");
    }
    private static BaseStationBlockEntity station(ServerLevel level) { return (BaseStationBlockEntity) level.getBlockEntity(CENTER.north()); }
    private static ServerPlayer player(Minecraft mc) { return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()); }
    private interface ServerAction { void run() throws Exception; }
    private static void server(Minecraft mc, ServerAction action) {
        mc.getSingleplayerServer().execute(() -> { try { action.run(); } catch (Throwable failure) { serverFailure = failure; } });
    }
    private static void openPhysical(Minecraft mc) {
        server(mc, () -> {
            var player = player(mc); player.teleportTo(mc.getSingleplayerServer().overworld(), TERMINAL.getX() + .5, TERMINAL.getY(), TERMINAL.getZ() - 1.5, 0, 0);
            NetworkHooks.openScreen(player, (StorageBlockEntity) player.level().getBlockEntity(TERMINAL), TERMINAL);
        });
    }
    private static StorageMenu menu(Minecraft mc) { return (StorageMenu) mc.player.containerMenu; }
    private static boolean storageReady(Minecraft mc, boolean online) {
        return mc.screen instanceof StorageScreen && renderedScreen == mc.screen && mc.player.containerMenu instanceof StorageMenu
                && renderedView == menu(mc).view() && menu(mc).view().contains("Session")
                && (!online || menu(mc).view().getBoolean("Available"));
    }
    private static Object field(Screen screen, String name) throws Exception {
        Field field = screen.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(screen);
    }
    private static void click(Screen screen, String name) throws Exception {
        Button button = (Button) field(screen, name); check(button.active && button.visible, "Unavailable control " + name);
        mouse(screen, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
    }
    private static void mouse(Screen screen, double x, double y) { screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0); }
    private static void firstTile(StorageScreen screen) throws Exception {
        var layout = (StorageLayout) field(screen, "layout");
        mouse(screen, (screen.width - layout.width()) / 2.0 + layout.browserX() + 20,
                (screen.height - layout.height()) / 2.0 + layout.browserY() + 12);
    }
    private static void replaceText(Screen screen, String name, String value) throws Exception {
        EditBox input = (EditBox) field(screen, name); mouse(screen, input.getX() + 5, input.getY() + 7);
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0); int length = input.getValue().length();
        for (int i = 0; i < length; i++) screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        for (int i = 0; i < value.length(); i++) screen.charTyped(value.charAt(i), 0);
        check(input.getValue().equals(value), "Text entry failed for " + name);
    }
    private static void request(Minecraft mc, StorageNetwork.Action action, int id, long amount, String text) {
        CompoundTag view = menu(mc).view(); StorageNetwork.request(new StorageNetwork.Request(menu(mc).containerId,
                view.getLong("Revision"), action, id, 0, amount, text, view.getLong("Session")));
    }
    private static final class ItemPreview extends Screen {
        private ItemPreview() { super(Component.translatable("item.itemexplorer.portable_terminal")); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xff17232c);
            graphics.drawCenteredString(font, title, width / 2, height / 2 - 112, 0xffdce8ec);
            graphics.pose().pushPose(); graphics.pose().translate(width / 2f - 64, height / 2f - 64, 0); graphics.pose().scale(8, 8, 8);
            graphics.renderItem(new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()), 0, 0); graphics.pose().popPose();
            graphics.drawCenteredString(font, "游戏内物品模型 · GUI 渲染", width / 2, height / 2 + 96, 0xff96adb8);
        }
        @Override public boolean isPauseScreen() { return false; }
    }
    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty("itemexplorer.portableClientProbe") == null || done) return;
        renderedScreen = event.getScreen();
        if (renderedScreen instanceof StorageScreen screen) renderedView = screen.getMenu().view();
        if (pendingScreenshot == null) return;
        try {
            event.getGuiGraphics().flush();
            try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(root.resolve(pendingScreenshot).toFile()); }
            completedScreenshot = pendingScreenshot; pendingScreenshot = null; log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(Minecraft.getInstance(), failure); }
    }
    @SubscribeEvent public static void worldRendered(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (System.getProperty("itemexplorer.portableClientProbe") == null || done || mc.screen != null || pendingScreenshot == null) return;
        try {
            event.getGuiGraphics().flush();
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(root.resolve(pendingScreenshot).toFile()); }
            completedScreenshot = pendingScreenshot; pendingScreenshot = null; log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(mc, failure); }
    }
    private static void screenshot(String name) { pendingScreenshot = name; completedScreenshot = null; }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(Stage next) { stage = next; stageSince = ticks; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String message) throws Exception { Files.writeString(root.resolve("report.txt"), message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); log("FAIL stage=" + stage + " ticks=" + ticks + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL " + stage + " " + failure + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
