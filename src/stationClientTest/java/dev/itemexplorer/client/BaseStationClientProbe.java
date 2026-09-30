package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.station.BaseStationStructure;
import dev.itemexplorer.station.StationConnection;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
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
import java.util.ArrayList;
import java.util.List;

/** Real baked-model and networked-screen acceptance in one guarded disposable world, with no OS input. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class BaseStationClientProbe {
    private static final BlockPos CENTER = new BlockPos(80, 82, 80);
    private static final BlockPos CONTROLLER = CENTER.north();
    private static final BlockPos INCOMPLETE_CENTER = CENTER.east(6);
    private static final BlockPos CAP = CENTER.above(4);
    private static final BlockPos WIRE = CENTER.south(2);
    private static final BlockPos TERMINAL = CENTER.south(2).east(3);
    private static final BlockPos NAS_WIRE = TERMINAL.east();
    private static final BlockPos NAS_A = TERMINAL.east(3), NAS_B = TERMINAL.east(2).south(3);
    private static String selectedDisk;
    private static boolean initialized, setupQueued, done, worldRendered;
    private static volatile boolean fixtureReady;
    private static volatile Throwable fixtureFailure;
    private static int ticks, stage, stageSince, worldFrames;
    private static long started;
    private static String pendingScreenshot, completedScreenshot;
    private static boolean pendingWorldScreenshot, renderedGuide;
    private static BaseStationScreen renderedScreen;
    private static StorageScreen renderedStorage;
    private static Path root;

    private BaseStationClientProbe() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.stationClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true;
                started = System.currentTimeMillis();
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                root = Path.of(System.getProperty("itemexplorer.stationClientProbe")).toAbsolutePath().normalize();
                check(root.getFileName().toString().equals("base-station-client")
                                && root.getParent().getFileName().toString().equals("work"),
                        "Refusing a probe root outside work/base-station-client");
                Files.createDirectories(root);
                check(root.toRealPath().equals(mc.gameDirectory.toPath().toRealPath()), "Client run directory differs from the isolated probe root");
                Files.writeString(root.resolve("report.txt"), "Base station real client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false;
                mc.options.guiScale().set(2);
                mc.options.fov().set(60);
                mc.options.gamma().set(1.0);
                mc.options.hideGui = true;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                log("START pid=" + ProcessHandle.current().pid() + " language=" + mc.options.languageCode);
            }
            ticks++;
            if (ticks > 2400 || System.currentTimeMillis() - started > 120_000)
                throw new IllegalStateException("Timed out after " + ticks + " ticks at stage " + stage);
            if (fixtureFailure != null) throw new IllegalStateException("Server fixture failed", fixtureFailure);
            if (!setupQueued && mc.getSingleplayerServer() != null && mc.player != null) {
                setupQueued = true;
                var server = mc.getSingleplayerServer();
                var playerId = mc.player.getUUID();
                server.execute(() -> {
                    try {
                        Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
                        Path expected = root.resolve("saves").resolve("Base Station Probe").toRealPath();
                        check(actual.equals(expected), "Refusing to change a world outside the exact isolated Base Station Probe directory: " + actual);
                        var player = server.getPlayerList().getPlayer(playerId);
                        check(player != null, "Integrated-server player missing");
                        var level = server.overworld();
                        // All edits are after the exact real-path check and bounded to this disposable scene.
                        for (int x = 73; x <= 91; x++) for (int z = 72; z <= 87; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, 81, z), ((x + z) & 1) == 0
                                    ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
                            for (int y = 82; y <= 90; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                            level.setBlockAndUpdate(new BlockPos(x, 89, z), Blocks.SEA_LANTERN.defaultBlockState());
                        }
                        level.getEntitiesOfClass(ItemEntity.class, new AABB(72, 80, 71, 93, 92, 89)).forEach(ItemEntity::discard);
                        assemble(level, CENTER);
                        assemble(level, INCOMPLETE_CENTER);
                        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
                        List<BlockPos> cables = new ArrayList<>();
                        cables.add(WIRE);
                        for (int x = 0; x <= 3; x++) cables.add(WIRE.above().east(x));
                        // Branch and loop demonstrate the shared cable geometry, not just a straight run.
                        cables.add(WIRE.above(2)); cables.add(WIRE.above(2).east());
                        for (BlockPos p : cables) level.setBlockAndUpdate(p, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
                        for (BlockPos p : cables) {
                            BlockState wire = level.getBlockState(p);
                            for (Direction face : Direction.values()) wire = wire.updateShape(face, level.getBlockState(p.relative(face)), level, p, p.relative(face));
                            level.setBlockAndUpdate(p, wire);
                        }
                        List<BlockPos> nasCables = List.of(NAS_WIRE, TERMINAL.east(2), TERMINAL.east(2).south(), TERMINAL.east(2).south(2));
                        level.setBlockAndUpdate(NAS_A, ModContent.NAS_BLOCK.get().defaultBlockState());
                        level.setBlockAndUpdate(NAS_B, ModContent.NAS_BLOCK.get().defaultBlockState());
                        for (BlockPos p : nasCables) level.setBlockAndUpdate(p, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
                        for (BlockPos p : nasCables) {
                            BlockState state = level.getBlockState(p);
                            for (Direction face : Direction.values()) state = state.updateShape(face, level.getBlockState(p.relative(face)), level, p, p.relative(face));
                            level.setBlockAndUpdate(p,state);
                        }
                        var nasA = (NasBlockEntity) level.getBlockEntity(NAS_A);
                        var nasB = (NasBlockEntity) level.getBlockEntity(NAS_B);
                        for (int i = 0; i < 4; i++) { nasA.install(i,new ItemStack(ModContent.DISK_64K.get())); nasA.rename(i,"材料盘 " + (i+1)); }
                        for (int i = 0; i < 2; i++) { nasB.install(i,new ItemStack(ModContent.DISK_256K.get())); nasB.rename(i,"工程盘 " + (i+1)); }
                        nasA.volume(0).inventory().insert(new ItemStack(Items.IRON_INGOT,64),64,0);
                        level.setBlockAndUpdate(INCOMPLETE_CENTER.above(4), Blocks.AIR.defaultBlockState());
                        // An adjacent solid block exposes incorrect full-cube occlusion around the thin mast.
                        level.setBlockAndUpdate(INCOMPLETE_CENTER.west().above(), Blocks.STONE.defaultBlockState());
                        check(((BaseStationBlockEntity) level.getBlockEntity(CONTROLLER)).validation().complete(), "Complete fixture did not form");
                        check(((BaseStationBlockEntity) level.getBlockEntity(INCOMPLETE_CENTER.north())).validation().matched() == 16,
                                "Incomplete fixture did not retain precisely sixteen parts");
                        level.setDayTime(6000);
                        level.setWeatherParameters(6000, 0, false, false);
                        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                        player.setGameMode(GameType.CREATIVE);
                        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 12000, 0, false, false));
                        player.getInventory().clearContent();
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                        // Keep the camera inside the cleared scene, not inside surrounding terrain.
                        player.teleportTo(level, 76.5, 86, 85.5, -128F, 25F);
                        fixtureReady = true;
                    } catch (Throwable failure) { fixtureFailure = failure; }
                });
            }
            if (!fixtureReady || mc.player == null || mc.level == null) return;
            switch (stage) {
                case 0 -> {
                    if (mc.screen != null || !mc.level.getBlockState(CONTROLLER).is(ModContent.BASE_STATION_CONTROLLER_BLOCK.get())
                            || !mc.level.getBlockState(CONTROLLER).getValue(BaseStationControllerBlock.FORMED)
                            || !mc.level.getBlockState(CAP).is(ModContent.BASE_STATION_CAP_BLOCK.get()) || worldFrames < 60) return;
                    check(mc.options.languageCode.equals("zh_cn"), "Real zh_cn options were not loaded");
                    check(mc.level.getBlockState(mc.gameRenderer.getMainCamera().getBlockPosition()).isAir(),
                            "Scene camera is inside a block rather than viewing the station");
                    verifyBakedModels(mc);
                    log("PASS isolated scene: complete 3x3x5 / 17 parts, second 16-part tower, adjacent solid block; frames=" + worldFrames);
                    screenshot("station-models.png", true);
                    advance(1);
                }
                case 1 -> {
                    if (!captured("station-models.png")) return;
                    mc.options.hideGui = false;
                    var server = mc.getSingleplayerServer();
                    var playerId = mc.player.getUUID();
                    server.execute(() -> {
                        try {
                            var player = server.getPlayerList().getPlayer(playerId);
                            player.getAbilities().flying = false;
                            player.onUpdateAbilities();
                            player.teleportTo(server.overworld(), 80.5, 82, 77.5, 0, 0);
                            var station = (BaseStationBlockEntity) server.overworld().getBlockEntity(CONTROLLER);
                            NetworkHooks.openScreen(player, station, CONTROLLER);
                        } catch (Throwable failure) { fixtureFailure = failure; }
                    });
                    advance(2);
                }
                case 2 -> {
                    if (!(mc.screen instanceof BaseStationScreen screen) || !(mc.player.containerMenu instanceof BaseStationMenu menu)
                            || renderedScreen != screen || !menu.ready() || !menu.complete() || menu.matched() != 17
                            || menu.cable().status() != StationConnection.Status.CONNECTED) return;
                    check(menu.cable().terminals() == 1 && menu.cable().cables() == 7, "Station did not receive real cable counts");
                    check(menu.issues().isEmpty() && menu.position().equals(CONTROLLER), "Complete menu data did not cross the real server/client connection");
                    check(!renderedGuide, "Inspector unexpectedly opened on construction guide");
                    log("PASS complete inspector: real ContainerData 17/17, zero issues; gui=" + screen.width + "x" + screen.height);
                    screenshot("station-complete.png", false);
                    advance(3);
                }
                case 3 -> {
                    if (!captured("station-complete.png")) return;
                    var server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        try { server.overworld().setBlockAndUpdate(CAP, Blocks.AIR.defaultBlockState()); }
                        catch (Throwable failure) { fixtureFailure = failure; }
                    });
                    advance(4);
                }
                case 4 -> {
                    if (!(mc.screen instanceof BaseStationScreen screen) || !(mc.player.containerMenu instanceof BaseStationMenu menu)
                            || renderedScreen != screen || !menu.ready() || menu.matched() != 16 || menu.issues().size() != 1
                            || mc.level.getBlockState(CONTROLLER).getValue(BaseStationControllerBlock.FORMED)
                            || menu.cable().status() != StationConnection.Status.INCOMPLETE) return;
                    var issue = menu.issues().get(0);
                    check(!menu.complete() && issue.expectedPart() == BaseStationStructure.Part.CAP
                                    && issue.reason() == BaseStationStructure.Reason.MISSING && issue.pos().equals(CAP),
                            "Missing cap coordinates/reason did not synchronize to the real screen");
                    log("PASS live inspector update without reopening: 16/17, missing cap at " + CAP);
                    screenshot("station-incomplete.png", false);
                    advance(5);
                }
                case 5 -> {
                    if (!captured("station-incomplete.png") || !(mc.screen instanceof BaseStationScreen screen)) return;
                    String guideText = Component.translatable("gui.itemexplorer.station_guide").getString();
                    Button toggle = screen.children().stream().filter(child -> child instanceof Button button
                                    && button.getMessage().getString().equals(guideText)).map(child -> (Button) child).findFirst().orElseThrow();
                    screen.mouseClicked(toggle.getX() + 5, toggle.getY() + 5, 0);
                    screen.mouseReleased(toggle.getX() + 5, toggle.getY() + 5, 0);
                    check(guide(screen), "Construction guide button did not react to screen mouse input");
                    advance(6);
                }
                case 6 -> {
                    if (!(mc.screen instanceof BaseStationScreen screen) || renderedScreen != screen || !renderedGuide) return;
                    log("PASS rendered construction guide toggled through its real button");
                    screenshot("station-guide.png", false);
                    advance(7);
                }
                case 7 -> {
                    if (!captured("station-guide.png")) return;
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480);
                    mc.resizeDisplay();
                    advance(8);
                }
                case 8 -> {
                    if (!(mc.screen instanceof BaseStationScreen screen) || screen.width != 320 || screen.height != 240
                            || renderedScreen != screen || !renderedGuide || ticks - stageSince < 5) return;
                    log("PASS construction guide at minimum 640x480 window / 320x240 GUI");
                    screenshot("station-guide-minimum.png", false);
                    advance(9);
                }
                case 9 -> {
                    if (!captured("station-guide-minimum.png")) return;
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 720);
                    mc.resizeDisplay();
                    var server = mc.getSingleplayerServer();
                    var playerId = mc.player.getUUID();
                    server.execute(() -> {
                        try {
                            server.overworld().setBlockAndUpdate(CAP, ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
                            var player = server.getPlayerList().getPlayer(playerId);
                            player.teleportTo(server.overworld(), 83.5, 82, 80.5, 0, 0);
                            NetworkHooks.openScreen(player, (StorageBlockEntity) server.overworld().getBlockEntity(TERMINAL), TERMINAL);
                        } catch (Throwable failure) { fixtureFailure = failure; }
                    });
                    advance(10);
                }
                case 10 -> {
                    if (!(mc.screen instanceof StorageScreen screen) || renderedStorage != screen
                            || !(mc.player.containerMenu instanceof StorageMenu menu)
                            || menu.cable().status() != StationConnection.Status.CONNECTED || !menu.view().contains("Revision")) return;
                    check(menu.cable().controller().equals(CONTROLLER), "Terminal controller coordinates did not synchronize");
                    if (menu.view().getInt("NasCount") != 2 || menu.view().getList("Volumes",Tag.TAG_COMPOUND).size() != 7) return;
                    check(menu.view().getInt("WiredNasCount") == 2, "Remote cabinets were not discovered through side wiring");
                    log("PASS terminal wired status, controller coordinates, two remote NAS and all six drives over side wiring");
                    screenshot("cable-terminal-connected.png", false); advance(11);
                }
                case 11 -> {
                    if (!captured("cable-terminal-connected.png")) return;
                    mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(WIRE, Blocks.AIR.defaultBlockState()));
                    advance(12);
                }
                case 12 -> {
                    if (!(mc.player.containerMenu instanceof StorageMenu menu) || menu.cable().status() != StationConnection.Status.NO_STATION) return;
                    check(menu.view().getInt("NasCount") == 2, "Cutting only the station wire removed independent local NAS");
                    log("PASS terminal updates to no station after cable break without reopening");
                    screenshot("cable-terminal-disconnected.png", false); advance(13);
                }
                case 13 -> {
                    if (!captured("cable-terminal-disconnected.png")) return;
                    mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(WIRE, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState()));
                    advance(14);
                }
                case 14 -> {
                    if (!(mc.player.containerMenu instanceof StorageMenu menu) || menu.cable().status() != StationConnection.Status.CONNECTED) return;
                    for (Tag value : menu.view().getList("Volumes",Tag.TAG_COMPOUND)) {
                        var drive = (CompoundTag) value;
                        if (drive.getInt("NasX") == NAS_A.getX() && drive.getInt("Bay") == 1) selectedDisk = drive.getString("Id");
                    }
                    check(selectedDisk != null, "Cannot find the remote material drive");
                    StorageNetwork.request(new StorageNetwork.Request(menu.containerId,menu.view().getLong("Revision"),
                            StorageNetwork.Action.SELECT_VOLUME,0,0,0,selectedDisk,menu.view().getLong("Session")));
                    advance(15);
                }
                case 15 -> {
                    if (!(mc.player.containerMenu instanceof StorageMenu menu) || !menu.view().getString("Volume").equals(selectedDisk)
                            || !menu.view().getBoolean("Available") || menu.view().getLong("Total") != 64) return;
                    int entry = menu.view().getList("Entries",Tag.TAG_COMPOUND).getCompound(0).getInt("Id");
                    StorageNetwork.request(new StorageNetwork.Request(menu.containerId,menu.view().getLong("Revision"),
                            StorageNetwork.Action.WITHDRAW,entry,0,16,"",menu.view().getLong("Session")));
                    advance(16);
                }
                case 16 -> {
                    if (!(mc.player.containerMenu instanceof StorageMenu menu) || menu.view().getLong("Total") != 48) return;
                    int iron = 0;
                    for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(Items.IRON_INGOT)) iron += mc.player.getInventory().getItem(i).getCount();
                    if (iron != 16) return;
                    log("PASS real remote-drive selection and withdrawal: 64 -> 48 stored, 16 in player inventory");
                    screenshot("wired-nas-selected.png", false); advance(17);
                }
                case 17 -> {
                    if (!captured("wired-nas-selected.png")) return;
                    mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(NAS_WIRE,Blocks.AIR.defaultBlockState()));
                    advance(18);
                }
                case 18 -> {
                    if (!(mc.player.containerMenu instanceof StorageMenu menu) || menu.view().getBoolean("Available") || menu.view().getInt("NasCount") != 0) return;
                    check(menu.view().getString("Volume").equals(selectedDisk), "Disconnected drive silently fell back to local storage");
                    log("PASS open terminal reports disconnected remote drive without silently selecting local storage");
                    screenshot("wired-nas-offline.png", false); advance(19);
                }
                case 19 -> {
                    if (!captured("wired-nas-offline.png")) return;
                    mc.getSingleplayerServer().execute(() -> {
                        var level=mc.getSingleplayerServer().overworld(); var state=ModContent.DATA_CABLE_BLOCK.get().defaultBlockState();
                        for (Direction face : Direction.values()) state=state.updateShape(face,level.getBlockState(NAS_WIRE.relative(face)),level,NAS_WIRE,NAS_WIRE.relative(face));
                        level.setBlockAndUpdate(NAS_WIRE,state);
                    });
                    advance(20);
                }
                case 20 -> {
                    if (!(mc.player.containerMenu instanceof StorageMenu menu) || !menu.view().getBoolean("Available")
                            || !menu.view().getString("Volume").equals(selectedDisk) || menu.view().getLong("Total") != 48) return;
                    screenshot("wired-nas-restored.png", false); advance(21);
                }
                case 21 -> {
                    if (!captured("wired-nas-restored.png")) return;
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 640, 480);
                    mc.resizeDisplay();
                    advance(22);
                }
                case 22 -> {
                    if (ticks - stageSince < 8 || !(mc.screen instanceof StorageScreen screen) || renderedStorage != screen) return;
                    check(screen.width == 320 && screen.height == 240, "Terminal minimum window size did not apply");
                    screenshot("cable-nas-minimum.png", false); advance(23);
                }
                case 23 -> {
                    if (!captured("cable-nas-minimum.png")) return;
                    log("PASS local NAS reconnect and minimum-window terminal; all checks; screenshots=11; real models + integrated server + storage requests; ticks=" + ticks);
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8);
                    done = true;
                    mc.stop();
                }
                default -> throw new IllegalStateException("Unknown stage " + stage);
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void assemble(net.minecraft.server.level.ServerLevel level, BlockPos center) {
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

    private static void verifyBakedModels(Minecraft mc) throws Exception {
        List<Block> parts = List.of(ModContent.BASE_STATION_CASING_BLOCK.get(), ModContent.BASE_STATION_CONTROLLER_BLOCK.get(),
                ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get(), ModContent.BASE_STATION_MODULE_BLOCK.get(),
                ModContent.BASE_STATION_MAST_BLOCK.get(), ModContent.BASE_STATION_ANTENNA_BLOCK.get(), ModContent.BASE_STATION_CAP_BLOCK.get(),
                ModContent.DATA_CABLE_BLOCK.get(), ModContent.STORAGE_BLOCK.get());
        int states = 0;
        for (Block part : parts) {
            for (BlockState state : part.getStateDefinition().getPossibleStates()) {
                BakedModel model = mc.getBlockRenderer().getBlockModel(state);
                checkModel(mc, model, state, state.toString());
                states++;
            }
            BakedModel item = mc.getItemRenderer().getModel(new ItemStack(part.asItem()), mc.level, mc.player, 0);
            checkModel(mc, item, null, "item " + part);
        }
        log("PASS baked JSON models: " + states + " block states + " + parts.size() + " item models; all quads have real texture atlas sprites");
    }

    private static void checkModel(Minecraft mc, BakedModel model, BlockState state, String label) {
        check(model != mc.getModelManager().getMissingModel(), "Missing baked model: " + label);
        check(!model.getParticleIcon().contents().name().equals(MissingTextureAtlasSprite.getLocation()), "Missing particle texture: " + label);
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(state, null, RandomSource.create(0)));
        for (Direction face : Direction.values()) quads.addAll(model.getQuads(state, face, RandomSource.create(0)));
        check(!quads.isEmpty(), "Empty baked model: " + label);
        for (BakedQuad quad : quads)
            check(!quad.getSprite().contents().name().equals(MissingTextureAtlasSprite.getLocation()), "Missing face texture: " + label);
    }

    @SubscribeEvent public static void levelRendered(RenderLevelStageEvent event) {
        if (done || !fixtureReady || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        worldRendered = true;
    }

    @SubscribeEvent public static void renderEnded(TickEvent.RenderTickEvent event) {
        if (done || !initialized || event.phase != TickEvent.Phase.END || !worldRendered) return;
        worldRendered = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            worldFrames++;
            if (pendingScreenshot != null && pendingWorldScreenshot) capture(mc);
        }
    }

    @SubscribeEvent public static void screenRendered(ScreenEvent.Render.Post event) {
        if (!done && initialized && event.getScreen() instanceof StorageScreen screen) {
            renderedStorage = screen;
            if (pendingScreenshot != null && !pendingWorldScreenshot) {
                // Exercise the actual hover rendering without sending operating-system mouse input.
                var layout = StorageLayout.fit(screen.width, screen.height);
                if (pendingScreenshot.startsWith("cable-")) screen.render(event.getGuiGraphics(), (screen.width - layout.width()) / 2 + 12,
                        (screen.height - layout.height()) / 2 + 12, 0);
                event.getGuiGraphics().flush();
                capture(Minecraft.getInstance());
            }
            return;
        }
        if (done || !initialized || !(event.getScreen() instanceof BaseStationScreen screen)) return;
        try {
            renderedScreen = screen;
            renderedGuide = guide(screen);
            if (pendingScreenshot != null && !pendingWorldScreenshot) {
                event.getGuiGraphics().flush();
                capture(Minecraft.getInstance());
            }
        } catch (Throwable failure) { fail(Minecraft.getInstance(), failure); }
    }

    private static boolean guide(BaseStationScreen screen) throws Exception {
        Field field = BaseStationScreen.class.getDeclaredField("guide");
        field.setAccessible(true);
        return field.getBoolean(screen);
    }

    private static void capture(Minecraft mc) {
        try {
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(root.resolve(pendingScreenshot).toFile());
            }
            completedScreenshot = pendingScreenshot;
            pendingScreenshot = null;
            log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void screenshot(String name, boolean world) {
        pendingScreenshot = name;
        pendingWorldScreenshot = world;
        completedScreenshot = null;
    }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(int next) { stage = next; stageSince = ticks; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String message) throws Exception {
        Files.writeString(root.resolve("report.txt"), message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            log("FAIL stage=" + stage + " ticks=" + ticks + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL stage=" + stage + " " + failure + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
