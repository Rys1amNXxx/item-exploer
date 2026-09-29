package dev.itemexplorer.disk;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.UUID;

public final class DiskItem extends Item {
    private final DiskTier tier;
    public DiskItem(DiskTier tier) { super(new Properties().stacksTo(1)); this.tier = tier; }
    public DiskTier tier() { return tier; }
    public static UUID id(ItemStack stack) {
        return stack.hasTag() && stack.getTag().hasUUID("DiskId") ? stack.getTag().getUUID("DiskId") : null;
    }
    public static void checkMetadata(ItemStack stack) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(stack.save(new net.minecraft.nbt.CompoundTag()), new java.io.DataOutputStream(bytes));
            if (bytes.size() > 4096) throw new IllegalArgumentException("item_too_large");
        } catch (java.io.IOException e) { throw new IllegalArgumentException("item_too_large", e); }
    }
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("disk.itemexplorer.spec", Component.translatable(tier.translationKey()), tier.limits().capacity()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("disk.itemexplorer.limits", tier.limits().entries(), tier.limits().folders()).withStyle(ChatFormatting.GRAY));
        UUID id = id(stack);
        tooltip.add(Component.translatable(id == null ? "disk.itemexplorer.blank" : "disk.itemexplorer.portable").withStyle(ChatFormatting.GRAY));
        if (id != null && flag.isAdvanced()) tooltip.add(Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
