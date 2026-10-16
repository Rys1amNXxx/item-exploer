package dev.itemexplorer.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.menu.LogisticsPortMenu;
import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class GuiResourceClientProbe {
    private static final int BACKDROP = 0xff16324e, RESULT = 0xffefdc52, ARROW = 0xffef8542;
    private static final List<String> GUI_TEXTURES = List.of("container/inventory.png", "container/crafting_table.png", "widgets.png");
    private static final List<String> SCENES = List.of("theme-surfaces", "storage-normal", "storage-minimum", "nas", "station",
            "logistics", "production", "remote-send", "remote-settings");
    private static final List<ThemeCase> cases = new ArrayList<>();
    private static final Map<String, Map<String, Integer>> baseline = new LinkedHashMap<>();
    private record ThemeCase(String name, String pack, int scale, int body, int recess, int edge,
                             boolean decorated, boolean hiddenRgb, boolean synthetic) {}
    private record Sample(String name, int x, int y, Integer expected) {}
    private record Surface(String name, int x, int y, int width, int height, Integer expected) {}
    private static Path root;
    private static List<String> originalPacks;
    private static CompletableFuture<Void> reload;
    private static Screen expectedScreen;
    private static List<Sample> samples;
    private static List<Surface> surfaces;
    private static boolean initialized, worldChecked, captured, done;
    private static int caseIndex, sceneIndex, ticks, frames;
    private static long started;

    private GuiResourceClientProbe() {}
    private static ThemeCase theme() { return cases.get(caseIndex); }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.guiClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        try {
            if (!initialized) initialize(client);
            ticks++;
            check(System.currentTimeMillis() - started < 600_000, "Timed out at " + theme().name() + "/" + sceneIndex);
            if (client.player == null || client.level == null || client.getSingleplayerServer() == null) return;
            if (!worldChecked) {
                check(client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toRealPath()
                        .equals(root.resolve("saves/Base Station Probe").toRealPath()), "Unexpected world directory");
                worldChecked = true;
            }
            if (reload != null) {
                if (!reload.isDone() || client.getOverlay() != null) return;
                reload.join(); reload = null;
                log("RELOAD complete case=" + theme().name() + " selected=" + client.getResourcePackRepository().getSelectedIds());
                for (String texture : GUI_TEXTURES) {
                    var resource = client.getResourceManager().getResource(new ResourceLocation("minecraft", "textures/gui/" + texture));
                    log("RESOURCE " + texture + " source=" + resource.map(value -> value.sourcePackId()).orElse("missing"));
                }
            }
            if (client.getOverlay() != null) return;
            if (expectedScreen == null) { showScene(client); return; }
            if (!captured) return;
            expectedScreen = null; captured = false; sceneIndex++;
            if (sceneIndex < SCENES.size()) return;
            sceneIndex = 0; caseIndex++;
            if (caseIndex == cases.size()) {
                log("PASS " + cases.size() + " resource cases, " + cases.size() * SCENES.size()
                        + " captures; solid themes, original slot art, 1x/2x/4x, alpha and reload restoration");
                Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8);
                done = true; client.stop(); return;
            }
            var selected = new ArrayList<>(originalPacks);
            if (theme().pack() != null) selected.add(theme().pack());
            client.getResourcePackRepository().reload();
            client.getResourcePackRepository().setSelected(selected);
            check(theme().pack() == null || client.getResourcePackRepository().getSelectedIds().contains(theme().pack()),
                    "Resource pack selection failed for " + theme().name());
            reload = client.reloadResourcePacks();
        } catch (Throwable failure) { fail(client, failure); }
    }

    private static void initialize(Minecraft client) throws Exception {
        initialized = true; started = System.currentTimeMillis();
        root = Path.of(System.getProperty("itemexplorer.guiClientProbe")).toAbsolutePath().normalize();
        check(root.getFileName().toString().startsWith("gui-resource-client")
                && root.getParent().getFileName().toString().equals("work"), "Refusing unguarded client directory");
        check(root.toRealPath().equals(client.gameDirectory.toPath().toRealPath()), "Client directory differs from probe root");
        Files.writeString(root.resolve("report.txt"), "GUI resource-pack real-render acceptance\n", StandardCharsets.UTF_8);
        client.options.pauseOnLostFocus = false; client.options.guiScale().set(2); client.options.hideGui = false;
        GLFW.glfwSetWindowAttrib(client.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwHideWindow(client.getWindow().getWindow());
        originalPacks = client.getResourcePackRepository().getSelectedIds().stream()
                .filter(pack -> !pack.startsWith("file/gui-probe-")).toList();
        cases.add(new ThemeCase("baseline", null, 0, 0, 0, 0, false, false, false));
        synthetic("light-1x", 1, 0xffc8c4b8, 0xff727880, 0xff484c58, false, false);
        synthetic("decorated-2x", 2, 0xffc8c4b8, 0xff727880, 0xff484c58, true, false);
        synthetic("nonperiodic-dark-4x", 4, 0xff292d3a, 0xff141925, 0xff696d7a, true, false);
        synthetic("semi-2x", 2, 0x80a0bcda, 0x80667587, 0x80545868, true, false);
        synthetic("transparent-1x", 1, 0x00f41070, 0x0047ff21, 0x00ffb436, false, true);
        synthetic("hidden-rgb-4x", 4, 0xff446680, 0xff8599a0, 0xff314455, false, true);
        synthetic("nearopaque-white-2x", 2, 0xfeffffff, 0x00ffffff, 0xff999999, false, false);
        synthetic("highlighted-dark-2x", 2, 0xff161616, 0x66ffffff, 0xff626262, false, false);
        var external = new LinkedHashSet<String>();
        String single = System.getProperty("itemexplorer.guiResourcePack");
        if (single != null && !single.isBlank()) external.add(single);
        String multiple = System.getProperty("itemexplorer.guiResourcePacks");
        if (multiple != null) for (String value : multiple.split("\\|")) if (!value.isBlank()) external.add(value);
        int externalIndex = 0;
        for (String source : external) createExternalPack(source, externalIndex++);
        cases.add(new ThemeCase("restored", null, 0, 0, 0, 0, false, false, false));
        log("START pid=" + ProcessHandle.current().pid() + " originalPacks=" + originalPacks);
        log("Client-only fixtures; source packs read-only; cases=" + cases.stream().map(ThemeCase::name).toList());
    }

    private static void synthetic(String name, int scale, int body, int recess, int edge, boolean decorated, boolean hiddenRgb) throws Exception {
        ThemeCase theme = new ThemeCase(name, "file/gui-probe-" + name, scale, body, recess, edge, decorated, hiddenRgb, true);
        cases.add(theme);
        Path pack = root.resolve("resourcepacks/gui-probe-" + name);
        Path textures = pack.resolve("assets/minecraft/textures/gui/container");
        Files.createDirectories(textures); metadata(pack, "Synthetic GUI palette and native-art regression: " + name);
        try (NativeImage inventory = new NativeImage(256 * scale, 256 * scale, false);
             NativeImage crafting = new NativeImage(256 * scale, 256 * scale, false)) {
            fill(inventory, 0, 0, 256, 256, 0xff701d31);
            fill(crafting, 0, 0, 256, 256, body);
            if (hiddenRgb) hiddenRgb(crafting, 0, 0, 256, 256, body);
            fill(crafting, 0, 0, 176, 4, edge); fill(crafting, 0, 162, 176, 4, edge);
            fill(crafting, 0, 4, 4, 158, edge); fill(crafting, 172, 4, 4, 158, edge);
            for (int slotIndex = 0; slotIndex < 9; slotIndex++) {
                int slotX = 29 + slotIndex % 3 * 18, slotY = 16 + slotIndex / 3 * 18;
                fill(crafting, slotX, slotY, 18, 18, edge);
                fill(crafting, slotX + 1, slotY + 1, 16, 16, recess);
                if (hiddenRgb) hiddenRgb(crafting, slotX + 1, slotY + 1, 16, 16, recess);
            }
            fill(crafting, 119, 30, 26, 26, RESULT);
            fill(crafting, 90, 35, 22, 15, ARROW);
            for (int slotIndex = 0; slotIndex < 36; slotIndex++) {
                int slotX = 7 + slotIndex % 9 * 18, slotY = slotIndex < 27 ? 83 + slotIndex / 9 * 18 : 141;
                fill(crafting, slotX, slotY, 18, 18, slotColor(slotIndex));
            }
            fill(crafting, 8, 84, 3, 3, 0xffff125c);
            if (decorated) {
                artwork(crafting, 84, 5, 44, 24, body >>> 24);
                artwork(crafting, 47, 34, 18, 18, recess >>> 24);
                artwork(crafting, 32, 0, 24, 4, edge >>> 24);
                artwork(crafting, 32, 162, 24, 4, edge >>> 24);
                artwork(crafting, 0, 32, 4, 24, edge >>> 24);
                artwork(crafting, 172, 32, 4, 24, edge >>> 24);
            }
            inventory.writeToFile(textures.resolve("inventory.png")); crafting.writeToFile(textures.resolve("crafting_table.png"));
        }
    }

    private static int slotColor(int index) {
        return 0xff000000 | (48 + index % 9 * 22) << 16 | (62 + index / 9 * 48) << 8 | 84 + index * 3;
    }
    private static int artColor(int textureX, int textureY, int alpha) {
        return alpha << 24 | (128 + (textureX * 31 + textureY * 7) % 128) << 16
                | (textureX * 13 + textureY * 17) % 24 << 8 | 80 + (textureX * 11 + textureY * 23) % 176;
    }
    private static void artwork(NativeImage image, int left, int top, int width, int height, int alpha) {
        for (int textureY = top; textureY < top + height; textureY++)
            for (int textureX = left; textureX < left + width; textureX++)
                fill(image, textureX, textureY, 1, 1, artColor(textureX, textureY, alpha));
    }
    private static void hiddenRgb(NativeImage image, int left, int top, int width, int height, int visible) {
        for (int textureY = top; textureY < top + height; textureY++)
            for (int textureX = left; textureX < left + width; textureX++)
                if ((visible >>> 24) == 0 || (textureX + textureY) % 4 == 0)
                    fill(image, textureX, textureY, 1, 1, artColor(textureX, textureY, 0));
    }
    private static void fill(NativeImage image, int left, int top, int width, int height, int argb) {
        int rgba = argb & 0xff00ff00 | (argb & 0xff) << 16 | argb >>> 16 & 0xff;
        int scale = image.getWidth() / 256;
        for (int pixelY = top * scale; pixelY < (top + height) * scale; pixelY++)
            for (int pixelX = left * scale; pixelX < (left + width) * scale; pixelX++) image.setPixelRGBA(pixelX, pixelY, rgba);
    }
    private static void metadata(Path pack, String description) throws Exception {
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"" + description + "\"}}\n", StandardCharsets.UTF_8);
    }

    private static void createExternalPack(String requested, int index) throws Exception {
        Path source = Path.of(requested).toAbsolutePath().normalize();
        check(Files.isDirectory(source) || Files.isRegularFile(source), "External pack does not exist: " + source);
        String name = "external-" + index;
        Path destination = root.resolve("resourcepacks/gui-probe-" + name);
        Files.createDirectories(destination); metadata(destination, "Isolated copy of supplied GUI textures");
        int copied = 0;
        try (ZipFile archive = Files.isRegularFile(source) ? new ZipFile(source.toFile()) : null) {
            for (String texture : GUI_TEXTURES) for (String suffix : List.of("", ".mcmeta")) {
                String resource = "assets/minecraft/textures/gui/" + texture + suffix;
                Path target = destination.resolve(resource);
                Files.createDirectories(target.getParent()); Files.deleteIfExists(target);
                if (archive == null) {
                    Path sourceTexture = source.resolve(resource);
                    if (Files.isRegularFile(sourceTexture)) {
                        Files.copy(sourceTexture, target, StandardCopyOption.REPLACE_EXISTING);
                        if (suffix.isEmpty()) copied++;
                    }
                } else {
                    var entry = archive.getEntry(resource);
                    if (entry != null) try (var input = archive.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                        if (suffix.isEmpty()) copied++;
                    }
                }
            }
        }
        check(copied > 0, "External pack has no expected vanilla GUI textures");
        cases.add(new ThemeCase(name, "file/gui-probe-" + name, 0, 0, 0, 0, false, false, false));
        log("EXTERNAL " + name + " read-only source=" + source + " copiedGuiTextures=" + copied);
    }

    private static void showScene(Minecraft client) throws Exception {
        String scene = SCENES.get(sceneIndex);
        boolean minimum = scene.equals("storage-minimum");
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), minimum ? 640 : 960, minimum ? 480 : 720);
        client.resizeDisplay(); samples = new ArrayList<>(); surfaces = new ArrayList<>();
        if (scene.equals("theme-surfaces")) {
            Screen screen = new SurfaceScreen(); client.setScreen(screen);
            client.player.containerMenu = client.player.inventoryMenu;
            Integer body = theme().synthetic() ? composite(theme().body(), BACKDROP) : null;
            Integer recess = theme().synthetic() ? composite(theme().recess(), BACKDROP) : null;
            Integer edge = theme().synthetic() ? composite(theme().edge(), BACKDROP) : null;
            Integer nested = theme().synthetic() ? composite(theme().recess(), composite(theme().body(), BACKDROP)) : null;
            samples.add(new Sample("body", 30, 30, body)); samples.add(new Sample("recess", 260, 40, recess));
            samples.add(new Sample("nested-recess", 32, 172, nested));
            samples.add(new Sample("nested-border-interior", 48, 188, nested));
            surfaces.add(new Surface("panel-solid-body", 25, 25, 190, 90, body));
            surfaces.add(new Surface("recess-solid-body", 250, 30, 180, 80, recess));
            surfaces.add(new Surface("nested-border-preserves-single-composite", 41, 181, 368, 78, nested));
            for (int inset = 0; inset < 4; inset++) {
                surfaces.add(new Surface("panel-top-edge-" + inset, 28, 20 + inset, 184, 1, edge));
                surfaces.add(new Surface("panel-left-edge-" + inset, 20 + inset, 28, 1, 84, edge));
                surfaces.add(new Surface("panel-bottom-edge-" + inset, 28, 119 - inset, 184, 1, edge));
                surfaces.add(new Surface("panel-right-edge-" + inset, 219 - inset, 28, 1, 84, edge));
            }
            if (theme().name().equals("nearopaque-white-2x") || theme().name().equals("highlighted-dark-2x")) {
                checkDarkNeutral(VanillaGui.recessText(0xffeeeeee, 0x66ffffff), "highlighted recess text");
                if (theme().name().equals("nearopaque-white-2x"))
                    checkDarkNeutral(VanillaGui.panelText(0xffeeeeee), "near-opaque white panel text");
                log("PASS " + theme().name() + "/readable-neutral-text-after-highlight");
            }
            ready(screen, "helper backdrop=" + Integer.toHexString(BACKDROP)); return;
        }
        var inventory = client.player.getInventory();
        AbstractContainerMenu menu;
        Screen screen;
        int panelWidth, panelHeight;
        if (scene.startsWith("storage") || scene.startsWith("remote")) {
            StorageMenu storage = new StorageMenu(71, inventory, BlockPos.ZERO, null);
            CompoundTag view = new CompoundTag();
            view.putBoolean("Available", true); view.putInt("Pages", 1); view.putLong("Capacity", 4096);
            storage.acceptView(view);
            StorageScreen parent = new StorageScreen(storage, inventory, Component.literal("GUI resource probe"));
            menu = storage;
            if (scene.startsWith("remote")) {
                screen = new RemoteTransferScreen(parent, storage, -1,
                        scene.equals("remote-settings") ? ItemStack.EMPTY : new ItemStack(Items.IRON_INGOT), 16);
                panelWidth = 390; panelHeight = 238;
            } else { screen = parent; panelWidth = minimum ? 320 : 440; panelHeight = minimum ? 234 : 340; }
        } else if (scene.equals("nas")) {
            NasMenu nas = new NasMenu(72, inventory, BlockPos.ZERO, null);
            menu = nas; screen = new NasScreen(nas, inventory, Component.literal("NAS")); panelWidth = 284; panelHeight = 236;
        } else if (scene.equals("station")) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            BaseStationMenu station;
            try { buffer.writeBlockPos(BlockPos.ZERO); station = new BaseStationMenu(73, inventory, buffer); }
            finally { buffer.release(); }
            station.setData(0, 1); menu = station;
            screen = new BaseStationScreen(station, inventory, Component.literal("Base station")); panelWidth = 310; panelHeight = 230;
        } else if (scene.equals("logistics")) {
            LogisticsPortMenu logistics = new LogisticsPortMenu(74, inventory, BlockPos.ZERO, null);
            menu = logistics; screen = new LogisticsPortScreen(logistics, inventory, Component.literal("Logistics"));
            panelWidth = 320; panelHeight = 238;
        } else {
            ProductionPortMenu production = new ProductionPortMenu(75, inventory, BlockPos.ZERO, null, 1, 0, 0);
            menu = production; screen = new ProductionPortScreen(production, inventory, Component.literal("Production"));
            panelWidth = 320; panelHeight = 238;
        }
        client.setScreen(screen); client.player.containerMenu = menu;
        int left = (screen.width - panelWidth) / 2, top = (screen.height - panelHeight) / 2;
        Integer body = theme().synthetic() && theme().body() >>> 24 == 255 ? theme().body() & 0xffffff : null;
        Integer recess = theme().synthetic() && theme().recess() >>> 24 == 255 ? theme().recess() & 0xffffff : null;
        samples.add(new Sample("panel", left + 5, top + panelHeight - 6, body));
        if (screen instanceof AbstractContainerScreen<?> container)
            check(container.getGuiLeft() == left && container.getGuiTop() == top, "Unexpected panel dimensions for " + scene);
        if (scene.startsWith("storage") || scene.equals("nas")) {
            int emptyCount = 0;
            for (int slotIndex = 0; slotIndex < 36; slotIndex++) {
                var slot = menu.slots.get(slotIndex);
                if (slot.hasItem()) continue;
                emptyCount++;
                samples.add(new Sample("inventory-slot-" + slotIndex, left + slot.x + 8, top + slot.y + 8,
                        theme().synthetic() ? slotColor(slotIndex) & 0xffffff : null));
            }
            check(emptyCount >= 30, "Too few empty slots to verify inventory and hotbar artwork");
        }
        if (scene.startsWith("storage")) {
            StorageLayout layout = StorageLayout.fit(screen.width, screen.height);
            check(layout.width() == panelWidth && layout.height() == panelHeight, "Storage size mismatch");
            samples.add(new Sample("craft-grid", left + layout.craftingX() + 8, top + layout.craftingY() + 8,
                    theme().hiddenRgb() ? null : recess));
            samples.add(new Sample("craft-result", left + layout.craftingResultX() + 8, top + layout.craftingResultY() + 8,
                    theme().synthetic() ? RESULT & 0xffffff : null));
            samples.add(new Sample("craft-arrow", left + layout.craftingX() + 70, top + layout.craftingResultY() + 7,
                    theme().synthetic() ? ARROW & 0xffffff : null));
            samples.add(new Sample("browser", left + layout.browserX() + layout.browserWidth() - 8,
                    top + layout.browserY() + layout.browserHeight() - 8, recess));
        }
        if (scene.equals("production")) samples.add(new Sample("recipe-slot", left + 19, top + 89,
                theme().synthetic() ? slotColor(13) & 0xffffff : null));
        ready(screen, "panel=" + panelWidth + "x" + panelHeight);
    }

    private static void ready(Screen screen, String description) throws Exception {
        expectedScreen = screen; frames = 0;
        log("SHOW " + theme().name() + "/" + SCENES.get(sceneIndex) + " screen=" + screen.width + "x" + screen.height + " " + description);
    }
    private static final class SurfaceScreen extends Screen {
        private SurfaceScreen() { super(Component.literal("Theme surface acceptance")); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, BACKDROP);
            VanillaGui.panel(graphics, 20, 20, 200, 100);
            VanillaGui.recess(graphics, 250, 30, 180, 80);
            VanillaGui.panel(graphics, 20, 160, 410, 130);
            VanillaGui.recess(graphics, 30, 170, 390, 100);
            VanillaGui.recessBorder(graphics, 40, 180, 370, 80);
        }
    }

    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty("itemexplorer.guiClientProbe") == null || done || captured
                || expectedScreen == null || event.getScreen() != expectedScreen || ++frames < 3) return;
        Minecraft client = Minecraft.getInstance();
        try {
            event.getGuiGraphics().flush();
            try (NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                String scene = SCENES.get(sceneIndex);
                image.writeToFile(root.resolve(theme().name() + "-" + scene + ".png"));
                Map<String, Integer> colors = new LinkedHashMap<>();
                for (Sample sample : samples) {
                    int actual = rgb(image, sample.x(), sample.y()); colors.put(sample.name(), actual);
                    if (sample.expected() != null) checkColor(actual, sample.expected(), scene + "/" + sample.name());
                    if (theme().name().equals("restored"))
                        check(actual == baseline.get(scene).get(sample.name()), "Reload removal did not restore " + scene + "/" + sample.name());
                }
                for (Surface surface : surfaces) verifySolid(image, surface);
                if (theme().name().equals("baseline")) baseline.put(scene, colors);
                log("PASS " + theme().name() + "/" + scene + " pixels=" + colors);
            }
            captured = true;
        } catch (Throwable failure) { fail(client, failure); }
    }

    private static int rgb(NativeImage image, int guiX, int guiY) {
        int pixelX = (int) ((guiX + 0.5) * image.getWidth() / expectedScreen.width);
        int pixelY = (int) ((guiY + 0.5) * image.getHeight() / expectedScreen.height);
        int rgba = image.getPixelRGBA(pixelX, pixelY);
        return (rgba & 0xff) << 16 | rgba & 0xff00 | rgba >>> 16 & 0xff;
    }
    private static int composite(int foreground, int background) {
        int alpha = foreground >>> 24, result = 0;
        for (int shift : new int[] {16, 8, 0}) {
            int channel = (((foreground >>> shift) & 255) * alpha + ((background >>> shift) & 255) * (255 - alpha) + 127) / 255;
            result |= channel << shift;
        }
        return result;
    }
    private static void verifySolid(NativeImage image, Surface surface) throws Exception {
        int reference = surface.expected() == null ? rgb(image, surface.x(), surface.y()) : surface.expected();
        for (int offsetY = 0; offsetY < surface.height(); offsetY++)
            for (int offsetX = 0; offsetX < surface.width(); offsetX++)
                checkColor(rgb(image, surface.x() + offsetX, surface.y() + offsetY), reference,
                        surface.name() + " at " + offsetX + "," + offsetY);
        log("PASS " + theme().name() + "/" + surface.name() + " solid=" + Integer.toHexString(reference));
    }
    private static void checkColor(int actual, int expected, String name) {
        for (int shift : new int[] {16, 8, 0}) check(Math.abs((actual >>> shift & 255) - (expected >>> shift & 255)) <= 2,
                theme().name() + "/" + name + " expected " + Integer.toHexString(expected) + " got " + Integer.toHexString(actual));
    }
    private static void checkDarkNeutral(int color, String name) {
        int red = color >>> 16 & 255, green = color >>> 8 & 255, blue = color & 255;
        check(red == green && green == blue && red <= 64,
                theme().name() + "/" + name + " must use a dark neutral color, got " + Integer.toHexString(color));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String message) throws Exception {
        Files.writeString(root.resolve("report.txt"), message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void fail(Minecraft client, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            log("FAIL case=" + caseIndex + " scene=" + sceneIndex + " ticks=" + ticks + "\n" + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL " + failure + "\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { failure.printStackTrace(); }
        client.stop();
    }
}
