package com.projectkorra.projectkorra.airbending.passive;

import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;

public class AirAgility extends AirAbility implements PassiveAbility {

    // Configurable variables.
    @Attribute("Jump")
    private int jumpPower;
    @Attribute(Attribute.SPEED)
    private int speedPower;
    private int jumpDuration;
    private int speedDuration;
    private boolean requiresSprinting;
    private double decayMinimum;
    private long minimumAirBlastTime;
    private double maxStamina;
    private double regenRate;
    private boolean showStaminaOnXPBar;
    private double xpBarInterpolationRate;
    private long lastAirBlastRegenTime;
    private long lastAirBlastBarUpdateTime;
    private float displayedAirBlastBar = -1.0f;

    public AirAgility(final Player player) {
        super(player);
        this.setFields();
    }

    public void setFields() {
        this.jumpPower = getConfig().getInt("Abilities.Air.Passive.AirAgility.JumpPower") - 1;
        this.speedPower = getConfig().getInt("Abilities.Air.Passive.AirAgility.SpeedPower") - 1;
        this.jumpDuration = getConfig().getInt("Abilities.Air.Passive.AirAgility.JumpDuration");
        this.speedDuration = getConfig().getInt("Abilities.Air.Passive.AirAgility.SpeedDuration");
        this.requiresSprinting = getConfig().getBoolean("Abilities.Air.Passive.AirAgility.RequiresSprinting");
        this.decayMinimum = getConfig().getDouble("Abilities.Air.AirBlast.DecayMinimum");
        this.minimumAirBlastTime = getConfig().getLong("Abilities.Air.AirBlast.MinimumAirBlastTime");
        this.maxStamina = getConfig().getDouble("Abilities.Air.AirBlast.MaxStamina", 1.2);
        this.regenRate = getConfig().getDouble("Abilities.Air.AirBlast.RegenRate", 0.25);
        this.showStaminaOnXPBar = getConfig().getBoolean("Abilities.Air.AirBlast.ShowStaminaOnXPBar", true);
        this.xpBarInterpolationRate = getConfig().getDouble("Abilities.Air.AirBlast.XPBarInterpolationRate", 3.0);
    }

    @Override
    public void progress() {
        final long now = System.currentTimeMillis();
        final long regenStartTime = bPlayer.getLastAirBlastTime() + minimumAirBlastTime;
        if (now <= regenStartTime) {
            this.lastAirBlastRegenTime = now;
        } else if (this.regenRate > 0 && bPlayer.getAirBlastDecay() < this.maxStamina) {
            final long lastRegenTime = Math.max(this.lastAirBlastRegenTime, regenStartTime);
            final double elapsedSeconds = (now - lastRegenTime) / 1000.0;
            if (elapsedSeconds > 0) {
                bPlayer.regenerateAirBlastDecay(this.regenRate * elapsedSeconds, this.maxStamina);
            }
            this.lastAirBlastRegenTime = now;
        }

        if (this.showStaminaOnXPBar) {
            float decay = (float) bPlayer.getAirBlastDecay();
            float staminaRange = (float) Math.max(0.01, this.maxStamina - this.decayMinimum);
            float normalized = (decay - (float) decayMinimum) / staminaRange;
            float val = this.interpolateAirBlastBar(Math.max(0f, Math.min(1f, normalized)), now);
            if (val != player.getExp()) {
                player.setExp(val);
            }
        }

        if (this.requiresSprinting && !this.player.isSprinting() || !this.bPlayer.canUsePassive(this) || !this.bPlayer.canBendPassive(this)) {
            return;
        }

        // Jump Buff.
        if (!this.player.hasPotionEffect(PotionEffectType.JUMP_BOOST) || this.player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() < this.jumpPower || (this.player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() == this.jumpPower && this.player.getPotionEffect(PotionEffectType.JUMP_BOOST).getDuration() == 1)) {
            this.player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, this.jumpDuration, this.jumpPower, true, false), true);
        }
        // Speed Buff.
        if (!this.player.hasPotionEffect(PotionEffectType.SPEED) || this.player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() < this.speedPower || (this.player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() == this.speedPower && this.player.getPotionEffect(PotionEffectType.SPEED).getDuration() == 1)) {
            this.player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, this.speedDuration, this.speedPower, true, false), true);
        }
    }

    private float interpolateAirBlastBar(final float target, final long now) {
        if (this.displayedAirBlastBar < 0 || this.lastAirBlastBarUpdateTime == 0) {
            this.displayedAirBlastBar = target;
            this.lastAirBlastBarUpdateTime = now;
            return target;
        }

        final double elapsedSeconds = Math.max(0, now - this.lastAirBlastBarUpdateTime) / 1000.0;
        this.lastAirBlastBarUpdateTime = now;

        final float difference = target - this.displayedAirBlastBar;
        final float maxStep = (float) (Math.max(0.01, this.xpBarInterpolationRate) * elapsedSeconds);
        if (Math.abs(difference) <= maxStep) {
            this.displayedAirBlastBar = target;
        } else {
            this.displayedAirBlastBar += Math.signum(difference) * maxStep;
        }

        return Math.max(0f, Math.min(1f, this.displayedAirBlastBar));
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
        return "AirAgility";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
    }

    @Override
    public boolean isInstantiable() {
        return true;
    }

    @Override
    public boolean isProgressable() {
        return true;
    }

    public int getJumpPower() {
        return this.jumpPower;
    }

    public int getSpeedPower() {
        return this.speedPower;
    }
}
