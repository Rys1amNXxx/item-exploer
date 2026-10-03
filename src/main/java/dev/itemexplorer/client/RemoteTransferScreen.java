package dev.itemexplorer.client;

import dev.itemexplorer.menu.StorageMenu;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Compact sending and receiver settings share the existing terminal container. */
public final class RemoteTransferScreen extends Screen {
    private static final UUID NO_TARGET = new UUID(0, 0);
    private static final int ROWS = 3, PANEL_HEIGHT = 238, TEXT = 0xff303b42, MUTED = 0xff52626a;
    private final StorageScreen parent;
    private final StorageMenu menu;
    private final int entry;
    private final ItemStack sample;
    private final String sourceVolume;
    private final int sourceFolder;
    private final long sourceSession, initialAmount;
    private EditBox name, quantity;
    private Button receiving, bind, send, refresh, saveName, settings, back, previousTargets, nextTargets;
    private final Button[] targetButtons = new Button[ROWS], amountButtons = new Button[4];
    private int left, top, panelWidth, targetPage;
    private boolean settingsPage, pending, initialized, feedbackSuccess;
    private UUID selectedTarget;
    private long selectedTargetRevision = -1;
    private List<CompoundTag> targetList = List.of();
    private CompoundTag observedView;
    private StorageNetwork.TransferAction pendingAction;
    private Component feedback = Component.empty();
    private long feedbackUntil, pendingAt, nextRefresh, cooldownUntil;
    private long requestedAmount, requestedStock;
    private String requestedTarget = "";

