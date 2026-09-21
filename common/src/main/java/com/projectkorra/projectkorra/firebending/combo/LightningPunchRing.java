package com.projectkorra.projectkorra.firebending.combo;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.util.ParticleUtil;

/** Explicit frames: electric sparks do not expand like FirePunch's flame particles. */
final class LightningPunchRing extends BukkitRunnable {
    private static final int FRAMES = 8;
    private static final int POINTS = 30;
    private final CoreAbility source;
    private final Location center;
    private final Vector[] directions = new Vector[POINTS];
    private int frame;

    static void play(final CoreAbility source, final LivingEntity target, final Location center) {
        final LightningPunchRing ring = new LightningPunchRing(source, target, center);
        ring.run();
        if (!ring.isCancelled()) ring.runTaskTimer(ProjectKorra.plugin, 1, 1);
    }

    private LightningPunchRing(final CoreAbility source, final LivingEntity target, final Location center) {
        this.source = source;
        this.center = center.clone();
        final Vector facing = target.getEyeLocation().toVector().subtract(source.getPlayer().getEyeLocation().toVector());
        final double yaw = Math.toRadians(-center.clone().setDirection(facing).getYaw());
        final double verticalOffset = center.getY() - target.getEyeLocation().getY();
        // Match the tilt of FirePunch's upper/lower-body impact ring.
        final double tilt = Math.toRadians((verticalOffset > 0.2 ? -106 : verticalOffset < -0.35 ? -136 : -120) + 7);
        for (int point = 0; point < POINTS; point++) {
            final double angle = point * Math.PI * 2 / POINTS;
            directions[point] = new Vector(Math.cos(angle), 0, Math.sin(angle)).rotateAroundX(tilt).rotateAroundY(yaw);
        }
    }

    @Override public void run() {
        if (isCancelled()) return;
        final Player player = source.getPlayer();
        if (!player.isOnline() || !center.getWorld().equals(player.getWorld())) {
            cancel();
            return;
        }
        // Keep rendering after the melee ability is consumed, retaining its prediction context.
        AbilityExecutionContext.run(source, () -> {
            final double radius = 0.15 + frame * 0.17;
            for (final Vector direction : directions) {
                final Location point = center.clone().add(direction.clone().multiply(radius));
                ParticleUtil.spawn(Particle.ELECTRIC_SPARK, point, 1, 0, 0, 0, 0, null);
            }
        });
        if (++frame >= FRAMES) cancel();
    }
}
