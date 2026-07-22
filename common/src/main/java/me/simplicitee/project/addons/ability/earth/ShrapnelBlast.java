package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.MetalAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import me.simplicitee.project.addons.ProjectAddons;

import java.util.Random;

public class ShrapnelBlast extends MetalAbility implements AddonAbility {

    public ShrapnelBlast(Player player) {
        super(player);

        if (bPlayer.isOnCooldown("Shrapnel")) {
            return;
        }

        int shots = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.Shrapnel.Blast.Shots");
        int spread = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.Shrapnel.Blast.Spread");
        long cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.Shrapnel.Blast.Cooldown");
        double damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Shrapnel.Blast.Damage");
        double speed = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Shrapnel.Blast.Speed");
        Random random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());

        for (int i = 0; i < shots; i++) {
            Location loc = player.getLocation().clone();

            int yaw = random.nextInt(spread / 2) - spread / 4;
            loc.setYaw(loc.getYaw() + yaw);

            int pitch = random.nextInt(spread / 2) - spread / 4;
            loc.setPitch(loc.getPitch() + pitch);

            new ShrapnelShot(player, loc.getDirection(), speed, damage);
        }

        bPlayer.addCooldown("Shrapnel", cooldown);
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "ShrapnelBlast";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public void progress() {
    }

    @Override
    public boolean isHiddenAbility() {
        return true;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return null;
    }

    @Override
    public String getVersion() {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.Shrapnel.Enabled");
    }
}
