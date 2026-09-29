package dev.itemexplorer.multiplayer;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID)
public final class MultiplayerServerProbe {
    private static final BlockPos TERMINAL = new BlockPos(8, 82, 8);
    private static MinecraftServer server;
    private static StorageBlockEntity terminal;
    private static ServerPlayer a, b;
    private static int ticks, stage, deadline, winnerCount;
    private static boolean done;
    private static String loser;

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!ProbeFiles.enabled()) return;
        server = event.getServer();
        ProbeFiles.check(server.isDedicatedServer(), "Must use a dedicated server JVM");
        ProbeFiles.write("server.pid", Long.toString(ProcessHandle.current().pid()));
        var level = server.overworld();
        level.getChunkAt(TERMINAL);
        for (int x = 5; x <= 11; x++) for (int z = 5; z <= 11; z++) level.setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(TERMINAL, ModContent.STORAGE_BLOCK.get().defaultBlockState());
        terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
        ProbeFiles.check(terminal != null && terminal.inventory().total() == 0, "Fixture must be new and empty");
        terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 64), 64, 0);
        ProbeFiles.log("server", "READY dedicated JVM pid=" + ProcessHandle.current().pid() + " initialIron=64");
        ProbeFiles.write("server-ready", "ready");
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (server == null || done || event.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ProbeFiles.exists("abort") || ticks > 9000) throw new IllegalStateException("Probe aborted or timed out");
            switch (stage) {
                case 0 -> {
                    a = server.getPlayerList().getPlayerByName("DevA"); b = server.getPlayerList().getPlayerByName("DevB");
                    if (a == null || b == null) return;
                    open(a, 7.5); open(b, 9.5);
                    stage = 1;
                }
                case 1 -> {
                    if (!ProbeFiles.exists("DevA-ready") || !ProbeFiles.exists("DevB-ready")) return;
                    String expected = Long.toString(terminal.inventory().revision());
                    ProbeFiles.check(ProbeFiles.read("DevA-ready").equals(expected) && ProbeFiles.read("DevB-ready").equals(expected), "Clients must capture same inventory revision");
                    ProbeFiles.log("server", "CAPTURED both clients ready at revision=" + expected);
                    ProbeFiles.write("race-go", "request 48 each"); stage = 2; deadline = -1;
                }
                case 2 -> {
                    if (!settled(ProbeFiles.exists("DevA-race-sent") && ProbeFiles.exists("DevB-race-sent"))) return;
                    long stored = terminal.inventory().total(); int ai = iron(a), bi = iron(b);
                    ProbeFiles.check(stored == 16 && (ai == 48 && bi == 0 || ai == 0 && bi == 48), "Race did not reject exactly one stale revision: " + counts());
                    loser = ai == 0 ? "DevA" : "DevB"; winnerCount = 48;
                    ProbeFiles.log("server", "RACE_PASS " + counts());
                    ProbeFiles.write("stale-go", "resend original captured requests"); stage = 3; deadline = -1;
                }
                case 3 -> {
                    if (!settled(ProbeFiles.exists("DevA-stale-sent") && ProbeFiles.exists("DevB-stale-sent"))) return;
                    ProbeFiles.check(terminal.inventory().total() == 16 && iron(a) + iron(b) == winnerCount && iron(loser.equals("DevA") ? a : b) == 0, "Stale replay changed inventory: " + counts());
                    ProbeFiles.log("server", "STALE_REPLAY_PASS " + counts());
                    ProbeFiles.write("retry-go", loser); stage = 4; deadline = -1;
                }
                case 4 -> {
                    if (!settled(ProbeFiles.exists(loser + "-retry-sent"))) return;
                    ProbeFiles.check(terminal.inventory().total() == 0 && iron(a) + iron(b) == 64 && iron(loser.equals("DevA") ? a : b) == 16, "Fresh retry did not conserve 64 iron: " + counts());
                    ProbeFiles.log("server", "RETRY_PASS " + counts());
                    terminal.inventory().insert(new ItemStack(Items.IRON_INGOT, 32), 32, 0);
                    ProbeFiles.write("close-go", "DevA"); stage = 5; deadline = -1;
                }
                case 5 -> {
                    if (!settled(ProbeFiles.exists("DevA-close-sent"))) return;
                    ProbeFiles.check(!(a.containerMenu instanceof StorageMenu) && terminal.inventory().total() == 32 && iron(a) + iron(b) == 64, "Closed-menu request changed inventory: " + counts());
                    ProbeFiles.log("server", "CLOSED_MENU_PASS " + counts() + " totalIncludingAddedFixture=96");
                    ProbeFiles.log("server", "PASS real dedicated server + two Forge TCP clients; no FakePlayer, no injected latency");
                    ProbeFiles.write("result", "PASS"); ProbeFiles.write("finish", "normal shutdown"); stage = 6; deadline = ticks + 80;
                }
                case 6 -> { if (ticks >= deadline || ProbeFiles.exists("DevA-exit") && ProbeFiles.exists("DevB-exit")) stop(); }
                default -> throw new IllegalStateException("Unknown stage");
            }
        } catch (Throwable failure) {
            ProbeFiles.log("server", "FAIL stage=" + stage + " " + failure);
            ProbeFiles.write("result", "FAIL " + failure); ProbeFiles.write("finish", "failure shutdown"); stop();
        }
    }
    private static void open(ServerPlayer player, double x) {
        ProbeFiles.check(!(player instanceof FakePlayer) && !player.connection.connection.isMemoryConnection(), "Requires a real TCP client");
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();
        player.teleportTo(server.overworld(), x, 82, 7.5, 0, 0);
        NetworkHooks.openScreen(player, terminal, TERMINAL);
        ProbeFiles.log("server", "JOIN " + player.getGameProfile().getName() + " uuid=" + player.getUUID() + " remote=" + player.connection.connection.getRemoteAddress() + " class=" + player.getClass().getName());
    }
    private static int iron(ServerPlayer player) { return player.getInventory().items.stream().filter(s -> s.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum(); }
    private static boolean settled(boolean sent) {
        if (!sent) return false;
        if (deadline < 0) deadline = ticks + 60;
        return ticks >= deadline;
    }
    private static String counts() { return "stored=" + terminal.inventory().total() + " DevA=" + iron(a) + " DevB=" + iron(b); }
    private static void stop() { done = true; server.halt(false); }
}
