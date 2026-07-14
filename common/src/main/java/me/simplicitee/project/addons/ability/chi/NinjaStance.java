package me.simplicitee.project.addons.ability.chi;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.StanceAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.util.ActionBar;
import me.simplicitee.project.addons.ProjectAddons;

import java.util.ArrayList;
import java.util.List;

public class NinjaStance extends ChiAbility implements AddonAbility, StanceAbility {

    @Attribute(Attribute.DURATION)
    private long stealthDuration;

    private boolean stealth, stealthReady, stealthStarted;
    private long stealthStart, stealthChargeTime, stealthReadyStart, stealthCooldown;
    private List<PotionEffect> effects = new ArrayList<>();
    private PotionEffect invis = new PotionEffect(PotionEffectType.INVISIBILITY, 5, 2, true, false);

    public NinjaStance(Player player) {
        super(player);

        if (bPlayer.isOnCooldown(this)) {
            return;
        }

        StanceAbility stance = bPlayer.getStance();
        if (stance instanceof CoreAbility) {
            ((CoreAbility) stance).remove();
            if (stance instanceof NinjaStance) {
                bPlayer.setStance(null);
                return;
            }
        }

        stealthDuration = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Chi.NinjaStance.Stealth.Duration");
        stealthChargeTime = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Chi.NinjaStance.Stealth.ChargeTime");
        stealthCooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Chi.NinjaStance.Stealth.Cooldown");
        effects.add(new PotionEffect(PotionEffectType.SPEED, 5, ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Chi.NinjaStance.SpeedAmplifier") + 1, true, false));
        effects.add(new PotionEffect(PotionEffectType.JUMP_BOOST, 5, ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Chi.NinjaStance.JumpAmplifier") + 1, true, false));

        start();
        bPlayer.setStance(this);
        GeneralMethods.displayMovePreview(player);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, 0.2F, 2F);
    }

    public static double getDamageModifier(BendingPlayer bPlayer) {
        return ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Chi.NinjaStance.DamageModifier");
    }

    @Override
    public long getCooldown() {
        return ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Chi.NinjaStance.Cooldown");
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public String getName() {
        return "NinjaStance";
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (stealth) {
            if (System.currentTimeMillis() >= stealthStart + stealthChargeTime) {
                stealthReady = true;
            }

            if (!stealthStarted) {
                if (stealthReady && !player.isSneaking()) {
                    stealthReadyStart = System.currentTimeMillis();
                    stealthStarted = true;
                } else if (!player.isSneaking()) {
                    stopStealth();
                    return;
                }

                GeneralMethods.displayColoredParticle(stealthReady && player.isSneaking() ? "00ee00" : "000000", player.getEyeLocation().add(player.getEyeLocation().getDirection()));
            } else {
                if (System.currentTimeMillis() >= stealthReadyStart + stealthDuration) {
                    stopStealth();
                    bPlayer.addCooldown("ninjastealth", stealthCooldown);
                } else {
                    player.addPotionEffect(invis);
                }
            }
        }

        player.addPotionEffects(effects);
    }

    @Override
    public void remove() {
        super.remove();
        bPlayer.addCooldown(this);
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
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getDescription() {
        return "This stance allows chiblockers to become faster and more stealthy (like a ninja)!";
    }

    @Override
    public String getInstructions() {
        return "Left click to begin to this stance > Hold sneak to begin stealth mode";
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Chi.NinjaStance.Enabled");
    }

    @Override
    public String getStanceName() {
        return getName();
    }

    public void beginStealth() {
        if (stealth) {
            ActionBar.sendActionBar(ChatColor.RED + "!> already cloaked <!", player);
            return;
        } else if (bPlayer.isOnCooldown("ninjastealth")) {
            ActionBar.sendActionBar(ChatColor.RED + "!> cooldown <!", player);
            return;
        }
        stealth = true;
        stealthStart = System.currentTimeMillis();
    }

    public void stopStealth() {
        stealth = false;
        stealthReady = false;
        stealthStarted = false;
    }

    public boolean isStealthed() {
        return stealth && stealthStarted;
    }
}
