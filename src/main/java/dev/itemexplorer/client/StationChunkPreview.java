package dev.itemexplorer.client;

import dev.itemexplorer.ItemExplorer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** A local, temporary coverage guide; drawing boundaries never requests chunks. */
@Mod.EventBusSubscriber(modid = ItemExplorer.MOD_ID, value = Dist.CLIENT)
public final class StationChunkPreview {
    private static ClientLevel previewLevel;
    private static BlockPos controller;
    private static long expiresAt;
    private static int radius;

    private StationChunkPreview() {}

    public static boolean isVisible(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level == previewLevel && controller != null
                && controller.equals(pos) && minecraft.level.getGameTime() < expiresAt;
    }

    public static void toggle(BlockPos pos, int requestedRadius) {
        if (isVisible(pos)) {
            clear();
            return;
        }
        previewLevel = Minecraft.getInstance().level;
        if (previewLevel == null) return;
        controller = pos.immutable();
        radius = Math.max(0, Math.min(1, requestedRadius));
        expiresAt = previewLevel.getGameTime() + 1_200;
    }

    private static void clear() {
        previewLevel = null;
        controller = null;
        expiresAt = 0;
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || controller == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != previewLevel || minecraft.level.getGameTime() >= expiresAt) {
            clear();
            return;
        }
        var camera = event.getCamera().getPosition();
        if (controller.distToCenterSqr(camera) > 256 * 256) return;
        var pose = event.getPoseStack();
        var buffers = minecraft.renderBuffers().bufferSource();
        var vertices = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        int chunkX = controller.getX() >> 4, chunkZ = controller.getZ() >> 4;
        double bottom = Math.max(minecraft.level.getMinBuildHeight(), controller.getY() - 8) + 0.05;
        double top = Math.min(minecraft.level.getMaxBuildHeight(), controller.getY() + 16) + 0.05;
        // Shared edges must end gold, so render the controller chunk after its neighbors.
        for (int pass = 0; pass < 2; pass++) for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                boolean center = x == chunkX && z == chunkZ;
                if (center != (pass == 1)) continue;
                LevelRenderer.renderLineBox(pose, vertices, x * 16, bottom, z * 16,
                        (x + 1) * 16, top, (z + 1) * 16,
                        center ? 1.0F : 0.25F, center ? 0.72F : 0.85F, center ? 0.25F : 0.9F, 0.85F);
                LevelRenderer.renderLineBox(pose, vertices, x * 16, controller.getY() + 0.05, z * 16,
                        (x + 1) * 16, controller.getY() + 0.05, (z + 1) * 16,
                        center ? 1.0F : 0.25F, center ? 0.72F : 0.85F, center ? 0.25F : 0.9F, 0.85F);
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
