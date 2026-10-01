package dev.itemexplorer.production;

import net.minecraft.world.level.block.state.BlockState;

public final class MachineAdapters {
    private MachineAdapters() {}
    public static boolean validKind(String kind) {
        return "furnace".equals(kind) || "blast_furnace".equals(kind) || "smoker".equals(kind) || "brewing".equals(kind);
    }
    public static MachineAdapter byKind(String kind) {
        return switch (kind) {
            case "furnace" -> FurnaceMachineAdapter.FURNACE;
            case "blast_furnace" -> FurnaceMachineAdapter.BLAST_FURNACE;
            case "smoker" -> FurnaceMachineAdapter.SMOKER;
            case "brewing" -> BrewingMachineAdapter.INSTANCE;
            default -> throw new IllegalArgumentException("production_invalid_config");
        };
    }
    public static MachineAdapter forState(BlockState state) {
        for (MachineAdapter adapter : new MachineAdapter[]{FurnaceMachineAdapter.FURNACE, FurnaceMachineAdapter.BLAST_FURNACE,
                FurnaceMachineAdapter.SMOKER, BrewingMachineAdapter.INSTANCE}) if (adapter.supports(state)) return adapter;
        return null;
    }
}
