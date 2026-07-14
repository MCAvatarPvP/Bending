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

package me.moros.hyperion.abilities.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.configuration.ConfigManager;

public class Evade extends AirAbility implements AddonAbility {
    private Vector direction;

    private long cooldown;

    private double angleStep;

    private int ticks = 0;

    public Evade(Player player) {
        super(player);

        if (!GeneralMethods.isOnGround(player) || player.getEyeLocation().getBlock().isLiquid() || hasAbility(player, Evade.class) || !bPlayer.canBend(this)) {
            return;
        }

        cooldown = ConfigManager.getConfig(bPlayer).getLong("Abilities.Air.Evade.Cooldown");
        direction = player.getEyeLocation().getDirection().setY(0).normalize().multiply(-0.2);

        angleStep = Math.PI / 10;
        if (player.getEyeLocation().getDirection().getY() >= 0) angleStep = -angleStep;

        player.setVelocity(player.getVelocity().add(new Vector(0, 0.1, 0)));

        player.setNoDamageTicks(5);
        start();
    }

    @Override
    public void progress() {
        if (!bPlayer.canBendIgnoreCooldowns(this) || ticks > 10) {
            remove();
            return;
        }
        for (int i = 0; i < 2; i++) {
            player.setVelocity(player.getVelocity().add(direction.clone()));
            direction.rotateAroundY(angleStep);
            playAirbendingParticles(player.getLocation().add(0, 1, 0), 2, 0, 0, 0);
        }
        ticks++;
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Air.Evade.Enabled");
    }

    @Override
    public String getName() {
        return "Evade";
    }

    @Override
    public String getDescription() {
        return ConfigManager.getConfig(bPlayer).getString("Abilities.Air.Evade.Description");
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
        return true;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void remove() {
        player.setNoDamageTicks(0);
        bPlayer.addCooldown(this);
        super.remove();
    }
}
