package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.disk.DiskItem;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.network.StorageNetwork.TransferAction;
import dev.itemexplorer.network.StorageNetwork.TransferRequest;
import dev.itemexplorer.transfer.RemoteTransfers;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemoteTransferProtocolTests {
    private static final UUID NO_TARGET = new UUID(0, 0);
    private record Viewer(ServerPlayer player, StorageMenu menu) {}

    private static Viewer open(GameTestHelper h, StorageBlockEntity terminal, int menuId) {
        ServerPlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "remote-protocol")) {
            @Override public boolean hasDisconnected() { return false; }
        };
        return open(terminal, player, menuId);
    }

    private static Viewer open(StorageBlockEntity terminal, ServerPlayer player, int menuId) {
        var pos = terminal.getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        StorageMenu menu = new StorageMenu(menuId, player.getInventory(), pos, terminal);
        player.containerMenu = menu;
        menu.broadcastChanges();
        return new Viewer(player, menu);
    }

    private static TransferRequest request(StorageMenu menu, TransferAction action, String name,
                                           boolean enabled, int entry, long amount, StorageBlockEntity target) {
        CompoundTag view = menu.snapshot();
        return new TransferRequest(menu.containerId, view.getLong("Session"), view.getLong("Revision"),
                view.getCompound("RemoteConfig").getLong("ConfigRevision"), action, name,
                view.getString("Volume"), view.getInt("Current"), enabled, entry, amount,
                target == null ? NO_TARGET : target.productionIdentity(),
                target == null ? -1 : target.transferConfig().revision());
    }

    private static void refresh(StorageMenu menu) {
        menu.handleTransfer(request(menu, TransferAction.REFRESH, "", false, 0, 0, null));
    }

    private static void navigate(StorageMenu menu, StorageNetwork.Action action, int id, String name) {
        CompoundTag view = menu.snapshot();
        menu.handle(new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), action,
                id, 0, 0, name, view.getLong("Session")));
    }

    private static int prepare(StorageBlockEntity source, StorageBlockEntity target) {
        source.inventory().insert(new ItemStack(Items.IRON_INGOT, 32), 32, 0);
        RemoteTransfers.configure(target, "收件站", "", 0, true, target.transferConfig().revision());
        return source.inventory().entries().get(0).id();
    }

    private static void rejected(GameTestHelper h, Runnable action, String reason) {
        boolean rejected = false;
        try { action.run(); } catch (RuntimeException expected) { rejected = true; }
        h.assertTrue(rejected, reason);
    }

    @GameTest(template = "empty")
    public static void transferAndStationPacketsPreserveIdentityAndNumericBoundaries(GameTestHelper h) {
        UUID target = UUID.randomUUID();
        String volume = UUID.randomUUID().toString(), name = "收".repeat(64);
        for (TransferAction action : TransferAction.values()) {
            TransferRequest original = new TransferRequest(Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE, action, name, volume, Integer.MAX_VALUE, true, Integer.MAX_VALUE,
                    Long.MAX_VALUE, target, Long.MIN_VALUE);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                original.encode(buffer);
                h.assertTrue(TransferRequest.decode(buffer).equals(original) && !buffer.isReadable(),
                        "Transfer packet changed identities, field order or 64-bit values for " + action);
            } finally { buffer.release(); }
        }
        var station = new StorageNetwork.StationNetworkRequest(Integer.MAX_VALUE, Long.MIN_VALUE, true, false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            station.encode(buffer);
            h.assertTrue(StorageNetwork.StationNetworkRequest.decode(buffer).equals(station) && !buffer.isReadable(),
                    "Station switch packet lost its menu/session or expected switch state");
        } finally { buffer.release(); }
        rejected(h, () -> new TransferRequest(1, 1, 1, 1, TransferAction.NAME, "x".repeat(65), "", 0,
                false, 0, 0, target, 1), "Transfer packet accepted an oversized name");
        rejected(h, () -> new TransferRequest(1, 1, 1, 1, TransferAction.BIND, "", "x".repeat(37), 0,
                false, 0, 0, target, 1), "Transfer packet accepted an oversized volume identity");
        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.buffer());
        try {
            oversized.writeVarInt(1); oversized.writeLong(1); oversized.writeLong(1); oversized.writeLong(1);
            oversized.writeEnum(TransferAction.NAME); oversized.writeUtf("x".repeat(65));
            oversized.writeUtf(""); oversized.writeVarInt(0); oversized.writeBoolean(false); oversized.writeVarInt(0);
            oversized.writeLong(0); oversized.writeUUID(target); oversized.writeLong(1);
            rejected(h, () -> TransferRequest.decode(oversized), "Wire decoder accepted an oversized transfer name");
        } finally { oversized.release(); }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void staleSourceRevisionCannotTransferButFreshPublishedRequestCan(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            int entry = prepare(station.first(), station.second());
            Viewer viewer = open(h, station.first(), 1);
            refresh(viewer.menu);
            TransferRequest stale = request(viewer.menu, TransferAction.SEND, "", false, entry, 8, station.second());
            station.first().inventory().insert(new ItemStack(Items.GOLD_INGOT), 1, 0);
            viewer.menu.handleTransfer(stale);
            h.assertTrue(station.first().inventory().total() == 33 && station.second().inventory().total() == 0,
                    "An old source inventory revision transferred items");
            TransferRequest fresh = request(viewer.menu, TransferAction.SEND, "", false, entry, 8, station.second());
            viewer.menu.handleTransfer(fresh);
            h.assertTrue(station.first().inventory().total() == 25 && station.second().inventory().total() == 8,
                    "Fresh published source context could not send after a stale request");
            viewer.menu.handleTransfer(fresh);
            h.assertTrue(station.first().inventory().total() == 25 && station.second().inventory().total() == 8,
                    "A replayed source request was committed twice");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void oldMountSessionClosedMenuAndReusedMenuIdCannotAuthorizeSending(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var nas = scene.nas(station);
            int entry = prepare(station.first(), station.second());
            Viewer viewer = open(h, station.first(), 1);
            refresh(viewer.menu);
            TransferRequest oldMount = request(viewer.menu, TransferAction.SEND, "", false, entry, 8, station.second());
            navigate(viewer.menu, StorageNetwork.Action.SELECT_VOLUME, 0, DiskItem.id(nas.disk(0)).toString());
            navigate(viewer.menu, StorageNetwork.Action.SELECT_VOLUME, 0, "");
            h.assertTrue(viewer.menu.session() != oldMount.session(), "Switching away and back did not renew the mount context");
            viewer.menu.handleTransfer(oldMount);
            h.assertTrue(station.second().inventory().total() == 0, "Old mount session survived volume changes");
            TransferRequest closedRequest = request(viewer.menu, TransferAction.SEND, "", false, entry, 8, station.second());
            viewer.menu.removed(viewer.player);
            viewer.player.containerMenu = viewer.player.inventoryMenu;
            viewer.menu.handleTransfer(closedRequest);
            h.assertTrue(station.first().inventory().total() == 32 && station.second().inventory().total() == 0,
                    "A closed terminal menu still accepted transfer packets");
            Viewer reopened = open(station.first(), viewer.player, viewer.menu.containerId);
            reopened.menu.handleTransfer(closedRequest);
            h.assertTrue(station.second().inventory().total() == 0, "Reused numeric menu id revived the old menu session");
            refresh(reopened.menu);
            reopened.menu.handleTransfer(request(reopened.menu, TransferAction.SEND, "", false, entry, 8, station.second()));
            h.assertTrue(station.first().inventory().total() == 24 && station.second().inventory().total() == 8,
                    "A fresh reopened menu could not explicitly authorize a transfer");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void receiverBindUsesOnlyTheMenusCurrentVolumeAndFolder(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var nas = scene.nas(station);
            int inbox = station.first().inventory().createFolder(0, "指定收件箱");
            Viewer viewer = open(h, station.first(), 1);
            TransferRequest current = request(viewer.menu, TransferAction.BIND, "", false, 0, 0, null);
            viewer.menu.handleTransfer(new TransferRequest(current.menuId(), current.session(), current.revision(), current.configRevision(),
                    TransferAction.BIND, "", "", inbox, false, 0, 0, NO_TARGET, -1));
            h.assertTrue(station.first().transferConfig().revision() == 0 && station.first().transferConfig().folder() == 0,
                    "A forged binding skipped the directory the player was browsing");
            viewer.menu.handleTransfer(new TransferRequest(current.menuId(), current.session(), current.revision(), current.configRevision(),
                    TransferAction.BIND, "", DiskItem.id(nas.disk(0)).toString(), 0, false, 0, 0, NO_TARGET, -1));
            h.assertTrue(station.first().transferConfig().revision() == 0 && station.first().transferConfig().volume().isEmpty(),
                    "A forged binding selected a disk outside the active volume context");
            navigate(viewer.menu, StorageNetwork.Action.OPEN, inbox, "");
            viewer.menu.handleTransfer(request(viewer.menu, TransferAction.BIND, "", false, 0, 0, null));
            h.assertTrue(station.first().transferConfig().folder() == inbox && station.first().transferConfig().volume().isEmpty()
                            && !station.first().transferConfig().enabled(),
                    "Binding the current directory failed or silently enabled reception");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void competingMenusCannotOverwriteAReceiverWithAnOldConfigurationRevision(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            Viewer first = open(h, station.first(), 1), second = open(h, station.first(), 2);
            TransferRequest oldName = request(second.menu, TransferAction.NAME, "过期名称", false, 0, 0, null);
            TransferRequest oldEnable = request(second.menu, TransferAction.RECEIVING, "", true, 0, 0, null);
            first.menu.handleTransfer(request(first.menu, TransferAction.NAME, "最新名称", false, 0, 0, null));
            long committedRevision = station.first().transferConfig().revision();
            second.menu.handleTransfer(oldName);
            second.menu.handleTransfer(oldEnable);
            h.assertTrue(station.first().transferConfig().name().equals("最新名称")
                            && station.first().transferConfig().revision() == committedRevision && !station.first().transferConfig().enabled(),
                    "An older second menu overwrote the receiver policy or enabled reception");
            second.menu.handleTransfer(request(second.menu, TransferAction.RECEIVING, "", true, 0, 0, null));
            h.assertTrue(station.first().transferConfig().enabled()
                            && station.first().transferConfig().revision() > committedRevision,
                    "A fresh acknowledged receiver policy could not be enabled");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void receiverCanBeDisabledAfterItsBoundDiskHasGoneOffline(GameTestHelper h) {
        try (var scene = new RemoteTransferTests.Scene(h)) {
            var station = scene.station(0);
            var nas = scene.nas(station);
            String volume = DiskItem.id(nas.disk(0)).toString();
            var disk = nas.volume(0).inventory();
            int inbox = disk.createFolder(0, "离线收件箱");
            disk.insert(new ItemStack(Items.IRON_INGOT, 16), 16, inbox);
            RemoteTransfers.configure(station.first(), "收件站", volume, inbox, true, 0);
            Viewer viewer = open(h, station.first(), 1);
            nas.eject(0);
            viewer.menu.handleTransfer(request(viewer.menu, TransferAction.RECEIVING, "", false, 0, 0, null));
            h.assertTrue(!station.first().transferConfig().enabled() && station.first().transferConfig().volume().equals(volume)
                            && station.first().transferConfig().folder() == inbox && disk.total() == 16,
                    "An offline receiver disk prevented disabling reception or changed its stable binding/content");
            long disabledRevision = station.first().transferConfig().revision();
            viewer.menu.handleTransfer(request(viewer.menu, TransferAction.RECEIVING, "", true, 0, 0, null));
            h.assertTrue(!station.first().transferConfig().enabled() && station.first().transferConfig().revision() == disabledRevision,
                    "An offline receiver disk could be re-enabled without validation");
            h.succeed();
        }
    }
}
