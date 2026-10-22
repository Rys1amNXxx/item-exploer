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
import dev.itemexplorer.production.ProcessingPattern;
import dev.itemexplorer.production.TerminalPrograms;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Opt-in rendered acceptance for the generic pattern editor in a disposable world. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class GenericProductionClientScene {
    private static final String PROPERTY = "itemexplorer.genericProductionClientProbe", PROGRAM = "多物料加工.exe", DUPLICATE = "重复名称.exe";
    private static final BlockPos MACHINE = new BlockPos(80, 82, 80), PORT = MACHINE.east(), TERMINAL = MACHINE.east(12);
    private static boolean initialized, setupQueued, verifyQueued, done;
    private static volatile boolean fixtureReady, verified, rejectedVerified;
    private static volatile Throwable serverFailure;
    private static volatile int sandFolder, ironFolder, glassFolder, nuggetFolder, programId, sandEntry, ironEntry, glassEntry, nuggetEntry;
    private static boolean rejectedCheckQueued;
    private static int ticks, stage, frames, stageFrame;
    private static long started;
    private static String pendingScreenshot, completedScreenshot;
    private static String pickerQuery;
    private static int pickerFrame;
    private static CompoundTag renderedView;
    private static Path root;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty(PROPERTY) == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty(PROPERTY)).toAbsolutePath().normalize(); Files.createDirectories(root);
                Files.writeString(root.resolve("report.txt"), "Generic production client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2);
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                log("START pid=" + ProcessHandle.current().pid() + " language=" + mc.options.languageCode);
            }
            if (++ticks > 2600 || System.currentTimeMillis() - started > 150_000)
                throw new IllegalStateException("Timed out at stage " + stage);
            if (serverFailure != null) throw new IllegalStateException("Server check failed", serverFailure);
            if (!setupQueued && mc.getSingleplayerServer() != null && mc.player != null) setup(mc);
            if (!fixtureReady || mc.player == null || frames <= stageFrame) return;
            if (mc.screen instanceof ProductionPortScreen editor && mc.player.containerMenu instanceof ProductionPortMenu menu) {
                CompoundTag view = menu.view();
                if (view.contains("Session") && renderedView == view && field(editor, "seen") == view) editorStep(mc, editor, view);
            } else if (mc.screen instanceof StorageScreen terminal && mc.player.containerMenu instanceof StorageMenu menu && stage == 32) {
                CompoundTag view = menu.view();
                if (view.contains("Session") && renderedView == view && field(terminal, "cachedView") == view) {
                    check(view.getInt("Current") == 0, "Closing the editor did not restore the terminal root folder");
                    clickFile(terminal, view); advance(33);
                }
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void setup(Minecraft mc) {
        setupQueued = true; var server = mc.getSingleplayerServer(); var playerId = mc.player.getUUID();
        server.execute(() -> {
            try {
                Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
                Path expected = root.resolve("saves").resolve("Generic Production Probe").toRealPath();
                check(actual.equals(expected), "Refusing to change a world outside Generic Production Probe: " + actual);
                var player = server.getPlayerList().getPlayer(playerId); check(player != null, "Integrated player missing");
                var level = server.overworld(); level.getChunkAt(MACHINE); level.getChunkAt(TERMINAL);
                for (int x = 78; x <= 94; x++) for (int z = 78; z <= 82; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.STONE.defaultBlockState());
                    for (int y = 82; y <= 85; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(MACHINE, Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(PORT, ModContent.PRODUCTION_BLOCK.get().defaultBlockState().setValue(ProductionPortBlock.FACING, Direction.EAST));
                for (int x = PORT.getX() + 1; x < TERMINAL.getX(); x++)
                    level.setBlockAndUpdate(new BlockPos(x, PORT.getY(), PORT.getZ()), ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
                level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
                var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                var port = (ProductionPortBlockEntity) level.getBlockEntity(PORT);
                check(terminal != null && port != null && terminal.inventory().total() == 0, "Fixture is not fresh");
                check("item_handler".equals(port.machineType()), "Barrel did not expose a generic inventory");
                var inventory = terminal.inventory();
                sandFolder = inventory.createFolder(0, "砂料"); ironFolder = inventory.createFolder(0, "铁料");
                glassFolder = inventory.createFolder(0, "玻璃成品"); nuggetFolder = inventory.createFolder(0, "副产物");
                inventory.insert(new ItemStack(Items.SAND, 8), 8, sandFolder);
                inventory.insert(new ItemStack(Items.IRON_INGOT, 12), 12, ironFolder);
                inventory.insert(new ItemStack(Items.GLASS), 1, 0); inventory.insert(new ItemStack(Items.IRON_NUGGET), 1, 0);
                sandEntry = inventory.entries().stream().filter(e -> e.stack().is(Items.SAND)).findFirst().orElseThrow().id();
                ironEntry = inventory.entries().stream().filter(e -> e.stack().is(Items.IRON_INGOT)).findFirst().orElseThrow().id();
                glassEntry = inventory.entries().stream().filter(e -> e.stack().is(Items.GLASS)).findFirst().orElseThrow().id();
                nuggetEntry = inventory.entries().stream().filter(e -> e.stack().is(Items.IRON_NUGGET)).findFirst().orElseThrow().id();
                programId = terminal.programs().create(0, PROGRAM);
                terminal.programs().create(0, DUPLICATE);
                player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
                player.teleportTo(level, 91.5, 82, 78.5, 0, 0); level.setDayTime(6000);
                TerminalPrograms.open(player, terminal, programId, 0, 0); fixtureReady = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }

    private static void editorStep(Minecraft mc, ProductionPortScreen screen, CompoundTag view) throws Exception {
        switch (stage) {
            case 0 -> {
                check(screen.width == 320 && screen.height == 240, "Client must render at GUI 320x240");
                check(new ItemStack(Items.IRON_INGOT).getHoverName().getString().equals("铁锭"), "zh_cn translations missing");
                check(!button(screen, "runAction").active, "Incomplete pattern can start");
                clickWidget(screen, "machineButton"); advance(1);
            }
            case 1 -> {
                if (pick(screen, "木桶", PORT.asLong(), "generic-machine-search.png")) advance(2);
            }
            case 2 -> {
                if (view.getLong("MachinePos") != PORT.asLong()
                        || (Boolean) field(screen, "selectionPending")) return;
                clickGrid(screen, false, 0); advance(3);
            }
            case 3 -> { if (pickMaterial(screen, "沙子", sandEntry, "generic-material-search.png")) advance(4); }
            case 4 -> { replaceText(screen, "amount", "2"); clickWidget(screen, "materialFolder"); advance(5); }
            case 5 -> { if (pick(screen, "砂料", sandFolder, "generic-folder-search.png")) advance(6); }
            case 6 -> { clickGrid(screen, false, 1); advance(7); }
            case 7 -> { if (pickMaterial(screen, "铁锭", ironEntry, null)) advance(8); }
            case 8 -> { replaceText(screen, "amount", "3"); clickWidget(screen, "connectionTab"); advance(9); }
            case 9 -> { clickWidget(screen, "materialFaceButton"); advance(10); }
            case 10 -> { if (pick(screen, "东", Direction.EAST.ordinal(), null)) advance(11); }
            case 11 -> { clickWidget(screen, "inputFaceButton"); advance(12); }
            case 12 -> { if (pick(screen, "上", Direction.UP.ordinal(), null)) advance(13); }
            case 13 -> { clickWidget(screen, "outputFaceButton"); advance(14); }
            case 14 -> { if (pick(screen, "下", Direction.DOWN.ordinal(), null)) advance(15); }
            case 15 -> { clickWidget(screen, "recipeTab"); advance(16); }
            case 16 -> { clickGrid(screen, true, 0); advance(17); }
            case 17 -> { if (pickMaterial(screen, "玻璃", glassEntry, null)) advance(18); }
            case 18 -> { replaceText(screen, "amount", "3"); clickWidget(screen, "materialFolder"); advance(19); }
            case 19 -> { if (pick(screen, "玻璃成品", glassFolder, null)) advance(20); }
            case 20 -> { clickGrid(screen, true, 1); advance(21); }
            case 21 -> { if (pickMaterial(screen, "铁粒", nuggetEntry, null)) advance(22); }
            case 22 -> { replaceText(screen, "amount", "2"); clickWidget(screen, "materialFolder"); advance(23); }
            case 23 -> { if (pick(screen, "副产物", nuggetFolder, null)) advance(24); }
            case 24 -> {
                checkGridOverview(screen); validateGeometry(screen);
                check(button(screen, "save").active && button(screen, "runAction").active, "Complete draft must enable Save and Save-and-trial");
                screenshot("generic-minimum-recipe.png"); advance(25);
            }
            case 25 -> {
                if (!captured("generic-minimum-recipe.png")) return;
                clickWidget(screen, "connectionTab"); advance(26);
            }
            case 26 -> { validateGeometry(screen); screenshot("generic-minimum-connections.png"); advance(100); }
            case 100 -> {
                if (!captured("generic-minimum-connections.png")) return;
                clickWidget(screen, "runTab"); advance(101);
            }
            case 101 -> { replaceText(screen, "count", "2"); advance(102); }
            case 102 -> {
                verifySummary(screen); validateGeometry(screen); screenshot("generic-minimum-run-preview.png"); advance(103);
            }
            case 103 -> {
                if (!captured("generic-minimum-run-preview.png")) return;
                clickWidget(screen, "recipeTab"); advance(27);
            }
            case 27 -> { clickWidget(screen, "save"); advance(28); }
            case 28 -> {
                if (!view.getBoolean("Configured") || (field(screen, "pendingSave") != null)) return;
                verifyPattern(ProcessingPattern.load(view.getCompound("Pattern")), 2);
                check(!view.getBoolean("Running"), "Save alone started a job");
                log("PASS searchable machine/item/folder selection; simultaneous 3x3 input/output overview; multi-face saved pattern");
                clickGrid(screen, false, 0); advance(29);
            }
            case 29 -> { replaceText(screen, "amount", "4"); advance(30); }
            case 30 -> {
                verifyPattern(ProcessingPattern.load(view.getCompound("Pattern")), 2);
                check(button(screen, "runAction").active && button(screen, "runAction").getMessage().getString().contains("保存"),
                        "Dirty draft does not explain that a trial saves the configuration first");
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); advance(32);
            }
            case 33 -> {
                verifyPattern(ProcessingPattern.load(view.getCompound("Pattern")), 2); checkGridOverview(screen);
                check(((List<?>) field(screen, "patternInputs")).size() == 2 && ((List<?>) field(screen, "patternOutputs")).size() == 2,
                        "Reopening lost pattern rows");
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 854, 480); mc.resizeDisplay(); advance(34);
            }
            case 34 -> {
                if (screen.width != 427 || screen.height != 240) return;
                validateGeometry(screen); checkGridOverview(screen); screenshot("generic-854-recipe.png"); advance(35);
            }
            case 35 -> {
                if (!captured("generic-854-recipe.png")) return;
                clickWidget(screen, "connectionTab"); advance(36);
            }
            case 36 -> { validateGeometry(screen); screenshot("generic-854-connections.png"); advance(37); }
            case 37 -> {
                if (!captured("generic-854-connections.png")) return;
                clickWidget(screen, "runTab"); advance(38);
            }
            case 38 -> { replaceText(screen, "count", "2"); advance(39); }
            case 39 -> { verifySummary(screen); validateGeometry(screen); screenshot("generic-854-run-preview.png"); advance(40); }
            case 40 -> {
                if (!captured("generic-854-run-preview.png")) return;
                clickWidget(screen, "recipeTab"); advance(41);
            }
            case 41 -> { replaceText(screen, "name", DUPLICATE); advance(42); }
            case 42 -> {
                clickWidget(screen, "runAction");
                check((field(screen, "pendingSave") != null) && !view.getBoolean("Running"), "Save-and-trial skipped the SAVE acknowledgement");
                advance(43);
            }
            case 43 -> {
                if ((field(screen, "pendingSave") != null)) return;
                check(!view.getBoolean("Running") && PROGRAM.equals(view.getString("Name")), "Rejected SAVE started a job or changed the saved file");
                check(((EditBox) field(screen, "name")).getValue().equals(DUPLICATE) && button(screen, "runAction").active,
                        "Rejected SAVE discarded the editable draft or left the editor disabled");
                if (!rejectedCheckQueued) verifyRejectedSave(mc);
                if (!rejectedVerified) return;
                log("PASS server-rejected duplicate-name SAVE never starts or inserts materials; form remains editable");
                replaceText(screen, "name", PROGRAM); clickGrid(screen, false, 0); advance(44);
            }
            case 44 -> { replaceText(screen, "amount", "3"); advance(45); }
            case 45 -> {
                clickWidget(screen, "runAction");
                check((field(screen, "pendingSave") != null) && !view.getBoolean("Running"), "Edited trial started before saving"); advance(46);
            }
            case 46 -> {
                if (!view.getBoolean("Running") || !"production_waiting_output".equals(view.getString("Status"))) return;
                verifyPattern(ProcessingPattern.load(view.getCompound("Pattern")), 3);
                check(view.getInt("Count") == 1 && ((EditBox) field(screen, "count")).getValue().equals("2"),
                        "Trial did not run exactly once or overwrote the normal batch count");
                check(!button(screen, "save").active && !button(screen, "runAction").active && button(screen, "cancel").active,
                        "Running controls did not lock editing/start and enable cancellation");
                if ((Integer) field(screen, "page") != 2) clickWidget(screen, "runTab");
                advance(47);
            }
            case 47 -> { validateGeometry(screen); screenshot("generic-854-running-trial.png"); advance(48); }
            case 48 -> {
                if (!captured("generic-854-running-trial.png")) return;
                clickWidget(screen, "cancel"); advance(49);
            }
            case 49 -> {
                if (view.getBoolean("Running")) return;
                if (!verifyQueued) verifyServer(mc);
                if (!verified) return;
                check(((EditBox) field(screen, "count")).getValue().equals("2"), "Cancelling a trial overwrote normal batch count");
                log("PASS SaveReceipt-gated Save-and-trial, one-batch material conservation, CANCEL, reopen, three tabs at GUI320x240 and427x240");
                Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8); done = true; mc.stop();
            }
            default -> { }
        }
    }

    private static void verifyPattern(ProcessingPattern pattern, int sandCount) {
        check(pattern.inputs().size() == 2 && pattern.outputs().size() == 2, "Pattern row counts changed");
        check(pattern.inputs().get(0).folder() == sandFolder && pattern.inputs().get(0).stack().is(Items.SAND)
                && pattern.inputs().get(0).stack().getCount() == sandCount, "Sand row changed");
        check(pattern.inputs().get(1).folder() == ironFolder && pattern.inputs().get(1).stack().is(Items.IRON_INGOT)
                && pattern.inputs().get(1).stack().getCount() == 3 && pattern.inputs().get(1).face() == Direction.EAST, "Iron row/side changed");
        check(pattern.outputs().get(0).folder() == glassFolder && pattern.outputs().get(0).stack().is(Items.GLASS)
                && pattern.outputs().get(0).stack().getCount() == 3, "Glass row changed");
        check(pattern.outputs().get(1).folder() == nuggetFolder && pattern.outputs().get(1).stack().is(Items.IRON_NUGGET)
                && pattern.outputs().get(1).stack().getCount() == 2, "Byproduct row changed");
        check(pattern.inputFace() == Direction.UP && pattern.outputFace() == Direction.DOWN, "Default machine faces changed");
    }
    private static void verifyServer(Minecraft mc) {
        verifyQueued = true; mc.getSingleplayerServer().execute(() -> {
            try {
                var level = mc.getSingleplayerServer().overworld();
                var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                var port = (ProductionPortBlockEntity) level.getBlockEntity(PORT);
                var machine = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(MACHINE);
                verifyPattern(terminal.programs().file(programId).definition().pattern(), 3);
                check(!port.isRunning() && !terminal.programs().file(programId).active(), "Cancel did not release both job owners");
                int machineTotal = 0; for (int slot = 0; slot < machine.getContainerSize(); slot++) machineTotal += machine.getItem(slot).getCount();
                check(machineTotal == 6 && terminal.inventory().total() == 16, "Save-and-trial/Cancel lost, duplicated, or used the old recipe ingredients");
                verified = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }
    private static void verifyRejectedSave(Minecraft mc) {
        rejectedCheckQueued = true; mc.getSingleplayerServer().execute(() -> {
            try {
                var level = mc.getSingleplayerServer().overworld();
                var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                var port = (ProductionPortBlockEntity) level.getBlockEntity(PORT);
                var machine = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(MACHINE);
                check(!port.isRunning() && !terminal.programs().file(programId).active() && machine.isEmpty()
                        && terminal.inventory().total() == 22 && terminal.programs().file(programId).name().equals(PROGRAM),
                        "A rejected SAVE started the existing saved recipe or transferred physical material");
                rejectedVerified = true;
            } catch (Throwable failure) { serverFailure = failure; }
        });
    }
    private static void verifySummary(ProductionPortScreen screen) {
        List<String> inputs = screen.runSummary(false), outputs = screen.runSummary(true);
        check(inputs.size() == 2 && inputs.get(0).contains("4") && inputs.get(1).contains("6")
                && outputs.size() == 2 && outputs.get(0).contains("6") && outputs.get(1).contains("4"),
                "Batch preview did not multiply every input and output by two: " + inputs + " / " + outputs);
    }

    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty(PROPERTY) == null || done
                || !(event.getScreen() instanceof ProductionPortScreen || event.getScreen() instanceof StorageScreen)) return;
        Minecraft mc = Minecraft.getInstance(); frames++;
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
        List<AbstractWidget> widgets = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible).toList();
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
    private static void checkGridOverview(Screen screen) throws Exception {
        Button[] inputs = (Button[]) field(screen, "inputGrid"), outputs = (Button[]) field(screen, "outputGrid");
        check(inputs.length == 9 && outputs.length == 9 && inputs[0].visible && inputs[1].visible
                && outputs[0].visible && outputs[1].visible, "Inputs and outputs are not simultaneously visible in two 3x3 grids");
    }
    private static void clickGrid(Screen screen, boolean output, int index) throws Exception {
        AbstractWidget widget = ((Button[]) field(screen, output ? "outputGrid" : "inputGrid"))[index];
        check(widget.visible && widget.active, "Unavailable material grid slot: " + output + "/" + index);
        click(screen, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
    }
    private static boolean pickMaterial(Screen screen, String query, long id, String screenshot) throws Exception {
        if (!(Boolean) field(screen, "pickerOpen")) { clickWidget(screen, "changeMaterial"); return false; }
        return pick(screen, query, id, screenshot);
    }
    private static boolean pick(Screen screen, String query, long id, String shot) throws Exception {
        check((Boolean) field(screen, "pickerOpen"), "Expected a searchable selector for " + query);
        if (!query.equals(pickerQuery)) {
            replaceText(screen, "search", query); pickerQuery = query; pickerFrame = frames;
            if (shot != null) screenshot(shot); return false;
        }
        if (frames <= pickerFrame || shot != null && !captured(shot)) return false;
        List<?> results = (List<?>) field(screen, "pickerResults"); int offset = (Integer) field(screen, "pickerOffset");
        Button[] buttons = (Button[]) field(screen, "pickerButtons");
        for (int i = 0; i < results.size(); i++) if (((Number) field(results.get(i), "id")).longValue() == id) {
            check(i >= offset && i < offset + buttons.length, "Search did not bring the requested result into view: " + query);
            Button button = buttons[i - offset]; check(button.visible && button.active, "Search result is not clickable: " + query);
            click(screen, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
            check(!(Boolean) field(screen, "pickerOpen"), "Choosing a result did not dismiss the selector");
            pickerQuery = null; return true;
        }
        throw new IllegalStateException("Search has no matching row for " + query + ": " + id);
    }
    private static void clickFile(StorageScreen screen, CompoundTag view) throws Exception {
        var files = view.getList("ProgramFiles", Tag.TAG_COMPOUND); int offset = view.getIntArray("PageFolders").length;
        for (int i = 0; i < files.size(); i++) if (files.getCompound(i).getInt("Id") == programId) {
            StorageLayout layout = (StorageLayout) field(screen, "layout"); int tile = offset + i;
            double x = (screen.width - layout.width()) / 2 + layout.browserX() + tile % layout.columns() * layout.cellWidth() + 12;
            double y = (screen.height - layout.height()) / 2 + layout.browserY() + tile / layout.columns() * 30 + 12;
            click(screen, x, y); return;
        }
        throw new IllegalStateException("Saved program is not visible in the terminal root");
    }
    private static void click(Screen screen, double x, double y) { screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0); }
    private static void clickWidget(Screen screen, String name) throws Exception {
        AbstractWidget widget = (AbstractWidget) field(screen, name); check(widget.visible && widget.active, "Unavailable control: " + name);
        click(screen, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
    }
    private static void replaceText(Screen screen, String name, String value) throws Exception {
        EditBox box = (EditBox) field(screen, name); String before = box.getValue(); clickWidget(screen, name);
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        for (int i = 0; i < before.length(); i++) screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        for (int i = 0; i < value.length(); i++) screen.charTyped(value.charAt(i), 0);
        check(value.equals(box.getValue()), "Text input did not reach " + name);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Button button(Screen screen, String name) throws Exception { return (Button) field(screen, name); }
    private static void screenshot(String name) { pendingScreenshot = name; completedScreenshot = null; }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(int next) { stage = next; stageFrame = frames; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String line) throws Exception {
        Files.writeString(root.resolve("report.txt"), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void fail(Minecraft mc, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); log("FAIL stage=" + stage + " " + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL stage=" + stage + " " + failure + "\n", StandardCharsets.UTF_8);
            if (mc.player != null && mc.player.containerMenu instanceof ProductionPortMenu menu) log("VIEW " + menu.view());
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(root.resolve("generic-failed-stage-" + stage + ".png").toFile()); }
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
