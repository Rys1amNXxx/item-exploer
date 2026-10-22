package dev.itemexplorer.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.itemexplorer.ItemExplorer;
import dev.itemexplorer.block.ProductionPortBlock;
import dev.itemexplorer.block.ProductionPortBlockEntity;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.model.data.ModelData;

/** Only the small indicator is dynamic. Pulsing never updates the world or sends packets. */
public final class ProductionPortRenderer implements BlockEntityRenderer<ProductionPortBlockEntity> {
    public static final ResourceLocation INDICATOR = new ResourceLocation(ItemExplorer.MOD_ID, "block/production_port_indicator");

    public ProductionPortRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(ProductionPortBlockEntity port, float partialTick, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
        var indicator = port.getBlockState().getValue(ProductionPortBlock.STATUS);
        int color = switch (indicator) {
            case IDLE -> 0x354044;
            case RUNNING -> 0x65DBE5;
            case WAITING -> 0xF0B653;
            case ERROR -> 0xF05C4F;
            case COMPLETE -> 0x83DE67;
        };
        float brightness = 1;
        if (indicator == ProductionPortBlock.Indicator.RUNNING) {
            double time = port.getLevel() == null ? Util.getMillis() / 50.0 : port.getLevel().getGameTime() + partialTick;
            float phase = (float) ((time + Math.floorMod(port.getBlockPos().asLong(), 32)) % 32);
            brightness = 0.65F + 0.35F * Mth.sin(phase * Mth.TWO_PI / 32);
        }
        pose.pushPose();
        pose.translate(.5, .5, .5);
        Direction facing = port.getBlockState().getValue(ProductionPortBlock.FACING);
        if (facing == Direction.UP || facing == Direction.DOWN)
            pose.mulPose(Axis.XP.rotationDegrees(facing == Direction.UP ? 90 : -90));
        else pose.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        pose.translate(-.5, -.5, -.5);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.solid()), null,
                minecraft.getModelManager().getModel(INDICATOR),
                ((color >> 16) & 255) / 255F * brightness, ((color >> 8) & 255) / 255F * brightness,
                (color & 255) / 255F * brightness,
                indicator == ProductionPortBlock.Indicator.IDLE ? light : LightTexture.FULL_BRIGHT,
                overlay, ModelData.EMPTY, RenderType.solid());
        pose.popPose();
    }
}
