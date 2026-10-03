package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.storage.StorageInventory;
import dev.itemexplorer.transfer.RemoteTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemoteTransferTests {
    record Station(BlockPos center, BlockPos cable, BaseStationBlockEntity controller,
                   StorageBlockEntity first, StorageBlockEntity second) {}

    /** Keep independent towers within this test's horizontal footprint and remove them even after an assertion. */
    static final class Scene implements AutoCloseable {
        private final GameTestHelper helper;
        private final List<BlockPos> placed = new ArrayList<>();

        Scene(GameTestHelper helper) { this.helper = helper; }

        private void place(BlockPos pos, BlockState state) {
            helper.getLevel().setBlockAndUpdate(pos, state);
            placed.add(pos.immutable());
        }

        private void place(BlockPos pos, Block block) { place(pos, block.defaultBlockState()); }

        private StorageBlockEntity terminal(BlockPos pos) {
            place(pos, ModContent.STORAGE_BLOCK.get());
            StorageBlockEntity terminal = (StorageBlockEntity) helper.getLevel().getBlockEntity(pos);
            RemoteTransfers.register(terminal);
            return terminal;
        }

        Station station(int floor) {
            BlockPos center = helper.absolutePos(new BlockPos(2, 6 + floor * 6, 1));
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                place(center.offset(x, 0, z), ModContent.BASE_STATION_CASING_BLOCK.get());
            place(center.north(), ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationControllerBlock.FACING, Direction.NORTH));
            place(center.south(), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, Direction.SOUTH));
            for (Direction side : List.of(Direction.EAST, Direction.WEST))
                place(center.relative(side), ModContent.BASE_STATION_MODULE_BLOCK.get().defaultBlockState()
                        .setValue(BaseStationPartBlock.FACING, side));
            for (int y = 1; y <= 3; y++) place(center.above(y), ModContent.BASE_STATION_MAST_BLOCK.get());
            for (Direction side : Direction.Plane.HORIZONTAL)
                place(center.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState()
                        .setValue(BaseStationPartBlock.FACING, side));
            place(center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get());
            BlockPos cable = center.south(2);
            place(cable, ModContent.DATA_CABLE_BLOCK.get());
            StorageBlockEntity first = terminal(cable.west()), second = terminal(cable.east());
            BaseStationBlockEntity controller = (BaseStationBlockEntity) helper.getLevel().getBlockEntity(center.north());
            helper.assertTrue(controller.validation().complete(), "Transfer fixture has an incomplete tower");
            return new Station(center, cable, controller, first, second);
        }

        NasBlockEntity nas(Station station) {
            BlockPos pos = station.cable.south();
            place(pos, ModContent.NAS_BLOCK.get());
            NasBlockEntity nas = (NasBlockEntity) helper.getLevel().getBlockEntity(pos);
            nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
            return nas;
        }

        @Override public void close() {
            for (int i = placed.size() - 1; i >= 0; i--)
                helper.getLevel().setBlockAndUpdate(placed.get(i), Blocks.AIR.defaultBlockState());
        }
    }

    private static long revision(StorageBlockEntity terminal) {
        return RemoteTransfers.configuration(terminal).getLong("ConfigRevision");
    }

    private static void configure(StorageBlockEntity terminal, String volume, int folder, boolean enabled) {
        RemoteTransfers.configure(terminal, "收件站", volume, folder, enabled, revision(terminal));
    }

    private static long send(StorageBlockEntity source, String volume, int entry, long amount, StorageBlockEntity target) {
        return RemoteTransfers.send(source, volume, entry, amount, target.productionIdentity(), revision(target));
    }

    private static int seed(StorageInventory inventory, int amount) {
        inventory.insert(new ItemStack(Items.IRON_INGOT, amount), amount, 0);
        return inventory.entries().get(0).id();
    }

    private static long count(StorageInventory inventory, int folder) {
        return inventory.entries().stream().filter(entry -> entry.folder() == folder).mapToLong(StorageInventory.Entry::count).sum();
    }

    private static CompoundTag target(StorageBlockEntity source, StorageBlockEntity destination) {
        for (Tag tag : RemoteTransfers.targets(source)) {
            CompoundTag candidate = (CompoundTag) tag;
            if (candidate.hasUUID("Id") && candidate.getUUID("Id").equals(destination.productionIdentity())) return candidate;
        }
        return null;
    }

    private static void rejected(GameTestHelper helper, Runnable action, String reason) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, reason);
    }

    @GameTest(template = "empty")
    public static void localStationTransfersWithoutWirelessAndDefaultsToClosedReception(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            StorageBlockEntity source = station.first, destination = station.second;
            h.assertTrue(!station.controller.networkOnline() && !RemoteTransfers.configuration(destination).getBoolean("Enabled"),
                    "A new station or receiver was silently opened");
            int entry = seed(source.inventory(), 64);
            rejected(h, () -> send(source, "", entry, 24, destination), "Closed receiver accepted a local transfer");
            int parent = destination.inventory().createFolder(0, "材料");
            int inbox = destination.inventory().createFolder(parent, "收件箱");
            configure(destination, "", inbox, true);
            long inventoryRevision = destination.inventory().revision(), configRevision = revision(destination);
            CompoundTag peer = target(source, destination);
            h.assertTrue(peer != null && peer.getBoolean("Local") && peer.getLong("Revision") == revision(destination),
                    "A local receiver was hidden while wireless was disabled or had the wrong configuration revision");
            h.assertTrue(peer.contains("Volume", Tag.TAG_STRING) && peer.getString("Volume").isEmpty()
                            && peer.contains("InboxVolumeName", Tag.TAG_STRING) && peer.getString("InboxVolumeName").isEmpty()
                            && peer.getString("InboxPath").equals("/材料 / 收件箱")
                            && peer.getString("InboxPath").equals(RemoteTransfers.configuration(destination).getString("InboxPath"))
                            && destination.inventory().revision() == inventoryRevision && revision(destination) == configRevision,
                    "Local target metadata lost its nested inbox path, disagreed with receiver settings, or mutated state");
            destination.inventory().renameFolder(parent, "工程");
            destination.inventory().renameFolder(inbox, "到货");
            CompoundTag renamed = target(source, destination);
            h.assertTrue(renamed != null && renamed.getString("InboxPath").equals("/工程 / 到货")
                            && renamed.getLong("Revision") == configRevision,
                    "Target discovery kept a stale folder path or changed the stable receiver binding after rename");
            h.assertTrue(send(source, "", entry, 24, destination) == 24 && source.inventory().total() == 40
                            && count(destination.inventory(), inbox) == 24 && count(destination.inventory(), 0) == 0,
                    "Local send did not atomically move the requested items into the configured folder");
            h.assertTrue(!RemoteTransfers.configuration(source).getBoolean("Enabled"), "Sending unnecessarily enabled source reception");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void crossStationDiscoveryAndSendRequireBothWirelessSwitches(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station a = scene.station(0), b = scene.station(1);
            int entry = seed(a.first.inventory(), 64);
            configure(b.first, "", 0, true);
            a.controller.setNetworkOnline(true);
            h.assertTrue(target(a.first, b.first) == null, "Receiver with a closed wireless switch was advertised");
            rejected(h, () -> send(a.first, "", entry, 8, b.first), "Closed destination wireless switch was ignored");
            a.controller.setNetworkOnline(false);
            b.controller.setNetworkOnline(true);
            h.assertTrue(target(a.first, b.first) == null, "Sender with a closed wireless switch discovered a remote receiver");
            rejected(h, () -> send(a.first, "", entry, 8, b.first), "Closed source wireless switch was ignored");
            a.controller.setNetworkOnline(true);
            configure(b.first, "", 0, false);
            rejected(h, () -> send(a.first, "", entry, 8, b.first), "Wireless connectivity bypassed receiver opt-in");
            configure(b.first, "", 0, true);
            CompoundTag peer = target(a.first, b.first);
            h.assertTrue(peer != null && !peer.getBoolean("Local"), "A ready remote terminal was not discoverable");
            h.assertTrue(send(a.first, "", entry, 8, b.first) == 8 && a.first.inventory().total() == 56
                    && b.first.inventory().total() == 8, "Wireless switch failures changed inventory or consumed transfer capacity");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void brokenCableAndEitherIncompleteTowerRejectCachedTargetsImmediately(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station a = scene.station(0), b = scene.station(1);
            a.controller.setNetworkOnline(true); b.controller.setNetworkOnline(true);
            configure(b.first, "", 0, true);
            int entry = seed(a.first.inventory(), 64);
            long sourceRevision = a.first.inventory().revision(), destinationRevision = b.first.inventory().revision();
            h.getLevel().setBlockAndUpdate(b.cable, Blocks.AIR.defaultBlockState());
            rejected(h, () -> send(a.first, "", entry, 64, b.first), "Cached target survived a severed cable");
            h.getLevel().setBlockAndUpdate(b.cable, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
            for (Station station : List.of(a, b)) {
                h.getLevel().setBlockAndUpdate(station.center.above(4), Blocks.AIR.defaultBlockState());
                rejected(h, () -> send(a.first, "", entry, 64, b.first), "Cached target survived a broken tower");
                h.getLevel().setBlockAndUpdate(station.center.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
            }
            h.assertTrue(a.first.inventory().revision() == sourceRevision && b.first.inventory().revision() == destinationRevision,
                    "Failed connectivity validation mutated storage");
            h.assertTrue(send(a.first, "", entry, 64, b.first) == 64 && a.first.inventory().total() == 0
                    && b.first.inventory().total() == 64, "Repair did not immediately restore transfers");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void staleReceiverConfigurationCannotSendOrOverwriteNewSettings(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            int entry = seed(station.first.inventory(), 32);
            int oldInbox = station.second.inventory().createFolder(0, "旧收件箱");
            int newInbox = station.second.inventory().createFolder(0, "新收件箱");
            configure(station.second, "", oldInbox, true);
            long oldRevision = revision(station.second);
            configure(station.second, "", newInbox, true);
            h.assertTrue(revision(station.second) > oldRevision, "Changed receiver binding did not advance its revision");
            rejected(h, () -> RemoteTransfers.send(station.first, "", entry, 8, station.second.productionIdentity(), oldRevision),
                    "Stale send followed a changed receiver binding");
            rejected(h, () -> RemoteTransfers.configure(station.second, "过期修改", "", oldInbox, true, oldRevision),
                    "Stale configuration overwrote a newer receiver binding");
            h.assertTrue(RemoteTransfers.configuration(station.second).getInt("Folder") == newInbox,
                    "Rejected configuration changed the destination");
            h.assertTrue(send(station.first, "", entry, 8, station.second) == 8 && count(station.second.inventory(), newInbox) == 8
                    && count(station.second.inventory(), oldInbox) == 0, "Fresh send did not use the reviewed receiver binding");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void deletedInboxDoesNotFallBackToRootOrAReusedFolderName(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            int entry = seed(station.first.inventory(), 32);
            int inbox = station.second.inventory().createFolder(0, "收件箱");
            configure(station.second, "", inbox, true);
            station.second.inventory().deleteFolder(inbox);
            int replacement = station.second.inventory().createFolder(0, "收件箱");
            rejected(h, () -> send(station.first, "", entry, 8, station.second), "Deleted folder silently retargeted a new folder or root");
            h.assertTrue(station.first.inventory().total() == 32 && station.second.inventory().total() == 0,
                    "Missing receiver folder changed item counts");
            configure(station.second, "", replacement, true);
            h.assertTrue(send(station.first, "", entry, 8, station.second) == 8 && count(station.second.inventory(), replacement) == 8,
                    "An explicit new binding did not restore reception");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void insufficientCapacityRejectsWholeBatchWithoutConsumingBandwidth(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            int entry = seed(station.first.inventory(), 64);
            StorageInventory destination = station.second.inventory();
            seed(destination, (int) destination.capacity() - 4);
            configure(station.second, "", 0, true);
            long sourceRevision = station.first.inventory().revision(), destinationRevision = destination.revision();
            rejected(h, () -> send(station.first, "", entry, 8, station.second), "Full receiver partially accepted a batch");
            h.assertTrue(station.first.inventory().total() == 64 && destination.total() == destination.capacity() - 4
                            && station.first.inventory().revision() == sourceRevision && destination.revision() == destinationRevision,
                    "Capacity refusal changed inventory or revision");
            h.assertTrue(send(station.first, "", entry, 4, station.second) == 4 && destination.total() == destination.capacity()
                    && station.first.inventory().total() == 60, "Rejected batch consumed the station's transfer budget");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void invalidQuantitiesAndInsufficientSourceNeverPartiallyTransfer(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            int entry = seed(station.first.inventory(), 32);
            configure(station.second, "", 0, true);
            for (long amount : new long[]{0, -1, 33, 64, 65, Long.MAX_VALUE})
                rejected(h, () -> send(station.first, "", entry, amount, station.second), "Invalid quantity was accepted: " + amount);
            h.assertTrue(station.first.inventory().total() == 32 && station.second.inventory().total() == 0,
                    "Invalid amount changed item totals");
            h.assertTrue(send(station.first, "", entry, 16, station.second) == 16,
                    "Invalid amount consumed the station's transfer budget");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void batchLimitCountsItemsAndPreservesTagsForUnstackableItems(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            ItemStack swords = new ItemStack(Items.DIAMOND_SWORD, 2);
            swords.getOrCreateTag().putString("RemoteTransferTest", "preserve-me");
            swords.setDamageValue(17);
            station.first.inventory().insert(swords, 2, 0);
            int entry = station.first.inventory().entries().get(0).id();
            configure(station.second, "", 0, true);
            h.assertTrue(send(station.first, "", entry, 2, station.second) == 2 && station.first.inventory().total() == 0
                            && station.second.inventory().total() == 2
                            && ItemStack.isSameItemSameTags(station.second.inventory().entries().get(0).stack(), swords),
                    "An unstackable batch was clipped to a vanilla stack or lost item tags");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void sharedNasAliasesMoveWithinAFullDiskAndRejectTheSameFolder(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            NasBlockEntity nas = scene.nas(station);
            String volume = DiskItem.id(nas.disk(0)).toString();
            StorageInventory inventory = nas.volume(0).inventory();
            int entry = seed(inventory, (int) inventory.capacity());
            int inbox = inventory.createFolder(0, "同盘收件箱");
            configure(station.second, volume, 0, true);
            rejected(h, () -> send(station.first, volume, entry, 32, station.second), "Two terminals disguised a send to the same inventory folder");
            configure(station.second, volume, inbox, true);
            h.assertTrue(send(station.first, volume, entry, 32, station.second) == 32 && inventory.total() == inventory.capacity()
                            && count(inventory, inbox) == 32 && count(inventory, 0) == inventory.capacity() - 32,
                    "Same-disk transfer duplicated items or incorrectly required extra free capacity");
            h.assertTrue(station.first.inventory().total() == 0 && station.second.inventory().total() == 0,
                    "NAS alias transfer leaked into terminal local storage");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void removedReceiverDiskNeverRetargetsAnotherBayAndCanReturnInADifferentBay(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station a = scene.station(0), b = scene.station(1);
            a.controller.setNetworkOnline(true); b.controller.setNetworkOnline(true);
            NasBlockEntity nas = scene.nas(b);
            String volume = DiskItem.id(nas.disk(0)).toString();
            StorageInventory originalInventory = nas.volume(0).inventory();
            nas.rename(0, "材料盘");
            int parent = originalInventory.createFolder(0, "项目");
            int inbox = originalInventory.createFolder(parent, "收件箱");
            configure(b.first, volume, inbox, true);
            long configRevision = revision(b.first), inventoryRevision = originalInventory.revision();
            CompoundTag peer = target(a.first, b.first), settings = RemoteTransfers.configuration(b.first);
            h.assertTrue(peer != null && peer.getString("Volume").equals(volume)
                            && peer.getString("InboxVolumeName").equals("材料盘") && peer.getString("InboxPath").equals("/项目 / 收件箱")
                            && peer.getString("InboxVolumeName").equals(settings.getString("InboxVolumeName"))
                            && peer.getString("InboxPath").equals(settings.getString("InboxPath"))
                            && originalInventory.revision() == inventoryRevision && revision(b.first) == configRevision,
                    "NAS target metadata did not describe the reachable bound disk and inbox without changing inventory");
            nas.rename(0, "归档盘");
            originalInventory.renameFolder(parent, "已归档");
            CompoundTag renamed = target(a.first, b.first);
            h.assertTrue(renamed != null && renamed.getString("InboxVolumeName").equals("归档盘")
                            && renamed.getString("InboxPath").equals("/已归档 / 收件箱") && renamed.getLong("Revision") == configRevision,
                    "Target discovery retained an old disk/folder name or changed its stable receiver revision");
            int entry = seed(a.first.inventory(), 32);
            ItemStack originalDisk = nas.eject(0);
            nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
            StorageInventory replacement = nas.volume(0).inventory();
            replacement.createFolder(0, "收件箱");
            h.assertTrue(target(a.first, b.first) == null, "Offline receiver disk metadata leaked through target discovery");
            rejected(h, () -> send(a.first, "", entry, 8, b.first), "Receiver binding followed a replacement disk in the same bay");
            h.assertTrue(originalInventory.total() == 0 && replacement.total() == 0 && b.first.inventory().total() == 0
                    && a.first.inventory().total() == 32, "Offline bound disk fell back to another inventory");
            nas.install(3, originalDisk);
            h.assertTrue(send(a.first, "", entry, 8, b.first) == 8 && count(originalInventory, inbox) == 8 && replacement.total() == 0,
                    "Restored stable disk identity did not reconnect in its new bay");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void replacingTerminalAtTheSamePositionCannotRetargetItsOldIdentity(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station station = scene.station(0);
            configure(station.second, "", 0, true);
            UUID oldIdentity = station.second.productionIdentity();
            long oldRevision = revision(station.second);
            int entry = seed(station.first.inventory(), 32);
            BlockPos pos = station.second.getBlockPos();
            h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            StorageBlockEntity replacement = scene.terminal(pos);
            configure(replacement, "", 0, true);
            h.assertTrue(!replacement.productionIdentity().equals(oldIdentity), "Replacement terminal reused another terminal's identity");
            rejected(h, () -> RemoteTransfers.send(station.first, "", entry, 8, oldIdentity, oldRevision),
                    "A stale target followed a terminal replacement at the same coordinates");
            h.assertTrue(station.first.inventory().total() == 32 && replacement.inventory().total() == 0,
                    "Stale receiver identity changed inventory");
            h.assertTrue(send(station.first, "", entry, 8, replacement) == 8, "New terminal could not be explicitly selected");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void normalSaveReloadPreservesReceiverIdentityFolderAndWirelessChoice(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            Station a = scene.station(0), b = scene.station(1);
            a.controller.setNetworkOnline(true); b.controller.setNetworkOnline(true);
            int inbox = b.first.inventory().createFolder(0, "原收件箱");
            configure(b.first, "", inbox, true);
            b.first.inventory().renameFolder(inbox, "已改名的收件箱");
            UUID identity = b.first.productionIdentity();
            CompoundTag settings = RemoteTransfers.configuration(b.first).copy();
            CompoundTag saved = b.first.saveWithFullMetadata();
            BlockPos pos = b.first.getBlockPos();
            BlockState state = h.getLevel().getBlockState(pos);
            h.getLevel().removeBlockEntity(pos);
            StorageBlockEntity restored = new StorageBlockEntity(pos, state);
            restored.load(saved);
            h.getLevel().setBlockEntity(restored);
            restored.onLoad();
            RemoteTransfers.register(restored);
            h.assertTrue(restored.productionIdentity().equals(identity) && RemoteTransfers.configuration(restored).equals(settings)
                            && restored.inventory().hasFolder(inbox), "Normal save/reload lost receiver identity or its stable directory binding");
            BaseStationBlockEntity restoredController = new BaseStationBlockEntity(b.controller.getBlockPos(), b.controller.getBlockState());
            restoredController.load(b.controller.saveWithFullMetadata());
            h.assertTrue(restoredController.networkOnline(), "Normal save/reload lost the explicit wireless choice");
            int entry = seed(a.first.inventory(), 32);
            h.assertTrue(send(a.first, "", entry, 8, restored) == 8 && count(restored.inventory(), inbox) == 8,
                    "Renamed and reloaded receiver folder was not used");
            StorageBlockEntity savedSource = new StorageBlockEntity(a.first.getBlockPos(), a.first.getBlockState());
            StorageBlockEntity savedDestination = new StorageBlockEntity(pos, state);
            savedSource.load(a.first.saveWithFullMetadata());
            savedDestination.load(restored.saveWithFullMetadata());
            h.assertTrue(savedSource.inventory().total() == 24 && savedDestination.inventory().total() == 8,
                    "Normally saved committed inventories failed to retain the transfer");
            CompoundTag malformed = restored.saveWithFullMetadata();
            malformed.getCompound("RemoteReceiver").putByte("Enabled", (byte) 2);
            savedDestination.load(malformed);
            h.assertTrue(!savedDestination.transferConfig().enabled() && savedDestination.inventory().total() == 8,
                    "Malformed receiver policy opened reception or changed saved inventory");
            malformed = restored.saveWithFullMetadata();
            malformed.getCompound("RemoteReceiver").putInt("Version", 999);
            savedDestination.load(malformed);
            h.assertTrue(!savedDestination.transferConfig().enabled() && savedDestination.inventory().total() == 8,
                    "Unknown receiver policy version opened reception or discarded inventory");
            h.succeed();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void stationBudgetIsSharedByAllTerminalsAndBothDirectionsAndRecoversAfterTwentyTicks(GameTestHelper h) {
        Scene scene = new Scene(h);
        try {
            Station a = scene.station(0), b = scene.station(1), c = scene.station(2);
            for (Station station : List.of(a, b, c)) {
                station.controller.setNetworkOnline(true);
                configure(station.first, "", 0, true);
                configure(station.second, "", 0, true);
                seed(station.first.inventory(), 64);
                seed(station.second.inventory(), 64);
            }
            int aEntry = a.first.inventory().entries().get(0).id();
            int otherAEntry = a.second.inventory().entries().get(0).id();
            int bEntry = b.first.inventory().entries().get(0).id();
            int cEntry = c.first.inventory().entries().get(0).id();
            h.assertTrue(send(a.first, "", aEntry, 8, b.first) == 8, "Initial station transfer failed");
            rejected(h, () -> send(a.second, "", otherAEntry, 8, c.first), "Another terminal bypassed source station bandwidth");
            rejected(h, () -> send(c.first, "", cEntry, 8, b.second), "Another terminal bypassed destination station bandwidth");
            rejected(h, () -> send(b.first, "", bEntry, 8, a.first), "Receiving did not consume the station's outgoing budget");
            rejected(h, () -> send(b.first, "", bEntry, 8, b.second), "Local transfer bypassed the same station bandwidth budget");
            h.runAfterDelay(19, () -> {
                try { rejected(h, () -> send(a.first, "", aEntry, 8, b.first), "Station bandwidth recovered before 20 ticks"); }
                catch (RuntimeException | Error failure) { scene.close(); throw failure; }
            });
            h.runAfterDelay(20, () -> {
                try (scene) {
                    h.assertTrue(send(a.first, "", aEntry, 8, b.first) == 8 && a.first.inventory().total() == 48
                                    && b.first.inventory().total() == 80 && a.second.inventory().total() == 64
                                    && b.second.inventory().total() == 64 && c.first.inventory().total() == 64,
                            "Bandwidth did not recover at 20 ticks or a rejected transfer changed counts");
                    h.succeed();
                }
            });
        } catch (RuntimeException | Error failure) { scene.close(); throw failure; }
    }
}
