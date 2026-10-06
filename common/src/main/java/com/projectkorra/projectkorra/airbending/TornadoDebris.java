package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** A fixed pool of purely visual glass fragments owned by one deployed tornado. */
final class TornadoDebris {
    private static final double TWO_PI = Math.PI * 2.0;
    private final int count;
    private final double minimumScale;
    private final double maximumScale;
    private final double orbitSpeed;
    private final List<Shard> shards;
    private boolean initialized;
    private boolean removed;

    TornadoDebris(final int count, final double minScale, final double maxScale,
                  final double orbitSpeed) {
        this.count = Math.max(0, Math.min(32, count));
        this.minimumScale = bounded(minScale, 0.08, 2.0, 0.2);
        this.maximumScale = bounded(maxScale, this.minimumScale, 2.0, this.minimumScale);
        this.orbitSpeed = bounded(orbitSpeed, 0.01, 0.6, 0.16);
        this.shards = new ArrayList<>(this.count);
    }

    /** Creates once, then only moves the same displays. Calling remove is terminal. */
    void update(final Location base, final double height, final double radius, final long tick) {
        if (this.removed || this.count == 0 || base == null || base.getWorld() == null) return;
        final double safeHeight = bounded(height, 0.4, 64.0, 0.4);
        final double safeRadius = bounded(radius, 0.25, 32.0, 0.25);
        final double sizeFactor = bounded(safeRadius / 7.0, 0.25, 1.0, 0.25);
        try {
            if (!this.initialized) this.create(base);
            for (final Shard shard : this.shards) {
                if (!shard.display.isValid()) continue;
                final double time = Math.max(0L, tick);
                final double angle = shard.phase + time * this.orbitSpeed * shard.orbitFactor;
                final double verticalPhase = shard.verticalPhase
                        + time * this.orbitSpeed * shard.verticalFactor;
                final double lift = 0.5 + 0.5 * Math.sin(verticalPhase);
                final double funnelRadius = 0.16 + Math.pow(lift, 0.72) * 0.74;
                final double orbit = safeRadius * funnelRadius * shard.radiusFactor;
                final Location location = base.clone().add(
                        Math.cos(angle) * orbit,
                        0.12 + lift * safeHeight * 0.86,
                        Math.sin(angle) * orbit * 0.84);
                location.setYaw(0.0F);
                location.setPitch(0.0F);
                shard.display.teleport(location);
                shard.display.setTransformation(this.transformation(shard, time, sizeFactor));
            }
        } catch (final RuntimeException | Error failure) {
            // Also cover an update failure after creation, so no orphan render entities survive.
            try {
                this.remove();
            } catch (final RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void create(final Location base) {
        boolean complete = false;
        try {
            for (int index = 0; index < this.count; index++) {
                final BlockDisplay display = base.getWorld().spawn(base, BlockDisplay.class);
                // Track immediately: any following setter can fail after the entity was spawned.
                final Shard shard = new Shard(display, index, this.minimumScale, this.maximumScale);
                this.shards.add(shard);
                display.setPersistent(false);
                display.setInvulnerable(true);
                display.setGravity(false);
                display.setSilent(true);
                display.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
                display.setBillboard(Display.Billboard.FIXED);
                display.setBrightness(new Display.Brightness(15, 15));
                display.setShadowRadius(0.0F);
                display.setShadowStrength(0.0F);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(2);
                display.setTeleportDuration(2);
                display.setViewRange(32.0F);
            }
            this.initialized = true;
            complete = true;
        } finally {
            if (!complete) this.remove();
        }
    }

    private Transformation transformation(final Shard shard, final double tick,
                                          final double sizeFactor) {
        final Quaternionf rotation = new Quaternionf()
                .rotateX((float) ((shard.phase + tick * shard.angularX) % TWO_PI))
                .rotateY((float) ((shard.verticalPhase + tick * shard.angularY) % TWO_PI))
                .rotateZ((float) ((shard.phase * 0.7 + tick * shard.angularZ) % TWO_PI));
        final Vector3f scale = new Vector3f(
                (float) bounded(shard.scaleX * sizeFactor, 0.08, 2.0, 0.08),
                (float) bounded(shard.scaleY * sizeFactor, 0.08, 2.0, 0.08),
                (float) bounded(shard.scaleZ * sizeFactor, 0.08, 2.0, 0.08));
        final Vector3f translation = new Vector3f(scale).mul(0.5F);
        rotation.transform(translation);
        translation.negate();
        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    /** Attempts every removal even if the platform rejects an individual display operation. */
    void remove() {
        this.removed = true;
        RuntimeException firstFailure = null;
        try {
            for (final Shard shard : this.shards) {
                try {
                    shard.display.remove();
                } catch (final RuntimeException failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
        } finally {
            this.shards.clear();
        }
        if (firstFailure != null) throw firstFailure;
    }

    private static double bounded(final double value, final double minimum,
                                  final double maximum, final double fallback) {
        return Math.max(minimum, Math.min(maximum, Double.isFinite(value) ? value : fallback));
    }

    private static double sample(final int index, final int salt) {
        long value = index * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static final class Shard {
        private final BlockDisplay display;
        private final double phase;
        private final double verticalPhase;
        private final double orbitFactor;
        private final double verticalFactor;
        private final double radiusFactor;
        private final double angularX;
        private final double angularY;
        private final double angularZ;
        private final double scaleX;
        private final double scaleY;
        private final double scaleZ;

        private Shard(final BlockDisplay display, final int index,
                      final double minimumScale, final double maximumScale) {
            this.display = display;
            this.phase = sample(index, 1) * TWO_PI;
            this.verticalPhase = sample(index, 2) * TWO_PI;
            this.orbitFactor = 0.72 + sample(index, 3) * 0.56;
            this.verticalFactor = 0.18 + sample(index, 4) * 0.22;
            this.radiusFactor = 0.62 + sample(index, 5) * 0.3;
            this.angularX = 0.04 + sample(index, 6) * 0.1;
            this.angularY = (sample(index, 7) - 0.5) * 0.16;
            this.angularZ = (sample(index, 8) - 0.5) * 0.14;
            final double scale = minimumScale + sample(index, 9) * (maximumScale - minimumScale);
            this.scaleX = scale * (0.75 + sample(index, 10) * 0.55);
            this.scaleY = scale * (0.35 + sample(index, 11) * 0.4);
            this.scaleZ = scale * (0.65 + sample(index, 12) * 0.5);
        }
    }
}
