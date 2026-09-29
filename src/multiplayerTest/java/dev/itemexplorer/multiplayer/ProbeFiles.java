package dev.itemexplorer.multiplayer;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

/** Files coordinate the probes only. Every tested storage operation travels through the game connection. */
final class ProbeFiles {
    static final String PROPERTY = "itemexplorer.multiplayerProbe";
    private ProbeFiles() {}
    static boolean enabled() { return System.getProperty(PROPERTY) != null; }
    static Path root() {
        Path root = Path.of(System.getProperty(PROPERTY)).toAbsolutePath().normalize();
        if (!root.toString().replace('\\', '/').contains("/work/acceptance-multiplayer/"))
            throw new IllegalStateException("Multiplayer probe requires its isolated work directory");
        return root;
    }
    static boolean exists(String name) { return Files.exists(root().resolve(name)); }
    static String read(String name) {
        try { return Files.readString(root().resolve(name)).trim(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static void write(String name, String value) {
        try {
            Path pending = root().resolve(name + "." + ProcessHandle.current().pid() + ".tmp");
            Files.writeString(pending, value + System.lineSeparator());
            Files.move(pending, root().resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static void log(String role, String message) {
        LogUtils.getLogger().info("MULTIPLAYER_PROBE {} {}", role, message);
        try { Files.writeString(root().resolve(role + "-probe.log"), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static void check(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