    public RemoteTransferScreen(StorageScreen parent, StorageMenu menu, int entry, ItemStack sample, long amount) {
        super(label("send_title"));
        this.parent = parent; this.menu = menu; this.entry = entry; this.sample = sample.copy(); this.initialAmount = amount;
        sourceVolume = menu.view().getString("Volume"); sourceFolder = menu.view().getInt("Current");
        sourceSession = menu.view().getLong("Session"); settingsPage = sample.isEmpty();
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("gui.itemexplorer.transfer_" + key, args);
    }
    private CompoundTag config() { return menu.view().getCompound("RemoteConfig"); }
    private Button button(int x, int y, int w, Component text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(left + x, top + y, w, 18).build());
    }

    @Override protected void init() {
        panelWidth = Math.min(390, width - 8); left = (width - panelWidth) / 2; top = (height - PANEL_HEIGHT) / 2;
        String savedName = name == null ? config().getString("Name") : name.getValue();
        String savedQuantity = quantity == null ? Long.toString(initialAmount) : quantity.getValue();
        name = addRenderableWidget(new EditBox(font, left + 62, top + 33, panelWidth - 138, 18, label("name")));
        name.setMaxLength(24); name.setHint(label("unnamed")); name.setValue(savedName);
        saveName = button(panelWidth - 70, 33, 60, label("save_name"), b -> request(StorageNetwork.TransferAction.NAME));
        bind = button(10, 111, panelWidth - 20, label("bind"), b -> request(StorageNetwork.TransferAction.BIND));
        receiving = button(10, 137, panelWidth - 20, label("receiving_off"), b -> request(StorageNetwork.TransferAction.RECEIVING));
        receiving.setTooltip(Tooltip.create(label("receiving_hint")));
        refresh = button(panelWidth - 106, 46, 44, label("refresh_short"), b -> request(StorageNetwork.TransferAction.REFRESH));
        refresh.setTooltip(Tooltip.create(label("targets_hint")));
        previousTargets = button(panelWidth - 56, 46, 20, Component.literal("<"), b -> changeTargetPage(-1));
        nextTargets = button(panelWidth - 30, 46, 20, Component.literal(">"), b -> changeTargetPage(1));
        for (int row = 0; row < ROWS; row++)
            targetButtons[row] = addRenderableWidget(new TargetButton(row, left + 10, top + 67 + row * 30, panelWidth - 20));
        quantity = addRenderableWidget(new EditBox(font, left + 52, top + 161, 34, 18, label("quantity")));
        quantity.setMaxLength(2); quantity.setFilter(s -> s.matches("[0-9]{0,2}")); quantity.setValue(savedQuantity);
        int[] amounts = {1, 16, 64};
        for (int i = 0; i < amounts.length; i++) {
            int amount = amounts[i];
            amountButtons[i] = button(94 + i * 32, 161, 28, Component.literal(Integer.toString(amount)),
                    b -> quantity.setValue(Integer.toString(amount)));
        }
        amountButtons[3] = button(190, 161, 42, label("max"),
                b -> quantity.setValue(Long.toString(Math.min(64, Math.max(0, currentStock())))));
        amountButtons[3].setTooltip(Tooltip.create(label("max_hint")));
        back = button(10, 214, 56, label("back"), b -> onClose());
        settings = button(72, 214, 82, label("settings"), b -> showSettings(true));
        send = button(panelWidth - 138, 214, 128, label("send_short"), b -> request(StorageNetwork.TransferAction.SEND));
        send.setTooltip(Tooltip.create(label("send_hint")));
        clearInputFocus(); observeView(); updateWidgets();
        if (!initialized) { initialized = true; request(StorageNetwork.TransferAction.REFRESH); }
    }

    private void clearInputFocus() {
        setFocused(null); name.setFocused(false); quantity.setFocused(false);
    }
    private void showSettings(boolean show) { settingsPage = show; clearInputFocus(); updateWidgets(); }
    private int targetPages() { return Math.max(1, (targetList.size() + ROWS - 1) / ROWS); }
    private void changeTargetPage(int direction) {
        targetPage = Math.max(0, Math.min(targetPages() - 1, targetPage + direction));
        clearInputFocus(); updateWidgets();
    }
    private CompoundTag rowTarget(int row) {
        int index = targetPage * ROWS + row;
        return index >= 0 && index < targetList.size() ? targetList.get(index) : null;
    }
    private CompoundTag target() {
        for (CompoundTag target : targetList) if (target.getUUID("Id").equals(selectedTarget)
                && target.getLong("Revision") == selectedTargetRevision) return target;
        return null;
    }
    private long amount() {
        try { return Long.parseLong(quantity.getValue()); } catch (NumberFormatException ignored) { return 0; }
    }
    /** A missing published row is never inferred from old samples or inventory totals. */
    private long currentStock() {
        if (!sameContext()) return -1;
        for (Tag tag : menu.view().getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) tag;
            if (item.getInt("Id") == entry) return item.getLong("Count");
        }
        return -1;
    }
    private boolean sameContext() {
        CompoundTag view = menu.view();
        return view.getLong("Session") == sourceSession && view.getString("Volume").equals(sourceVolume)
                && view.getInt("Current") == sourceFolder && !view.getBoolean("Searching");
    }
    private boolean writable() {
        return sameContext() && menu.view().getBoolean("Available") && !menu.view().getBoolean("Locked");
    }
    private boolean canSend() {
        return !pending && writable() && target() != null && amount() >= 1 && amount() <= 64
                && currentStock() >= amount() && Util.getMillis() >= cooldownUntil;
    }
    private void request(StorageNetwork.TransferAction action) {
        observeView();
        CompoundTag view = menu.view(), config = config(), target = target();
        if (!view.contains("Session") || pending || action == StorageNetwork.TransferAction.SEND && !canSend()) return;
        if (action == StorageNetwork.TransferAction.BIND && !writable()) return;
        if (action == StorageNetwork.TransferAction.SEND) {
            requestedAmount = amount(); requestedStock = currentStock(); requestedTarget = targetDisplayName(target);
        }
        StorageNetwork.request(new StorageNetwork.TransferRequest(menu.containerId, view.getLong("Session"),
                view.getLong("Revision"), config.getLong("ConfigRevision"), action, name.getValue(),
                sourceVolume, sourceFolder, !config.getBoolean("Enabled"), entry, amount(),
                target == null ? NO_TARGET : target.getUUID("Id"), target == null ? -1 : target.getLong("Revision")));
        pending = true; pendingAction = action; pendingAt = Util.getMillis(); observedView = view;
        if (action != StorageNetwork.TransferAction.REFRESH) feedback = Component.empty();
        nextRefresh = pendingAt + 3000; updateWidgets();
    }
    private void setFeedback(Component text, boolean success) {
        feedback = text; feedbackSuccess = success; feedbackUntil = Util.getMillis() + 8000;
    }
    /** Consume each reply before a second snapshot in the same frame can replace its result. */
    public void acceptSnapshot(StorageMenu updatedMenu) {
        if (updatedMenu != menu) return;
        observeView(); updateWidgets();
    }
    private void observeView() {
        CompoundTag view = menu.view();
        if (view == observedView) return;
        observedView = view;
        List<CompoundTag> targets = new ArrayList<>();
        for (Tag tag : view.getList("RemoteTargets", Tag.TAG_COMPOUND)) {
            CompoundTag candidate = (CompoundTag) tag;
            if (candidate.hasUUID("Id")) targets.add(candidate);
        }
        targets.sort(Comparator.comparing((CompoundTag t) -> !t.getBoolean("Local")).thenComparingLong(t -> t.getLong("Pos")));
        targetList = List.copyOf(targets); targetPage = Math.min(targetPage, targetPages() - 1);
        String message = view.getString("Message");
        // Background stock snapshots must not unlock an in-flight send or configuration request.
        if (pending && (pendingAction == StorageNetwork.TransferAction.REFRESH || !message.isEmpty())) {
            if (!message.isEmpty() && pendingAction != StorageNetwork.TransferAction.REFRESH) {
                if (message.equals("transfer_sent") && pendingAction == StorageNetwork.TransferAction.SEND) {
                    long remaining = currentStock() >= 0 ? currentStock() : Math.max(0, requestedStock - requestedAmount);
                    setFeedback(label("sent_details", requestedAmount, requestedTarget, remaining), true);
                    cooldownUntil = Util.getMillis() + 1000;
                } else {
                    setFeedback(Component.translatable("message.itemexplorer." + message), message.equals("transfer_configured"));
                    if (message.equals("remote_busy") || message.equals("remote_cooldown")) cooldownUntil = Util.getMillis() + 1000;
                }
            }
            pending = false; pendingAction = null;
        }
        if (selectedTarget != null && target() == null) {
            selectedTarget = null; selectedTargetRevision = -1;
            setFeedback(Component.translatable("message.itemexplorer.remote_target_changed"), false);
        }
    }

    private String diskName(CompoundTag data) {
        String disk = data.getString("InboxVolumeName");
        return disk.isEmpty() ? label(data.getString("Volume").isEmpty() ? "local_disk" : "nas_disk").getString() : disk;
    }
    private String inboxPath(CompoundTag data) {
        return data.contains("InboxPath") ? data.getString("InboxPath") : label("inbox_unavailable").getString();
    }
    private String destination(CompoundTag data) { return diskName(data) + " " + inboxPath(data); }
    private String targetName(CompoundTag target) {
        return target.getString("Name").isBlank() ? label("unnamed").getString() : target.getString("Name");
    }
    private String coordinates(CompoundTag target) {
        BlockPos pos = BlockPos.of(target.getLong("Pos"));
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
    private boolean duplicateName(CompoundTag target) {
        String name = targetName(target);
        return targetList.stream().filter(t -> targetName(t).equals(name)).count() > 1;
    }
    private String targetDisplayName(CompoundTag target) {
        return targetName(target) + (duplicateName(target) ? " (" + coordinates(target) + ")" : "");
    }
    private Component targetDetails(CompoundTag target) {
        return Component.literal(targetName(target) + " · " + coordinates(target) + "\n" + destination(target))
                .append("\n").append(label(target.getBoolean("Local") ? "local" : "remote"));
    }
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }
    private void line(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
        g.drawString(font, fit(text.getString(), maxWidth), left + x, top + y, color, false);
    }
    private Component summary() {
        CompoundTag target = target();
        if (sample.isEmpty()) return label("select_item_short");
        if (target == null) return label("choose_target");
        return label("summary", amount(), sample.getHoverName(), targetDisplayName(target));
    }
    private void updateWidgets() {
        if (name == null || send == null) return;
        name.visible = saveName.visible = bind.visible = receiving.visible = settingsPage;
        quantity.visible = send.visible = settings.visible = refresh.visible = previousTargets.visible = nextTargets.visible = !settingsPage;
        saveName.active = receiving.active = !pending && menu.view().contains("Session");
        name.setEditable(!pending); bind.active = !pending && writable();
        receiving.setMessage(label(config().getBoolean("Enabled") ? "receiving_on" : "receiving_off"));
        previousTargets.active = !pending && targetPage > 0;
        nextTargets.active = !pending && targetPage + 1 < targetPages();
        refresh.active = !pending; quantity.setEditable(!pending && writable());
        for (int row = 0; row < ROWS; row++) {
            CompoundTag target = rowTarget(row);
            targetButtons[row].visible = !settingsPage && target != null;
            targetButtons[row].active = !pending && target != null;
            targetButtons[row].setMessage(target == null ? Component.empty() : targetDetails(target));
            targetButtons[row].setTooltip(target == null ? null : Tooltip.create(targetDetails(target)));
        }
        int[] presets = {1, 16, 64, 1};
        for (int i = 0; i < amountButtons.length; i++) {
            amountButtons[i].visible = !settingsPage;
            amountButtons[i].active = !pending && writable() && currentStock() >= presets[i];
        }
        send.active = canSend();
        send.setMessage(pending && pendingAction == StorageNetwork.TransferAction.SEND ? label("sending")
                : Util.getMillis() < cooldownUntil ? label("cooldown") : label("send_amount", Math.max(0, amount())));
        back.setMessage(label(settingsPage && !sample.isEmpty() ? "back_to_send" : "back"));
    }

    private final class TargetButton extends Button {
        private final int row;
        private CompoundTag displayedTarget;
        private TargetButton(int row, int x, int y, int width) {
            super(x, y, width, 28, Component.empty(), b -> ((TargetButton) b).chooseDisplayed(), DEFAULT_NARRATION);
            this.row = row;
        }
        private void chooseDisplayed() {
            observeView();
            CompoundTag current = rowTarget(row);
            if (pending || displayedTarget == null || current == null
                    || !current.getUUID("Id").equals(displayedTarget.getUUID("Id"))
                    || current.getLong("Revision") != displayedTarget.getLong("Revision")) return;
            selectedTarget = displayedTarget.getUUID("Id"); selectedTargetRevision = displayedTarget.getLong("Revision");
            clearInputFocus(); updateWidgets();
        }
        @Override public void renderString(GuiGraphics g, Font font, int color) {
            CompoundTag target = rowTarget(row);
            displayedTarget = target == null ? null : target.copy();
            if (target == null) return;
            boolean selected = target.getUUID("Id").equals(selectedTarget) && target.getLong("Revision") == selectedTargetRevision;
            if (selected) g.renderOutline(getX(), getY(), getWidth(), getHeight(), 0xff8ed8ad);
            String badge = label(target.getBoolean("Local") ? "local" : "remote").getString();
            int badgeWidth = font.width(badge), nameWidth = getWidth() - badgeWidth - 23;
            String name = targetName(target);
            if (duplicateName(target)) {
                String suffix = " (" + coordinates(target) + ")";
                name = fit(name, Math.max(0, nameWidth - font.width(suffix))) + suffix;
            }
            g.drawString(font, fit((selected ? "✓ " : "") + name, nameWidth), getX() + 6, getY() + 4, color, false);
            g.drawString(font, badge, getX() + getWidth() - badgeWidth - 6, getY() + 4, active ? 0xffd6dfb2 : 0xff858585, false);
            g.drawString(font, fit(destination(target), getWidth() - 12), getX() + 6, getY() + 16, active ? 0xffdddddd : 0xff858585, false);
        }
    }

    @Override public void tick() {
        name.tick(); quantity.tick(); observeView();
        if (minecraft.player == null || minecraft.player.containerMenu != menu) { minecraft.setScreen(null); return; }
        if (pending && Util.getMillis() - pendingAt > 3000) {
            boolean sending = pendingAction == StorageNetwork.TransferAction.SEND;
            pending = false; pendingAction = null;
            setFeedback(label(sending ? "send_timeout" : "refresh_timeout"), false);
            nextRefresh = Util.getMillis() + 3000;
        }
        if (!pending && Util.getMillis() >= nextRefresh) request(StorageNetwork.TransferAction.REFRESH);
        updateWidgets();
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!settingsPage && !pending && mouseX >= left + 10 && mouseX < left + panelWidth - 10
                && mouseY >= top + 67 && mouseY < top + 157) {
            changeTargetPage(delta > 0 ? -1 : 1); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (settingsPage && !sample.isEmpty()) { showSettings(false); return; }
        minecraft.setScreen(minecraft.player != null && minecraft.player.containerMenu == menu ? parent : null);
    }
    private boolean inside(double x, double y, int px, int py, int w, int h) {
        return x >= left + px && x < left + px + w && y >= top + py && y < top + py + h;
    }
    private void drawSettings(GuiGraphics g) {
        CompoundTag config = config();
        line(g, label("settings_title"), 10, 10, panelWidth - 20, TEXT);
        line(g, label("name"), 10, 38, 48, TEXT);
        line(g, label("current_inbox"), 10, 66, panelWidth - 20, MUTED);
        line(g, Component.literal(diskName(config)), 10, 81, panelWidth - 20, TEXT);
        line(g, Component.literal(inboxPath(config)), 10, 95, panelWidth - 20, TEXT);
        int y = 165;
        for (var text : font.split(label("receiving_hint"), panelWidth - 20)) {
            if (y > 187) break;
            g.drawString(font, text, left + 10, top + y, MUTED, false); y += 11;
        }
    }
    private void drawSend(GuiGraphics g) {
        line(g, title, 10, 10, panelWidth - 145, TEXT);
        line(g, label(!config().getBoolean("StationConnected") ? "route_missing"
                : config().getBoolean("NetworkOnline") ? "route_online" : "route_local"), panelWidth - 136, 10, 126, MUTED);
        if (!sample.isEmpty()) {
            g.renderItem(sample, left + 10, top + 27);
            long stock = currentStock();
            Component count = stock < 0 ? label("stock_unavailable") : label("stock", stock);
            int stockWidth = Math.min(112, font.width(count));
            line(g, sample.getHoverName(), 31, 31, panelWidth - stockWidth - 49, TEXT);
            line(g, count, panelWidth - stockWidth - 10, 31, stockWidth, stock < 0 ? 0xff943e32 : MUTED);
        } else line(g, label("select_item_short"), 10, 31, panelWidth - 20, MUTED);
        line(g, label("targets_page", targetPage + 1, targetPages()), 10, 51, panelWidth - 126, TEXT);
        if (targetList.isEmpty()) {
            g.fill(left + 10, top + 67, left + panelWidth - 10, top + 155, 0xffbec5c1);
            int y = 82;
            Component explanation = !config().getBoolean("StationConnected") ? label("no_station") : label("empty_targets");
            for (var text : font.split(explanation, panelWidth - 44)) {
                if (y > 142) break;
                g.drawString(font, text, left + 22, top + y, MUTED, false); y += 12;
            }
        }
        line(g, label("quantity"), 10, 166, 39, TEXT);
        line(g, Component.literal("1–64"), panelWidth - 58, 166, 48, MUTED);
        line(g, summary(), 10, 185, panelWidth - 20, TEXT);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        observeView(); updateWidgets(); renderBackground(g);
        g.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xff343e45);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + PANEL_HEIGHT - 2, 0xffd1d5d0);
        if (settingsPage) drawSettings(g); else drawSend(g);
        Component status = !settingsPage && !sameContext() ? label("context_changed")
                : Util.getMillis() < feedbackUntil ? feedback : Component.empty();
        line(g, status, 10, 200, panelWidth - 20, feedbackSuccess && (settingsPage || sameContext()) ? 0xff286539 : 0xff953c30);
        super.render(g, mouseX, mouseY, partialTick);
        if (settingsPage && inside(mouseX, mouseY, 10, 65, panelWidth - 20, 40))
            g.renderTooltip(font, Component.literal(destination(config())), mouseX, mouseY);
        else if (!settingsPage && inside(mouseX, mouseY, 10, 183, panelWidth - 20, 11)) {
            CompoundTag target = target();
            if (target != null) g.renderTooltip(font, List.of(summary(), targetDetails(target)), java.util.Optional.empty(), mouseX, mouseY);
        } else if (!status.getString().isEmpty() && inside(mouseX, mouseY, 10, 198, panelWidth - 20, 12))
            g.renderTooltip(font, status, mouseX, mouseY);
    }
}
