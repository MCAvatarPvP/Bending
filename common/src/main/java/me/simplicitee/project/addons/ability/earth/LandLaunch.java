package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import me.simplicitee.project.addons.ProjectAddons;

public class LandLaunch extends EarthAbility implements AddonAbility, PassiveAbility {

    @Attribute("Power")
    private int amp;

    public LandLaunch(Player player) {
        super(player);

        amp = ProjectAddons.instance.getConfig(bPlayer).getInt("Passives.Earth.LandLaunch.Power");
    }

    @Override
    public void progress() {
        if (bPlayer.isElementToggled(Element.EARTH) && GeneralMethods.isOnGround(player) && isEarthbendable(player.getLocation().getBlock().getRelative(BlockFace.DOWN))) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 10, amp));
        }
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public String getName() {
        return "LandLaunch";
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
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return "Simplicitee";
    }

    @Override
    public String getVersion() {
        return ProjectAddons.instance.version();
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Passives.Earth.LandLaunch.Enabled");
    }

    @Override
    public String getInstructions() {
        return "Jump while on earth";
    }

    @Override
    public String getDescription() {
        return "Skilled earthbenders can boost their jumps by bending the earth right below their feet!";
    }
}
