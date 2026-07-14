package me.simplicitee.project.addons.ability.chi;

import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import me.simplicitee.project.addons.ProjectAddons;

public class Camouflage extends ChiAbility implements PassiveAbility {

    public Camouflage(Player player) {
        super(player);

        start();
    }

    @Override
    public void progress() {
        if (!player.isSneaking()) {
            return;
        }

        if (player.getLocation().getBlock().getType() == Material.TALL_GRASS && player.getEyeLocation().getBlock().getType() == Material.TALL_GRASS) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 10, 10, true, false));
        }
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public String getName() {
        return "Camouflage";
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
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
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Passives.Chi.Camouflage.Enabled");
    }

    @Override
    public String getDescription() {
        return "Sneak while in tall grass to camouflage yourself!";
    }
}
