package dev.itemexplorer.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

final class VanillaGui {
    private static final ResourceLocation CONTAINER = new ResourceLocation("minecraft", "textures/gui/container/crafting_table.png");
    private static volatile GuiPalette palette = GuiPalette.DEFAULT;

    private VanillaGui() {}

    static void reload(ResourceManager resources) {
        palette = GuiPalette.load(resources);
    }

    static void panel(GuiGraphics graphics, int left, int top, int width, int height) {
        surface(graphics, left, top, width, height, palette.panel(), true);
    }

    static void recess(GuiGraphics graphics, int left, int top, int width, int height) {
        surface(graphics, left - 1, top - 1, width + 2, height + 2, palette.recess(), true);
    }

    static void recessBorder(GuiGraphics graphics, int left, int top, int width, int height) {
        surface(graphics, left - 1, top - 1, width + 2, height + 2, palette.recess(), false);
    }

    static void slot(GuiGraphics graphics, int left, int top) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(CONTAINER, left - 1, top - 1, 79, 101, 18, 18);
        RenderSystem.disableBlend();
    }

    static void playerInventory(GuiGraphics graphics, int left, int top) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(CONTAINER, left - 1, top - 1, 7, 83, 162, 54);
        graphics.blit(CONTAINER, left - 1, top + 57, 7, 141, 162, 18);
        RenderSystem.disableBlend();
    }

    static void crafting(GuiGraphics graphics, int gridLeft, int gridTop, int resultLeft, int resultTop) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(CONTAINER, gridLeft - 1, gridTop - 1, 29, 16, 54, 54);
        graphics.blit(CONTAINER, gridLeft + 60, resultTop, 90, 35, 22, 15);
        graphics.blit(CONTAINER, resultLeft - 5, resultTop - 5, 119, 30, 26, 26);
        RenderSystem.disableBlend();
    }

    static int panelText(int preferred) {
        return readable(preferred, palette.panel().background());
    }

    static int recessText(int preferred) {
        return recessText(preferred, 0);
    }

    static int recessText(int preferred, int overlay) {
        int background = composite(palette.recess().background(), palette.panel().background());
        return readable(preferred, composite(overlay, background));
    }

    static boolean panelShadow() {
        return palette.panel().background() >>> 24 < 255 || luminance(palette.panel().background()) < 0.2;
    }

    private static int readable(int preferred, int background) {
        double darkest = luminance(composite(background, 0xff000000));
        double lightest = luminance(composite(background, 0xffffffff));
        if (contrast(preferred, darkest, lightest) >= 4.5) return preferred;
        return contrast(0xff101010, darkest, lightest) > contrast(0xfff5f5f5, darkest, lightest) ? 0xff101010 : 0xfff5f5f5;
    }

    private static double contrast(int foreground, double darkest, double lightest) {
        double brightness = luminance(foreground);
        if (brightness < darkest) return (darkest + 0.05) / (brightness + 0.05);
        if (brightness > lightest) return (brightness + 0.05) / (lightest + 0.05);
        return 1;
    }

    private static int composite(int foreground, int background) {
        double foregroundAlpha = (foreground >>> 24) / 255.0;
        double backgroundAlpha = (background >>> 24) / 255.0 * (1 - foregroundAlpha);
        double alpha = foregroundAlpha + backgroundAlpha;
        if (alpha == 0) return 0;
        int result = (int) Math.round(alpha * 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            double channel = ((foreground >>> shift) & 255) * foregroundAlpha + ((background >>> shift) & 255) * backgroundAlpha;
            result |= (int) Math.round(channel / alpha) << shift;
        }
        return result;
    }

    private static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 255) + 0.7152 * linear((color >> 8) & 255) + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static void surface(GuiGraphics graphics, int left, int top, int width, int height, GuiPalette.Surface surface, boolean background) {
        int depth = Math.min(surface.depth(), Math.min(width, height) / 2);
        if (background) graphics.fill(left + depth, top + depth, left + width - depth, top + height - depth, surface.background());
        for (int inset = 0; inset < depth; inset++) {
            graphics.fill(left + inset, top + inset, left + width - inset, top + inset + 1, surface.top(inset));
            graphics.fill(left + inset, top + height - inset - 1, left + width - inset, top + height - inset, surface.bottom(inset));
            graphics.fill(left + inset, top + inset + 1, left + inset + 1, top + height - inset - 1, surface.left(inset));
            graphics.fill(left + width - inset - 1, top + inset + 1, left + width - inset, top + height - inset - 1, surface.right(inset));
        }
    }
}
