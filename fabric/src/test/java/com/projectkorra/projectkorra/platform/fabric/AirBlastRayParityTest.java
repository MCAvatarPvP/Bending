package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Numeric regression for the shared 0.2-block ray used by staged AirBlast. */
class AirBlastRayParityTest {
    @Test
    void commonAirBlastRayUsesTheSameUnitVectorAsPaper() {
        // Reproduces a source-then-turn shot without relying on a live world.
        // The half degree offset exercises a vector whose raw trig length is
        // not exactly one in double precision.
        final float yaw = 180.5F;
        final float pitch = 17.0F;
        final Vec3d paper = normalizedBukkitDirection(yaw, pitch);
        final Vec3d common = commonDirection(yaw, pitch);

        assertEquals(1.0, common.length(), 4.0E-16);
        assertEquals(paper.x, common.x, 4.0E-16);
        assertEquals(paper.y, common.y, 4.0E-16);
        assertEquals(paper.z, common.z, 4.0E-16);
    }

    @Test
    void everyPointInAirBlastTargetMarchMatchesPaperNumerically() {
        final Vec3d eye = new Vec3d(17.3478125, 73.62, -9.73125);
        final Vec3d paper = normalizedBukkitDirection(180.5F, 17.0F);
        final Vec3d common = commonDirection(180.5F, 17.0F);

        for (double distance = 0.0; distance <= 32.0; distance += 0.2) {
            final Vec3d paperSample = eye.add(paper.multiply(distance));
            final Vec3d commonSample = eye.add(common.multiply(distance));
            assertEquals(paperSample.x, commonSample.x, 1.0E-14, "x sample at " + distance);
            assertEquals(paperSample.y, commonSample.y, 1.0E-14, "y sample at " + distance);
            assertEquals(paperSample.z, commonSample.z, 1.0E-14, "z sample at " + distance);
            assertEquals(BlockPos.ofFloored(paperSample), BlockPos.ofFloored(commonSample),
                    "selected block at " + distance);
        }
    }

    private static Vec3d commonDirection(final float yaw, final float pitch) {
        final Location location = new Location(null, 0.0, 0.0, 0.0);
        location.setYaw(yaw);
        location.setPitch(pitch);
        final Vector direction = location.getDirection().normalize();
        return new Vec3d(direction.getX(), direction.getY(), direction.getZ());
    }

    private static Vec3d normalizedBukkitDirection(final float yaw, final float pitch) {
        final double yawRadians = Math.toRadians(yaw);
        final double pitchRadians = Math.toRadians(pitch);
        final double horizontal = Math.cos(pitchRadians);
        return new Vec3d(-horizontal * Math.sin(yawRadians), -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)).normalize();
    }
}
