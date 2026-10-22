package dev.itemexplorer.production;

import dev.itemexplorer.block.LogisticsPortBlockEntity;
import dev.itemexplorer.block.NasBlockEntity;
import dev.itemexplorer.block.StorageBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

/** Bounded, sided access to external inventories. Never retains a capability across ticks. */
public final class ItemHandlerAccess {
    public static final int MAX_SLOTS = 256;

    private ItemHandlerAccess() {}

    public static IItemHandler handler(BlockEntity machine, Direction face) {
        if (machine == null || machine.isRemoved() || face == null
                || machine instanceof LogisticsPortBlockEntity || machine instanceof StorageBlockEntity
                || machine instanceof NasBlockEntity) return null;
        try {
            IItemHandler handler = machine.getCapability(ForgeCapabilities.ITEM_HANDLER, face).orElse(null);
            return handler != null && handler.getSlots() > 0 && handler.getSlots() <= MAX_SLOTS ? handler : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    public static boolean hasHandler(BlockEntity machine) {
        for (Direction face : Direction.values()) if (handler(machine, face) != null) return true;
        return false;
    }
}
