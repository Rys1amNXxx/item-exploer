package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DefaultVolumeTests {
    private record Scene(StorageBlockEntity terminal, NasBlockEntity nas, NasBlockEntity other, BlockPos cable) {}

    private static Scene scene(GameTestHelper h) {
        BlockPos terminal = new BlockPos(0, 1, 2);
        h.setBlock(terminal, ModContent.STORAGE_BLOCK.get());
        for (int x = 1; x <= 3; x++) h.setBlock(new BlockPos(x, 1, 2), ModContent.DATA_CABLE_BLOCK.get());
        h.setBlock(new BlockPos(2, 1, 3), ModContent.DATA_CABLE_BLOCK.get());
        h.setBlock(new BlockPos(4, 1, 2), ModContent.NAS_BLOCK.get());
        h.setBlock(new BlockPos(2, 1, 4), ModContent.NAS_BLOCK.get());
        var nas = (NasBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 1, 2)));
        var other = (NasBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 1, 4)));
        nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        return new Scene((StorageBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(terminal)),
                nas, other, h.absolutePos(new BlockPos(1, 1, 2)));
    }

    private static void select(StorageMenu menu, String volume) {
        menu.handle(request(menu, Action.SELECT_VOLUME, 0, 0, volume));
    }

    private static void setDefault(StorageMenu menu) {
        menu.handle(request(menu, Action.SET_DEFAULT_VOLUME, 0, 0, menu.snapshot().getString("Volume")));
    }

    private static String configure(GameTestHelper h, Scene s) {
        String id = s.nas.volume(0).id().toString();
        StorageMenu menu = open(h, s.terminal);
        select(menu, id); setDefault(menu);
        h.assertTrue(s.terminal.defaultVolume().equals(id), "Default drive request failed");
        return id;
    }

    @GameTest(template = "empty")
    public static void defaultDriveSurvivesSaveAndOpensRootWithDepositsOnThatDrive(GameTestHelper h) {
        Scene s = scene(h);
        StorageMenu menu = open(h, s.terminal);
        h.assertTrue(menu.snapshot().getString("Volume").isEmpty(), "New terminals must start locally");
        String id = s.nas.volume(0).id().toString();
        int folder = s.nas.volume(0).inventory().createFolder(0, "Materials");
        select(menu, id);
        menu.handle(request(menu, Action.OPEN, folder, 0, ""));
        setDefault(menu);
        h.assertTrue(menu.currentFolder() == folder, "Setting default changed the current location");
        CompoundTag saved = s.terminal.saveWithFullMetadata();
        s.terminal.load(saved);
        StorageMenu reopened = open(h, s.terminal);
        h.assertTrue(reopened.snapshot().getString("Volume").equals(id) && reopened.currentFolder() == 0
                && reopened.snapshot().getString("DefaultVolume").equals(id), "Saved default did not reopen at the drive root");
        reopened.setCarried(new ItemStack(Items.IRON_INGOT, 7));
        reopened.handle(request(reopened, Action.DEPOSIT_CURSOR, 0, 0, ""));
        h.assertTrue(s.nas.volume(0).inventory().total() == 7 && s.terminal.inventory().total() == 0
                && reopened.getCarried().isEmpty(), "Opening default drive deposited into the wrong storage");
        select(reopened, ""); setDefault(reopened);
        s.terminal.load(s.terminal.saveWithFullMetadata());
        h.assertTrue(open(h, s.terminal).snapshot().getString("Volume").isEmpty()
                && !s.terminal.saveWithFullMetadata().contains("DefaultVolume"), "Resetting to the local drive was not saved");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void missingOrMalformedPreferenceKeepsOldSavesUsable(GameTestHelper h) {
        Scene s = scene(h);
        CompoundTag saved = s.terminal.saveWithFullMetadata();
        for (int variant = 0; variant < 3; variant++) {
            s.terminal.setDefaultVolume(s.nas.volume(0).id().toString());
            if (variant == 1) saved.putString("DefaultVolume", "invalid");
            if (variant == 2) saved.putIntArray("DefaultVolume", new int[] {1, 2});
            s.terminal.load(saved);
            h.assertTrue(s.terminal.defaultVolume().isEmpty() && !s.terminal.inventory().isLocked()
                    && open(h, s.terminal).snapshot().getString("Volume").isEmpty(), "Old or malformed preference blocked local storage");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void ejectionAndReplacementKeepPreferenceAndMovingTheSameDiskRestoresIt(GameTestHelper h) {
        Scene s = scene(h); String id = configure(h, s);
        StorageMenu active = open(h, s.terminal);
        var pending = request(active, Action.DEPOSIT_CURSOR, 0, 0, "");
        active.setCarried(new ItemStack(Items.DIAMOND, 3));
        ItemStack disk = s.nas.eject(0);
        s.nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        active.handle(pending);
        h.assertTrue(!active.snapshot().getBoolean("Available") && active.getCarried().getCount() == 3
                && s.terminal.inventory().total() == 0 && s.nas.volume(0).inventory().total() == 0,
                "An in-flight deposit was redirected after ejecting the default drive");
        h.assertTrue(open(h, s.terminal).snapshot().getString("Volume").isEmpty()
                && s.terminal.defaultVolume().equals(id), "Missing default did not fall back or lost its identity");
        s.other.install(3, disk); s.other.rename(3, "Renamed default");
        h.assertTrue(open(h, s.terminal).snapshot().getString("Volume").equals(id), "Moving/renaming a drive lost the preference");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void disconnectedAndProtectedDefaultsFallBackUntilAvailableAgain(GameTestHelper h) {
        Scene s = scene(h); String id = configure(h, s);
        h.getLevel().setBlockAndUpdate(s.cable, Blocks.AIR.defaultBlockState());
        StorageMenu fallback = open(h, s.terminal);
        h.assertTrue(fallback.snapshot().getString("Volume").isEmpty()
                && fallback.snapshot().getString("DefaultVolume").equals(id), "Cable disconnect cleared the preference");
        h.getLevel().setBlockAndUpdate(s.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        h.assertTrue(fallback.snapshot().getString("Volume").isEmpty()
                && open(h, s.terminal).snapshot().getString("Volume").equals(id), "Reconnect jumped an open menu or failed to reopen the default");
        var inventory = s.nas.volume(0).inventory();
        CompoundTag good = (CompoundTag) inventory.save(), broken = good.copy();
        broken.putInt("Version", 999); inventory.load(broken);
        h.assertTrue(open(h, s.terminal).snapshot().getString("Volume").isEmpty()
                && s.terminal.defaultVolume().equals(id) && inventory.save().equals(broken), "Protected default did not safely fall back");
        inventory.load(good);
        h.assertTrue(open(h, s.terminal).snapshot().getString("Volume").equals(id), "Repaired drive was not used on reopening");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void staleWrongDriveSearchAndOfflineRequestsCannotChangeDefault(GameTestHelper h) {
        Scene s = scene(h); String id = s.nas.volume(0).id().toString();
        StorageMenu menu = open(h, s.terminal); select(menu, id);
        var valid = request(menu, Action.SET_DEFAULT_VOLUME, 0, 0, id);
        menu.handle(valid.withSession(valid.session() + 1));
        menu.handle(new StorageNetwork.Request(menu.containerId, valid.revision() - 1,
                Action.SET_DEFAULT_VOLUME, 0, 0, 0, id, valid.session()));
        menu.handle(request(menu, Action.SET_DEFAULT_VOLUME, 0, 0, UUID.randomUUID().toString()));
        h.assertTrue(s.terminal.defaultVolume().isEmpty(), "Invalid request changed the default");
        var view = menu.snapshot();
        menu.handleSearch(new StorageNetwork.SearchRequest(menu.containerId, view.getLong("Session"), 1, -1, 0,
                StorageNetwork.SearchAction.START, false, 0, 6, 0, 0, view.getLong("Revision"), new int[0]));
        menu.handle(valid);
        h.assertTrue(menu.snapshot().getBoolean("Searching") && s.terminal.defaultVolume().isEmpty(), "Search allowed a default change");
        StorageMenu fresh = open(h, s.terminal); select(fresh, id);
        s.nas.eject(0);
        setDefault(fresh);
        h.assertTrue(s.terminal.defaultVolume().isEmpty(), "Offline drive became the default");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void preferenceSyncsToViewersWithoutMovingThemOrChangingAnotherTerminal(GameTestHelper h) throws Exception {
        Scene s = scene(h); StorageMenu viewer = open(h, s.terminal);
        viewer.broadcastChanges();
        String id = configure(h, s);
        viewer.broadcastChanges();
        var sentView = StorageMenu.class.getDeclaredField("sentView"); sentView.setAccessible(true);
        CompoundTag sent = (CompoundTag) sentView.get(viewer);
        h.assertTrue(sent.getString("DefaultVolume").equals(id) && sent.getString("Volume").isEmpty(),
                "Default was not broadcast to other viewers, or changed their current drive");
        BlockPos otherPos = new BlockPos(2, 2, 2); h.setBlock(otherPos, ModContent.STORAGE_BLOCK.get());
        var other = (StorageBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(otherPos));
        h.assertTrue(open(h, other).snapshot().getString("Volume").isEmpty() && other.defaultVolume().isEmpty(),
                "One terminal changed another terminal's opening preference");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void programReturnRestoresLocalFolderDespiteDifferentDefaultDrive(GameTestHelper h) {
        Scene s = scene(h); String id = configure(h, s);
        int folder = s.terminal.inventory().createFolder(0, "Programs");
        StorageMenu returning = open(h, s.terminal);
        returning.restoreLocation(folder, 0, 12);
        var view = returning.snapshot();
        h.assertTrue(view.getString("Volume").isEmpty() && view.getInt("Current") == folder
                && view.getInt("PageSize") == 12 && view.getString("DefaultVolume").equals(id), "Program return was redirected to the default disk");
        StorageMenu deletedFolder = open(h, s.terminal);
        deletedFolder.restoreLocation(999, 0);
        h.assertTrue(deletedFolder.snapshot().getString("Volume").isEmpty() && deletedFolder.currentFolder() == 0,
                "Returning to a deleted program folder did not fall back to local root");
        h.succeed();
    }
}
