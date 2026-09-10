package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.authority.AuthoritativeEffects;
import com.projectkorra.projectkorra.util.DisplayBlockUtils;

import java.util.concurrent.ThreadLocalRandom;

/** Short-lived cosmetic rubble for naturally expired walls and pillars. */
final class RaiseEarthRevertEffects {
    private RaiseEarthRevertEffects() {
    }

    static void play(final Location center, final BlockData data) {
        AuthoritativeEffects.run(() -> {
            final World world = center.getWorld();
            world.spawnParticle(Particle.BLOCK, center, 14, 0.38, 0.36, 0.38, 0.065, data);
            world.spawnParticle(Particle.FALLING_DUST, center, 2, 0.35, 0.25, 0.35, 0.0, data);

            final ThreadLocalRandom random = ThreadLocalRandom.current();
            final double angle = random.nextDouble(Math.PI * 2.0);
            for (int i = 0; i < 2; i++) {
                final double side = angle + i * Math.PI;
                final Vector outward = new Vector(Math.cos(side), 0, Math.sin(side));
                final Location start = center.clone().add(outward.clone().multiply(0.2));
                DisplayBlockUtils.spawnEarthFragment(start, data,
                        outward.clone().multiply(0.065).setY(0.025 + random.nextDouble(0.035)),
                        new Vector(outward.getZ(), 0.4, -outward.getX()).normalize(),
                        (float) random.nextDouble(Math.PI), 0.12F,
                        0.3F + random.nextFloat() * 0.12F, 40);
            }

            Platform.scheduler().runLater(() -> AuthoritativeEffects.run(() ->
                    world.spawnParticle(Particle.FALLING_DUST, center.clone().add(0, -0.3, 0),
                            2, 0.42, 0.18, 0.42, 0.0, data)), 4L);
        });
    }
}
