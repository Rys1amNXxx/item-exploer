package dev.itemexplorer.station;

import net.minecraftforge.common.ForgeConfigSpec;

/** Per-world server limit, shared by stations in every dimension. */
public final class StationChunkLoadingConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STATIONS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("stationChunkLoading");
        MAX_ACTIVE_STATIONS = builder.comment("Maximum active chunk-maintaining base stations across all dimensions.",
                        "Each station owns nine fully ticking chunk tickets; overlapping stations still count separately.",
                        "Set to 0 to disable station chunk loading. No energy or fuel is consumed.")
                .defineInRange("maxActiveStations", 16, 0, 1024);
        builder.pop();
        SPEC = builder.build();
    }

    private StationChunkLoadingConfig() {}
}
