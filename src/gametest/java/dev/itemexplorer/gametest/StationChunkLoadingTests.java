package dev.itemexplorer.gametest;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.portable.PortableAccess;
import dev.itemexplorer.station.StationChunkLoading;
import dev.itemexplorer.station.StationChunkLoadingConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.itemexplorer.gametest.GameTestSupport.*;

/** Inspect Forge's actual persistent tickets; do not substitute the feature's status flag for chunk ownership. */
@GameTestHolder(ItemExplorer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StationChunkLoadingTests {
    private static final class Scene implements AutoCloseable {
        private final GameTestHelper helper;
        private final RemoteTransferTests.Scene delegate;
        private final List<BlockPos> owners = new ArrayList<>();

        Scene(GameTestHelper helper) { this.helper = helper; this.delegate = new RemoteTransferTests.Scene(helper); }
        RemoteTransferTests.Station station(int floor) {
            var station = delegate.station(floor);
            owners.add(station.controller().getBlockPos());
            return station;
        }
        @Override public void close() {
            try { delegate.close(); }
            finally {
                // Tests never leave tickets behind, including when an assertion exposes a broken release hook.
                for (BlockPos owner : owners) {
                    StationChunkLoading.release(helper.getLevel(), owner);
                    for (long chunk : expected(owner)) {
                    ChunkPos pos = new ChunkPos(chunk);
                    ForgeChunkManager.forceChunk(helper.getLevel(), ItemExplorer.MOD_ID, owner, pos.x, pos.z, false, true);
                    ForgeChunkManager.forceChunk(helper.getLevel(), ItemExplorer.MOD_ID, owner, pos.x, pos.z, false, false);
                    }
                }
            }
        }
    }

    private static Set<Long> expected(BlockPos owner) {
        Set<Long> result = new HashSet<>();
        int x = Math.floorDiv(owner.getX(), 16), z = Math.floorDiv(owner.getZ(), 16);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) result.add(ChunkPos.asLong(x + dx, z + dz));
        return result;
    }

    private static Set<Long> tickets(GameTestHelper h, BlockPos owner, boolean ticking) {
        var data = h.getLevel().getDataStorage().computeIfAbsent(ForcedChunksSavedData::load, ForcedChunksSavedData::new, "chunks");
        Set<Long> result = new HashSet<>();
        for (Tag modTag : data.save(new CompoundTag()).getList("ForgeForced", Tag.TAG_COMPOUND)) {
            CompoundTag mod = (CompoundTag) modTag;
            if (!mod.getString("Mod").equals(ItemExplorer.MOD_ID)) continue;
            for (Tag chunkTag : mod.getList("ModForced", Tag.TAG_COMPOUND)) {
                CompoundTag chunk = (CompoundTag) chunkTag;
                for (Tag blockTag : chunk.getList(ticking ? "TickingBlocks" : "Blocks", Tag.TAG_COMPOUND))
                    if (NbtUtils.readBlockPos((CompoundTag) blockTag).equals(owner)) result.add(chunk.getLong("Chunk"));
            }
        }
        return result;
    }

    private static void assertTickets(GameTestHelper h, BaseStationBlockEntity controller, boolean active, String context) {
        h.assertTrue(tickets(h, controller.getBlockPos(), true).equals(active ? expected(controller.getBlockPos()) : Set.of()),
                context + ": wrong persisted Forge ticking owner set");
        h.assertTrue(tickets(h, controller.getBlockPos(), false).isEmpty(), context + ": non-ticking tickets unexpectedly allocated");
        h.assertTrue(controller.chunkLoadingActive() == active, context + ": displayed state disagrees with actual tickets");
    }

    private static void module(GameTestHelper h, RemoteTransferTests.Station station, Direction side, boolean installed) {
        h.getLevel().setBlockAndUpdate(station.center().relative(side),
                (installed ? ModContent.BASE_STATION_CHUNK_LOADER_BLOCK : ModContent.BASE_STATION_MODULE_BLOCK).get()
                        .defaultBlockState().setValue(BaseStationPartBlock.FACING, side));
    }

    @GameTest(template = "empty")
    public static void loadingIsOptInRequiresAModuleAndDoesNotRequireWireless(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var station = scene.station(0);
            var controller = station.controller();
            h.assertTrue(!controller.chunkLoadingEnabled(), "A newly placed controller enabled chunk loading");
            assertTickets(h, controller, false, "Fresh station");
            controller.setChunkLoadingEnabled(true);
            assertTickets(h, controller, false, "Blank modules cannot force chunks");
            module(h, station, Direction.WEST, true);
            h.assertTrue(controller.validation().complete() && !controller.networkOnline(), "Loader invalidated assembly or enabled wireless");
            controller.refreshStructure();
            assertTickets(h, controller, false, "Structure inspection must not synchronously load chunks");
            StationChunkLoading.reconcile(controller);
            assertTickets(h, controller, true, "Next reconciliation after module installation");
            controller.setNetworkOnline(true);
            controller.setNetworkOnline(false);
            assertTickets(h, controller, true, "Wireless switch is independent");
            controller.setChunkLoadingEnabled(false);
            assertTickets(h, controller, false, "Explicit off releases tickets");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void eitherModuleSlotWorksAndTwoModulesStillForceExactlyNineTickingChunks(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var station = scene.station(0);
            module(h, station, Direction.EAST, true);
            station.controller().setChunkLoadingEnabled(true);
            assertTickets(h, station.controller(), true, "East module");
            module(h, station, Direction.WEST, true);
            assertTickets(h, station.controller(), true, "Two modules do not expand the square");
            module(h, station, Direction.EAST, false);
            assertTickets(h, station.controller(), true, "West module remains");
            for (long chunk : expected(station.controller().getBlockPos())) {
                ChunkPos pos = new ChunkPos(chunk);
                h.assertTrue(h.getLevel().getChunkSource().getChunkNow(pos.x, pos.z) != null,
                        "Ticket did not actually load target chunk " + pos);
            }
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void brokenTowerWrongFacingAndRemovedModuleReleaseThenRepairReacquires(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var station = scene.station(0);
            module(h, station, Direction.EAST, true);
            station.controller().setChunkLoadingEnabled(true);
            assertTickets(h, station.controller(), true, "Initially active");
            BlockPos cap = station.center().above(4);
            var correctCap = h.getLevel().getBlockState(cap);
            h.getLevel().setBlockAndUpdate(cap, Blocks.AIR.defaultBlockState());
            assertTickets(h, station.controller(), false, "Broken cap");
            h.getLevel().setBlockAndUpdate(cap, correctCap);
            StationChunkLoading.reconcile(station.controller());
            assertTickets(h, station.controller(), true, "Repaired cap");
            BlockPos module = station.center().east();
            var correctModule = h.getLevel().getBlockState(module);
            h.getLevel().setBlockAndUpdate(module, correctModule.setValue(BaseStationPartBlock.FACING, Direction.WEST));
            assertTickets(h, station.controller(), false, "Inward-facing loader");
            h.assertTrue(!station.controller().validation().complete(), "Inward-facing loader wrongly formed the tower");
            h.getLevel().setBlockAndUpdate(module, correctModule);
            StationChunkLoading.reconcile(station.controller());
            assertTickets(h, station.controller(), true, "Corrected loader");
            module(h, station, Direction.EAST, false);
            h.assertTrue(station.controller().validation().complete(), "Replacing loader by blank module damaged structure");
            assertTickets(h, station.controller(), false, "Last loader removed");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void overlappingStationsOwnTheirTicketsIndependentlyAndControllerRemovalReleasesOnlyItsOwn(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var a = scene.station(0);
            var b = scene.station(1);
            module(h, a, Direction.EAST, true); module(h, b, Direction.WEST, true);
            a.controller().setChunkLoadingEnabled(true); b.controller().setChunkLoadingEnabled(true);
            h.assertTrue(expected(a.controller().getBlockPos()).equals(expected(b.controller().getBlockPos())), "Fixture must overlap fully");
            assertTickets(h, a.controller(), true, "Owner A"); assertTickets(h, b.controller(), true, "Owner B");
            h.getLevel().setBlockAndUpdate(a.controller().getBlockPos(), Blocks.AIR.defaultBlockState());
            h.assertTrue(tickets(h, a.controller().getBlockPos(), true).isEmpty(), "Destroyed controller retained its persistent tickets");
            assertTickets(h, b.controller(), true, "Destroying A must keep B's shared chunks forced");
            b.controller().setChunkLoadingEnabled(false);
            assertTickets(h, b.controller(), false, "Final owner disabled");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void controllerPreferenceSurvivesSerializationButSavedFormedStateCannotBypassMissingModule(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var station = scene.station(0);
            module(h, station, Direction.EAST, true);
            station.controller().setChunkLoadingEnabled(true);
            long revision = station.controller().chunkLoadingRevision();
            station.controller().setChunkLoadingEnabled(true);
            station.controller().refreshStructure();
            h.assertTrue(station.controller().chunkLoadingRevision() == revision, "Repeated requested value or refresh changed config revision");
            CompoundTag saved = station.controller().saveWithFullMetadata();
            var state = h.getLevel().getBlockState(station.controller().getBlockPos());
            station.controller().setChunkLoadingEnabled(false);
            module(h, station, Direction.EAST, false);
            h.getLevel().removeBlockEntity(station.controller().getBlockPos());
            var restored = new BaseStationBlockEntity(station.controller().getBlockPos(), state);
            restored.load(saved);
            h.getLevel().setBlockEntity(restored);
            restored.onLoad();
            h.assertTrue(restored.chunkLoadingEnabled() && restored.chunkLoadingRevision() == revision,
                    "Saved opt-in preference or revision was not restored");
            assertTickets(h, restored, false, "Saved formed state cannot synthesize a missing loader");
            module(h, station, Direction.EAST, true);
            StationChunkLoading.reconcile(restored);
            assertTickets(h, restored, true, "Restored controller reacquires after hardware repair");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void pairedHandheldCanWithdrawFromForcedStationFarOutsideInteractionRange(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var station = scene.station(0);
            module(h, station, Direction.EAST, true);
            station.controller().setChunkLoadingEnabled(true);
            station.controller().setNetworkOnline(true);
            station.first().setPortableEnabled(true);
            station.first().inventory().insert(new ItemStack(Items.IRON_INGOT, 31), 31, 0);
            var terminalPos = station.first().getBlockPos();
            var player = player(h, terminalPos);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
            h.assertTrue(PortableAccess.pair(player, InteractionHand.MAIN_HAND, station.first()).isEmpty(), "Could not pair fixture handheld");
            player.setPos(terminalPos.getX() + 4096, terminalPos.getY(), terminalPos.getZ() + 4096);
            var access = PortableAccess.openSession(player, InteractionHand.MAIN_HAND);
            h.assertTrue(access.status().isEmpty(), "Distant forced station failed portable access: " + access.status());
            var menu = new StorageMenu(1, player.getInventory(), terminalPos, access.terminal(), access.session());
            player.containerMenu = menu;
            menu.broadcastChanges();
            CompoundTag view = menu.snapshot();
            int entry = station.first().inventory().entries().get(0).id();
            menu.handle(new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), StorageNetwork.Action.WITHDRAW,
                    entry, 0, 7, "", view.getLong("Session")));
            int received = player.getInventory().items.stream().filter(stack -> stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
            h.assertTrue(received == 7 && station.first().inventory().total() == 24,
                    "Distant forced station lost/duplicated portable withdrawal");
            assertTickets(h, station.controller(), true, "Handheld use must retain persistent loading");
            menu.removed(player);
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void globalLimitRejectsAdditionalStationAndReleasedSlotCanBeReused(GameTestHelper h) {
        int original = StationChunkLoadingConfig.MAX_ACTIVE_STATIONS.get();
        try (Scene scene = new Scene(h)) {
            int existing = StationChunkLoading.activeStationCount(h.getLevel().getServer());
            StationChunkLoadingConfig.MAX_ACTIVE_STATIONS.set(existing + 1);
            var a = scene.station(0); var b = scene.station(1);
            module(h, a, Direction.EAST, true); module(h, b, Direction.WEST, true);
            a.controller().setChunkLoadingEnabled(true);
            b.controller().setChunkLoadingEnabled(true);
            assertTickets(h, a.controller(), true, "First available global slot");
            assertTickets(h, b.controller(), false, "Global limit blocks second station even with overlapping chunks");
            h.assertTrue(b.controller().chunkLoadingEnabled() && b.controller().chunkLoadingStatus().equals("limit_reached"),
                    "Limit must preserve explicit request and explain inactivity");
            a.controller().setChunkLoadingEnabled(false);
            StationChunkLoading.reconcile(b.controller());
            assertTickets(h, b.controller(), true, "Released global slot can be reused");
            h.succeed();
        } finally { StationChunkLoadingConfig.MAX_ACTIVE_STATIONS.set(original); }
    }

    private static BaseStationMenu physicalMenu(ServerPlayer player, BaseStationBlockEntity controller, int id) {
        var menu = new BaseStationMenu(id, player.getInventory(), controller);
        player.containerMenu = menu;
        menu.broadcastChanges();
        return menu;
    }

    @GameTest(template = "empty")
    public static void staleSessionsRevisionsAndRemoteControllerPacketsCannotChangeTickets(GameTestHelper h) {
        try (Scene scene = new Scene(h)) {
            var station = scene.station(0);
            module(h, station, Direction.EAST, true);
            var controller = station.controller();
            BlockPos pos = controller.getBlockPos();
            var player = player(h, pos);
            var menu = physicalMenu(player, controller, 1);
            var stale = new StorageNetwork.StationChunkRequest(menu.containerId, menu.networkView().getLong("Session"),
                    menu.chunkLoadingView().getLong("Revision"), true);
            controller.setChunkLoadingEnabled(true);
            controller.setChunkLoadingEnabled(false);
            menu.handleChunkLoading(stale);
            assertTickets(h, controller, false, "Old revision cannot re-enable loading after an on/off cycle");
            menu = physicalMenu(player, controller, 2);
            long session = menu.networkView().getLong("Session");
            long revision = menu.chunkLoadingView().getLong("Revision");
            menu.handleChunkLoading(new StorageNetwork.StationChunkRequest(menu.containerId, session + 1, revision, true));
            assertTickets(h, controller, false, "Wrong menu session");
            menu.handleChunkLoading(new StorageNetwork.StationChunkRequest(menu.containerId, session, revision, true));
            assertTickets(h, controller, true, "Fresh physical request");
            menu = physicalMenu(player, controller, 3);
            var disable = new StorageNetwork.StationChunkRequest(menu.containerId, menu.networkView().getLong("Session"),
                    menu.chunkLoadingView().getLong("Revision"), false);
            player.setPos(pos.getX() + 128, pos.getY(), pos.getZ());
            menu.handleChunkLoading(disable);
            assertTickets(h, controller, true, "Out-of-range player cannot change loading policy");
            standOn(player, pos);
            menu.handleChunkLoading(disable);
            assertTickets(h, controller, false, "Current nearby request can disable loading");
            h.succeed();
        }
    }

    @GameTest(template = "empty")
    public static void chunkLoadingPacketPreservesSessionRevisionAndBooleanAcrossEncoding(GameTestHelper h) {
        for (boolean enabled : List.of(false, true)) {
            var request = new StorageNetwork.StationChunkRequest(Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, enabled);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                request.encode(buffer);
                h.assertTrue(StorageNetwork.StationChunkRequest.decode(buffer).equals(request) && !buffer.isReadable(),
                        "Chunk policy request changed identity/revision field order or lost enabled=" + enabled);
            } finally { buffer.release(); }
        }
        h.succeed();
    }
}
