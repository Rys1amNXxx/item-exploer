package dev.itemexplorer.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

record GuiPalette(GuiPalette.Surface panel, GuiPalette.Surface recess) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CONTAINER = new ResourceLocation("minecraft", "textures/gui/container/crafting_table.png");
    private static final int MAX_TEXTURE_SIDE = 4096;
    static final GuiPalette DEFAULT = new GuiPalette(
            new Surface(0xffc6c6c6,
                    new int[]{0xff000000, 0xffffffff, 0xffffffff, 0xffc6c6c6},
                    new int[]{0xff000000, 0xff555555, 0xff555555, 0xffc6c6c6},
                    new int[]{0xff000000, 0xffffffff, 0xffffffff, 0xffc6c6c6},
                    new int[]{0xff000000, 0xff555555, 0xff555555, 0xffc6c6c6}),
            new Surface(0xff8b8b8b, new int[]{0xff373737}, new int[]{0xffffffff},
                    new int[]{0xff373737}, new int[]{0xffffffff}));

    static GuiPalette load(ResourceManager resources) {
        try {
            var resource = resources.getResource(CONTAINER).orElseThrow(() -> new IOException("Missing crafting table texture"));
            try (var input = new DataInputStream(new BufferedInputStream(resource.open()))) {
                input.mark(24);
                if (input.readLong() != 0x89504e470d0a1a0aL || input.readInt() != 13 || input.readInt() != 0x49484452)
                    throw new IOException("Invalid GUI PNG header");
                int width = input.readInt(), height = input.readInt();
                if (width < 256 || height < 256 || width > MAX_TEXTURE_SIDE || height > MAX_TEXTURE_SIDE
                        || width % 256 != 0 || height % 256 != 0)
                    throw new IOException("Unsupported GUI atlas dimensions " + width + "x" + height);
                input.reset();
                try (NativeImage image = NativeImage.read(input)) {
                    Histogram histogram = new Histogram(image);
                    histogram.add(5, 5, 23, 74);
                    histogram.add(84, 5, 60, 24);
                    histogram.add(84, 56, 84, 24);
                    int panelBackground = histogram.color();
                    int[] panelTop = new int[4], panelBottom = new int[4];
                    int[] panelLeft = new int[4], panelRight = new int[4];
                    for (int depth = 0; depth < 4; depth++) {
                        histogram.add(8, depth, 160, 1);
                        panelTop[depth] = histogram.color();
                        histogram.add(8, 165 - depth, 160, 1);
                        panelBottom[depth] = histogram.color();
                        histogram.add(depth, 8, 1, 150);
                        panelLeft[depth] = histogram.color();
                        histogram.add(175 - depth, 8, 1, 150);
                        panelRight[depth] = histogram.color();
                    }
                    Surface panel = new Surface(panelBackground, panelTop, panelBottom, panelLeft, panelRight);
                    addCraftingSlots(histogram, 3, 3, 12, 12);
                    int recessBackground = histogram.color();
                    addCraftingSlots(histogram, 1, 0, 16, 1);
                    int recessTop = histogram.color();
                    addCraftingSlots(histogram, 1, 17, 16, 1);
                    int recessBottom = histogram.color();
                    addCraftingSlots(histogram, 0, 1, 1, 16);
                    int recessLeft = histogram.color();
                    addCraftingSlots(histogram, 17, 1, 1, 16);
                    int recessRight = histogram.color();
                    return new GuiPalette(panel, new Surface(recessBackground,
                            new int[]{recessTop}, new int[]{recessBottom}, new int[]{recessLeft}, new int[]{recessRight}));
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Unable to read vanilla GUI palette; using default colors: {}", failure.toString());
            return DEFAULT;
        }
    }

    private static void addCraftingSlots(Histogram histogram, int offsetX, int offsetY, int width, int height) {
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++)
            histogram.add(29 + column * 18 + offsetX, 16 + row * 18 + offsetY, width, height);
    }

    static final class Surface {
        private final int background;
        private final int[] top, bottom, left, right;

        private Surface(int background, int[] top, int[] bottom, int[] left, int[] right) {
            this.background = background;
            this.top = top.clone();
            this.bottom = bottom.clone();
            this.left = left.clone();
            this.right = right.clone();
        }

        int background() { return background; }
        int depth() { return top.length; }
        int top(int depth) { return top[depth]; }
        int bottom(int depth) { return bottom[depth]; }
        int left(int depth) { return left[depth]; }
        int right(int depth) { return right[depth]; }
    }

    private static final class Histogram {
        private final NativeImage image;
        private final int scaleX, scaleY;
        private final int[] counts = new int[65536];
        private final int[] reds = new int[65536], greens = new int[65536];
        private final int[] blues = new int[65536], alphas = new int[65536];

        private Histogram(NativeImage image) {
            this.image = image;
            scaleX = image.getWidth() / 256;
            scaleY = image.getHeight() / 256;
        }

        private void add(int left, int top, int width, int height) {
            for (int pixelY = top * scaleY; pixelY < (top + height) * scaleY; pixelY++)
                for (int pixelX = left * scaleX; pixelX < (left + width) * scaleX; pixelX++) {
                    int pixel = image.getPixelRGBA(pixelX, pixelY);
                    int alpha = pixel >>> 24;
                    int red = alpha == 0 ? 0 : pixel & 0xff;
                    int green = alpha == 0 ? 0 : pixel >>> 8 & 0xff;
                    int blue = alpha == 0 ? 0 : pixel >>> 16 & 0xff;
                    int bucket = (alpha >>> 4) << 12 | (red >>> 4) << 8 | (green >>> 4) << 4 | blue >>> 4;
                    counts[bucket]++;
                    reds[bucket] += red;
                    greens[bucket] += green;
                    blues[bucket] += blue;
                    alphas[bucket] += alpha;
                }
        }

        private int color() {
            int dominant = 0;
            for (int bucket = 1; bucket < counts.length; bucket++)
                if (counts[bucket] > counts[dominant]) dominant = bucket;
            int count = counts[dominant];
            int color = count == 0 ? 0 : (alphas[dominant] / count) << 24 | (reds[dominant] / count) << 16
                    | (greens[dominant] / count) << 8 | blues[dominant] / count;
            Arrays.fill(counts, 0);
            Arrays.fill(reds, 0);
            Arrays.fill(greens, 0);
            Arrays.fill(blues, 0);
            Arrays.fill(alphas, 0);
            return color;
        }
    }
}
