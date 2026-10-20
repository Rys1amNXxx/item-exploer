package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.Action;
import dev.itemexplorer.network.StorageNetwork.PortableAction;
import dev.itemexplorer.network.StorageNetwork.PortableRequest;
import dev.itemexplorer.network.StorageNetwork.SearchAction;
import dev.itemexplorer.portable.PortableAccess;
import dev.itemexplorer.portable.PortableConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

import static dev.itemexplorer.gametest.GameTestSupport.*;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PortableTerminalTests {
    private record Viewer(ServerPlayer player, StorageMenu menu) {}

    private static ServerPlayer player(GameTestHelper h, StorageBlockEntity terminal, InteractionHand hand) {
        ServerPlayer player = GameTestSupport.player(h, terminal.getBlockPos());
        player.getInventory().selected = 0;
        player.setItemInHand(hand, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
        return player;
    }

    private static void far(ServerPlayer player, StorageBlockEntity terminal) {
        BlockPos pos = terminal.getBlockPos();
        player.setPos(pos.getX() + 1024.5, pos.getY() + 1, pos.getZ() + 1024.5);
    }

    private static Viewer open(GameTestHelper h, RemoteTransferTests.Station station, InteractionHand hand) {
        station.controller().setNetworkOnline(true);
        station.first().setPortableEnabled(true);
        ServerPlayer player = player(h, station.first(), hand);
        h.assertTrue(PortableAccess.pair(player, hand, station.first()).isEmpty(), "Nearby opted-in terminal did not pair");
        far(player, station.first());
        return reopen(h, player, hand, 1);
    }

    private static Viewer reopen(GameTestHelper h, ServerPlayer player, InteractionHand hand, int id) {
        var access = PortableAccess.openSession(player, hand);
        h.assertTrue(access.status().isEmpty() && access.terminal() != null && access.session() != null,
                "Paired portable terminal did not open: " + access.status());
        StorageMenu menu = new StorageMenu(id, player.getInventory(), access.terminal().getBlockPos(), access.terminal(), access.session());
        player.containerMenu = menu;
        menu.broadcastChanges();
        return new Viewer(player, menu);
    }

    private static PortableRequest policy(StorageMenu menu, PortableAction action, boolean enabled) {
        CompoundTag view = menu.snapshot();
        return new PortableRequest(menu.containerId, view.getLong("Session"), view.getLong("PortableAccessRevision"), action, enabled);
    }

    private static StorageMenu physical(ServerPlayer player, StorageBlockEntity terminal, int id) {
        standOn(player, terminal.getBlockPos());
        StorageMenu menu = GameTestSupport.open(player, terminal, id);
        menu.broadcastChanges();
        return menu;
    }

    @GameTest(template = "empty")
    public static void pairingRequiresLocalOptInAndPersistsTheRegisteredGrant(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            ServerPlayer player = player(h, station.first(), InteractionHand.MAIN_HAND);
            h.assertTrue(!PortableAccess.pair(player, InteractionHand.MAIN_HAND, station.first()).isEmpty()
                            && PortableAccess.binding(player.getMainHandItem()) == null && station.first().portableDeviceCount() == 0,
                    "A closed terminal paired or retained an unregistered binding");
            station.first().setPortableEnabled(true);
            far(player, station.first());
            h.assertTrue(!PortableAccess.pair(player, InteractionHand.MAIN_HAND, station.first()).isEmpty()
                            && station.first().portableDeviceCount() == 0,
                    "Pairing bypassed the physical proximity requirement");
            standOn(player, station.first().getBlockPos());
            h.assertTrue(PortableAccess.pair(player, InteractionHand.MAIN_HAND, station.first()).isEmpty(), "Local pairing failed");
            var binding = PortableAccess.binding(player.getMainHandItem());
            h.assertTrue(binding != null && station.first().acceptsPortableDevice(binding.device(), binding.grant()),
                    "Pairing failed to register the device grant on the target");
            long revision = station.first().portableRevision();
            PortableAccess.pair(player, InteractionHand.MAIN_HAND, station.first());
            h.assertTrue(station.first().portableDeviceCount() == 1 && station.first().portableRevision() == revision,
                    "Pairing the same authorized device duplicated or rotated its grant");
            station.first().load(station.first().saveWithFullMetadata());
            h.assertTrue(station.first().portableEnabled() && station.first().portableDeviceCount() == 1
                            && station.first().acceptsPortableDevice(binding.device(), binding.grant())
                            && !station.first().transferConfig().enabled(),
                    "Saving lost the device grant or portable opt-in changed receiving policy");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void distantPortableAccessCanWithdrawAndDepositOnLocalAndNasVolumes(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var nas = scene.nas(station);
            station.first().inventory().insert(new ItemStack(Items.IRON_INGOT, 16), 16, 0);
            nas.volume(0).inventory().insert(new ItemStack(Items.DIAMOND, 7), 7, 0);
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            CompoundTag view = v.menu.snapshot();
            h.assertTrue(v.menu.stillValid(v.player) && view.getBoolean("Portable") && view.getBoolean("PortableConnected")
                            && view.getBoolean("Available"), "Long distance portable access was rejected or not identified");
            v.menu.handle(request(v.menu, Action.WITHDRAW, station.first().inventory().entries().get(0).id(), 6, ""));
            h.assertTrue(carried(v.player, Items.IRON_INGOT) == 6 && station.first().inventory().total() == 10,
                    "A distant local withdrawal lost or duplicated items");
            v.menu.handle(request(v.menu, Action.SELECT_VOLUME, 0, 0, nas.volume(0).id().toString()));
            v.menu.handle(request(v.menu, Action.WITHDRAW, nas.volume(0).inventory().entries().get(0).id(), 3, ""));
            v.menu.setCarried(new ItemStack(Items.GOLD_INGOT, 4));
            v.menu.handle(request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
            h.assertTrue(carried(v.player, Items.DIAMOND) == 3 && nas.volume(0).inventory().total() == 8
                            && station.first().inventory().total() == 10 && v.menu.getCarried().isEmpty(),
                    "Distant NAS access changed the wrong drive or lost items");
            v.menu.handle(request(v.menu, Action.SELECT_VOLUME, 0, 0, ""));
            v.menu.setCarried(new ItemStack(Items.COBBLESTONE, 2));
            v.menu.handle(request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
            h.assertTrue(station.first().inventory().total() == 12 && nas.volume(0).inventory().total() == 8,
                    "Returning to the local drive redirected the deposit to NAS");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void activeHotbarDeviceCannotBePickedSwappedThrownOrDeposited(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            ItemStack device = v.player.getMainHandItem();
            v.player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 2));
            h.assertTrue(!v.menu.slots.get(27).mayPickup(v.player) && !v.menu.slots.get(27).mayPlace(new ItemStack(Items.STONE))
                            && v.menu.slots.get(0).mayPickup(v.player) && v.menu.slots.get(0).mayPlace(new ItemStack(Items.STONE)),
                    "Slot permissions confused inventory indices with menu slot indices");
            v.menu.clicked(27, 0, ClickType.PICKUP, v.player);
            v.menu.clicked(27, 0, ClickType.QUICK_MOVE, v.player);
            v.menu.clicked(27, 1, ClickType.THROW, v.player);
            v.menu.clicked(0, 0, ClickType.SWAP, v.player);
            v.menu.handle(request(v.menu, Action.DEPOSIT_SLOT, 27, 0, ""));
            v.menu.handle(request(v.menu, Action.DEPOSIT_SLOTS, 0, 1L << 27 | 1L, ""));
            h.assertTrue(v.player.getMainHandItem() == device && v.menu.getCarried().isEmpty()
                            && station.first().inventory().total() == 0 && carried(v.player, Items.DIAMOND) == 2,
                    "A vanilla click, forged deposit or batch naming the device moved anything");
            v.menu.handle(request(v.menu, Action.DEPOSIT_SLOT, 0, 0, ""));
            h.assertTrue(station.first().inventory().total() == 2 && carried(v.player, Items.DIAMOND) == 0,
                    "Protecting the device also blocked ordinary inventory deposits");
            v.player.getInventory().setItem(0, ItemStack.EMPTY);
            v.player.getInventory().setItem(1, device);
            h.assertTrue(!v.menu.stillValid(v.player), "Moving the active device to another slot kept the menu authorized");
            v.menu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
            v.menu.handle(request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
            h.assertTrue(station.first().inventory().total() == 2 && v.menu.getCarried().getCount() == 3,
                    "A menu that lost its device still mutated storage");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void activeOffhandDeviceRejectsSwapAndLosingItInvalidatesAccess(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            Viewer v = open(h, station, InteractionHand.OFF_HAND);
            ItemStack device = v.player.getOffhandItem();
            v.player.getInventory().setItem(9, new ItemStack(Items.DIAMOND));
            v.menu.clicked(0, 40, ClickType.SWAP, v.player);
            h.assertTrue(v.player.getOffhandItem() == device && carried(v.player, Items.DIAMOND) == 1
                            && v.menu.stillValid(v.player), "The offhand swap key removed the active handheld device");
            v.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            h.assertTrue(!v.menu.stillValid(v.player), "Removing the offhand device kept access authorized");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void stationInterruptionPreservesLocationAndRejectsOldSessionAfterReconnect(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            int folder = station.first().inventory().createFolder(0, "外出材料");
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            v.menu.handle(request(v.menu, Action.OPEN, folder, 0, ""));
            v.menu.setCarried(new ItemStack(Items.DIAMOND, 3));
            var stale = request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, "");
            station.controller().setNetworkOnline(false);
            v.menu.handle(stale);
            CompoundTag offline = v.menu.snapshot();
            h.assertTrue(v.menu.stillValid(v.player) && !offline.getBoolean("PortableConnected") && !offline.getBoolean("Available")
                            && offline.getLong("Session") != stale.session() && v.menu.currentFolder() == folder
                            && !offline.getString("PortableStatus").isEmpty() && v.menu.getCarried().getCount() == 3
                            && station.first().inventory().total() == 0,
                    "Disconnect closed the handheld, lost its directory, or accepted an in-flight deposit");
            station.controller().setNetworkOnline(true);
            v.menu.handle(stale);
            h.assertTrue(v.menu.snapshot().getBoolean("PortableConnected") && v.menu.currentFolder() == folder
                            && station.first().inventory().total() == 0 && v.menu.getCarried().getCount() == 3,
                    "Reconnection revived an old operation or lost its directory");
            v.menu.handle(request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
            h.assertTrue(station.first().inventory().total() == 3 && station.first().inventory().entries().get(0).folder() == folder,
                    "A fresh operation after reconnect did not use the retained directory");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void revokedGrantCannotResumeAfterReenableUntilPairedAgain(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            var oldBinding = PortableAccess.binding(v.player.getMainHandItem());
            var old = request(v.menu, Action.CREATE, 0, 0, "forbidden");
            station.first().revokePortableDevices();
            v.menu.handle(old);
            h.assertTrue(!v.menu.snapshot().getBoolean("PortableConnected") && station.first().portableDeviceCount() == 0
                            && !station.first().acceptsPortableDevice(oldBinding.device(), oldBinding.grant())
                            && !PortableAccess.openSession(v.player, InteractionHand.MAIN_HAND).status().isEmpty(),
                    "Revocation left the item or open menu authorized");
            station.first().setPortableEnabled(false);
            station.first().setPortableEnabled(true);
            h.assertTrue(!PortableAccess.openSession(v.player, InteractionHand.MAIN_HAND).status().isEmpty(),
                    "Toggling portable access revived a revoked grant");
            standOn(v.player, station.first().getBlockPos());
            h.assertTrue(PortableAccess.pair(v.player, InteractionHand.MAIN_HAND, station.first()).isEmpty(),
                    "A revoked device could not explicitly pair again locally");
            var newBinding = PortableAccess.binding(v.player.getMainHandItem());
            h.assertTrue(!newBinding.grant().equals(oldBinding.grant()) && station.first().portableDeviceCount() == 1,
                    "Re-pairing reused a revoked secret or duplicated the device");
            far(v.player, station.first());
            Viewer reopened = reopen(h, v.player, InteractionHand.MAIN_HAND, 1);
            reopened.menu.handle(old);
            h.assertTrue(reopened.menu.currentFolder() == 0 && reopened.menu.session() != old.session(),
                    "Reusing a menu id accepted an operation from the revoked session");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void replacementAtTheSamePositionCannotInheritPortableAuthorization(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            v.menu.setCarried(new ItemStack(Items.DIAMOND, 3));
            var old = request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, "");
            BlockPos pos = station.first().getBlockPos();
            h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(pos, ModContent.STORAGE_BLOCK.get().defaultBlockState());
            var replacement = (StorageBlockEntity) h.getLevel().getBlockEntity(pos);
            replacement.setPortableEnabled(true);
            v.menu.handle(old);
            h.assertTrue(!v.menu.snapshot().getBoolean("Available") && replacement.inventory().total() == 0
                            && v.menu.getCarried().getCount() == 3
                            && !PortableAccess.openSession(v.player, InteractionHand.MAIN_HAND).status().isEmpty(),
                    "Replacing a terminal silently retargeted an existing device or in-flight action");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void missingNasDiskCannotRedirectPortableDepositsToLocalOrReplacementDisk(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var nas = scene.nas(station);
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            String id = nas.volume(0).id().toString();
            int folder = nas.volume(0).inventory().createFolder(0, "保留目录");
            v.menu.handle(request(v.menu, Action.SELECT_VOLUME, 0, 0, id));
            v.menu.handle(request(v.menu, Action.OPEN, folder, 0, ""));
            v.menu.setCarried(new ItemStack(Items.DIAMOND, 3));
            var pending = request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, "");
            ItemStack original = nas.eject(0);
            nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
            v.menu.handle(pending);
            CompoundTag offline = v.menu.snapshot();
            h.assertTrue(!offline.getBoolean("Available") && offline.getString("Volume").equals(id)
                            && offline.getInt("Current") == folder && v.menu.currentFolder() == folder
                            && station.first().inventory().total() == 0 && nas.volume(0).inventory().total() == 0
                            && v.menu.getCarried().getCount() == 3,
                    "A removed NAS disk redirected a portable deposit or lost its stable identity/directory");
            nas.eject(0);
            nas.install(0, original);
            v.menu.handle(pending);
            h.assertTrue(v.menu.snapshot().getBoolean("Available") && v.menu.currentFolder() == folder
                            && nas.volume(0).inventory().total() == 0 && v.menu.getCarried().getCount() == 3,
                    "Restoring the same NAS disk lost the directory or revived an in-flight deposit");
            v.menu.handle(request(v.menu, Action.DEPOSIT_CURSOR, 0, 0, ""));
            h.assertTrue(nas.volume(0).inventory().total() == 3 && nas.volume(0).inventory().entries().get(0).folder() == folder
                            && station.first().inventory().total() == 0 && v.menu.getCarried().isEmpty(),
                    "Fresh portable deposit after disk restoration did not use the retained directory");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void portableDefaultAndLastDirectoryAreSavedWithoutChangingThePhysicalTerminal(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var nas = scene.nas(station);
            String volume = nas.volume(0).id().toString();
            int folder = nas.volume(0).inventory().createFolder(0, "便携收藏");
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            v.menu.handle(request(v.menu, Action.SELECT_VOLUME, 0, 0, volume));
            v.menu.handle(request(v.menu, Action.OPEN, folder, 0, ""));
            v.menu.handle(request(v.menu, Action.SET_DEFAULT_VOLUME, 0, 0, volume));
            h.assertTrue(v.menu.snapshot().getString("DefaultVolume").equals(volume) && station.first().defaultVolume().isEmpty(),
                    "The handheld default changed the shared physical terminal preference");
            v.menu.removed(v.player);
            v.player.containerMenu = v.player.inventoryMenu;
            v.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.of(v.player.getMainHandItem().save(new CompoundTag())));
            Viewer reopened = reopen(h, v.player, InteractionHand.MAIN_HAND, 2);
            h.assertTrue(reopened.menu.snapshot().getString("Volume").equals(volume) && reopened.menu.currentFolder() == folder
                            && reopened.menu.snapshot().getString("DefaultVolume").equals(volume),
                    "Saving and reopening the device lost its default or last directory");
            var other = player(h, station.first(), InteractionHand.MAIN_HAND);
            h.assertTrue(PortableAccess.pair(other, InteractionHand.MAIN_HAND, station.first()).isEmpty(), "Second device pairing failed");
            far(other, station.first());
            Viewer independent = reopen(h, other, InteractionHand.MAIN_HAND, 3);
            h.assertTrue(independent.menu.snapshot().getString("Volume").isEmpty() && independent.menu.currentFolder() == 0
                            && independent.menu.snapshot().getString("DefaultVolume").isEmpty(),
                    "One handheld changed another handheld's browsing preference");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void portableSearchCanWithdrawNestedIngredientsAndCraftThemManually(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            int folder = station.first().inventory().createFolder(0, "染料材料");
            station.first().inventory().insert(new ItemStack(Items.CORNFLOWER, 2), 2, folder);
            int entry = station.first().inventory().entries().get(0).id();
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            CompoundTag view = v.menu.snapshot();
            v.menu.handleSearch(new StorageNetwork.SearchRequest(v.menu.containerId, view.getLong("Session"), 1,
                    view.getLong("SearchRevision"), view.getLong("ResultViewSeq"), SearchAction.START, false,
                    0, 6, 0, 0, view.getLong("Revision"), new int[0]));
            view = v.menu.snapshot();
            v.menu.handleSearch(new StorageNetwork.SearchRequest(v.menu.containerId, view.getLong("Session"), view.getLong("QuerySeq"),
                    view.getLong("SearchRevision"), view.getLong("ResultViewSeq"), SearchAction.APPLY, false,
                    0, 6, 0, 0, view.getLong("Revision"), new int[]{entry}));
            view = v.menu.snapshot();
            h.assertTrue(view.getBoolean("SearchReady") && view.getInt("SearchMatches") == 1, "Portable search lost a nested ingredient");
            v.menu.handleSearch(new StorageNetwork.SearchRequest(v.menu.containerId, view.getLong("Session"), view.getLong("QuerySeq"),
                    view.getLong("SearchRevision"), view.getLong("ResultViewSeq"), SearchAction.TAKE, false,
                    0, 6, entry, 2, view.getLong("Revision"), new int[0]));
            h.assertTrue(carried(v.player, Items.CORNFLOWER) == 2 && station.first().inventory().total() == 0,
                    "Portable search withdrawal lost or duplicated ingredients");
            v.menu.clicked(28, 0, ClickType.PICKUP, v.player);
            v.menu.clicked(StorageMenu.CRAFT_GRID_END - 1, 0, ClickType.PICKUP, v.player);
            v.menu.clicked(StorageMenu.CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE, v.player);
            h.assertTrue(carried(v.player, Items.BLUE_DYE) == 2 && carried(v.player, Items.CORNFLOWER) == 0
                            && v.player.getMainHandItem().is(ModContent.PORTABLE_TERMINAL_ITEM.get())
                            && station.first().inventory().total() == 0,
                    "Portable manual crafting lost ingredients, stored outputs, or consumed the device");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void portableMenusCannotChangeAccessPolicyReceivingPolicyOrProgramFiles(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            Viewer v = open(h, station, InteractionHand.MAIN_HAND);
            long revision = station.first().portableRevision();
            v.menu.handlePortable(policy(v.menu, PortableAction.ENABLE, false));
            v.menu.handlePortable(policy(v.menu, PortableAction.REVOKE_ALL, false));
            CompoundTag view = v.menu.snapshot();
            v.menu.handleTransfer(new StorageNetwork.TransferRequest(v.menu.containerId, view.getLong("Session"), view.getLong("Revision"),
                    station.first().transferConfig().revision(), StorageNetwork.TransferAction.RECEIVING, "", "", 0,
                    true, 0, 0, new UUID(0, 0), -1));
            v.menu.handleFile(new StorageNetwork.FileRequest(v.menu.containerId, view.getLong("Session"), view.getLong("Revision"),
                    view.getLong("ProgramRevision"), StorageNetwork.FileAction.CREATE, 0, 0, "remote.exe"));
            h.assertTrue(station.first().portableEnabled() && station.first().portableRevision() == revision
                            && station.first().portableDeviceCount() == 1 && !station.first().transferConfig().enabled()
                            && station.first().programs().files().isEmpty() && !v.menu.snapshot().getBoolean("ProgramsAvailable"),
                    "A forged handheld request changed access/receiving policy or created a program");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void physicalAccessPolicyRejectsStaleRevisionsAndStillRequiresProximity(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var player = player(h, station.first(), InteractionHand.MAIN_HAND);
            StorageMenu menu = physical(player, station.first(), 1);
            PortableRequest enable = policy(menu, PortableAction.ENABLE, true);
            PortableRequest staleRevoke = policy(menu, PortableAction.REVOKE_ALL, false);
            menu.handlePortable(enable);
            PortableAccess.pair(player, InteractionHand.MAIN_HAND, station.first());
            long revision = station.first().portableRevision();
            menu.handlePortable(staleRevoke);
            h.assertTrue(station.first().portableEnabled() && station.first().portableDeviceCount() == 1
                            && station.first().portableRevision() == revision,
                    "An old policy packet revoked a newly paired handheld");
            PortableRequest disable = policy(menu, PortableAction.ENABLE, false);
            far(player, station.first());
            h.assertTrue(!menu.stillValid(player), "Portable support removed physical terminal distance checks");
            menu.handlePortable(disable);
            h.assertTrue(station.first().portableEnabled(), "An out-of-range physical menu changed the policy");
            standOn(player, station.first().getBlockPos());
            menu.handlePortable(disable);
            h.assertTrue(!station.first().portableEnabled(), "A fresh nearby policy request could not close portable access");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void portablePolicyPacketsPreserveSessionAndRevisionBoundaries(GameTestHelper h) {
        for (PortableAction action : PortableAction.values()) {
            PortableRequest original = new PortableRequest(Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, action, true);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                original.encode(buffer);
                h.assertTrue(PortableRequest.decode(buffer).equals(original) && !buffer.isReadable(),
                        "Portable policy packet changed its field order or integer boundaries for " + action);
            } finally { buffer.release(); }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void corruptOrFuturePortableGrantsFailClosedAndRevokedSecretsStayInvalid(GameTestHelper h) {
        PortableConfig config = new PortableConfig();
        config.setEnabled(true);
        UUID device = UUID.randomUUID(), grant = config.grant(device, null);
        CompoundTag saved = config.save();
        config.load(saved);
        h.assertTrue(config.accepts(device, grant) && config.deviceCount() == 1, "Valid portable grant did not survive loading");
        CompoundTag future = saved.copy();
        future.putInt("Version", 999);
        config.load(future);
        h.assertTrue(!config.enabled() && !config.accepts(device, grant) && config.deviceCount() == 0,
                "An unsupported grant format kept wireless access enabled");
        CompoundTag malformed = saved.copy();
        malformed.putString("Devices", "invalid");
        config.load(malformed);
        h.assertTrue(!config.enabled() && !config.accepts(device, grant), "Malformed grant data opened wireless access");
        config.load(saved);
        config.revokeAll();
        config.load(config.save());
        h.assertTrue(config.enabled() && config.deviceCount() == 0 && !config.accepts(device, grant),
                "Saving after revocation restored an old authorization secret");
        h.succeed();
    }
}
