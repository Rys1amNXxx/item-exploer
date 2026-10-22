package dev.itemexplorer.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.block.BaseStationBlockEntity;
import dev.itemexplorer.block.BaseStationControllerBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.data.ModelData;

/** Only the small wireless indicator is dynamic; the original structure lamp stays chunk-baked. */
public final class BaseStationRenderer implements BlockEntityRenderer<BaseStationBlockEntity> {
    public static final ResourceLocation NETWORK_LED = new ResourceLocation(ItemExplorer.MOD_ID, "block/base_station_network_led");

    public BaseStationRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(BaseStationBlockEntity station, float partialTick, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
        var state = station.getBlockState();
        if (!state.getValue(BaseStationControllerBlock.FORMED) || !state.getValue(BaseStationControllerBlock.NETWORKED)) return;
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - state.getValue(BaseStationControllerBlock.FACING).toYRot()));
        pose.translate(-.5, -.5, -.5);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.solid()), null,
                minecraft.getModelManager().getModel(NETWORK_LED), 0.36F, 0.78F, 0.80F,
                LightTexture.FULL_BRIGHT, overlay, ModelData.EMPTY, RenderType.solid());
        pose.popPose();
    }
}
