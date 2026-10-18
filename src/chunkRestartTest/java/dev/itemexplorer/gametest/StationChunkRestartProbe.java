package dev.itemexplorer.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.BaseStationPartBlock;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.portable.PortableAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Two independent dedicated-server JVMs; no real player or spawn ticket can keep the remote fixture ticking. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class StationChunkRestartProbe {
    private static final Logger LOG = LogUtils.getLogger();
    // A chunk-border tower ensures boot validation cannot accidentally rely on every part sharing its owner's chunk.
    private static final BlockPos CENTER = new BlockPos(4096, 82, 4096), OWNER = CENTER.north();
    private static final BlockPos CABLE = CENTER.south(2), TERMINAL = CABLE.west(), NAS = CABLE.east();
    private static final BlockPos FURNACE = OWNER.west(5), STALE_OWNER = OWNER.east(160);
    private static final ChunkPos STRAY_CHUNK = new ChunkPos(OWNER.east(80));
    private static MinecraftServer server;
    private static int ticks, stage, cookedBefore;
    private static boolean done;
    private static Path root;

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (System.getProperty("itemexplorer.chunkRestartProbe") == null) return;
        server = event.getServer();
        try {
            root = Path.of(System.getProperty("itemexplorer.chunkRestartRoot")).toRealPath();
            Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
            check(actual.equals(root.resolve("world").toRealPath()), "Exact disposable world path verified");
            check(root.getParent().getFileName().toString().equals("chunk-loading-restart")
                            && root.getParent().getParent().getFileName().toString().equals("work"),
                    "Probe is restricted to work/chunk-loading-restart/<unique run>/world");
            line("START phase=" + phase() + " pid=" + ProcessHandle.current().pid());
        } catch (Throwable failure) { fail(failure); }
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (server == null || done || event.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks < 40) return;
            checkNoPlayers();
            if (stage == 0) {
                if (phase().equals("seed")) seed(server.overworld());
                else if (phase().equals("verify")) verifyLoaded(server.overworld());
                else throw new IllegalArgumentException("Unknown phase " + phase());
                stage = 1;
                return;
            }
            if (ticks < 300) return;
            var level = server.overworld();
            var furnace = (FurnaceBlockEntity) level.getBlockEntity(FURNACE);
            check(furnace != null && furnace.getItem(2).is(Items.IRON_INGOT)
                            && furnace.getItem(2).getCount() > cookedBefore,
                    "Vanilla furnace actually smelted beyond spawn/player range; before=" + cookedBefore
                            + " after=" + (furnace == null ? -1 : furnace.getItem(2).getCount()));
            if (phase().equals("seed")) {
                seedStaleTickets(level);
                line("SEED_PASS normal shutdown will persist world, portable pairing and Forge tickets");
            } else {
                verifyPortable(level);
                var controller = (BaseStationBlockEntity) level.getBlockEntity(OWNER);
                controller.setChunkLoadingEnabled(false);
                check(tickets(level, OWNER, true).isEmpty() && tickets(level, OWNER, false).isEmpty(),
                        "Disabling after restart releases every owner ticket");
                line("VERIFY_PASS separate JVM restored border station, filtered stale tickets and ran machines with no players");
            }
            done = true;
            server.halt(false);
        } catch (Throwable failure) { fail(failure); }
    }

    private static void checkNoPlayers() {
        if (!server.getPlayerList().getPlayers().isEmpty()) throw new AssertionError("Unexpected real player in isolated probe world");
    }
    private static String phase() { return System.getProperty("itemexplorer.chunkRestartProbe"); }
    private static void line(String message) {
        LOG.info("CHUNK_RESTART_PROBE {}", message);
        try {
            Path destination = (root == null ? Path.of(".") : root).resolve("chunk-restart-" + phase() + ".txt");
            Files.writeString(destination, message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        line("PASS " + message);
    }
    private static void fail(Throwable failure) {
        done = true;
        LOG.error("CHUNK_RESTART_PROBE FAIL", failure);
        line("FAIL " + failure);
        server.halt(false);
    }

    private static Set<Long> expected() {
        Set<Long> result = new HashSet<>();
        ChunkPos center = new ChunkPos(OWNER);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) result.add(ChunkPos.asLong(center.x + dx, center.z + dz));
        return result;
    }
    private static Set<Long> tickets(ServerLevel level, BlockPos owner, boolean ticking) {
        var data = level.getDataStorage().computeIfAbsent(ForcedChunksSavedData::load, ForcedChunksSavedData::new, "chunks");
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

    private static void seed(ServerLevel level) throws Exception {
        check(!Files.exists(root.resolve("expected.nbt")), "Unique seed world has no previous expected snapshot");
        for (long chunk : expected()) {
            ChunkPos pos = new ChunkPos(chunk);
            level.getChunk(pos.x, pos.z);
        }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            level.setBlockAndUpdate(CENTER.offset(x, 0, z), ModContent.BASE_STATION_CASING_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(OWNER, ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                .setValue(BaseStationControllerBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(CENTER.south(), ModContent.BASE_STATION_NETWORK_PORT_BLOCK.get().defaultBlockState()
                .setValue(BaseStationPartBlock.FACING, Direction.SOUTH));
        for (Direction side : List.of(Direction.EAST, Direction.WEST))
            level.setBlockAndUpdate(CENTER.relative(side), (side == Direction.EAST ? ModContent.BASE_STATION_CHUNK_LOADER_BLOCK
                    : ModContent.BASE_STATION_MODULE_BLOCK).get().defaultBlockState().setValue(BaseStationPartBlock.FACING, side));
        for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(CENTER.above(y), ModContent.BASE_STATION_MAST_BLOCK.get().defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL)
            level.setBlockAndUpdate(CENTER.above(3).relative(side), ModContent.BASE_STATION_ANTENNA_BLOCK.get().defaultBlockState()
                    .setValue(BaseStationPartBlock.FACING, side));
        level.setBlockAndUpdate(CENTER.above(4), ModContent.BASE_STATION_CAP_BLOCK.get().defaultBlockState());
        var controller = (BaseStationBlockEntity) level.getBlockEntity(OWNER);
        check(controller != null && controller.validation().complete(), "Border station assembled across four chunks");
        check(!controller.chunkLoadingEnabled(), "New controller starts with chunk loading off");
        controller.setChunkLoadingEnabled(true);
        controller.setNetworkOnline(true);
        check(tickets(level, OWNER, true).equals(expected()), "Seed owns exactly nine Forge ticking tickets");
        level.setBlockAndUpdate(CABLE, ModContent.DATA_CABLE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(NAS, ModContent.NAS_BLOCK.get().defaultBlockState());
        var terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        var nas = (NasBlockEntity) level.getBlockEntity(NAS);
        terminal.setPortableEnabled(true);
        terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 31), 31, 0);
        nas.install(0, new ItemStack(ModContent.DISK_64K.get()));
        nas.volume(0).inventory().insert(new ItemStack(Items.GOLD_INGOT, 43), 43, 0);
        var player = player(level);
        player.setPos(TERMINAL.getX() + .5, TERMINAL.getY() + .5, TERMINAL.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.PORTABLE_TERMINAL_ITEM.get()));
        check(PortableAccess.pair(player, InteractionHand.MAIN_HAND, terminal).isEmpty(), "Portable pairing created before shutdown");
        CompoundTag expected = new CompoundTag();
        expected.putLong("Pid", ProcessHandle.current().pid());
        expected.put("Handheld", player.getMainHandItem().save(new CompoundTag()));
        expected.putString("Volume", nas.volume(0).id().toString());
        try (var out = Files.newOutputStream(root.resolve("expected.nbt"))) { NbtIo.writeCompressed(expected, out); }
        level.setBlockAndUpdate(FURNACE, Blocks.FURNACE.defaultBlockState());
        var furnace = (FurnaceBlockEntity) level.getBlockEntity(FURNACE);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 32));
        furnace.setItem(1, new ItemStack(Items.COAL, 8));
        cookedBefore = 0;
    }

    private static void seedStaleTickets(ServerLevel level) {
        ChunkPos stale = new ChunkPos(STALE_OWNER);
        ForgeChunkManager.forceChunk(level, ItemExplorer.MOD_ID, STALE_OWNER, stale.x, stale.z, true, true);
        ChunkPos center = new ChunkPos(OWNER);
        // Simulate an interrupted/incomplete saved square; boot must fill this missing corner.
        ForgeChunkManager.forceChunk(level, ItemExplorer.MOD_ID, OWNER, center.x - 1, center.z - 1, false, true);
        // Invalid extra range and legacy non-ticking tickets must be removed while valid ownership survives boot.
        ForgeChunkManager.forceChunk(level, ItemExplorer.MOD_ID, OWNER, STRAY_CHUNK.x, STRAY_CHUNK.z, true, true);
        ForgeChunkManager.forceChunk(level, ItemExplorer.MOD_ID, OWNER, STRAY_CHUNK.x, STRAY_CHUNK.z, true, false);
        Set<Long> validRemaining = new HashSet<>(tickets(level, OWNER, true));
        validRemaining.retainAll(expected());
        check(tickets(level, STALE_OWNER, true).size() == 1 && tickets(level, OWNER, true).size() == 9
                        && validRemaining.size() == 8 && tickets(level, OWNER, false).size() == 1,
                "Seeded 8 valid ticking tickets, 1 missing corner, 1 stray ticking ticket, 1 non-ticking ticket and 1 absent owner");
    }

    private static CompoundTag expectedData() throws Exception {
        try (var in = Files.newInputStream(root.resolve("expected.nbt"))) { return NbtIo.readCompressed(in); }
    }
    private static void verifyLoaded(ServerLevel level) throws Exception {
        check(expectedData().getLong("Pid") != ProcessHandle.current().pid(), "Verification runs in a distinct JVM");
        // Deliberately do not call getChunk/getBlockEntity until proving loading occurred without probe assistance.
        for (long chunk : expected()) {
            ChunkPos pos = new ChunkPos(chunk);
            check(level.getChunkSource().getChunkNow(pos.x, pos.z) != null, "Boot automatically loaded target chunk " + pos);
        }
        check(tickets(level, OWNER, true).equals(expected()) && tickets(level, OWNER, false).isEmpty(),
                "Boot repaired missing corner to exactly nine ticking tickets and removed stray/non-ticking tickets");
        check(tickets(level, STALE_OWNER, true).isEmpty(), "Boot removed stale owner whose controller no longer exists");
        var controller = (BaseStationBlockEntity) level.getBlockEntity(OWNER);
        check(controller != null && controller.chunkLoadingEnabled() && controller.chunkLoadingActive()
                        && controller.validation().complete(), "Enabled preference and complete border structure restored");
        var furnace = (FurnaceBlockEntity) level.getBlockEntity(FURNACE);
        check(furnace != null && furnace.getItem(2).is(Items.IRON_INGOT), "Furnace and seed-phase production survived world save");
        cookedBefore = furnace.getItem(2).getCount();
    }

    private static FakePlayer player(ServerLevel level) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), "chunk-restart-probe")) {
            @Override public boolean hasDisconnected() { return false; }
        };
    }
    private static void request(StorageMenu menu, StorageNetwork.Action action, int id, long amount, String name) {
        CompoundTag view = menu.snapshot();
        menu.handle(new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), action, id, 0, amount, name, view.getLong("Session")));
    }
    private static void verifyPortable(ServerLevel level) throws Exception {
        var player = player(level);
        player.setPos(0, 82, 0);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.of(expectedData().getCompound("Handheld")));
        var access = PortableAccess.openSession(player, InteractionHand.MAIN_HAND);
        check(access.status().isEmpty(), "Previously paired handheld opens from over 4,000 blocks away after restart");
        var menu = new StorageMenu(1, player.getInventory(), TERMINAL, access.terminal(), access.session());
        player.containerMenu = menu;
        menu.broadcastChanges();
        var terminal = access.terminal();
        request(menu, StorageNetwork.Action.WITHDRAW, terminal.inventory().entries().get(0).id(), 7, "");
        var nas = (NasBlockEntity) level.getBlockEntity(NAS);
        request(menu, StorageNetwork.Action.SELECT_VOLUME, 0, 0, expectedData().getString("Volume"));
        request(menu, StorageNetwork.Action.WITHDRAW, nas.volume(0).inventory().entries().get(0).id(), 9, "");
        int iron = player.getInventory().items.stream().filter(stack -> stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
        int gold = player.getInventory().items.stream().filter(stack -> stack.is(Items.GOLD_INGOT)).mapToInt(ItemStack::getCount).sum();
        check(iron == 7 && gold == 9 && terminal.inventory().total() == 24 && nas.volume(0).inventory().total() == 34,
                "Remote portable withdraws exact local/NAS quantities after real server restart");
        menu.removed(player);
    }
}
