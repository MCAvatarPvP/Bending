package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ParticleUtil;

public abstract class SandAbility extends EarthAbility implements SubAbility {

    public SandAbility(final Player player) {
        super(player);
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return EarthAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.SAND;
    }

    /** Fine geometric grains used for readable sand silhouettes instead of block-particle clouds. */
    protected void displayFineSand(final Location location, final int amount,
                                   final double offsetX, final double offsetY,
                                   final double offsetZ, final double speed,
                                   final boolean red) {
        if (location == null || location.getWorld() == null || amount <= 0) return;
        final Color color = red ? Color.fromRGB(184, 85, 48) : Color.fromRGB(218, 185, 112);
        ParticleUtil.spawn(Particle.DUST, location, amount, offsetX, offsetY, offsetZ,
                speed, new Particle.DustOptions(color, 0.82F));
    }

    /** Adds a small textured burst for launches, impacts, and source selection. */
    protected void displaySandBurst(final Location location, final int amount,
                                    final double offsetX, final double offsetY,
                                    final double offsetZ, final double speed,
                                    final boolean red) {
        if (location == null || location.getWorld() == null || amount <= 0) return;
        displayFineSand(location, amount, offsetX, offsetY, offsetZ, speed, red);
        final Material sand = red ? Material.RED_SAND : Material.SAND;
        ParticleUtil.spawn(Particle.FALLING_DUST, location, Math.max(1, amount / 2),
                offsetX, offsetY, offsetZ, speed, sand.createBlockData());
    }

    protected void playSandSound(final Location location, final Sound sound,
                                 final float volume, final float pitch) {
        if (location == null || location.getWorld() == null || sound == null
                || !getConfig().getBoolean("Properties.Earth.PlaySound")) return;
        location.getWorld().playSound(location, sound, SoundCategory.MASTER, volume, pitch);
    }
}
