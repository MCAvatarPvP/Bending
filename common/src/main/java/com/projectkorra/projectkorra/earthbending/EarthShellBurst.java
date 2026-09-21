package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.authority.AuthoritativeEffects;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Individual traveling shards with forgiving swept hitboxes and one hit per target. */
final class EarthShellBurst extends BukkitRunnable {
    private static final int LIFETIME = 32;
    private static final int FADE_TICKS = 6;
    private final Player owner;
    private final Location center;
    private final double range;
    private final double hitRadius;
    private final List<Shard> shards = new ArrayList<>();
    private final Set<UUID> affected = new HashSet<>();
    private final BiPredicate<LivingEntity, Location> onHit;
    private int ticks;

    record Piece(Location location, BlockData data) { }

    static void play(final Player owner, final Location center, final List<Piece> pieces, final double range,
                     final double hitRadius, final BiPredicate<LivingEntity, Location> onHit) {
        if (pieces.isEmpty() || range <= 0) return;
        // The server owns both the visible shards and their contacts.
        AuthoritativeEffects.run(() -> {
            final EarthShellBurst burst = new EarthShellBurst(owner, center, pieces, range, hitRadius, onHit);
            burst.runTaskTimer(ProjectKorra.plugin, 1, 1);
        });
    }

    private EarthShellBurst(final Player owner, final Location center, final List<Piece> pieces, final double range,
                            final double hitRadius, final BiPredicate<LivingEntity, Location> onHit) {
        this.owner = owner;
        this.center = center.clone();
        this.range = range;
        this.onHit = onHit;
        this.hitRadius = hitRadius;
        int index = 0;
        for (final Piece piece : pieces) {
            // A short fracture puff at each shell block keeps the launch readable.
            center.getWorld().spawnParticle(Particle.BLOCK, piece.location(), 5,
                    0.28, 0.25, 0.28, 0.065, piece.data());
            center.getWorld().spawnParticle(Particle.FALLING_DUST, piece.location(), 1,
                    0.2, 0.12, 0.2, 0, piece.data());
            final Vector radial = piece.location().toVector().subtract(center.toVector()).setY(0);
            final double angle = radial.lengthSquared() < 0.01 ? index * 2.399963
                    : Math.atan2(radial.getZ(), radial.getX());
            // Fan each block's pieces symmetrically instead of stacking them in narrow lanes.
            for (int fragment = 0; fragment < 4; fragment++) {
                final double direction = angle + (fragment - 1.5) * Math.PI / 15;
                final Vector outward = new Vector(Math.cos(direction), 0, Math.sin(direction));
                final Location start = piece.location().clone().add(outward.clone().multiply(0.12));
                final double speed = Math.min(1.6, range / 15.0) * (fragment % 2 == 0 ? 1.0 : 0.93);
                final Vector motion = outward.clone().multiply(speed).setY(0.24 + (index % 3) * 0.035);
                final BlockDisplay display = center.getWorld().spawn(start, BlockDisplay.class);
                display.setBlock(piece.data());
                display.setPersistent(false);
                display.setInvulnerable(true);
                display.setGravity(false);
                display.setShadowRadius(0);
                display.setShadowStrength(0);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(1);
                display.setTeleportDuration(1);
                final Shard shard = new Shard(display, start, motion, piece.data(),
                        new Vector3f((float) -outward.getZ(), 0.35F, (float) outward.getX()).normalize(),
                        fragment % 2 == 0 ? 0.48F : 0.34F, index++ * 1.7F);
                shard.transform(1);
                shards.add(shard);
            }
        }
    }

    @Override
    public void run() {
        AuthoritativeEffects.run(this::tick);
    }

