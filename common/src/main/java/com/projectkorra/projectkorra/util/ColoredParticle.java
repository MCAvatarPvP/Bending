package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle.DustOptions;

public class ColoredParticle {

    private final DustOptions dust;

    public ColoredParticle(final Color color, final float size) {
        this.dust = new DustOptions(color, size);
    }

    public DustOptions getDustOptions() {
        return this.dust;
    }

    public void display(final Location loc, final int amount, final double offsetX, final double offsetY, final double offsetZ) {
        ParticleEffect.REDSTONE.display(loc, amount, offsetX, offsetY, offsetZ, this.dust);
    }
}
