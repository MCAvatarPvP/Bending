package com.projectkorra.projectkorra.waterbending.passive;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.EarthArmor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.waterbending.WaterSpout;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;

public class FastSwim extends WaterAbility implements PassiveAbility {
    private static final int COSMETIC_ICE_SPEED_DURATION = 10;
    private static final int COSMETIC_ICE_SPEED_AMPLIFIER = 0;

    private long cooldown;
    private double swimSpeed;
    private long duration;
    private boolean allowWaterArms;

    public FastSwim(final Player player) {
        this(player, false);
    }

    /**
     * Fabric receives the client sneak packet before the common abstraction has a Bukkit-style
     * PlayerToggleSneakEvent, and by the time a normal Fabric tick observes the state the
     * player is already sneaking.  Bukkit historically calls this constructor while
     * Player#isSneaking() still reflects the old state.  This overload lets the Fabric
     * input bridge explicitly say "this is the shift-down edge" without starting
     * FastSwim on shift release.
     */
    public FastSwim(final Player player, final boolean fromSneakDownEdge) {
        super(player);
        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        if (player.isSneaking() && !fromSneakDownEdge) { // the Bukkit sneak event calls before they actually start sneaking
            return;
        }

        this.cooldown = getConfig().getLong("Abilities.Water.Passive.FastSwim.Cooldown");
        this.swimSpeed = getConfig().getDouble("Abilities.Water.Passive.FastSwim.SpeedFactor");
        this.duration = getConfig().getLong("Abilities.Water.Passive.FastSwim.Duration");
        this.allowWaterArms = getConfig().getBoolean("Abilities.Water.Passive.FastSwim.AllowWaterArms");

        this.start();
    }

    public static double getSwimSpeed(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getDouble("Abilities.Water.Passive.FastSwim.SpeedFactor");
    }

    public static void applyCosmeticIceSpeed(final Player player) {
        if (getIceMaterial(player) == Material.ICE || !player.isOnGround()) {
            return;
        }

        final CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
        if (!PassiveManager.hasPassive(player, passive)) {
            return;
        }

        final Block block = player.getLocation().getBlock();
        final Block below = block.getRelative(BlockFace.DOWN);
        if (!isIce(block) && !isIce(below)) {
            return;
        }

        final PotionEffect current = player.getPotionEffect(PotionEffectType.SPEED);
        if (current == null || current.getAmplifier() < COSMETIC_ICE_SPEED_AMPLIFIER
                || current.getAmplifier() == COSMETIC_ICE_SPEED_AMPLIFIER && current.getDuration() <= 1) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, COSMETIC_ICE_SPEED_DURATION, COSMETIC_ICE_SPEED_AMPLIFIER, true, false), true);
        }
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canUsePassive(this) || !this.bPlayer.canBendPassive(this) || CoreAbility.hasAbility(this.player, EarthArmor.class)) {
            this.remove();
            return;
        }

        //Don't remove, for SpoutHop - avoids forcing SpoutHop users to re-sneak and recreate FastSwim (Less clunky)
        WaterSpout spout = CoreAbility.getAbility(player, WaterSpout.class);
        if (spout != null) {
            if (!spout.canSpoutHop()) {
                remove();
            }
            return;
        }

        if (CoreAbility.hasAbility(this.player, WaterArms.class) && !this.allowWaterArms) {
            this.remove();
            return;
        }

        if (this.duration > 0 && System.currentTimeMillis() > this.getStartTime() + this.duration) {
            this.bPlayer.addCooldown(this);
            this.remove();
            return;
        }

        if (this.bPlayer.getBoundAbility() == null || (this.bPlayer.getBoundAbility() != null && !this.bPlayer.getBoundAbility().isSneakAbility())) {
            if (this.player.isSneaking()) {
                if (this.isPlayerInWater() && !this.bPlayer.isOnCooldown(this)) {
                    GeneralMethods.setVelocity(this, this.player, this.player.getEyeLocation().getDirection().clone().normalize().multiply(this.swimSpeed));
                }
            } else {
                this.bPlayer.addCooldown(this);
                this.remove();
            }
        }
    }

    private boolean isPlayerInWater() {
        final Location feet = this.player.getLocation();
        return isWater(feet.getBlock())
                || isWater(feet.clone().add(0, 0.5, 0).getBlock())
                || isWater(this.player.getEyeLocation().getBlock());
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public String getName() {
        return "FastSwim";
    }

    @Override
    public Location getLocation() {
        return this.player.getLocation();
    }

    @Override
    public boolean isInstantiable() {
        return false;
    }

    @Override
    public boolean isProgressable() {
        return true;
    }
}
