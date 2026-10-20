package dev.itemexplorer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

/** Sparse, state-driven effects. Steady states stay quiet; only faults, formation and chunk loading are visible. */
public final class DeviceParticles {
    /** Matches the data cable and status-light teal. */
    private static final DustParticleOptions LINK_DUST = new DustParticleOptions(new Vector3f(0.36F, 0.78F, 0.80F), 0.6F);
    private static final int FORMED_RING_POINTS = 16;

    private DeviceParticles() {}

    /**
     * Client-side, from animateTick: a spark on the fault face, sometimes with a wisp of smoke.
     * {@code depth} is the face's offset from the block center along {@code face}.
     */
    public static void fault(Level level, BlockPos pos, Direction face, double depth, double spread, RandomSource random) {
        double x = pos.getX() + .5 + face.getStepX() * depth + inPlane(face, Direction.Axis.X, spread, random);
        double y = pos.getY() + .5 + face.getStepY() * depth + inPlane(face, Direction.Axis.Y, spread, random);
        double z = pos.getZ() + .5 + face.getStepZ() * depth + inPlane(face, Direction.Axis.Z, spread, random);
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, face.getStepX() * .04 + random.triangle(0, .04),
                face.getStepY() * .04 + random.triangle(.02, .03), face.getStepZ() * .04 + random.triangle(0, .04));
        if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, x, y, z, 0, .02, 0);
    }

    /** Server-side, once per genuine assembly: a ring of light spreading from the mast cap. */
    public static void stationFormed(ServerLevel level, BlockPos cap) {
        double x = cap.getX() + .5, y = cap.getY() + 1.05, z = cap.getZ() + .5;
        for (int i = 0; i < FORMED_RING_POINTS; i++) {
            float angle = i * Mth.TWO_PI / FORMED_RING_POINTS;
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 0, Mth.cos(angle), .05, Mth.sin(angle), .12);
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y - .3, z, 6, .1, .2, .1, .05);
    }

    /** Server-side, periodically while chunk loading holds: a faint glow on the module's outward face. */
    public static void chunkLoaderPulse(ServerLevel level, BlockPos module, Direction outward) {
        double x = module.getX() + .5 + outward.getStepX() * .56;
        double z = module.getZ() + .5 + outward.getStepZ() * .56;
        double spreadX = outward.getAxis() == Direction.Axis.X ? 0 : .2;
        double spreadZ = outward.getAxis() == Direction.Axis.Z ? 0 : .2;
        level.sendParticles(LINK_DUST, x, module.getY() + .5, z, 2, spreadX, .2, spreadZ, 0);
    }

    private static double inPlane(Direction face, Direction.Axis axis, double spread, RandomSource random) {
        return face.getAxis() == axis ? 0 : (random.nextDouble() - .5) * 2 * spread;
    }
}
