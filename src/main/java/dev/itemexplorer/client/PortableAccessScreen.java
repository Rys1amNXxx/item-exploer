package dev.itemexplorer.client;

import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import dev.itemexplorer.station.StationConnection;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Fixed-terminal access management shares the already validated storage container. */
public final class PortableAccessScreen extends Screen {
    private static final int PANEL_HEIGHT = 228, TEXT = 0xff303b42, MUTED = 0xff52626a;
    private final RemoteTransferScreen parent;
    private final StorageMenu menu;
    private Button enabled, revoke;
    private int left, top, panelWidth;
    private boolean pending, feedbackSuccess;
    private long requestedRevision, pendingAt, confirmUntil, feedbackUntil;
    private CompoundTag observedView;
    private Component feedback = Component.empty();

    public PortableAccessScreen(RemoteTransferScreen parent, StorageMenu menu) {
        super(label("access"));
        this.parent = parent;
        this.menu = menu;
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("gui.itemexplorer.portable_" + key, args);
    }

    private Button button(int x, int y, int width, Component text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(left + x, top + y, width, 18).build());
    }

    @Override protected void init() {
        panelWidth = Math.min(360, width - 8);
        left = (width - panelWidth) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        enabled = button(10, 78, panelWidth - 20, label("access_off"),
                b -> request(StorageNetwork.PortableAction.ENABLE));
        enabled.setTooltip(Tooltip.create(label("toggle_hint")));
        revoke = button(10, 102, panelWidth - 20, label("revoke_all"), b -> {
            if (Util.getMillis() < confirmUntil) request(StorageNetwork.PortableAction.REVOKE_ALL);
            else { confirmUntil = Util.getMillis() + 5000; updateWidgets(); }
        });
        revoke.setTooltip(Tooltip.create(label("revoke_hint")));
        button(10, 202, 72, Component.translatable("gui.itemexplorer.transfer_back"), b -> onClose());
        observeView();
        updateWidgets();
    }

    private void request(StorageNetwork.PortableAction action) {
        CompoundTag view = menu.view();
        if (pending || menu.isPortable() || !view.contains("Session") || !view.contains("PortableAccessRevision")) return;
        requestedRevision = view.getLong("PortableAccessRevision");
        StorageNetwork.request(new StorageNetwork.PortableRequest(menu.containerId, view.getLong("Session"),
                requestedRevision, action, !view.getBoolean("PortableAccessEnabled")));
        pending = true;
        pendingAt = Util.getMillis();
        confirmUntil = 0;
        feedback = Component.empty();
        updateWidgets();
    }

    private void observeView() {
        CompoundTag view = menu.view();
        if (view == observedView) return;
        observedView = view;
        if (!pending) return;
        boolean changed = view.getLong("PortableAccessRevision") != requestedRevision;
        String message = view.getString("Message");
        if (!changed && message.isEmpty()) return;
        pending = false;
        feedbackSuccess = changed;
        feedback = changed ? label("saved") : Component.translatable("message.itemexplorer." + message);
        feedbackUntil = Util.getMillis() + 5000;
    }

    private void updateWidgets() {
        if (enabled == null) return;
        CompoundTag view = menu.view();
        enabled.active = !pending && !menu.isPortable() && view.contains("PortableAccessRevision");
        enabled.setMessage(label(view.getBoolean("PortableAccessEnabled") ? "access_on" : "access_off"));
        revoke.active = enabled.active && view.getInt("PortablePairings") > 0;
        revoke.setMessage(label(Util.getMillis() < confirmUntil ? "revoke_confirm" : "revoke_all"));
    }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != menu) { minecraft.setScreen(null); return; }
        observeView();
        if (pending && Util.getMillis() - pendingAt > 3000) {
            pending = false;
            feedbackSuccess = false;
            feedback = Component.translatable("message.itemexplorer.stale");
            feedbackUntil = Util.getMillis() + 5000;
        }
        updateWidgets();
    }

    private void line(GuiGraphics graphics, Component text, int y, int color) {
        String value = text.getString();
        if (font.width(value) > panelWidth - 20)
            value = font.plainSubstrByWidth(value, panelWidth - 20 - font.width("…")) + "…";
        graphics.drawString(font, value, left + 10, top + y, VanillaGui.panelText(color), VanillaGui.panelShadow());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        observeView();
        updateWidgets();
        renderBackground(graphics);
        VanillaGui.panel(graphics, left, top, panelWidth, PANEL_HEIGHT);
        line(graphics, title, 10, TEXT);
        boolean connected = menu.cable().status() == StationConnection.Status.CONNECTED;
        boolean wireless = menu.view().getCompound("RemoteConfig").getBoolean("NetworkOnline");
        int y = 31;
        for (var text : font.split(label(!connected ? "station_needed" : wireless ? "station_ready" : "wireless_needed"), panelWidth - 20)) {
            graphics.drawString(font, text, left + 10, top + y, VanillaGui.panelText(MUTED), VanillaGui.panelShadow());
            y += 11;
            if (y > 53) break;
        }
        line(graphics, label("devices", menu.view().getInt("PortablePairings")), 61, TEXT);
        y = 130;
        for (var text : font.split(label("setup_hint"), panelWidth - 20)) {
            graphics.drawString(font, text, left + 10, top + y, VanillaGui.panelText(MUTED), VanillaGui.panelShadow());
            y += 11;
            if (y > 174) break;
        }
        if (Util.getMillis() < feedbackUntil) line(graphics, feedback, 185, feedbackSuccess ? 0xff286539 : 0xff953c30);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= left + 10 && mouseX < left + panelWidth - 10 && mouseY >= top + 130 && mouseY < top + 180)
            graphics.renderTooltip(font, label("setup_hint"), mouseX, mouseY);
        else if (Util.getMillis() < feedbackUntil && mouseX >= left + 10 && mouseX < left + panelWidth - 10
                && mouseY >= top + 183 && mouseY < top + 199)
            graphics.renderTooltip(font, feedback, mouseX, mouseY);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        minecraft.setScreen(minecraft.player != null && minecraft.player.containerMenu == menu ? parent : null);
    }
}
