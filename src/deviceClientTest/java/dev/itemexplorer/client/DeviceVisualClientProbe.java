package dev.itemexplorer.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.ModContent;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Uses Minecraft's baked models and registered BERs, without loading any user world. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class DeviceVisualClientProbe {
    private static final String[] SCENES = {"device-states", "port-six-directions"};
    private static Path root;
    private static boolean initialized, done, captured;
    private static long started;
    private static int scene, frames;
    private static PreviewScreen preview;

    private DeviceVisualClientProbe() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (System.getProperty("itemexplorer.deviceClientProbe") == null || done || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        try {
            if (!initialized) {
                initialized = true; started = System.currentTimeMillis();
                root = Path.of(System.getProperty("itemexplorer.deviceClientProbe")).toAbsolutePath().normalize();
                check(root.getFileName().toString().equals("device-visual-review")
                        && root.getParent().getFileName().toString().equals("work"), "Unexpected probe directory");
                check(root.toRealPath().equals(client.gameDirectory.toPath().toRealPath()), "Client directory differs from probe root");
                Files.writeString(root.resolve("report.txt"), "Device model and indicator real-render acceptance\n", StandardCharsets.UTF_8);
                client.options.pauseOnLostFocus = false;
                GLFW.glfwSetWindowAttrib(client.getWindow().getWindow(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
                GLFW.glfwHideWindow(client.getWindow().getWindow());
            }
            check(System.currentTimeMillis() - started < 180_000, "Client probe timed out");
            if (client.getOverlay() != null) return;
            if (preview == null) {
                if (!(client.screen instanceof TitleScreen)) return;
                verifyResources(client);
                show(client);
            } else if (captured) {
                scene++;
                if (scene == SCENES.length) {
                    log("PASS: 30 production state/orientation combinations, 16 station state/orientation combinations; two real-render screenshots.");
                    Files.writeString(root.resolve("result.txt"), "PASS\n", StandardCharsets.UTF_8);
                    done = true; client.stop();
                } else show(client);
            }
        } catch (Throwable failure) { fail(client, failure); }
    }

    private static void show(Minecraft client) {
        captured = false; frames = 0;
        preview = new PreviewScreen(scene);
        client.setScreen(preview);
    }

    private static void verifyResources(Minecraft client) {
        for (Direction facing : Direction.values()) for (ProductionPortBlock.Indicator indicator : ProductionPortBlock.Indicator.values()) {
            BlockState state = ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                    .setValue(ProductionPortBlock.FACING, facing).setValue(ProductionPortBlock.STATUS, indicator);
            checkModel(client, client.getBlockRenderer().getBlockModel(state), state, state.toString());
            check(client.getBlockEntityRenderDispatcher().getRenderer(new ProductionPortBlockEntity(BlockPos.ZERO, state)) != null,
                    "Missing production port renderer");
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) for (boolean formed : new boolean[]{false, true})
            for (boolean networked : new boolean[]{false, true}) {
                BlockState state = ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                        .setValue(BaseStationControllerBlock.FACING, facing).setValue(BaseStationControllerBlock.FORMED, formed)
                        .setValue(BaseStationControllerBlock.NETWORKED, networked);
                checkModel(client, client.getBlockRenderer().getBlockModel(state), state, state.toString());
                check(client.getBlockEntityRenderDispatcher().getRenderer(new BaseStationBlockEntity(BlockPos.ZERO, state)) != null,
                        "Missing base station renderer");
            }
        for (ResourceLocation location : List.of(ProductionPortRenderer.INDICATOR, BaseStationRenderer.NETWORK_LED)) {
            checkModel(client, client.getModelManager().getModel(location), null, location.toString());
        }
    }

    private static void checkModel(Minecraft client, BakedModel model, BlockState state, String label) {
        check(model != client.getModelManager().getMissingModel(), "Missing baked model: " + label);
        List<Direction> faces = new ArrayList<>(List.of(Direction.values())); faces.add(null);
        int quads = 0;
        for (Direction face : faces) for (var quad : model.getQuads(state, face, RandomSource.create(0))) {
            check(!quad.getSprite().contents().name().equals(MissingTextureAtlasSprite.getLocation()), "Missing texture: " + label);
            quads++;
        }
        check(quads > 0, "Empty baked model: " + label);
    }

    private static final class PreviewScreen extends Screen {
        private final int scene;
        PreviewScreen(int scene) { super(Component.literal("Device appearance acceptance")); this.scene = scene; }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xff17212b);
            graphics.drawString(font, "ITEM EXPLORER / DEVICE INDICATORS", 18, 15, 0xffecf0f3, false);
            graphics.drawString(font, "Minecraft baked models + registered block entity renderers", 18, 29, 0xff9aacbc, false);
            if (scene == 0) {
                var states = ProductionPortBlock.Indicator.values();
                int cardWidth = (width - 36) / states.length;
                for (int i = 0; i < states.length; i++) {
                    int x = 18 + i * cardWidth;
                    card(graphics, x, 54, cardWidth - 5, 153, states[i].name());
                    port(graphics, Direction.NORTH, states[i], x + (cardWidth - 5) / 2, 129, 104, partialTick);
                }
                String[] labels = {"INCOMPLETE", "FORMED / WIRELESS OFF", "FORMED / WIRELESS ON"};
                int stationWidth = (width - 36) / labels.length;
                for (int i = 0; i < labels.length; i++) {
                    int x = 18 + i * stationWidth;
                    card(graphics, x, 222, stationWidth - 5, 151, labels[i]);
                    BlockState state = ModContent.BASE_STATION_CONTROLLER_BLOCK.get().defaultBlockState()
                            .setValue(BaseStationControllerBlock.FORMED, i > 0).setValue(BaseStationControllerBlock.NETWORKED, i == 2);
                    model(graphics, new BaseStationBlockEntity(BlockPos.ZERO, state), Direction.NORTH,
                            x + (stationWidth - 5) / 2, 300, 67, partialTick);
                }
            } else {
                Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN};
                int cardWidth = (width - 36) / 3;
                for (int i = 0; i < directions.length; i++) {
                    int x = 18 + i % 3 * cardWidth, y = 54 + i / 3 * 165;
                    card(graphics, x, y, cardWidth - 5, 155, directions[i].name() + " / RUNNING");
                    port(graphics, directions[i], ProductionPortBlock.Indicator.RUNNING,
                            x + (cardWidth - 5) / 2, y + 76, 118, partialTick);
                }
            }
            graphics.drawString(font, "Fixture states only; no world loaded. Check light alignment and socket clearance.", 18, height - 15, 0xff9aacbc, false);
        }

        private void card(GuiGraphics graphics, int x, int y, int w, int h, String label) {
            graphics.fill(x, y, x + w, y + h, 0xff25323f);
            graphics.drawCenteredString(font, label, x + w / 2, y + h - 17, 0xffd8e3e9);
        }

        private void port(GuiGraphics graphics, Direction facing, ProductionPortBlock.Indicator indicator,
                          int x, int y, float scale, float partialTick) {
            BlockState state = ModContent.PRODUCTION_BLOCK.get().defaultBlockState()
                    .setValue(ProductionPortBlock.FACING, facing).setValue(ProductionPortBlock.STATUS, indicator);
            model(graphics, new ProductionPortBlockEntity(BlockPos.ZERO, state), facing, x, y, scale, partialTick);
        }

        private <T extends BlockEntity> void model(GuiGraphics graphics, T entity, Direction facing,
                                                   int x, int y, float scale, float partialTick) {
            graphics.flush();
            var pose = graphics.pose(); pose.pushPose();
            pose.translate(x, y, 200);
            pose.scale(scale, -scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(22));
            pose.mulPose(Axis.YP.rotationDegrees(145));
            // Aim the camera at each outward face while exercising that face's actual blockstate/BER rotation.
            switch (facing) {
                case EAST -> pose.mulPose(Axis.YP.rotationDegrees(90));
                case SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(180));
                case WEST -> pose.mulPose(Axis.YP.rotationDegrees(270));
                case UP -> pose.mulPose(Axis.XP.rotationDegrees(-90));
                case DOWN -> pose.mulPose(Axis.XP.rotationDegrees(90));
                default -> { }
            }
            pose.translate(-.5, -.5, -.5);
            RenderSystem.enableDepthTest(); Lighting.setupFor3DItems();
            var buffers = graphics.bufferSource();
            minecraft.getBlockRenderer().renderSingleBlock(entity.getBlockState(), pose, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            var renderer = minecraft.getBlockEntityRenderDispatcher().getRenderer(entity);
            check(renderer != null, "Missing renderer during preview");
            renderer.render(entity, partialTick, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            buffers.endBatch(); pose.popPose();
            Lighting.setupForFlatItems(); RenderSystem.disableDepthTest();
        }
    }

    @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event) {
        if (System.getProperty("itemexplorer.deviceClientProbe") == null || done || captured
                || preview == null || event.getScreen() != preview || ++frames < 8) return;
        Minecraft client = Minecraft.getInstance();
        try {
            event.getGuiGraphics().flush();
            try (NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                image.writeToFile(root.resolve(SCENES[scene] + ".png"));
            }
            log("CAPTURE " + SCENES[scene]); captured = true;
        } catch (Throwable failure) { fail(client, failure); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void log(String message) throws Exception {
        Files.writeString(root.resolve("report.txt"), message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void fail(Minecraft client, Throwable failure) {
        done = true;
        try {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            log("FAIL\n" + trace);
            Files.writeString(root.resolve("result.txt"), "FAIL\n", StandardCharsets.UTF_8);
        } catch (Exception ignored) { failure.printStackTrace(); }
        client.stop();
    }
}
