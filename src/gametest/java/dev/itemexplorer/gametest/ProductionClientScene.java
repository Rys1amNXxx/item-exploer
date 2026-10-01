package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.client.ProductionPortScreen;
import dev.itemexplorer.client.StorageScreen;
import dev.itemexplorer.menu.ProductionPortMenu;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
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

/** Real rendered terminal-file workflow in an explicitly disposable integrated-server world. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class ProductionClientScene {
    private static final BlockPos FURNACE = new BlockPos(80, 82, 80), PORT = FURNACE.east(), TERMINAL = FURNACE.east(12);
    private static final String PROGRAM = "铁锭自动熔炼.exe", RENAMED = "铁锭生产.exe";
    private static boolean initialized, setupQueued, verifyQueued, done;
    private static volatile boolean fixtureReady, verified;
    private static volatile Throwable fixtureFailure;
    private static volatile int inputFolder, fuelFolder, outputFolder, programFolder, inputEntry, fuelEntry;
    private static int ticks, stage, renderedFrames, stageFrame, programId, copyId;
    private static long started, selectionContext;
    private static String pendingScreenshot, completedScreenshot;
    private static CompoundTag renderedView;
    private static Path root;

    private ProductionClientScene() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.productionClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty("itemexplorer.productionClientProbe")).toAbsolutePath().normalize();
                Files.createDirectories(root);
                Files.writeString(root.resolve("report.txt"), "Terminal program-file client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2);
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                log("START real client pid=" + ProcessHandle.current().pid() + " language=" + mc.options.languageCode);
            }
            ticks++;
            if (ticks > 3000 || System.currentTimeMillis() - started > 150_000)
                throw new IllegalStateException("Timed out at stage " + stage + " after " + ticks + " ticks");
            if (fixtureFailure != null) throw new IllegalStateException("Server fixture/check failed", fixtureFailure);
            if (!setupQueued && mc.getSingleplayerServer() != null && mc.player != null) setup(mc);
            if (!fixtureReady || mc.player == null) return;
            Screen screen = mc.screen; CompoundTag view;
            if (screen instanceof StorageScreen && mc.player.containerMenu instanceof StorageMenu menu) {
                view = menu.view();
                if (field(screen, "cachedView") != view) return;
            } else if (screen instanceof ProductionPortScreen && mc.player.containerMenu instanceof ProductionPortMenu menu) {
                view = menu.view();
                if (field(screen, "seen") != view) return;
            } else return;
            if (!view.contains("Session") || renderedView != view || stage > 0 && renderedFrames <= stageFrame) return;
            if (screen instanceof StorageScreen terminal) terminalStep(mc, terminal, view);
            else editorStep(mc, (ProductionPortScreen) screen, view);
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void setup(Minecraft mc) {
        setupQueued = true;
        var server = mc.getSingleplayerServer(); var playerId = mc.player.getUUID();
        server.execute(() -> {
            try {
                Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
                Path expected = root.resolve("saves").resolve("Production Probe").toRealPath();
                check(actual.equals(expected), "Refusing to change a world outside isolated Production Probe: " + actual);
                var player = server.getPlayerList().getPlayer(playerId);
                check(player != null, "Integrated-server player missing");
                var level = server.overworld(); level.getChunkAt(FURNACE); level.getChunkAt(TERMINAL);
                for (int x = 78; x <= 94; x++) for (int z = 78; z <= 82; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.STONE.defaultBlockState());
                    for (int y = 82; y <= 85; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(77, 80, 77, 96, 87, 84)).forEach(ItemEntity::discard);
                level.setBlockAndUpdate(FURNACE, Blocks.FURNACE.defaultBlockState());
                level.setBlockAndUpdate(PORT, ModContent.PRODUCTION_BLOCK.get().defaultBlockState().setValue(ProductionPortBlock.FACING, Direction.EAST));
                for (int x = PORT.getX() + 1; x < TERMINAL.getX(); x++)
                    level.setBlockAndUpdate(new BlockPos(x, PORT.getY(), PORT.getZ()), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
                level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
                var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                var port = (ProductionPortBlockEntity) level.getBlockEntity(PORT);
                check(terminal != null && port != null && terminal.inventory().total() == 0, "Fixture is not fresh");
                var inventory = terminal.inventory();
                inputFolder = inventory.createFolder(0, "原料"); fuelFolder = inventory.createFolder(0, "燃料");
                outputFolder = inventory.createFolder(0, "产物"); programFolder = inventory.createFolder(0, "程序");
                inventory.insert(new ItemStack(Items.IRON_ORE, 3), 3, inputFolder);
                inventory.insert(new ItemStack(Items.COAL), 1, fuelFolder);
                inputEntry = inventory.entries().stream().filter(entry -> entry.folder() == inputFolder).findFirst().orElseThrow().id();
                fuelEntry = inventory.entries().stream().filter(entry -> entry.folder() == fuelFolder).findFirst().orElseThrow().id();
                player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
                player.teleportTo(level, 91.5, 82, 78.5, 0, 0); level.setDayTime(6000);
                check(player.distanceToSqr(PORT.getX() + .5, PORT.getY() + .5, PORT.getZ() + .5) > 64,
                        "Player must be beyond the old port-menu distance limit");
                NetworkHooks.openScreen(player, terminal, TERMINAL); fixtureReady = true;
            } catch (Throwable failure) { fixtureFailure = failure; }
        });
    }

    private static void terminalStep(Minecraft mc, StorageScreen screen, CompoundTag view) throws Exception {
        switch (stage) {
            case 0 -> {
                check(screen.width == 320 && screen.height == 240, "Minimum client must be GUI 320x240");
                check(new ItemStack(Items.IRON_INGOT).getHoverName().getString().equals("铁锭"), "Real zh_cn translations missing");
                check(view.getBoolean("ProgramsAvailable"), "Local terminal must allow program files");
                clickFolderTile(screen, view, programFolder); advance(1);
            }
            case 1 -> {
                if (view.getInt("Current") != programFolder) return;
                clickWidget(screen, "create"); advance(2);
            }
            case 2 -> {
                check((Boolean) field(screen, "modal") && button(screen, "modalKind").visible, "New dialog must expose program type");
                clickWidget(screen, "modalKind"); replaceText(screen, "folderName", PROGRAM); advance(3);
            }
            case 3 -> {
                check((Boolean) field(screen, "programModal"), "New dialog did not select program type");
                clickWidget(screen, "modalOk"); advance(4);
            }
            case 4 -> {
                CompoundTag file = namedFile(view, PROGRAM); if (file == null) return;
                programId = file.getInt("Id");
                check(file.getInt("Folder") == programFolder && !file.getBoolean("Configured"), "New file must belong to the chosen folder");
                log("PASS New > Program creates a real file in terminal folder " + programFolder + "; id=" + programId);
                screenshot("production-terminal-program-file.png"); advance(5);
            }
            case 5 -> {
                if (!captured("production-terminal-program-file.png")) return;
                clickProgramTile(screen, view, programId, 0); advance(6);
            }
            case 16 -> {
                if (view.getInt("Current") != programFolder) return;
                check(view.getInt("Page") == 0 && namedFile(view, PROGRAM) != null, "Back must restore the source folder and page");
                log("PASS Escape returns from the distant machine editor to the original terminal folder/page");
                clickProgramTile(screen, view, programId, 1); advance(17);
            }
            case 17 -> {
                check(integer(screen, "selectedProgram") == programId, "Right-click must select the program file");
                clickWidget(screen, "rename"); replaceText(screen, "folderName", RENAMED); clickWidget(screen, "modalOk"); advance(18);
            }
            case 18 -> {
                if (namedFile(view, RENAMED) == null) return;
                clickProgramTile(screen, view, programId, 1); advance(19);
            }
            case 19 -> {
                check(button(screen, "all").getMessage().getString().equals("复制"), "Selection must expose Copy");
                clickWidget(screen, "all"); advance(20);
            }
            case 20 -> {
                var files = view.getList("ProgramFiles", Tag.TAG_COMPOUND); if (files.size() != 2) return;
                for (Tag tag : files) if (((CompoundTag) tag).getInt("Id") != programId) copyId = ((CompoundTag) tag).getInt("Id");
                check(copyId > 0, "Copy must have its own stable file identity");
                clickProgramTile(screen, view, copyId, 1); advance(21);
            }
            case 21 -> {
                clickWidget(screen, "move"); clickTreeFolder(screen, outputFolder); advance(22);
            }
            case 22 -> {
                if (file(view, copyId) != null) return;
                clickTreeFolder(screen, outputFolder); advance(23);
            }
            case 23 -> {
                if (view.getInt("Current") != outputFolder || file(view, copyId) == null) return;
                check(file(view, copyId).getBoolean("Configured") && !file(view, copyId).getBoolean("Active"), "Copy must preserve configuration without starting");
                clickProgramTile(screen, view, copyId, 1); advance(24);
            }
            case 24 -> { clickWidget(screen, "delete"); advance(25); }
            case 25 -> {
                if (file(view, copyId) != null) return;
                clickTreeFolder(screen, programFolder); advance(26);
            }
            case 26 -> {
                if (view.getInt("Current") != programFolder || namedFile(view, RENAMED) == null) return;
                if (!verifyQueued) verifyServer(mc);
                if (!verified) return;
                log("PASS UI rename/copy/move/delete; one configured original remains; copy never ran; materials conserved");
                log("PASS all checks; screenshots=4; GUI320x240 and427x240; terminal-owned editor; ticks=" + ticks);
                Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8); done = true; mc.stop();
            }
            default -> { }
        }
    }

    private static void editorStep(Minecraft mc, ProductionPortScreen screen, CompoundTag view) throws Exception {
        switch (stage) {
            case 6 -> {
                check(view.getInt("ProgramId") == programId && PROGRAM.equals(view.getString("Name")), "Left-click must open the exact terminal file");
                validateGeometry(screen);
                check(!button(screen, "start").active, "Unconfigured program may not start");
                check(!view.getList("Machines", Tag.TAG_COMPOUND).isEmpty(), "Connected distant production machine missing");
                selectionContext = view.getLong("Context"); clickWidget(screen, "machineButton"); advance(7);
            }
            case 7 -> {
                if (view.getLong("MachinePos") != PORT.asLong() || view.getLong("Context") == selectionContext) return;
                chooseFolder(screen, "inputFolder", "inputFolderButton", inputFolder);
                chooseFolder(screen, "fuelFolder", "fuelFolderButton", fuelFolder);
                chooseFolder(screen, "outputFolder", "outputFolderButton", outputFolder); advance(8);
            }
            case 8 -> {
                check(button(screen, "save").active && !button(screen, "start").active, "Unsaved selections must enable Save only");
                check(integer(screen, "inputEntry") == inputEntry && integer(screen, "fuelEntry") == fuelEntry, "Folder choices must preserve stable sample IDs");
                clickWidget(screen, "save"); advance(9);
            }
            case 9 -> {
                if (!view.getBoolean("Configured")) return;
                check(view.getInt("InputEntry") == inputEntry && view.getInt("FuelEntry") == fuelEntry
                        && view.getLong("SavedMachinePos") == PORT.asLong(), "Saved file did not preserve machine/sample IDs");
                check(button(screen, "start").active, "Saved form must enable Start");
                log("PASS terminal-file SAVE round trip and connected machine selection at GUI320x240");
                screenshot("production-minimum-configured.png"); advance(10);
            }
            case 10 -> {
                if (!captured("production-minimum-configured.png")) return;
                replaceText(screen, "count", "2"); advance(11);
            }
            case 11 -> {
                check(button(screen, "start").active, "Changing only run count must not require saving the program again");
                clickWidget(screen, "start"); advance(12);
            }
            case 12 -> {
                if (!view.getBoolean("Running")) return;
                check(view.getInt("Count") == 2, "Start must use the new run count");
                check(!button(screen, "save").active && !button(screen, "start").active && button(screen, "cancel").active,
                        "Running job must disable Save/Start and enable Cancel");
                log("PASS runtime count=2 without another Save; START and running controls");
                screenshot("production-minimum-running.png"); advance(13);
            }
            case 13 -> {
                if (!captured("production-minimum-running.png") || view.getBoolean("Running") || view.getInt("Completed") != 2) return;
                check(entryCount(view, inputEntry) == 1 && entryCount(view, fuelEntry) == 0 && itemCount(view, Items.IRON_INGOT) == 2,
                        "Two runs must consume two ores/one coal and return two ingots");
                check(integer(screen, "fuelEntry") == fuelEntry && button(screen, "start").active,
                        "Depleted fuel must keep saved ghost identity and allow another Start");
                log("PASS real vanilla smelt completed twice; output=2, ore=1, coal=0; ghost identity remains restartable");
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 854, 480); mc.resizeDisplay(); advance(14);
            }
            case 14 -> {
                if (screen.width != 427 || screen.height != 240) return;
                validateGeometry(screen); screenshot("production-854-completed.png"); advance(15);
            }
            case 15 -> {
                if (!captured("production-854-completed.png")) return;
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); advance(16);
            }
            default -> { }
        }
    }

    private static void verifyServer(Minecraft mc) {
        verifyQueued = true; var server = mc.getSingleplayerServer(); var playerId = mc.player.getUUID();
        server.execute(() -> {
            try {
                var level = server.overworld(); var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                var furnace = (FurnaceBlockEntity) level.getBlockEntity(FURNACE);
                check(terminal.inventory().total() == 3, "File operations changed stored materials");
                check(terminal.programs().files().size() == 1 && terminal.programs().file(copyId) == null, "Deleted copy must be absent");
                var file = terminal.programs().file(programId);
                check(file != null && file.folder() == programFolder && RENAMED.equals(file.name()) && !file.active() && file.definition() != null,
                        "Original file identity, definition or containing folder changed");
                check(furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty() && furnace.getItem(2).isEmpty(), "Machine did not finish cleanly");
                var player = server.getPlayerList().getPlayer(playerId);
                check(player.getInventory().isEmpty(), "File/editor controls transferred material items to player");
                check(player.containerMenu instanceof StorageMenu, "Back must restore terminal menu");
                verified = true;
            } catch (Throwable failure) { fixtureFailure = failure; }
        });
    }

    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty("itemexplorer.productionClientProbe") == null || done
                || !(event.getScreen() instanceof ProductionPortScreen || event.getScreen() instanceof StorageScreen)) return;
        Minecraft mc = Minecraft.getInstance(); renderedFrames++;
        if (mc.player != null) {
            if (mc.player.containerMenu instanceof ProductionPortMenu menu) renderedView = menu.view();
            else if (mc.player.containerMenu instanceof StorageMenu menu) renderedView = menu.view();
        }
        if (pendingScreenshot == null) return;
        try {
            event.getGuiGraphics().flush();
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(root.resolve(pendingScreenshot).toFile()); }
            completedScreenshot = pendingScreenshot; pendingScreenshot = null; log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(mc, failure); }
    }
    private static void validateGeometry(ProductionPortScreen screen) {
        List<AbstractWidget> widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        check(widgets.size() == 12, "Expected 12 configuration/navigation controls, got " + widgets.size());
        for (int i = 0; i < widgets.size(); i++) {
            AbstractWidget a = widgets.get(i);
            check(a.getX() >= 0 && a.getY() >= 0 && a.getX() + a.getWidth() <= screen.width
                    && a.getY() + a.getHeight() <= screen.height, "Control outside viewport: " + a.getMessage().getString());
            for (int j = i + 1; j < widgets.size(); j++) {
                AbstractWidget b = widgets.get(j);
                check(a.getX() + a.getWidth() <= b.getX() || b.getX() + b.getWidth() <= a.getX()
                        || a.getY() + a.getHeight() <= b.getY() || b.getY() + b.getHeight() <= a.getY(),
                        "Overlapping controls: " + a.getMessage().getString() + " / " + b.getMessage().getString());
            }
        }
    }
    private static void click(Screen screen, double x, double y, int button) {
        screen.mouseClicked(x, y, button); screen.mouseReleased(x, y, button);
    }
    private static void clickWidget(Screen screen, String name) throws Exception {
        AbstractWidget widget = (AbstractWidget) field(screen, name);
        check(widget.visible && widget.active, "Control is not available: " + name);
        click(screen, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0);
    }
    private static void clickTile(StorageScreen screen, int index, int button) throws Exception {
        StorageLayout layout = (StorageLayout) field(screen, "layout");
        double x = (screen.width - layout.width()) / 2 + layout.browserX() + index % layout.columns() * layout.cellWidth() + 12;
        double y = (screen.height - layout.height()) / 2 + layout.browserY() + index / layout.columns() * 30 + 12;
        click(screen, x, y, button);
    }
    private static void clickFolderTile(StorageScreen screen, CompoundTag view, int folder) throws Exception {
        int[] ids = view.getIntArray("PageFolders");
        for (int i = 0; i < ids.length; i++) if (ids[i] == folder) { clickTile(screen, i, 0); return; }
        throw new IllegalStateException("Folder tile is not on this page: " + folder);
    }
    private static void clickProgramTile(StorageScreen screen, CompoundTag view, int id, int button) throws Exception {
        var files = view.getList("ProgramFiles", Tag.TAG_COMPOUND); int offset = view.getIntArray("PageFolders").length;
        for (int i = 0; i < files.size(); i++) if (files.getCompound(i).getInt("Id") == id) { clickTile(screen, offset + i, button); return; }
        throw new IllegalStateException("Program tile is not on this page: " + id);
    }
    private static void clickTreeFolder(StorageScreen screen, int id) throws Exception {
        List<?> tree = (List<?>) field(screen, "tree"); StorageLayout layout = (StorageLayout) field(screen, "layout");
        int scroll = integer(screen, "folderScroll");
        for (int i = 0; i < tree.size(); i++) if ((Integer) field(tree.get(i), "id") == id) {
            check(i >= scroll && i < scroll + layout.treeRows(), "Tree target is not visible: " + id);
            click(screen, (screen.width - layout.width()) / 2 + 55,
                    (screen.height - layout.height()) / 2 + layout.browserY() + (i - scroll) * 12 + 6, 0); return;
        }
        throw new IllegalStateException("Missing tree folder: " + id);
    }
    private static void chooseFolder(ProductionPortScreen screen, String selected, String control, int target) throws Exception {
        for (int i = 0; i < 16 && integer(screen, selected) != target; i++) clickWidget(screen, control);
        check(integer(screen, selected) == target, "Folder cycle did not reach " + target);
    }
    private static void replaceText(Screen screen, String name, String value) throws Exception {
        EditBox box = (EditBox) field(screen, name); String before = box.getValue(); clickWidget(screen, name);
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        for (int i = 0; i < before.length(); i++) screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        for (int i = 0; i < value.length(); i++) screen.charTyped(value.charAt(i), 0);
        check(box.getValue().equals(value), "Screen input did not produce expected " + name);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Button button(Screen screen, String name) throws Exception { return (Button) field(screen, name); }
    private static int integer(Screen screen, String name) throws Exception { return (Integer) field(screen, name); }
    private static CompoundTag namedFile(CompoundTag view, String name) {
        for (Tag tag : view.getList("ProgramFiles", Tag.TAG_COMPOUND)) if (((CompoundTag) tag).getString("Name").equals(name)) return (CompoundTag) tag;
        return null;
    }
    private static CompoundTag file(CompoundTag view, int id) {
        for (Tag tag : view.getList("ProgramFiles", Tag.TAG_COMPOUND)) if (((CompoundTag) tag).getInt("Id") == id) return (CompoundTag) tag;
        return null;
    }
    private static long entryCount(CompoundTag view, int id) {
        for (Tag tag : view.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag; if (entry.getInt("Id") == id) return entry.getLong("Count");
        }
        return 0;
    }
    private static long itemCount(CompoundTag view, net.minecraft.world.item.Item item) {
        long total = 0;
        for (Tag tag : view.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag;
            if (entry.getInt("Folder") == outputFolder && ItemStack.of(entry.getCompound("Stack")).is(item)) total += entry.getLong("Count");
        }
        return total;
    }
    private static void screenshot(String name) { pendingScreenshot = name; completedScreenshot = null; }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(int next) { stage = next; stageFrame = renderedFrames; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String line) throws Exception {
        Files.writeString(root.resolve("report.txt"), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            log("FAIL stage=" + stage + " ticks=" + ticks + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL stage=" + stage + " " + failure + "\n", StandardCharsets.UTF_8);
            if (mc.player != null) {
                if (mc.player.containerMenu instanceof ProductionPortMenu menu) log("EDITOR VIEW " + menu.view());
                else if (mc.player.containerMenu instanceof StorageMenu menu) log("TERMINAL VIEW " + menu.view());
            }
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(root.resolve("production-failed-stage-" + stage + ".png").toFile());
            }
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
