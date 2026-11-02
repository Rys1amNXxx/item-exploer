package dev.itemexplorer.production;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static dev.itemexplorer.production.ProcessingPattern.Role;
import static dev.itemexplorer.production.ProcessingPattern.Material;

/** One operation's resolved handlers; identity is preserved when several rows share an inventory. */
final class ProcessingRoute {
    private final ProcessingPattern pattern;
    private final Map<Material, IItemHandler> handlers = new IdentityHashMap<>();
    private final Map<IItemHandler, Set<Integer>> protectedSlots = new IdentityHashMap<>();

    ProcessingRoute(ProcessingPattern pattern, Function<Material, IItemHandler> resolve) {
        this.pattern = pattern;
        for (Material material : pattern.inputs()) bind(material, resolve);
        for (Material material : pattern.outputs()) bind(material, resolve);
        for (Material material : pattern.inputs()) if (protectedRole(material)) {
            if (!protectedSlots.computeIfAbsent(handler(material), unused -> new HashSet<>()).add(material.slot()))
                throw new IllegalArgumentException("production_invalid_config");
        }
        for (Material material : pattern.inputs()) if (!protectedRole(material)) checkOverlap(material);
        for (Material material : pattern.outputs()) checkOverlap(material);
    }

    private void bind(Material material, Function<Material, IItemHandler> resolve) {
        IItemHandler handler = resolve.apply(material);
        if (handler == null || handler.getSlots() < 1 || handler.getSlots() > ItemHandlerAccess.MAX_SLOTS)
            throw new IllegalArgumentException("production_machine_offline");
        if (material.slot() >= handler.getSlots()) throw new IllegalArgumentException("production_invalid_config");
        handlers.put(material, handler);
    }

    private void checkOverlap(Material material) {
        if (material.slot() >= 0 && excluded(handler(material)).contains(material.slot()))
            throw new IllegalArgumentException("production_invalid_config");
    }

    IItemHandler handler(Material material) { return handlers.get(material); }
    Set<Integer> excluded(IItemHandler handler) { return protectedSlots.getOrDefault(handler, Set.of()); }
    static boolean protectedRole(Material material) { return material.role() == Role.RETAINED || material.role() == Role.SUPPLY; }

    void requireReady() {
        for (Material material : pattern.inputs()) {
            if (material.role() == Role.RETAINED) requireRetained(material);
            else if (material.role() == Role.SUPPLY) supplyDeficit(material);
            else requireEmpty(material);
        }
        for (Material material : pattern.outputs()) requireEmpty(material);
    }

    void requireRetained() {
        for (Material material : pattern.inputs()) if (material.role() == Role.RETAINED) requireRetained(material);
    }

    private void requireRetained(Material material) {
        ItemStack present = handler(material).getStackInSlot(material.slot()), expected = material.stack();
        ItemStack actualSample = present.copy(), expectedSample = expected.copy();
        // Wear is the expected operation of a retained tool, while names/enchantments and other data remain exact.
        if (actualSample.isDamageableItem() && expectedSample.isDamageableItem()) {
            actualSample.setDamageValue(0); expectedSample.setDamageValue(0);
        }
        if (!ProcessingPattern.matches(actualSample, expectedSample) || present.getCount() < expected.getCount())
            throw new IllegalArgumentException("production_machine_busy");
    }

    int supplyDeficit(Material material) {
        IItemHandler handler = handler(material); ItemStack present = handler.getStackInSlot(material.slot()), target = material.stack();
        int limit = Math.min(handler.getSlotLimit(material.slot()), target.getMaxStackSize());
        if (target.getCount() > limit || (!present.isEmpty() && !ProcessingPattern.matches(present, target))
                || present.getCount() > limit) throw new IllegalArgumentException("production_machine_busy");
        return Math.max(0, target.getCount() - present.getCount());
    }

    private void requireEmpty(Material material) {
        IItemHandler handler = handler(material);
        int first = material.slot() < 0 ? 0 : material.slot(), end = material.slot() < 0 ? handler.getSlots() : first + 1;
        for (int slot = first; slot < end; slot++) if (!excluded(handler).contains(slot) && !handler.getStackInSlot(slot).isEmpty())
            throw new IllegalArgumentException("production_machine_busy");
    }
}
