package dev.itemexplorer.portable;

import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.StorageBlockEntity;
import dev.itemexplorer.menu.StorageMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import java.util.List;

/** Handheld access credential; its inventory remains in the bound terminal and NAS devices. */
public final class PortableTerminalItem extends Item {
    public PortableTerminalItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null || !player.isShiftKeyDown()
                || !level.getBlockState(context.getClickedPos()).is(ModContent.STORAGE_BLOCK.get())) return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            StorageBlockEntity terminal = level.getBlockEntity(context.getClickedPos()) instanceof StorageBlockEntity found ? found : null;
            String status = PortableAccess.pair(serverPlayer, context.getHand(), terminal);
            player.displayClientMessage(status.isEmpty()
                    ? Component.translatable("portable.itemexplorer.paired", terminal.getDisplayName())
                    : Component.translatable("gui.itemexplorer." + status), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PortableAccess.OpenResult result = PortableAccess.openSession(serverPlayer, hand);
            if (!result.status().isEmpty()) {
                player.displayClientMessage(Component.translatable("gui.itemexplorer." + result.status()), true);
                return InteractionResultHolder.fail(stack);
            }
            StorageBlockEntity terminal = result.terminal();
            PortableAccess.Session session = result.session();
            NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                    (id, inventory, menuPlayer) -> new StorageMenu(id, inventory, terminal.getBlockPos(), terminal, session),
                    Component.translatable("item.itemexplorer.portable_terminal")), data -> {
                data.writeBlockPos(terminal.getBlockPos());
                data.writeBoolean(true);
                data.writeVarInt(session.slot());
            });
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        PortableAccess.Binding binding = PortableAccess.binding(stack);
        if (binding == null) {
            tooltip.add(Component.translatable("portable.itemexplorer.unbound").withStyle(ChatFormatting.GRAY));
        } else {
            String name = PortableAccess.boundName(stack);
            if (name.isEmpty()) name = Component.translatable("block.itemexplorer.storage_terminal").getString();
            tooltip.add(Component.translatable("portable.itemexplorer.bound", name,
                    binding.pos().getX(), binding.pos().getY(), binding.pos().getZ(), binding.dimension()).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("portable.itemexplorer.pairing_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
