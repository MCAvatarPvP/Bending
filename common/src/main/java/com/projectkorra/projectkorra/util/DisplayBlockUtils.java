package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

public class DisplayBlockUtils {
    public static void spawnEarthFragment(
            final Location spawnLocation,
            final BlockData blockData,
            final Vector initialVelocity,
            final Vector spinAxis,
            final float initialRotation,
            final float initialAngularVelocity,
            final float scale,
            final int maximumLifetime) {

        if (spawnLocation.getWorld() == null) {
            return;
        }

        final BlockDisplay display = spawnLocation.getWorld().spawn(
                spawnLocation,
                BlockDisplay.class
        );

        display.setBlock(blockData);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);

        display.setShadowRadius(scale * 0.48F);
        display.setShadowStrength(0.85F);

        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);

        display.setTransformation(createCenteredBlockTransform(
                scale,
                initialRotation,
                spinAxis
        ));

        new BukkitRunnable() {

            private static final double GRAVITY = 0.045D;
            private static final double AIR_DRAG = 0.985D;

            /*
             * Prevents the fragment from detecting the original earth
             * construct immediately after spawning.
             */
            private static final int GROUND_CHECK_GRACE_TICKS = 5;

            private final Location current = spawnLocation.clone();
            private final Vector motion = initialVelocity.clone();

            private int ticks;

            private float rotation = initialRotation;
            private float angularVelocity = initialAngularVelocity;

            @Override
            public void run() {
                if (!display.isValid()
                        || display.isDead()
                        || this.current.getWorld() == null) {
                    removeFragment();
                    return;
                }

                this.ticks++;

                /*
                 * Apply light horizontal air resistance.
                 */
                this.motion.setX(this.motion.getX() * AIR_DRAG);
                this.motion.setZ(this.motion.getZ() * AIR_DRAG);

                /*
                 * Apply gravity.
                 */
                this.motion.setY(this.motion.getY() - GRAVITY);

                /*
                 * Move the fragment.
                 */
                this.current.add(this.motion);

                this.rotation += this.angularVelocity;

                /*
                 * Slowly reduce spin while airborne.
                 */
                this.angularVelocity *= 0.992F;

                display.setTransformation(createCenteredBlockTransform(
                        scale,
                        this.rotation,
                        spinAxis
                ));

                display.teleport(this.current);

                /*
                 * Only check for ground after the grace period and while
                 * descending. This prevents nearby walls or the original
                 * construct from removing the display.
                 */
                if (this.ticks > GROUND_CHECK_GRACE_TICKS
                        && this.motion.getY() <= 0.0D
                        && isTouchingGround(this.current, scale)) {
                    removeFragment();
                    return;
                }

                if (this.ticks >= maximumLifetime) {
                    removeFragment();
                }
            }

            private void removeFragment() {
                if (display.isValid()) {
                    display.remove();
                }

                this.cancel();
            }
        }.runTaskTimer(ProjectKorra.plugin, 1L, 1L);
    }

    private static Transformation createCenteredBlockTransform(
            final float scale,
            final float rotation,
            final Vector axis) {

        final Quaternionf quaternion = new Quaternionf()
                .fromAxisAngleRad(
                        (float) axis.getX(),
                        (float) axis.getY(),
                        (float) axis.getZ(),
                        rotation
                );

        /*
         * The untransformed center of a block is 0.5, 0.5, 0.5.
         * Scale it, rotate it, then negate it so that the resulting center
         * remains on the BlockDisplay entity location.
         */
        final Vector3f translation = new Vector3f(
                scale * 0.5F,
                scale * 0.5F,
                scale * 0.5F
        );

        quaternion.transform(translation);
        translation.negate();

        return new Transformation(
                translation,
                quaternion,
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        );
    }

    public static Vector randomHorizontalVector(final Random random) {
        final Vector vector = new Vector(
                random.nextDouble() - 0.5D,
                0.0D,
                random.nextDouble() - 0.5D
        );

        if (vector.lengthSquared() < 0.001D) {
            vector.setX(1.0D);
        }

        return vector.normalize();
    }

    private static void setLocationFromVector(
            final Location location,
            final Vector vector) {

        location.setX(vector.getX());
        location.setY(vector.getY());
        location.setZ(vector.getZ());
    }

    private static boolean isTouchingGround(
            final Location center,
            final float scale) {

        /*
         * The BlockDisplay's entity location represents the center because
         * createCenteredBlockTransform(...) centers the rendered block.
         *
         * Move downward by half the rendered size, plus a small epsilon, to
         * inspect the block directly beneath the fragment.
         */
        final double bottomY = center.getY() - scale * 0.5D - 0.03D;

        return !center.getWorld()
                .getBlockAt(
                        center.getBlockX(),
                        (int) Math.floor(bottomY),
                        center.getBlockZ()
                )
                .isPassable();
    }
}
