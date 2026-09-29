package dev.itemexplorer.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.block.NasBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraftforge.client.model.data.ModelData;

/** The static shell is chunk-baked; only installed trays and small indicators render here. */
public final class NasRenderer implements BlockEntityRenderer<NasBlockEntity> {
    public static final ResourceLocation TRAY = model("nas_tray"), BADGE = model("nas_badge"),
            FRONT_LED = model("nas_front_led"), TOP_LED = model("nas_top_led");
    private static final int[] TIER_COLORS = {0xA7C96B, 0x69C6CC, 0xB691D2, 0xE4AA61};

    private static ResourceLocation model(String name) { return ResourceLocation.tryBuild(ItemExplorer.MOD_ID, "block/" + name); }
    public NasRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(NasBlockEntity nas, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - nas.getBlockState().getValue(HorizontalDirectionalBlock.FACING).toYRot()));
        pose.translate(-.5, -.5, -.5);
        for (int i = 0; i < NasBlockEntity.BAYS; i++) {
            int color = nas.isVisibleLocked() || nas.hasVisibleDisk(i) && !nas.isVisibleOnline(i) ? 0xF05C4F
                    : nas.isVisibleActive(i) ? 0x78E6FF : 0x83DE67;
            boolean lit = nas.isVisibleLocked() || nas.hasVisibleDisk(i);
            pose.pushPose();
            pose.translate(0, -3.0 * i / 16, 0);
            if (nas.hasVisibleDisk(i)) {
                renderModel(TRAY, pose, buffers, light, overlay, 0xFFFFFF);
                renderModel(BADGE, pose, buffers, light, overlay, TIER_COLORS[nas.visibleTier(i)]);
            }
            if (lit) renderModel(FRONT_LED, pose, buffers, LightTexture.FULL_BRIGHT, overlay, color);
            pose.popPose();
            if (lit) {
                pose.pushPose();
                pose.translate(3.0 * i / 16, 0, 0);
                renderModel(TOP_LED, pose, buffers, LightTexture.FULL_BRIGHT, overlay, color);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    private static void renderModel(ResourceLocation location, PoseStack pose, MultiBufferSource buffers, int light, int overlay, int color) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.solid()), null,
                minecraft.getModelManager().getModel(location), ((color >> 16) & 255) / 255f,
                ((color >> 8) & 255) / 255f, (color & 255) / 255f, light, overlay, ModelData.EMPTY, RenderType.solid());
    }
}
