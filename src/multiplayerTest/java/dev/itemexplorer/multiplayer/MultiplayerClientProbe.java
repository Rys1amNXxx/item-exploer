package dev.itemexplorer.multiplayer;

import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class MultiplayerClientProbe {
    private static boolean initialized, connecting, race, stale, retry, close, stopped;
    private static long started;
    private static StorageNetwork.Request captured;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!ProbeFiles.enabled() || stopped || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        String role = System.getProperty("itemexplorer.multiplayerName");
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                ProbeFiles.check(role.equals("DevA") || role.equals("DevB"), "Unexpected client role");
                mc.options.pauseOnLostFocus = false;
                GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                ProbeFiles.write(role + ".pid", Long.toString(ProcessHandle.current().pid()));
                ProbeFiles.log(role, "START real Forge Minecraft client pid=" + ProcessHandle.current().pid() + " username=" + mc.getUser().getName());
                ProbeFiles.check(mc.getUser().getName().equals(role), "Launch username differs from probe role");
            }
            if (ProbeFiles.exists("finish") || ProbeFiles.exists("abort") || System.currentTimeMillis() - started > 420_000) {
                ProbeFiles.log(role, "EXIT requesting normal Minecraft shutdown"); ProbeFiles.write(role + "-exit", "normal shutdown requested");
                stopped = true;
                if (mc.getConnection() != null) mc.getConnection().getConnection().disconnect(Component.literal("Multiplayer acceptance complete"));
                mc.stop(); return;
            }
            if (!connecting && mc.screen instanceof TitleScreen && ProbeFiles.exists("server-ready")) {
                connecting = true;
                String address = "127.0.0.1:" + System.getProperty("itemexplorer.multiplayerPort", "25589");
                ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address), new ServerData("Isolated multiplayer probe", address, false), false);
                return;
            }
            if (mc.player == null || !(mc.player.containerMenu instanceof StorageMenu menu) || !menu.view().contains("Session")) return;
            CompoundTag view = menu.view();
            if (captured == null && view.getLong("Total") == 64) {
                captured = request(menu, 48);
                ProbeFiles.write(role + "-ready", Long.toString(captured.revision()));
                ProbeFiles.log(role, "CAPTURE menu=" + captured.menuId() + " revision=" + captured.revision() + " session=" + captured.session()
                        + " remote=" + mc.getConnection().getConnection().getRemoteAddress() + " memoryConnection=" + mc.getConnection().getConnection().isMemoryConnection());
            }
            if (captured != null && !race && ProbeFiles.exists("race-go")) {
                StorageNetwork.request(captured); race = true; ProbeFiles.write(role + "-race-sent", "WITHDRAW 48");
            }
            if (race && !stale && ProbeFiles.exists("stale-go")) {
                StorageNetwork.request(captured); stale = true; ProbeFiles.write(role + "-stale-sent", "original request replayed");
            }
            if (!retry && ProbeFiles.exists("retry-go") && ProbeFiles.read("retry-go").equals(role) && view.getLong("Total") == 16 && view.getLong("Revision") > captured.revision()) {
                StorageNetwork.request(request(menu, 48)); retry = true; ProbeFiles.write(role + "-retry-sent", "fresh WITHDRAW 48");
            }
            if (!close && role.equals("DevA") && ProbeFiles.exists("close-go") && view.getLong("Total") == 32) {
                StorageNetwork.Request late = request(menu, 32);
                mc.player.closeContainer(); StorageNetwork.request(late); close = true;
                ProbeFiles.write(role + "-close-sent", "vanilla close then stale WITHDRAW 32 on same TCP connection");
            }
        } catch (Throwable failure) {
            ProbeFiles.log(role, "FAIL " + failure); ProbeFiles.write("abort", role + " " + failure);
            stopped = true; mc.stop();
        }
    }
    private static StorageNetwork.Request request(StorageMenu menu, long amount) {
        CompoundTag view = menu.view();
        var entries = view.getList("Entries", Tag.TAG_COMPOUND);
        ProbeFiles.check(!entries.isEmpty(), "Expected an iron entry in real received snapshot");
        return new StorageNetwork.Request(menu.containerId, view.getLong("Revision"), StorageNetwork.Action.WITHDRAW,
                entries.getCompound(0).getInt("Id"), 0, amount, "", view.getLong("Session"));
    }
}
