package dev.itemexplorer.client;

import dev.itemexplorer.menu.NasMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class NasScreen extends AbstractContainerScreen<NasMenu> {
    private final Button[] install = new Button[4], eject = new Button[4];
    private CompoundTag seen;
    private String error = "";
    private long errorUntil;
    public NasScreen(NasMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 284; imageHeight = 236; }
    private Component label(String key) { return Component.translatable("gui.itemexplorer." + key); }
    @Override protected void init() {
        super.init();
        for (int i = 0; i < 4; i++) {
            final int bay = i;
            install[i] = addRenderableWidget(Button.builder(label("install"), b -> send(bay, false)).bounds(leftPos + 196, topPos + 27 + i * 29, 38, 18).build());
            eject[i] = addRenderableWidget(Button.builder(label("eject"), b -> send(bay, true)).bounds(leftPos + 238, topPos + 27 + i * 29, 38, 18).build());
            install[i].setTooltip(Tooltip.create(label("install_hint")));
            eject[i].setTooltip(Tooltip.create(label("eject_hint")));
        }
    }
    private void send(int bay, boolean eject) {
        if (!menu.view().contains("Session")) return;
        StorageNetwork.request(new StorageNetwork.NasRequest(menu.containerId, menu.view().getLong("Session"), menu.view().getLong("Revision"), bay, eject));
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ListTag bays = menu.view().getList("Bays", Tag.TAG_COMPOUND);
        for (int i = 0; i < 4; i++) {
            boolean ready = bays.size() == 4 && !menu.view().getBoolean("Locked");
            boolean empty = ready && ItemStack.of(bays.getCompound(i).getCompound("Stack")).isEmpty();
            install[i].active = ready && empty && !menu.getCarried().isEmpty(); eject[i].active = ready && !empty;
        }
        if (seen != menu.view()) {
            seen = menu.view(); String message = seen.getString("Message");
            if (!message.isEmpty()) { error = message; errorUntil = Util.getMillis() + 4500; }
        }
        renderBackground(g); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
        if (menu.view().getBoolean("Locked") || Util.getMillis() < errorUntil) {
            var lines = font.split(Component.translatable("message.itemexplorer." + (menu.view().getBoolean("Locked") ? "storage_locked" : error)), 260);
            int y = topPos + 130 - lines.size() * 10;
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            g.fill(leftPos + 8, y - 4, leftPos + 276, topPos + 134, 0xffeeeeee);
            for (var line : lines) { g.drawString(font, line, leftPos + 12, y, 0xffa02020, false); y += 10; }
            g.pose().popPose();
        }
        if (mouseX >= leftPos + 8 && mouseX < leftPos + 190 && mouseY >= topPos + 24 && mouseY < topPos + 140) {
            int i = (mouseY - topPos - 24) / 29;
            if (i < bays.size()) {
                CompoundTag bay = bays.getCompound(i); ItemStack stack = ItemStack.of(bay.getCompound("Stack"));
                if (!stack.isEmpty() && menu.getCarried().isEmpty()) {
                    var lines = new java.util.ArrayList<>(getTooltipFromItem(minecraft, stack));
                    lines.add(Component.translatable("message.itemexplorer." + bay.getString("Status")));
                    g.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
                }
            }
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff373737);
        g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffeeeeee);
        g.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xffc6c6c6);
        g.drawString(font, title, leftPos + 9, topPos + 9, 0xff303030, false);
        ListTag bays = menu.view().getList("Bays", Tag.TAG_COMPOUND);
        for (int i = 0; i < 4; i++) {
            int y = topPos + 24 + i * 29;
            g.fill(leftPos + 8, y, leftPos + 190, y + 26, 0xff8b8b8b);
            CompoundTag bay = bays.size() > i ? bays.getCompound(i) : new CompoundTag();
            ItemStack stack = ItemStack.of(bay.getCompound("Stack"));
            if (!stack.isEmpty()) g.renderItem(stack, leftPos + 12, y + 5);
            String name = (i + 1) + ". " + (stack.isEmpty() ? label("empty_bay").getString() : stack.getHoverName().getString());
            g.drawString(font, font.plainSubstrByWidth(name, 152), leftPos + 32, y + 3, 0xff202020, false);
            String status = bay.getString("Status");
            String detail = status.equals("online") ? bay.getLong("Total") + " / " + bay.getLong("Capacity")
                    : Component.translatable("message.itemexplorer." + (status.isEmpty() ? "empty_bay" : status)).getString();
            g.drawString(font, font.plainSubstrByWidth(detail, 152), leftPos + 32, y + 15, 0xff303030, false);
        }
        for (var slot : menu.slots) {
            int x = leftPos + slot.x, y = topPos + slot.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xffffffff); g.fill(x - 1, y - 1, x + 16, y + 16, 0xff373737); g.fill(x, y, x + 16, y + 16, 0xff8b8b8b);
        }
    }
}
