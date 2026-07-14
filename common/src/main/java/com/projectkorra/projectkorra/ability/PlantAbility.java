package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ParticleUtil;

public abstract class PlantAbility extends WaterAbility implements SubAbility {

    public PlantAbility(final Player player) {
        super(player);
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return WaterAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.PLANT;
    }

    // Because Plantbending deserves particles too!
    public void playPlantbendingParticles(final Location loc, final int amount, final double xOffset, final double yOffset, final double zOffset) {
        ParticleUtil.spawn(Particle.BLOCK, loc.clone().add(0.5, 0, 0.5), amount, xOffset, yOffset, zOffset, 1.0, Material.OAK_LEAVES.createBlockData());
    }
}
