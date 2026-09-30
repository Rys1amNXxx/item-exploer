package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageLayout;
import dev.itemexplorer.menu.StorageMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
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

/** Opt-in real client acceptance in one explicitly disposable world; never part of the release JAR. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class SearchClientProbe {
    private static final BlockPos TERMINAL = new BlockPos(80, 82, 80);
    private static boolean initialized, setupQueued, done;
    private static volatile boolean fixtureReady;
    private static volatile Throwable fixtureFailure;
    private static volatile int folderA, folderB, deepFolder, deepEntry;
    private static int ticks, stage, stageSince, largeSavedPage;
    private static long started, takeView;
    private static String pendingScreenshot, completedScreenshot;
    private static CompoundTag renderedView;
    private static Path root;

    private SearchClientProbe() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.searchClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty("itemexplorer.searchClientProbe")).toAbsolutePath().normalize();
                Files.createDirectories(root);
                Files.writeString(root.resolve("report.txt"), "Search client acceptance\n", StandardCharsets.UTF_8);
                mc.options.pauseOnLostFocus = false;
                mc.options.guiScale().set(2);
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                log("START real Minecraft client pid=" + ProcessHandle.current().pid() + " language=" + mc.options.languageCode);
            }
            ticks++;
            if (ticks > 2000 || System.currentTimeMillis() - started > 120_000)
                throw new IllegalStateException("Timed out after " + ticks + " ticks at stage " + stage);
            if (fixtureFailure != null) throw new IllegalStateException("Fixture failed", fixtureFailure);
            if (!setupQueued && mc.getSingleplayerServer() != null && mc.player != null) {
                setupQueued = true;
                var server = mc.getSingleplayerServer(); var playerId = mc.player.getUUID();
                server.execute(() -> {
                    try {
                        Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
                        Path expected = root.resolve("saves").resolve("Search Probe").toRealPath();
                        check(actual.equals(expected), "Refusing to change a world outside the isolated Search Probe directory: " + actual);
                        var player = server.getPlayerList().getPlayer(playerId);
                        check(player != null, "Real integrated-server player missing");
                        var level = server.overworld(); level.getChunkAt(TERMINAL);
                        for (int x = 78; x <= 82; x++) for (int z = 78; z <= 82; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.STONE.defaultBlockState());
                            for (int y = 82; y <= 85; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                        }
                        // Replacing the previous fixture can drop its local storage contents on a rerun.
                        // The exact disposable-world check above precedes this tightly bounded cleanup.
                        level.getEntitiesOfClass(ItemEntity.class, new AABB(77, 80, 77, 84, 87, 84)).forEach(ItemEntity::discard);
                        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
                        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                        check(terminal != null && terminal.inventory().total() == 0, "Fixture terminal is not fresh");
                        var inventory = terminal.inventory();
                        folderA = inventory.createFolder(0, "原料"); folderB = inventory.createFolder(0, "其他");
                        deepFolder = inventory.createFolder(folderA, "精炼");
                        for (int i = 1; i <= 8; i++) inventory.createFolder(deepFolder, "子目录" + i);
                        inventory.insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
                        inventory.insert(new ItemStack(Items.IRON_INGOT, 32), 32, folderA);
                        inventory.insert(new ItemStack(Items.IRON_INGOT, 64), 64, deepFolder);
                        inventory.insert(new ItemStack(Items.IRON_INGOT, 48), 48, folderB);
                        ItemStack named = new ItemStack(Items.IRON_INGOT, 4); named.setHoverName(Component.literal("探针铁锭"));
                        inventory.insert(named, 4, folderB);
                        inventory.insert(new ItemStack(Items.DIAMOND, 7), 7, folderA);
                        inventory.insert(new ItemStack(Items.GOLD_INGOT, 3), 3, folderB);
                        deepEntry = inventory.entries().stream().filter(e -> e.folder() == deepFolder).findFirst().orElseThrow().id();
                        check(inventory.total() == 174, "Wrong fixture total");
                        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
                        player.teleportTo(level, 80.5, 82, 78.5, 0, 0);
                        NetworkHooks.openScreen(player, terminal, TERMINAL);
                        fixtureReady = true;
                    } catch (Throwable failure) { fixtureFailure = failure; }
                });
            }
            if (!fixtureReady || !(mc.screen instanceof StorageScreen screen) || !(mc.player.containerMenu instanceof StorageMenu menu)
                    || !menu.view().contains("Session")) return;
            CompoundTag view = menu.view(); StorageLayout layout = layout(screen);
            // Network snapshots can arrive before the first rendered frame. Drive only what a user can see.
            if (renderedView != view || field(screen, "cachedView") != view) return;
            switch (stage) {
                case 0 -> {
                    if (view.getLong("Total") != 174 || layout.width() != 320 || layout.height() != 234) return;
                    check(new ItemStack(Items.IRON_INGOT).getHoverName().getString().equals("铁锭"), "Client did not load real zh_cn item translations");
                    log("PASS real Chinese translation and 320x234 layout; gui=" + screen.width + "x" + screen.height);
                    click(screen, layout.browserX() + layout.cellWidth() / 2, layout.browserY() + 15, 0);
                    advance(1);
                }
                case 1 -> {
                    if (view.getInt("Current") != folderA || view.getBoolean("Searching")) return;
                    check(view.getInt("Page") == 0, "Anchor page differs from zero");
                    openSearch(screen); type(screen, "铁锭"); advance(2);
                }
                case 2 -> {
                    if (!ready(screen, view, 5, false)) return;
                    check(view.getInt("SearchRoot") == folderA, "Search was not anchored to starting folder");
                    log("PASS Chinese query typed through StorageScreen.charTyped: entire drive has 5 separate iron entries");
                    screenshot("search-minimum-full-drive.png"); advance(3);
                }
                case 3 -> {
                    if (!captured("search-minimum-full-drive.png")) return;
                    click(screen, layout.searchScopeX() + 23, 32, 0); advance(4);
                }
                case 4 -> {
                    if (!ready(screen, view, 2, true)) return;
                    for (Tag tag : view.getList("Entries", Tag.TAG_COMPOUND)) {
                        int folder = ((CompoundTag) tag).getInt("Folder");
                        check(folder == folderA || folder == deepFolder, "Recursive result escaped starting folder tree");
                    }
                    log("PASS recursive range: starting folder and child only, 2 iron entries");
                    replaceText(screen, "searchQuery", 8, 24, "@minecraft 铁锭"); advance(5);
                }
                case 5 -> {
                    if (!ready(screen, view, 2, true) || !text(screen, "searchQuery").equals("@minecraft 铁锭")) return;
                    int index = entryIndex(view, deepEntry); if (index < 0) return;
                    click(screen, layout.browserX() + 50, layout.browserY() + index * 30 + 10, 0);
                    replaceText(screen, "quantity", layout.controlsX() + 27, layout.controlsY() + 1, "7");
                    takeView = view.getLong("ResultViewSeq");
                    click(screen, layout.browserX() + 50, layout.browserY() + index * 30 + 10, 1);
                    advance(6);
                }
                case 6 -> {
                    if (view.getLong("ResultViewSeq") <= takeView || entryCount(view, deepEntry) != 63
                            || mc.player.getInventory().countItem(Items.IRON_INGOT) != 1) return;
                    check(integer(screen, "selected") == deepEntry, "TAKE/count refresh lost the selected entry");
                    check(text(screen, "quantity").equals("7"), "TAKE/count refresh changed quantity text");
                    check(view.getLong("Total") == 173, "TAKE did not conserve stored plus player inventory");
                    log("PASS @namespace+Chinese AND query; right-click TAKE 1 kept selected=" + deepEntry + " and quantity=7; stored=173 playerIron=1");
                    screenshot("search-minimum-recursive-selected.png"); advance(7);
                }
                case 7 -> {
                    if (!captured("search-minimum-recursive-selected.png")) return;
                    click(screen, layout.controlsX() + 152, layout.controlsY() + 9, 0); advance(8);
                }
                case 8 -> {
                    if (view.getBoolean("Searching") || view.getInt("Current") != deepFolder || view.getInt("Page") != 1) return;
                    check(view.getInt("Located") == deepEntry && entryIndex(view, deepEntry) >= 0, "Locate did not open the item's correct mixed-content page");
                    check(integer(screen, "selected") == deepEntry, "Locate did not highlight target entry");
                    log("PASS locate opened source folder " + deepFolder + " on page 2 and highlighted the entry");
                    openSearch(screen); type(screen, "不存在"); screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); advance(9);
                }
                case 9 -> {
                    if (ticks - stageSince < 10 || view.getBoolean("Searching")) return;
                    check(view.getInt("Current") == deepFolder && view.getInt("Page") == 1, "Esc failed to restore prior folder and page");
                    log("PASS immediate Esc before debounced APPLY restored source folder page 2");
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 720); mc.resizeDisplay(); advance(10);
                }
                case 10 -> {
                    if (layout.width() != 440 || layout.height() != 340 || view.getInt("PageSize") != 25 || view.getBoolean("Searching")) return;
                    largeSavedPage = view.getInt("Page");
                    log("PASS 440x340 expanded layout; gui=" + screen.width + "x" + screen.height);
                    openSearch(screen); type(screen, "iron_ingot"); advance(11);
                }
                case 11 -> {
                    if (!ready(screen, view, 5, false) || view.getInt("PageSize") != 5) return;
                    check(view.getList("Entries", Tag.TAG_COMPOUND).size() == 5, "Expanded search should show all 5 entries");
                    log("PASS English registry-ID query from Chinese client; 5 rows include separate custom-NBT entry");
                    screenshot("search-expanded-full-drive.png"); advance(12);
                }
                case 12 -> {
                    if (!captured("search-expanded-full-drive.png")) return;
                    screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); advance(13);
                }
                case 13 -> {
                    if (ticks - stageSince < 10 || view.getBoolean("Searching")) return;
                    check(view.getInt("Current") == deepFolder && view.getInt("Page") == largeSavedPage, "Expanded Esc restore failed");
                    check(view.getLong("Total") == 173 && mc.player.getInventory().countItem(Items.IRON_INGOT) == 1, "Final conservation check failed");
                    log("PASS all checks; screenshots=3; ticks=" + ticks + "; real client + integrated server; input via screen methods; no OS input");
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8);
                    done = true; mc.stop();
                }
                default -> throw new IllegalStateException("Unknown stage " + stage);
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (done || !(event.getScreen() instanceof StorageScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof StorageMenu menu) renderedView = menu.view();
        if (pendingScreenshot == null) return;
        try {
            event.getGuiGraphics().flush();
            try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                image.writeToFile(root.resolve(pendingScreenshot).toFile());
            }
            completedScreenshot = pendingScreenshot; pendingScreenshot = null;
            log("SCREENSHOT " + completedScreenshot);
        } catch (Throwable failure) { fail(Minecraft.getInstance(), failure); }
    }

    private static void openSearch(StorageScreen screen) throws Exception {
        click(screen, 217, 32, 0);
        check(((EditBox) field(screen, "searchQuery")).isFocused(), "Search button failed to focus the input");
    }
    private static void replaceText(StorageScreen screen, String name, int x, int y, String value) throws Exception {
        String before = text(screen, name);
        click(screen, x + 5, y + 7, 0);
        screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        for (int i = 0; i < before.length(); i++) screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        type(screen, value);
        check(text(screen, name).equals(value), "Screen input did not produce expected " + name + " text");
    }
    private static void type(StorageScreen screen, String value) {
        for (int i = 0; i < value.length(); i++) screen.charTyped(value.charAt(i), 0);
    }
    private static void click(StorageScreen screen, int x, int y, int button) throws Exception {
        StorageLayout layout = layout(screen);
        screen.mouseClicked((screen.width - layout.width()) / 2.0 + x, (screen.height - layout.height()) / 2.0 + y, button);
        screen.mouseReleased((screen.width - layout.width()) / 2.0 + x, (screen.height - layout.height()) / 2.0 + y, button);
    }
    private static Object field(StorageScreen screen, String name) throws Exception {
        Field field = StorageScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(screen);
    }
    private static StorageLayout layout(StorageScreen screen) throws Exception { return (StorageLayout) field(screen, "layout"); }
    private static int integer(StorageScreen screen, String name) throws Exception { return (Integer) field(screen, name); }
    private static String text(StorageScreen screen, String name) throws Exception { return ((EditBox) field(screen, name)).getValue(); }
    private static boolean ready(StorageScreen screen, CompoundTag view, int count, boolean recursive) throws Exception {
        var ready = StorageScreen.class.getDeclaredMethod("searchReady"); ready.setAccessible(true);
        return view.getBoolean("Searching") && view.getBoolean("SearchReady") && view.getInt("SearchMatches") == count
                && view.getBoolean("SearchRecursive") == recursive && (Boolean) ready.invoke(screen);
    }
    private static int entryIndex(CompoundTag view, int id) {
        var entries = view.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) if (entries.getCompound(i).getInt("Id") == id) return i;
        return -1;
    }
    private static long entryCount(CompoundTag view, int id) {
        int index = entryIndex(view, id); return index < 0 ? -1 : view.getList("Entries", Tag.TAG_COMPOUND).getCompound(index).getLong("Count");
    }
    private static void screenshot(String name) { pendingScreenshot = name; completedScreenshot = null; }
    private static boolean captured(String name) { return name.equals(completedScreenshot); }
    private static void advance(int next) { stage = next; stageSince = ticks; }
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
        } catch (Exception ignored) { failure.printStackTrace(); }
        mc.stop();
    }
}
