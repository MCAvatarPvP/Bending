package com.projectkorra.projectkorra.airbending.passive;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.ConfigManager;

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
	}

	@Override
	public void progress() {
		if (System.currentTimeMillis() - bPlayer.getLastAirBlastTime() > minimumAirBlastTime) {
			bPlayer.resetAirBlastDecay();
		}

		float decay = (float) bPlayer.getAirBlastDecay();  // between 0.4 and 1.0
		float normalized = (decay - 0.4f) / (1.0f - (float) decayMinimum);  // Normalized to 0.0–1.0
		player.setExp(Math.max(0f, Math.min(1f, normalized)));  // Clamp between 0 and 1

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