    private void tick() {
        if (++ticks >= LIFETIME || !owner.isOnline() || !center.getWorld().equals(owner.getWorld())) {
            for (final Shard shard : shards) shard.display.remove();
            shards.clear();
            cancel();
            return;
        }
        // Query once per tick; only the individual shard paths can cause a hit.
        final double extent = range + hitRadius;
        final BoundingBox area = new BoundingBox(center.clone().add(-extent, -8, -extent).toVector(),
                center.clone().add(extent, 8, extent).toVector());
        final var targets = center.getWorld().getNearbyEntities(area, entity ->
                entity instanceof LivingEntity && !entity.isDead()
                        && !entity.getUniqueId().equals(owner.getUniqueId()));
        for (final var iterator = shards.iterator(); iterator.hasNext();) {
            final Shard shard = iterator.next();
            if (!shard.display.isValid() || shard.display.isDead() || shard.fade == 0) {
                shard.display.remove();
                iterator.remove();
                continue;
            }
            if (shard.fade < 0) {
                shard.motion.multiply(0.985).setY(shard.motion.getY() - 0.032);
                // Sweep fast shards in small steps so they stop at walls and ground.
                final int steps = Math.max(1, (int) Math.ceil(shard.motion.length() / 0.2));
                final Vector step = shard.motion.clone().multiply(1.0 / steps);
                for (int i = 0; i < steps; i++) {
                    final Location next = shard.location.clone().add(step);
                    if (!next.getWorld().isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)
                            || next.getY() < next.getWorld().getMinHeight()
                            || Math.hypot(next.getX() - center.getX(), next.getZ() - center.getZ()) >= range) {
                        shard.fade = FADE_TICKS;
                        break;
                    }
                    if (!next.getBlock().isPassable()
                            || !next.clone().subtract(0, shard.scale * 0.35, 0).getBlock().isPassable()) {
                        shard.shatter();
                        break;
                    }
                    final BoundingBox contact = new BoundingBox(new Vector(
                            Math.min(shard.location.getX(), next.getX()) - hitRadius,
                            Math.min(shard.location.getY(), next.getY()) - hitRadius,
                            Math.min(shard.location.getZ(), next.getZ()) - hitRadius), new Vector(
                            Math.max(shard.location.getX(), next.getX()) + hitRadius,
                            Math.max(shard.location.getY(), next.getY()) + hitRadius,
                            Math.max(shard.location.getZ(), next.getZ()) + hitRadius));
                    shard.location.add(step);
                    for (final var entity : targets) {
                        final BoundingBox body = entity.getBoundingBox();
                        if (!body.overlaps(contact)) continue;
                        final Location touch = new Location(next.getWorld(),
                                Math.max(body.getMinX(), Math.min(next.getX(), body.getMaxX())),
                                Math.max(body.getMinY(), Math.min(next.getY(), body.getMaxY())),
                                Math.max(body.getMinZ(), Math.min(next.getZ(), body.getMaxZ())));
                        // Wider entity hitboxes must not reach through nearby walls or past the range cap.
                        if (Math.hypot(touch.getX() - center.getX(), touch.getZ() - center.getZ()) > range
                                || !clearPath(next, touch)) continue;
                        if (affected.contains(entity.getUniqueId()) || onHit.test((LivingEntity) entity, next)) {
                            affected.add(entity.getUniqueId());
                            shard.shatter();
                            break;
                        }
                    }
                    if (shard.fade >= 0) break;
                }
            }
            // Just a few flecks behind the larger pieces during the initial flight.
            if (shard.fade < 0 && shard.scale > 0.4F && ticks <= 12 && ticks % 4 == 0) {
                shard.location.getWorld().spawnParticle(Particle.BLOCK, shard.location, 1,
                        0.04, 0.04, 0.04, 0.01, shard.data);
            }
            if (ticks >= LIFETIME - FADE_TICKS && shard.fade < 0) shard.fade = FADE_TICKS;
            shard.rotation += 0.22F;
            shard.transform(shard.fade < 0 ? 1 : (float) shard.fade-- / FADE_TICKS);
            shard.display.teleport(shard.location);
        }
        if (shards.isEmpty()) cancel();
    }

    private static boolean clearPath(final Location from, final Location to) {
        final Vector direction = to.toVector().subtract(from.toVector());
        final int steps = Math.max(1, (int) Math.ceil(direction.length() / 0.2));
        direction.multiply(1.0 / steps);
        final Location point = from.clone();
        for (int i = 0; i <= steps; i++, point.add(direction)) {
            if (!point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
                    || !point.getBlock().isPassable()) return false;
        }
        return true;
    }

    private static final class Shard {
        private final BlockDisplay display;
        private final Location location;
        private final Vector motion;
        private final BlockData data;
        private final Vector3f axis;
        private final float scale;
        private float rotation;
        private int fade = -1;

        private Shard(final BlockDisplay display, final Location location, final Vector motion, final BlockData data,
                      final Vector3f axis, final float scale, final float rotation) {
            this.display = display;
            this.location = location;
            this.motion = motion;
            this.data = data;
            this.axis = axis;
            this.scale = scale;
            this.rotation = rotation;
        }

        private void shatter() {
            if (fade >= 0) return;
            fade = FADE_TICKS;
            location.getWorld().spawnParticle(Particle.BLOCK, location, 4,
                    0.12, 0.12, 0.12, 0.045, data);
            location.getWorld().spawnParticle(Particle.FALLING_DUST, location, 1,
                    0.1, 0.06, 0.1, 0, data);
        }

        private void transform(final float fadeScale) {
            final Quaternionf spin = new Quaternionf().fromAxisAngleRad(axis, rotation);
            final Vector3f size = new Vector3f(scale * 0.8F, scale * 0.45F, scale * 1.3F).mul(fadeScale);
            final Vector3f translation = spin.transform(new Vector3f(size).mul(-0.5F));
            display.setTransformation(new Transformation(translation, spin, size, new Quaternionf()));
        }
    }
}
