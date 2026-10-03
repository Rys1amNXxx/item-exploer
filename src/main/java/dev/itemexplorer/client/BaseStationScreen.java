package dev.itemexplorer.client;

import dev.itemexplorer.menu.BaseStationMenu;
import dev.itemexplorer.station.BaseStationStructure;
import dev.itemexplorer.station.StationConnection;
import dev.itemexplorer.network.StorageNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/** Structure diagnostics, construction guide, and the station's wireless network switch. */
public final class BaseStationScreen extends AbstractContainerScreen<BaseStationMenu> {
    private static final String[][] LAYERS = {
            {"FPF", "BFB", "FCF"}, {"...", ".M.", "..."}, {"...", ".M.", "..."},
            {".A.", "AMA", ".A."}, {"...", ".T.", "..."}
    };
    private boolean guide;
    private int page;
    private int rows;
    private Button toggle, previous, next, network;

    public BaseStationScreen(BaseStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 310;
        imageHeight = 230;
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("gui.itemexplorer.station_" + key, args);
    }

    @Override protected void init() {
        imageWidth = Math.min(310, width - 8);
        imageHeight = Math.min(230, height - 8);
        rows = Math.max(1, (imageHeight - 109) / 24);
        super.init();
        toggle = addRenderableWidget(Button.builder(label(guide ? "inspect" : "guide"), b -> {
            guide = !guide;
            b.setMessage(label(guide ? "inspect" : "guide"));
        }).bounds(leftPos + imageWidth - 86, topPos + 7, 78, 18).build());
        previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> page = Math.max(0, page - 1))
                .bounds(leftPos + 9, topPos + imageHeight - 25, 24, 18).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> page++)
                .bounds(leftPos + imageWidth - 33, topPos + imageHeight - 25, 24, 18).build());
        network = addRenderableWidget(Button.builder(label("network_off"), b -> {
            StorageNetwork.request(new StorageNetwork.StationNetworkRequest(menu.containerId,
                    menu.networkView().getLong("Session"), menu.networkOnline(), !menu.networkOnline()));
        }).bounds(leftPos + 11, topPos + imageHeight - 53, imageWidth - 22, 18).build());
        network.setTooltip(Tooltip.create(label("network_hint")));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int pages = Math.max(1, (menu.issues().size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        previous.visible = next.visible = !guide && menu.ready() && !menu.complete();
        previous.active = page > 0;
        next.active = page + 1 < pages;
        network.visible = !guide && menu.ready() && menu.complete();
        network.active = menu.networkView().contains("Session");
        network.setMessage(label(menu.networkOnline() ? "network_on" : "network_off"));
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    private void line(GuiGraphics g, Component text, int x, int y, int color, int maxWidth) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), maxWidth), leftPos + x, topPos + y, color, false);
    }

    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff343e45);
        g.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffeef0eb);
        g.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xffc6cbcc);
        line(g, title, 10, 12, 0xff25343c, imageWidth - 104);
        if (guide) {
            drawGuide(g);
            return;
        }
        if (!menu.ready()) {
            line(g, label("loading"), 11, 38, 0xff485961, imageWidth - 22);
            return;
        }
        line(g, label(menu.complete() ? "complete" : "incomplete"), 11, 36,
                menu.complete() ? 0xff286539 : 0xff953c30, imageWidth - 22);
        line(g, label("count", menu.matched(), BaseStationStructure.TOTAL_PARTS), 11, 49, 0xff35434b, imageWidth - 22);
        line(g, Component.translatable(menu.cable().status().key()), 11, 62,
                menu.cable().status() == StationConnection.Status.CONNECTED ? 0xff286539 : 0xff53636b, imageWidth - 22);
        if (menu.complete()) {
            line(g, Component.translatable("gui.itemexplorer.cable_counts", menu.cable().cables(), menu.cable().terminals()),
                    11, 78, 0xff53636b, imageWidth - 22);
            int y = 100;
            for (var line : font.split(label("ready_hint"), imageWidth - 32)) {
                g.drawString(font, line, leftPos + 16, topPos + y, 0xff35434b, false);
                y += 12;
            }
            line(g, label("no_power"), 11, imageHeight - 22, 0xff53636b, imageWidth - 22);
            return;
        }
        List<BaseStationStructure.Issue> issues = menu.issues();
        for (int row = 0; row < rows && page * rows + row < issues.size(); row++) {
            var issue = issues.get(page * rows + row);
            int y = 80 + row * 24;
            g.fill(leftPos + 9, topPos + y - 2, leftPos + imageWidth - 9, topPos + y + 21, 0xffe1e4df);
            String part = Component.translatable("block.itemexplorer.base_station_" + partId(issue.expectedPart())).getString();
            line(g, label("issue", part, label("reason_" + issue.reason().name().toLowerCase(Locale.ROOT))),
                    13, y, 0xff593d35, imageWidth - 26);
            var p = issue.pos();
            line(g, label("position", p.getX(), p.getY(), p.getZ()), 13, y + 11, 0xff53636b, imageWidth - 26);
        }
        int pages = Math.max(1, (issues.size() + rows - 1) / rows);
        g.drawCenteredString(font, label("page", page + 1, pages), leftPos + imageWidth / 2, topPos + imageHeight - 20, 0xff35434b);
    }

    private static String partId(BaseStationStructure.Part part) {
        return switch (part) {
            case CASING -> "casing";
            case CONTROLLER -> "controller";
            case NETWORK_PORT -> "network_port";
            case MODULE -> "module";
            case MAST -> "mast";
            case ANTENNA -> "antenna";
            case CAP -> "cap";
        };
    }

    private void drawGuide(GuiGraphics g) {
        line(g, label("dimensions"), 11, 36, 0xff35434b, imageWidth - 22);
        int step = (imageWidth - 24) / 5;
        for (int layer = 0; layer < 5; layer++) {
            int x = 12 + layer * step;
            line(g, label("layer", layer + 1), x, 54, 0xff35434b, step - 2);
            for (int z = 0; z < 3; z++) for (int col = 0; col < 3; col++) {
                char symbol = LAYERS[layer][z].charAt(col);
                int cx = leftPos + x + col * 12, cy = topPos + 68 + z * 12;
                g.fill(cx, cy, cx + 11, cy + 11, cellColor(symbol));
                if (symbol != '.') g.drawString(font, String.valueOf(symbol), cx + 3, cy + 2, 0xfffaf9f1, false);
            }
        }
        line(g, label("front_hint"), 11, 111, 0xff53636b, imageWidth - 22);
        String[] keys = {"legend_base", "legend_tower", "legend_empty", "rotation_hint"};
        int y = 128;
        for (String key : keys) {
            for (var line : font.split(label(key), imageWidth - 24)) {
                if (y + 9 > imageHeight - 8) return;
                g.drawString(font, line, leftPos + 12, topPos + y, 0xff35434b, false);
                y += 11;
            }
            y += 3;
        }
    }

    private static int cellColor(char c) {
        return switch (c) {
            case 'F' -> 0xff75818b;
            case 'C' -> 0xffa78036;
            case 'P' -> 0xff367f93;
            case 'B' -> 0xff5a7388;
            case 'M' -> 0xff4b5966;
            case 'A' -> 0xff688f82;
            case 'T' -> 0xff478f91;
            default -> 0xffadb7b8;
        };
    }
}
