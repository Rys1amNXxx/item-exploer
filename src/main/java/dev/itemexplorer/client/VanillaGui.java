package dev.itemexplorer.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSequence;

final class VanillaGui {
    private static final ResourceLocation CONTAINER = new ResourceLocation("minecraft", "textures/gui/container/crafting_table.png");
    private static volatile GuiPalette palette = GuiPalette.DEFAULT;

    /** Text roles shared by every screen. Status tones keep their hue when a theme forces a contrast fix. */
    enum Tone {
        TEXT(0xff303030), MUTED(0xff505050), SUCCESS(0xff286539), WARNING(0xff8a5a12), ERROR(0xffa02020);

        final int color;
        Tone(int color) { this.color = color; }
        boolean neutral() { return this == TEXT || this == MUTED; }
    }

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

    /** Small is 8x8 for tree rows; large is 16x13 for tiles and slots. */
    static void folderIcon(GuiGraphics graphics, int left, int top, boolean small) {
        int w = small ? 8 : 16, h = small ? 6 : 11;
        graphics.fill(left, top, left + w / 2, top + 3, 0xff725421);
        graphics.fill(left, top + 2, left + w, top + h + 2, 0xff725421);
        graphics.fill(left + 1, top + 1, left + w / 2 - 1, top + 3, 0xffffdf88);
        graphics.fill(left + 1, top + 3, left + w - 1, top + h + 1, 0xffd7ae54);
        graphics.fill(left + 1, top + 3, left + w - 1, top + 4, 0xffffdf88);
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

    static int panelText(Tone tone) {
        return tone.neutral() ? panelText(tone.color) : tinted(tone.color, palette.panel().background());
    }

    static int recessText(Tone tone) {
        int background = composite(palette.recess().background(), palette.panel().background());
        return tone.neutral() ? readable(tone.color, background) : tinted(tone.color, background);
    }

    static void panelString(GuiGraphics graphics, Font font, String text, int x, int y, Tone tone) {
        int color = panelText(tone); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void panelString(GuiGraphics graphics, Font font, Component text, int x, int y, Tone tone) {
        int color = panelText(tone); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void panelString(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, Tone tone) {
        int color = panelText(tone); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void recessString(GuiGraphics graphics, Font font, String text, int x, int y, Tone tone) {
        int color = recessText(tone); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    /** Cuts at a glyph boundary and marks the cut, so a shortened label never passes for the whole name. */
    static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    static int bannerHeight(Font font, Component text, int width) {
        return font.split(text, width - 18).size() * font.lineHeight + 12;
    }

    /** A raised message panel with a stripe in the tone's color; bannerHeight gives its size for anchoring. */
    static void banner(GuiGraphics graphics, Font font, Component text, Tone tone, int left, int top, int width) {
        var lines = font.split(text, width - 18);
        int height = lines.size() * font.lineHeight + 12;
        panel(graphics, left, top, width, height);
        graphics.fill(left + 4, top + 4, left + 6, top + height - 4, panelText(tone));
        for (int i = 0; i < lines.size(); i++) panelString(graphics, font, lines.get(i), left + 10, top + 6 + i * font.lineHeight, tone);
    }

    static void panelString(GuiGraphics graphics, Font font, String text, int x, int y, int preferred) {
        int color = panelText(preferred); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void panelString(GuiGraphics graphics, Font font, Component text, int x, int y, int preferred) {
        int color = panelText(preferred); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void panelString(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int preferred) {
        int color = panelText(preferred); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void recessString(GuiGraphics graphics, Font font, String text, int x, int y, int preferred) {
        recessString(graphics, font, text, x, y, preferred, 0);
    }

    static void recessString(GuiGraphics graphics, Font font, String text, int x, int y, int preferred, int overlay) {
        int color = recessText(preferred, overlay); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    static void recessString(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int preferred) {
        int color = recessText(preferred); graphics.drawString(font, text, x, y, color, shadow(color));
    }

    /**
     * Like vanilla, only light text gets a drop shadow. When contrast forces dark text, the shadow is a
     * near-black copy one pixel down-right and reads as a doubled glyph.
     */
    static boolean shadow(int text) {
        return luminance(text) > 0.18;
    }

    private static int readable(int preferred, int background) {
        double darkest = luminance(composite(background, 0xff000000));
        double lightest = luminance(composite(background, 0xffffffff));
        if (contrast(preferred, darkest, lightest) >= 4.5) return preferred;
        return contrast(0xff101010, darkest, lightest) > contrast(0xfff5f5f5, darkest, lightest) ? 0xff101010 : 0xfff5f5f5;
    }

    /**
     * Like readable(), but first slides the color toward black or white, so a green or red status that is too
     * light for the panel stays green or red instead of collapsing to the neutral fallback.
     */
    private static int tinted(int preferred, int background) {
        double darkest = luminance(composite(background, 0xff000000));
        double lightest = luminance(composite(background, 0xffffffff));
        if (contrast(preferred, darkest, lightest) >= 4.5) return preferred;
        int target = contrast(0xff101010, darkest, lightest) > contrast(0xfff5f5f5, darkest, lightest) ? 0xff000000 : 0xffffffff;
        for (int step = 1; step < 10; step++) {
            int mixed = mix(preferred, target, step / 10.0);
            if (contrast(mixed, darkest, lightest) >= 4.5) return mixed;
        }
        return readable(preferred, background);
    }

    private static int mix(int from, int to, double amount) {
        int result = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = from >>> shift & 255, b = to >>> shift & 255;
            result |= (int) Math.round(a + (b - a) * amount) << shift;
        }
        return result;
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
