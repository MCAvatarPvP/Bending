package com.projectkorra.projectkorra.firebending;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import com.projectkorra.projectkorra.util.ParticleEffect;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.util.DamageHandler;

public class FireManipulation extends FireAbility {

	public static enum FireManipulationType {
		SHIFT, CLICK;
	}

	// Configurable variables.
	private double shieldCollisionRadius;

	private long shieldCooldown;
	private double shieldRange;
	private double shieldDamage;
	private int shieldParticles;
	private long maxDuration;
	private long damageInterval;
	private long minimumShootTime;

	// Instance related variables.
	private FireManipulationType fireManipulationType;

	private Map<Location, Long> points;
	private int damageTick;

	public FireManipulation(final Player player, final FireManipulationType fireManipulationType) {
		super(player);
		if (!this.bPlayer.canBend(this)) {
			return;
		}

		this.fireManipulationType = fireManipulationType;
		this.setFields();
		this.start();
	}

	public void setFields() {
		if (this.fireManipulationType == FireManipulationType.SHIFT) {
			this.shieldCooldown = applyModifiersCooldown(getConfig().getLong("Abilities.Fire.FireManipulation.Shield.Cooldown"));
			this.shieldRange = applyModifiersRange(getConfig().getDouble("Abilities.Fire.FireManipulation.Shield.Range"));
			this.shieldDamage = applyModifiersDamage(getConfig().getDouble("Abilities.Fire.FireManipulation.Shield.Damage"));
			this.shieldCollisionRadius = getConfig().getDouble("Abilities.Fire.FireManipulation.Shield.CollisionRadius");
			this.shieldParticles = getConfig().getInt("Abilities.Fire.FireManipulation.Shield.Particles");
			this.maxDuration = (long) applyModifiers(getConfig().getLong("Abilities.Fire.FireManipulation.Shield.MaxDuration"));
			this.damageInterval = getConfig().getLong("Abilities.Fire.FireManipulation.Shield.DamageInterval");
			this.minimumShootTime = getConfig().getLong("Abilities.Fire.FireManipulation.Stream.MinimumShootTime");
			this.points = new ConcurrentHashMap<>();
		} else if (this.fireManipulationType == FireManipulationType.CLICK) {

		}
	}

	public void click() {
		if (System.currentTimeMillis() - this.getStartTime() > minimumShootTime) {
			new FireManipulationStream(player, this);
			this.remove();
		}
	}

	@Override
	public void progress() {
		if (!this.bPlayer.canBend(this)) {
			this.remove();
			return;
		}

		if (!this.player.isSneaking()) {
			this.bPlayer.addCooldown(this, this.shieldCooldown);
			this.remove();
			return;
		} else if (System.currentTimeMillis() - this.getStartTime() > this.maxDuration) {
			this.bPlayer.addCooldown(this, this.shieldCooldown);
			this.remove();
			return;
		}

		if (System.currentTimeMillis() - this.getStartTime() > minimumShootTime) {
			Location location = player.getEyeLocation();
			location.add(location.getDirection());
			playFirebendingParticles(location, 1, .01, .01, .01);
		}

		final Location targetLocation = GeneralMethods.getTargetedLocation(this.player, this.shieldRange);
		this.points.put(targetLocation, System.currentTimeMillis());
		for (final Location point : this.points.keySet()) {
			if (System.currentTimeMillis() - this.points.get(point) > 1500) {
				this.points.remove(point);
				return;
			}
			playFirebendingParticles(point, shieldParticles, 0.25, 0.25, 0.25);
			if (System.currentTimeMillis() - this.getStartTime() > this.damageTick * this.damageInterval) {
				this.damageTick++;
				for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(point, 1.2D)) {
					if (entity instanceof LivingEntity && entity.getUniqueId() != this.player.getUniqueId()) {
						DamageHandler.damageEntity(entity, this.shieldDamage, this);
					}
				}
			}
			if (new Random().nextInt(this.points.keySet().size()) == 0) {
				playFirebendingSound(point);
			}
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
		return "FireManipulation";
	}

	@Override
	public Location getLocation() {
		return null;
	}

	@Override
	public List<Location> getLocations() {
		final List<Location> locations = new ArrayList<>();
		if (this.points != null) {
			locations.addAll(this.points.keySet());
		}
		return locations;
	}

	public Map<Location, Long> getPoints() {
		return points;
	}

	public FireManipulationType getFireManipulationType() {
		return this.fireManipulationType;
	}

	@Override
	public double getCollisionRadius() {
		return shieldCollisionRadius;
	}
}
