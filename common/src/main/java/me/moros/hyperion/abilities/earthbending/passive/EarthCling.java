/*
 *   Copyright 2016, 2017, 2020 Moros <https://github.com/PrimordialMoros>
 *
 * 	  This file is part of Hyperion.
 *
 *    Hyperion is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Hyperion is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with Hyperion.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.moros.hyperion.abilities.earthbending.passive;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleEffect;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.abilities.earthbending.EarthGlove;
import me.moros.hyperion.configuration.ConfigManager;
import me.moros.hyperion.methods.CoreMethods;

public class EarthCling extends EarthAbility implements AddonAbility, PassiveAbility {
    @Attribute(Attribute.SPEED)
    private double speed;
    private boolean slide;
    private double slideSpeed;
    private long cooldown;

    public EarthCling(Player player) {
        super(player);

        if (!isEnabled()) return;
        speed = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.EarthGlove.ClingPassive.Speed");
        slide = ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Earth.EarthGlove.ClingPassive.Slide");
        slideSpeed = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.EarthGlove.ClingPassive.SlideSpeed");
        cooldown = ConfigManager.getConfig(bPlayer).getLong("Abilities.Earth.EarthGlove.ClingPassive.Cooldown");
    }

    @Override
    public void progress() {
        if (bPlayer.getBoundAbility() == null || !bPlayer.getBoundAbility().getName().equalsIgnoreCase("EarthGlove")) {
            return;
        }

        if (!player.isSneaking() || GeneralMethods.isOnGround(player) || bPlayer.isOnCooldown(this)) {
            return;
        }

        int counter = 2;
        if (bPlayer.isOnCooldown(EarthGlove.getCooldownForSide(EarthGlove.Side.LEFT))) counter--;
        if (bPlayer.isOnCooldown(EarthGlove.getCooldownForSide(EarthGlove.Side.RIGHT))) counter--;
        if (counter > 0) {
            if (CoreMethods.isAgainstWall(player, true)) {
                if (counter == 2) {
                    Vector vel = new Vector();
                    if (slide) {
                        vel.add(player.getLocation().getDirection().multiply(slideSpeed));
                        BlockData bd = CoreMethods.getWallBlockData(player, true);
                        ParticleEffect.BLOCK_CRACK.display(player.getLocation(), 4, 0.3, 0, 0.3, 0, bd);
                        bPlayer.addCooldown(this);
                    }
                    player.setVelocity(vel);
                } else {
                    Vector vel = player.getVelocity().clone();
                    if (vel.getY() < 0) {
                        player.setVelocity(vel.multiply(speed));
                        ParticleEffect.CRIT.display(player.getEyeLocation(), 2, 0.05F, 0.4F, 0.05F, 0.1F);
                        ParticleEffect.BLOCK_CRACK.display(player.getEyeLocation(), 3, 0.1F, 0.4F, 0.1F, 0.1F, Material.STONE.createBlockData());
                    }
                }
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Earth.EarthGlove.ClingPassive.Enabled");
    }

    @Override
    public String getName() {
        return "EarthCling";
    }

    @Override
    public String getDescription() {
        return ConfigManager.getConfig(bPlayer).getString("Abilities.Earth.EarthGlove.ClingPassive.Description");
    }

    @Override
    public String getAuthor() {
        return Hyperion.getAuthor();
    }

    @Override
    public String getVersion() {
        return Hyperion.getVersion();
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public boolean isInstantiable() {
        return true;
    }

    @Override
    public boolean isProgressable() {
        return true;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }
}
