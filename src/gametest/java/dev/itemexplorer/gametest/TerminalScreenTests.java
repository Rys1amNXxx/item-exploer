package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlock;
import dev.itemexplorer.block.StorageBlock.Screen;
import dev.itemexplorer.block.StorageBlockEntity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerminalScreenTests {
    private static Screen screen(StorageBlockEntity terminal) {
        return terminal.getLevel().getBlockState(terminal.getBlockPos()).getValue(StorageBlock.SCREEN);
    }

    @GameTest(template = "empty")
    public static void screenFollowsOpenWindows(GameTestHelper h) {
        StorageBlockEntity terminal = terminal(h);
        h.assertTrue(screen(terminal) == Screen.IDLE, "A new terminal did not start idle");
        var first = player(h, terminal.getBlockPos()); var second = player(h, terminal.getBlockPos());
        var firstMenu = open(first, terminal, 1); var secondMenu = open(second, terminal, 2);
        h.assertTrue(screen(terminal) == Screen.ACTIVE, "Opening the terminal did not light the active screen");
        firstMenu.removed(first);
        h.assertTrue(screen(terminal) == Screen.ACTIVE, "The screen went idle while another window was open");
        firstMenu.removed(first);
        secondMenu.removed(second);
        h.assertTrue(screen(terminal) == Screen.IDLE, "Closing every window did not return the screen to idle");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void staleActiveStateIsCleared(GameTestHelper h) {
        StorageBlockEntity terminal = terminal(h);
        var level = h.getLevel();
        // A world saved while a window was open keeps the active state on disk; loading must not trust it.
        level.setBlockAndUpdate(terminal.getBlockPos(), level.getBlockState(terminal.getBlockPos()).setValue(StorageBlock.SCREEN, Screen.ACTIVE));
        terminal.refreshScreen();
        h.assertTrue(screen(terminal) == Screen.IDLE, "A saved active screen survived without any viewer");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedDataShowsLockedScreen(GameTestHelper h) {
        StorageBlockEntity terminal = terminal(h);
        CompoundTag tag = terminal.saveWithoutMetadata();
        tag.getCompound("Storage").putInt("Version", 999);
        terminal.load(tag);
        h.assertTrue(terminal.inventory().isLocked() && screen(terminal) == Screen.LOCKED,
                "Protected terminal data did not show the locked screen");
        var player = player(h, terminal.getBlockPos());
        var menu = open(player, terminal);
        h.assertTrue(screen(terminal) == Screen.LOCKED, "Opening a protected terminal hid the locked screen");
        menu.removed(player);
        h.assertTrue(screen(terminal) == Screen.LOCKED, "Closing a protected terminal hid the locked screen");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void nasHasNoScreenState(GameTestHelper h) {
        h.assertTrue(!ModContent.NAS_BLOCK.get().defaultBlockState().hasProperty(StorageBlock.SCREEN),
                "The NAS inherited the terminal screen state");
        h.succeed();
    }
}
