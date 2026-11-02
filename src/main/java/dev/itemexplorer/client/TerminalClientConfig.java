package dev.itemexplorer.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Per-player terminal preferences; never synced to the server. */
public final class TerminalClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue COMPACT_GRID;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("terminal");
        COMPACT_GRID = builder.comment("Show stored items as an icon-only slot grid instead of labelled cards.")
                .define("compactGrid", false);
        builder.pop();
        SPEC = builder.build();
    }

    private TerminalClientConfig() {}

    static boolean compactGrid() {
        // Screens can open before the client config is loaded in some test harnesses.
        return SPEC.isLoaded() && COMPACT_GRID.get();
    }

    static void setCompactGrid(boolean compact) {
        if (!SPEC.isLoaded()) return;
        COMPACT_GRID.set(compact);
        COMPACT_GRID.save();
    }
}
