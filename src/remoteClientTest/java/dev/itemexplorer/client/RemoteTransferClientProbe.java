package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.station.StationConnection;
import dev.itemexplorer.transfer.RemoteTransfers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
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
import java.util.UUID;

/** Real transport and renderer acceptance in one exact, disposable world; no OS input. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class RemoteTransferClientProbe {
    private static final BlockPos CENTER_A = new BlockPos(80, 82, 80), CENTER_B = CENTER_A.east(8);
    private static final BlockPos SOURCE = CENTER_A.south(2).east(3), TARGET = CENTER_B.south(2).east(3);
    private enum Stage {
        START, STATION_A, NETWORK_A, STATION_A_SHOT, STATION_B, NETWORK_B,
        RECEIVER, RECEIVER_FOLDER, RECEIVER_REMOTE, RECEIVER_NAME, RECEIVER_BIND, RECEIVER_ENABLE,
        RECEIVER_SHOT, SOURCE, SOURCE_CURSOR, SOURCE_CHOOSE, SOURCE_PAGE_NEXT, SOURCE_PAGE_BACK,
        SOURCE_REMOTE, SOURCE_SETTINGS, SOURCE_SETTINGS_BACK,
        SENT, NORMAL_SHOT, MINIMUM, MINIMUM_SHOT,
        OFFLINE, REJECTED, REJECTED_SHOT, RETURN_PARENT, RETURN_ITEMS, VERIFIED
    }
    private static Stage stage = Stage.START;
    private static boolean initialized, setupQueued, done;
    private static volatile boolean fixtureReady, offlineReady, finalVerified;
    private static volatile Throwable serverFailure;
    private static volatile int inbox;
    private static int ticks, stageSince;
    private static long started, sourceSession, targetRevision;
    private static UUID targetIdentity, paginationTarget;
    private static int sourceEntry;
    private static String pendingScreenshot, completedScreenshot;
    private static Screen renderedScreen;
    private static CompoundTag rejectionView;
    private static Path root;

    private RemoteTransferClientProbe() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.remoteClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty("itemexplorer.remoteClientProbe")).toAbsolutePath().normalize();
                check(root.getFileName().toString().equals("remote-transfer-client")
                        && root.getParent().getFileName().toString().equals("work"), "Refusing an unguarded probe directory");
                Files.createDirectories(root);
                check(root.toRealPath().equals(mc.gameDirectory.toPath().toRealPath()), "Client directory differs from isolated probe root");
                Files.writeString(root.resolve("report.txt"), "Remote transfer real client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.options.hideGui = false;
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                log("START pid=" + ProcessHandle.current().pid() + " language=" + mc.options.languageCode);
            }
            ticks++;
            if (ticks > 2400 || System.currentTimeMillis() - started > 120_000)
                throw new IllegalStateException("Timed out at " + stage + " after " + ticks + " ticks");
            if (serverFailure != null) throw new IllegalStateException("Server probe failed", serverFailure);
            if (!setupQueued && mc.getSingleplayerServer() != null && mc.player != null) {
                setupQueued = true;
                server(mc, () -> prepare(mc));
            }
            if (!fixtureReady || mc.player == null || mc.level == null) return;
            if (ticks - stageSince < 3) return;
            switch (stage) {
                case START -> {
                    check(mc.options.languageCode.equals("zh_cn"), "Real zh_cn options were not loaded");
                    open(mc, CENTER_A.north()); advance(Stage.STATION_A);
                }
                case STATION_A, STATION_B -> {
                    if (!(mc.screen instanceof BaseStationScreen screen) || renderedScreen != screen
                            || !screen.getMenu().complete() || !screen.getMenu().networkView().contains("Session")) return;
                    check(!screen.getMenu().networkOnline(), "New station unexpectedly joined network");
                    click(screen, "network");
                    advance(stage == Stage.STATION_A ? Stage.NETWORK_A : Stage.NETWORK_B);
                }
                case NETWORK_A -> {
                    if (!(mc.player.containerMenu instanceof BaseStationMenu menu) || !menu.networkOnline()) return;
                    screenshot("station-network-on.png"); advance(Stage.STATION_A_SHOT);
                }
                case STATION_A_SHOT -> {
                    if (!captured("station-network-on.png")) return;
                    open(mc, CENTER_B.north()); advance(Stage.STATION_B);
                }
                case NETWORK_B -> {
                    if (!(mc.player.containerMenu instanceof BaseStationMenu menu) || !menu.networkOnline()) return;
                    log("PASS both network switches enabled through real station buttons and packets");
                    open(mc, TARGET); advance(Stage.RECEIVER);
                }
                case RECEIVER -> {
                    if (!(mc.screen instanceof StorageScreen screen) || !storageReady(mc, screen)) return;
                    clickFirstTile(screen); advance(Stage.RECEIVER_FOLDER);
                }
                case RECEIVER_FOLDER -> {
                    if (!(mc.screen instanceof StorageScreen screen) || !storageReady(mc, screen)
                            || menu(mc).view().getInt("Current") != inbox) return;
                    click(screen, "remoteOpen"); advance(Stage.RECEIVER_REMOTE);
                }
                case RECEIVER_REMOTE -> {
                    if (!remoteReady(mc)) return;
                    check((Boolean) field(mc.screen, "settingsPage"), "Empty source should open receiver settings directly");
                    replaceText(mc.screen, "name", "东站收件箱"); click(mc.screen, "saveName"); advance(Stage.RECEIVER_NAME);
                }
                case RECEIVER_NAME -> {
                    if (!remoteReady(mc) || !config(mc).getString("Name").equals("东站收件箱")) return;
                    click(mc.screen, "bind"); advance(Stage.RECEIVER_BIND);
                }
                case RECEIVER_BIND -> {
                    if (!remoteReady(mc) || config(mc).getInt("Folder") != inbox) return;
                    click(mc.screen, "receiving"); advance(Stage.RECEIVER_ENABLE);
                }
                case RECEIVER_ENABLE -> {
                    if (!remoteReady(mc) || !config(mc).getBoolean("Enabled")) return;
                    check(menu(mc).view().getString("Message").equals("transfer_configured"), "Missing receiver confirmation");
                    screenshot("receiver-configured.png"); advance(Stage.RECEIVER_SHOT);
                }
                case RECEIVER_SHOT -> {
                    if (!captured("receiver-configured.png")) return;
                    log("PASS named receiver, bound current inbox directory and enabled receiving through real buttons");
                    open(mc, SOURCE); advance(Stage.SOURCE);
                }
                case SOURCE -> {
                    if (!(mc.screen instanceof StorageScreen screen) || !storageReady(mc, screen)) return;
                    check(menu(mc).view().getLong("Total") == 64, "Source fixture has wrong quantity");
                    clickFirstTile(screen); clickHotbar(screen); advance(Stage.SOURCE_CURSOR);
                }
                case SOURCE_CURSOR -> {
                    if (!(mc.screen instanceof StorageScreen screen) || !storageReady(mc, screen)) return;
                    check(menu(mc).getCarried().is(Items.DIAMOND) && menu(mc).getCarried().getCount() == 3,
                            "Real slot click did not put three diamonds on cursor");
                    sourceSession = menu(mc).view().getLong("Session");
                    click(screen, "remoteOpen"); advance(Stage.SOURCE_CHOOSE);
                }
                case SOURCE_CHOOSE -> {
                    if (!remoteReady(mc) || menu(mc).view().getList("RemoteTargets", net.minecraft.nbt.Tag.TAG_COMPOUND).size() != 5) return;
                    check(!((Button) field(mc.screen, "send")).active, "Destination should require explicit selection");
                    check(!(Boolean) field(mc.screen, "settingsPage"), "Selected item should open the sending page");
                    List<CompoundTag> targets = targetList(mc.screen);
                    if (!targetRendered(mc.screen, 0)) return;
                    clickWidget(mc.screen, ((Button[]) field(mc.screen, "targetButtons"))[0]);
                    paginationTarget = (UUID) field(mc.screen, "selectedTarget");
                    check(paginationTarget.equals(targets.get(0).getUUID("Id")), "First visible target did not select the correct identity");
                    click(mc.screen, "nextTargets");
                    advance(Stage.SOURCE_PAGE_NEXT);
                }
                case SOURCE_PAGE_NEXT -> {
                    if (!remoteReady(mc)) return;
                    check(paginationTarget.equals(field(mc.screen, "selectedTarget")), "Pagination silently changed selected identity");
                    click(mc.screen, "previousTargets");
                    advance(Stage.SOURCE_PAGE_BACK);
                }
                case SOURCE_PAGE_BACK -> {
                    if (!remoteReady(mc)) return;
                    check(paginationTarget.equals(field(mc.screen, "selectedTarget")), "Returning page changed selected identity");
                    if (!selectTarget(mc.screen, "东站收件箱")) return;
                    log("PASS explicit target selection and pagination preserve stable receiver identity");
                    advance(Stage.SOURCE_REMOTE);
                }
                case SOURCE_REMOTE -> {
                    if (!remoteReady(mc) || !((Button) field(mc.screen, "send")).active) return;
                    check(menu(mc).getCarried().is(Items.DIAMOND) && menu(mc).getCarried().getCount() == 3,
                            "Opening child transfer screen lost carried items");
                    var target = targetList(mc.screen).stream().filter(t -> t.getUUID("Id").equals(fieldUnchecked(mc.screen, "selectedTarget")))
                            .findFirst().orElseThrow();
                    targetIdentity = target.getUUID("Id"); targetRevision = target.getLong("Revision");
                    check(target.getString("InboxPath").equals("/远程收件"), "Receiver list does not expose the configured inbox path");
                    sourceEntry = menu(mc).view().getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getInt("Id");
                    clickWidget(mc.screen, ((Button[]) field(mc.screen, "amountButtons"))[3]);
                    check(((EditBox) field(mc.screen, "quantity")).getValue().equals("64"), "Maximum shortcut ignored source stock or batch limit");
                    clickWidget(mc.screen, ((Button[]) field(mc.screen, "amountButtons"))[1]);
                    check(((EditBox) field(mc.screen, "quantity")).getValue().equals("16"), "Sixteen shortcut did not set amount");
                    click(mc.screen, "settings"); advance(Stage.SOURCE_SETTINGS);
                }
                case SOURCE_SETTINGS -> {
                    if (!remoteReady(mc)) return;
                    check((Boolean) field(mc.screen, "settingsPage"), "Settings button did not open the independent receiver page");
                    check(menu(mc).view().getLong("Session") == sourceSession, "Settings navigation changed source session");
                    click(mc.screen, "back"); advance(Stage.SOURCE_SETTINGS_BACK);
                }
                case SOURCE_SETTINGS_BACK -> {
                    if (!remoteReady(mc)) return;
                    check(!(Boolean) field(mc.screen, "settingsPage"), "Settings back button did not return to sending");
                    check(targetIdentity.equals(field(mc.screen, "selectedTarget")), "Settings navigation lost the selected target");
                    check(((EditBox) field(mc.screen, "quantity")).getValue().equals("16"), "Settings navigation lost quantity");
                    check(!config(mc).getBoolean("Enabled"), "Sender should not have to enable receiving");
                    log("PASS quantity shortcuts and separate receiving settings preserve item, target, quantity and session");
                    click(mc.screen, "send"); advance(Stage.SENT);
                }
                case SENT -> {
                    if (!remoteReady(mc) || !menu(mc).view().getString("Message").equals("transfer_sent")) return;
                    check(menu(mc).view().getLong("Total") == 48, "Successful network transfer did not remove exactly sixteen");
                    verifySnapshotCoalescing(mc);
                    screenshot("remote-send-zh-normal.png"); advance(Stage.NORMAL_SHOT);
                }
                case NORMAL_SHOT -> {
                    if (!captured("remote-send-zh-normal.png")) return;
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480); mc.resizeDisplay(); advance(Stage.MINIMUM);
                }
                case MINIMUM -> {
                    if (!remoteReady(mc) || mc.screen.width != 320 || mc.screen.height != 240) return;
                    for (var child : mc.screen.children()) if (child instanceof AbstractWidget widget && widget.visible)
                        check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget.getWidth() <= mc.screen.width
                                && widget.getY() + widget.getHeight() <= mc.screen.height, "Minimum-window widget leaves screen");
                    check(targetIdentity.equals(field(mc.screen, "selectedTarget")), "Resize changed selected target");
                    clickWidget(mc.screen, ((Button[]) field(mc.screen, "amountButtons"))[3]);
                    check(((EditBox) field(mc.screen, "quantity")).getValue().equals("48"), "Maximum shortcut used stale stock after sending");
                    clickWidget(mc.screen, ((Button[]) field(mc.screen, "amountButtons"))[1]);
                    screenshot("remote-send-zh-minimum.png"); advance(Stage.MINIMUM_SHOT);
                }
                case MINIMUM_SHOT -> {
                    if (!captured("remote-send-zh-minimum.png")) return;
                    log("PASS real remote send; Chinese feedback rendered at 960x720 and 640x480");
                    server(mc, () -> {
                        station(mc.getSingleplayerServer().overworld(), CENTER_B).setNetworkOnline(false);
                        offlineReady = true;
                    });
                    advance(Stage.OFFLINE);
                }
                case OFFLINE -> {
                    if (!offlineReady || ticks - stageSince < 25 || !remoteReady(mc)
                            || targetList(mc.screen).stream().anyMatch(t -> t.getUUID("Id").equals(targetIdentity))) return;
                    check(!((Button) field(mc.screen, "send")).active, "Offline target still enables the send button");
                    check(field(mc.screen, "selectedTarget") == null, "Offline target was not invalidated");
                    check(targetList(mc.screen).size() == 1 && targetList(mc.screen).get(0).getBoolean("Local"),
                            "Disconnect scenario must retain a local alternative to detect silent retargeting");
                    // A stale or modified client may still send the previously advertised identity.
                    CompoundTag view = menu(mc).view();
                    rejectionView = view;
                    StorageNetwork.request(new StorageNetwork.TransferRequest(menu(mc).containerId, view.getLong("Session"),
                            view.getLong("Revision"), config(mc).getLong("ConfigRevision"), StorageNetwork.TransferAction.SEND,
                            "", view.getString("Volume"), view.getInt("Current"), false, sourceEntry, 1, targetIdentity, targetRevision));
                    advance(Stage.REJECTED);
                }
                case REJECTED -> {
                    if (!remoteReady(mc) || menu(mc).view() == rejectionView) return;
                    String rejection = menu(mc).view().getString("Message");
                    check(rejection.equals("remote_target_changed") || rejection.equals("remote_network_offline"),
                            "Stale offline target returned unexpected feedback: " + rejection);
                    check(menu(mc).view().getLong("Total") == 48, "Offline destination consumed source items");
                    log("PASS old target identity rejected after disconnect: " + rejection);
                    screenshot("remote-offline-rejected.png"); advance(Stage.REJECTED_SHOT);
                }
                case REJECTED_SHOT -> {
                    if (!captured("remote-offline-rejected.png")) return;
                    mc.screen.onClose(); advance(Stage.RETURN_PARENT);
                }
                case RETURN_PARENT -> {
                    if (!(mc.screen instanceof StorageScreen screen) || !storageReady(mc, screen)) return;
                    check(menu(mc).view().getLong("Session") == sourceSession
                                    && menu(mc).getCarried().is(Items.DIAMOND) && menu(mc).getCarried().getCount() == 3,
                            "Returning from child screen reset session or cursor items");
                    clickHotbar(screen); advance(Stage.RETURN_ITEMS);
                }
                case RETURN_ITEMS -> {
                    if (!(mc.screen instanceof StorageScreen screen) || !storageReady(mc, screen)
                            || !menu(mc).getCarried().isEmpty()) return;
                    server(mc, () -> {
                        ServerLevel level = mc.getSingleplayerServer().overworld();
                        StorageBlockEntity source = (StorageBlockEntity) level.getBlockEntity(SOURCE);
                        StorageBlockEntity target = (StorageBlockEntity) level.getBlockEntity(TARGET);
                        check(source.inventory().total() == 48 && target.inventory().total() == 16,
                                "Server inventories did not conserve all 64 items");
                        check(target.inventory().entries().size() == 1 && target.inventory().entries().get(0).folder() == inbox,
                                "Remote items missed the configured inbox directory");
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                        check(player.containerMenu instanceof StorageMenu && player.containerMenu.getCarried().isEmpty()
                                        && player.getInventory().countItem(Items.DIAMOND) == 3,
                                "Parent container no longer handles real slot clicks after child screen");
                        finalVerified = true;
                    });
                    advance(Stage.VERIFIED);
                }
                case VERIFIED -> {
                    if (!finalVerified) return;
                    log("PASS stale advertised destination rejected when remote network went offline; totals source=48,target=16");
                    log("PASS child screen preserves menu session and three cursor diamonds; returned parent accepts real slot clicks");
                    log("PASS all checks; screenshots=5; actual client/server packets; no OS input; ticks=" + ticks);
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8);
                    done = true; mc.stop();
                }
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void prepare(Minecraft mc) throws Exception {
        var server = mc.getSingleplayerServer();
        Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
        Path expected = root.resolve("saves/Base Station Probe").toRealPath();
        check(actual.equals(expected), "Refusing to change world outside exact isolated Base Station Probe path: " + actual);
        var level = server.overworld();
        for (int x = 77; x <= 94; x++) for (int z = 77; z <= 90; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.SMOOTH_STONE.defaultBlockState());
            for (int y = 82; y <= 88; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        for (BlockPos center : List.of(CENTER_A, CENTER_B)) {
            assemble(level, center);
            for (int x = 0; x <= 2; x++)
                level.setBlockAndUpdate(center.south(2).east(x), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
            BlockPos terminal = center.south(2).east(3);
            level.setBlockAndUpdate(terminal, ModContent.STORAGE_BLOCK.get().defaultBlockState());
            station(level, center).refreshStructure();
            check(station(level, center).validation().complete(), "Fixture tower incomplete");
            check(StationConnection.inspect(level, terminal, false).status() == StationConnection.Status.CONNECTED,
                    "Fixture terminal not connected to its own station");
        }
        var source = (StorageBlockEntity) level.getBlockEntity(SOURCE);
        var target = (StorageBlockEntity) level.getBlockEntity(TARGET);
        for (int x = 0; x <= 3; x++)
            level.setBlockAndUpdate(CENTER_B.south(3).east(x), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        List<BlockPos> extras = List.of(CENTER_B.south(4).east(), CENTER_B.south(4).east(2),
                CENTER_B.south(4).east(3), CENTER_A.south(3).east());
        for (int i = 0; i < extras.size(); i++) {
            BlockPos pos = extras.get(i);
            level.setBlockAndUpdate(pos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
            var extra = (StorageBlockEntity) level.getBlockEntity(pos);
            RemoteTransfers.configure(extra, "A" + (i + 1) + " 辅助收件", "", 0, true, extra.transferConfig().revision());
            check(StationConnection.inspect(level, pos, false).status() == StationConnection.Status.CONNECTED,
                    "Extra receiver is not connected to the remote base station");
        }
        source.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        inbox = target.inventory().createFolder(0, "远程收件");
        level.getEntitiesOfClass(ItemEntity.class, new AABB(76, 80, 76, 96, 90, 87)).forEach(ItemEntity::discard);
        var player = server.getPlayerList().getPlayer(mc.player.getUUID());
        check(player != null, "Integrated-server player missing");
        player.setGameMode(GameType.CREATIVE); player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        level.setDayTime(6000); level.setWeatherParameters(6000, 0, false, false);
        fixtureReady = true;
    }

    private static void assemble(ServerLevel level, BlockPos center) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(center.offset(x, 0, z), ModContent.BASE_STATION_CASING_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(center.north(), ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                .setValue(BaseStationControllerBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(center.south(), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState()
                .setValue(BaseStationPartBlock.FACING, Direction.SOUTH));
        for (Direction side : List.of(Direction.EAST, Direction.WEST))
            level.setBlockAndUpdate(center.relative(side), ModContent.BASE_STATION_MODULE_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(center.above(y), ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL)
            level.setBlockAndUpdate(center.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        level.setBlockAndUpdate(center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
    }
    private static BaseStationBlockEntity station(ServerLevel level, BlockPos center) {
        return (BaseStationBlockEntity) level.getBlockEntity(center.north());
    }
    private interface ServerAction { void run() throws Exception; }
    private static void server(Minecraft mc, ServerAction action) {
        mc.getSingleplayerServer().execute(() -> { try { action.run(); } catch (Throwable failure) { serverFailure = failure; } });
    }
    private static void open(Minecraft mc, BlockPos pos) {
        server(mc, () -> {
            var server = mc.getSingleplayerServer(); var player = server.getPlayerList().getPlayer(mc.player.getUUID());
            player.teleportTo(server.overworld(), pos.getX() + .5, pos.getY(), pos.getZ() - 1.5, 0, 0);
            NetworkHooks.openScreen(player, (MenuProvider) server.overworld().getBlockEntity(pos), pos);
        });
    }
    private static StorageMenu menu(Minecraft mc) { return (StorageMenu) mc.player.containerMenu; }
    private static CompoundTag config(Minecraft mc) { return menu(mc).view().getCompound("RemoteConfig"); }
    private static boolean storageReady(Minecraft mc, StorageScreen screen) {
        return renderedScreen == screen && mc.player.containerMenu instanceof StorageMenu menu
                && menu.view().contains("Session") && menu.view().getBoolean("Available");
    }
    private static boolean remoteReady(Minecraft mc) throws Exception {
        return mc.screen instanceof RemoteTransferScreen && renderedScreen == mc.screen
                && mc.player.containerMenu instanceof StorageMenu && !(Boolean) field(mc.screen, "pending");
    }
    private static Object field(Screen screen, String name) throws Exception {
        Field field = screen.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(screen);
    }
    /** Replays the acknowledged result locally to cover three queued updates before another render. */
    private static void verifySnapshotCoalescing(Minecraft mc) throws Exception {
        CompoundTag success = menu(mc).view().copy(), background = success.copy();
        background.putString("Message", "");
        Field pending = mc.screen.getClass().getDeclaredField("pending"); pending.setAccessible(true); pending.set(mc.screen, true);
        Field action = mc.screen.getClass().getDeclaredField("pendingAction"); action.setAccessible(true);
        action.set(mc.screen, StorageNetwork.TransferAction.SEND);
        ClientEvents.receive(new StorageNetwork.Snapshot(menu(mc).containerId, background.copy()));
        check((Boolean) field(mc.screen, "pending"), "Background stock update unlocked an in-flight send");
        ClientEvents.receive(new StorageNetwork.Snapshot(menu(mc).containerId, success));
        ClientEvents.receive(new StorageNetwork.Snapshot(menu(mc).containerId, background.copy()));
        check(!(Boolean) field(mc.screen, "pending"), "A later same-frame stock update hid the acknowledged send result");
        String message = ((Component) field(mc.screen, "feedback")).getString();
        check(message.contains("16") && message.contains("东站收件箱") && message.contains("48"),
                "Same-frame stock updates lost detailed send feedback: " + message);
        log("PASS synthetic same-frame snapshots: background does not unlock pending; acknowledged result survives a later empty-message update");
    }
    private static void click(Screen screen, String name) throws Exception {
        Button button = (Button) field(screen, name);
        check(button.active && button.visible, "Button unavailable: " + name);
        clickWidget(screen, button);
    }
    private static void clickWidget(Screen screen, Button button) {
        check(button.active && button.visible, "Button unavailable: " + button.getMessage().getString());
        mouse(screen, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
    }
    @SuppressWarnings("unchecked")
    private static List<CompoundTag> targetList(Screen screen) throws Exception {
        return (List<CompoundTag>) field(screen, "targetList");
    }
    private static Object fieldUnchecked(Screen screen, String name) {
        try { return field(screen, name); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static boolean targetRendered(Screen screen, int row) throws Exception {
        Button button = ((Button[]) field(screen, "targetButtons"))[row];
        Field displayed = button.getClass().getDeclaredField("displayedTarget"); displayed.setAccessible(true);
        CompoundTag rendered = (CompoundTag) displayed.get(button);
        int index = (int) field(screen, "targetPage") * 3 + row;
        return rendered != null && index < targetList(screen).size()
                && rendered.equals(targetList(screen).get(index));
    }
    private static boolean selectTarget(Screen screen, String name) throws Exception {
        List<CompoundTag> targets = targetList(screen);
        int index = -1;
        for (int i = 0; i < targets.size(); i++) if (targets.get(i).getString("Name").equals(name)) index = i;
        check(index >= 0, "Expected receiver not found: " + name);
        int expectedPage = index / 3;
        if ((int) field(screen, "targetPage") < expectedPage) { click(screen, "nextTargets"); return false; }
        if ((int) field(screen, "targetPage") > expectedPage) { click(screen, "previousTargets"); return false; }
        if (!targetRendered(screen, index % 3)) return false;
        clickWidget(screen, ((Button[]) field(screen, "targetButtons"))[index % 3]);
        check(targets.get(index).getUUID("Id").equals(field(screen, "selectedTarget")), "Target row selected wrong receiver");
        return true;
    }
    private static void mouse(Screen screen, double x, double y) { screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0); }
    private static void clickFirstTile(StorageScreen screen) throws Exception {
        StorageLayout layout = (StorageLayout) field(screen, "layout");
        mouse(screen, (screen.width - layout.width()) / 2.0 + layout.browserX() + 12,
                (screen.height - layout.height()) / 2.0 + layout.browserY() + 12);
    }
    private static void clickHotbar(StorageScreen screen) throws Exception {
        StorageLayout layout = (StorageLayout) field(screen, "layout");
        mouse(screen, (screen.width - layout.width()) / 2.0 + layout.inventoryX() + 8,
                (screen.height - layout.height()) / 2.0 + layout.inventoryY() + 58 + 8);
    }
    private static void replaceText(Screen screen, String name, String value) throws Exception {
        EditBox input = (EditBox) field(screen, name);
        mouse(screen, input.getX() + 5, input.getY() + 7);
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        int previousLength = input.getValue().length();
        for (int i = 0; i < previousLength; i++) screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        for (int i = 0; i < value.length(); i++) screen.charTyped(value.charAt(i), 0);
        check(input.getValue().equals(value), "Text entry failed for " + name);
    }
    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty("itemexplorer.remoteClientProbe") == null || done) return;
        renderedScreen = event.getScreen();
        if (pendingScreenshot == null) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            event.getGuiGraphics().flush();
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(root.resolve(pendingScreenshot).toFile());
            }
            completedScreenshot = pendingScreenshot; pendingScreenshot = null; log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(mc, failure); }
    }
    private static void screenshot(String name) { pendingScreenshot = name; completedScreenshot = null; }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(Stage next) { stage = next; stageSince = ticks; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String message) throws Exception {
        Files.writeString(root.resolve("report.txt"), message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            log("FAIL stage=" + stage + " ticks=" + ticks + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL " + stage + " " + failure + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
