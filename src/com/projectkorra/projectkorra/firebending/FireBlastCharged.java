package com.projectkorra.projectkorra.firebending;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.util.TempBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.avatar.AvatarState;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;

public class FireBlastCharged extends FireAbility {

	private static final Map<Entity, FireBlastCharged> EXPLOSIONS = new ConcurrentHashMap<>();
	public static List<UUID> funPlayers = new ArrayList<>();
    private boolean coolSpiral;

    private boolean charged;
	private boolean launched;
	private boolean canDamageBlocks;
	private boolean dissipate;
	private boolean canChargeInWater;
	private long time;
	@Attribute(Attribute.CHARGE_DURATION)
	private long chargeTime;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	private long interval;
	@Attribute("Min" + Attribute.DAMAGE)
	private double minDamage;
	@Attribute("Max" + Attribute.DAMAGE)
	private double maxDamage;
	private double explosionMinDamage;
	private double explosionMaxDamage;
	@Attribute(Attribute.RANGE)
	private double range;
	private double collisionRadius;
	@Attribute(Attribute.DAMAGE + Attribute.RANGE)
	private double damageRadius;
	@Attribute("Explosion" + Attribute.RANGE)
	private double explosionRadius;
	private double explosionPower;
	private long damagedRevertTime;
	private double funThingPower;
	private double innerRadius;
	@Attribute(Attribute.FIRE_TICK)
	private double fireTicks;
	private double fireRadius;
	private TNTPrimed explosion;
	private Location origin;
	private Location location;
	private Vector direction;
	private boolean useFireBlastCooldown;
    private double spiralAngle = 0.0;
    private double angularSpeed = 0.45;

	public FireBlastCharged(final Player player) {
		super(player);

		if (!this.bPlayer.canBendIgnoreCooldowns(this) || hasAbility(player, FireBlastCharged.class)) {
			return;
		}

		this.charged = false;
		this.launched = false;
		this.canDamageBlocks = getConfig().getBoolean("Abilities.Fire.FireBlast.Charged.DamageBlocks");
		this.dissipate = getConfig().getBoolean("Abilities.Fire.FireBlast.Dissipate");
		this.canChargeInWater = getConfig().getBoolean("Abilities.Fire.FireBlast.Charged.CanChargeInWater");
		this.chargeTime = (long) applyInverseModifiers(getConfig().getLong("Abilities.Fire.FireBlast.Charged.ChargeTime"));
		this.cooldown = applyModifiersCooldown(getConfig().getLong("Abilities.Fire.FireBlast.Charged.Cooldown"));
		this.time = System.currentTimeMillis();
		this.interval = 25;
		this.collisionRadius = applyModifiers(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.CollisionRadius"));
		this.minDamage = applyModifiersDamage(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.MinimumDamage"));
		this.maxDamage = applyModifiersDamage(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.MaximumDamage"));
		this.explosionMinDamage = getConfig().getDouble("Abilities.Fire.FireBlast.Charged.ExplosionMinimumDamage");
		this.explosionMaxDamage = getConfig().getDouble("Abilities.Fire.FireBlast.Charged.ExplosionMaximumDamage");
		this.range = applyModifiersRange(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.Range"));
		this.damageRadius = applyModifiers(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.DamageRadius"));
		this.explosionRadius = applyModifiers(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.ExplosionRadius"));
		this.explosionPower = getConfig().getDouble("Abilities.Fire.FireBlast.Charged.ExplosionPower");
		this.damagedRevertTime = getConfig().getLong("Abilities.Fire.FireBlast.Charged.DamagedBlocksRevertTime");
		this.fireTicks = applyModifiers(getConfig().getDouble("Abilities.Fire.FireBlast.Charged.FireTicks"));
		this.fireRadius = getConfig().getDouble("Abilities.Fire.FireBlast.Charged.FireRadius");
		this.funThingPower = getConfig().getDouble("Abilities.Fire.FireBlast.Charged.FunThing");
		this.useFireBlastCooldown = getConfig().getBoolean("Abilities.Fire.FireBlast.Charged.UseFireBlastCD");
        this.coolSpiral = getConfig().getBoolean("Abilities.Fire.FireBlast.Charged.CoolSpiral", true);
		this.innerRadius = this.damageRadius / 2;

		if (useFireBlastCooldown) {
			Ability ability = getAbility(FireBlast.class);

			if (bPlayer.isOnCooldown(ability)) {
				return;
			}
		} else {
			if (bPlayer.isOnCooldown("FireBlastCharged")) {
				return;
			}
		}

		//this.applyModifiers();

		if (canChargeInWater || !player.getEyeLocation().getBlock().isLiquid()) {
			this.start();
		}
	}

	@Deprecated
	private void applyModifiers() {
		long chargeTimeMod = 0;
		double minDamageMod = 0;
		double maxDamageMod = 0;
		double rangeMod = 0;

		if (isDay(player.getWorld())) {
			chargeTimeMod = (long) (this.chargeTime / getDayFactor() - this.chargeTime);
			minDamageMod = this.getDayFactor(this.minDamage) - this.minDamage;
			maxDamageMod = this.getDayFactor(this.maxDamage) - this.maxDamage;
			rangeMod = this.getDayFactor(this.range) - this.range;
		}

		chargeTimeMod = (long) (bPlayer.canUseSubElement(SubElement.BLUE_FIRE) ? (chargeTime / BlueFireAbility.getCooldownFactor() - chargeTime) + chargeTimeMod : chargeTimeMod);
		minDamageMod = (bPlayer.canUseSubElement(SubElement.BLUE_FIRE) ? (BlueFireAbility.getDamageFactor() * minDamage - minDamage) + minDamageMod : minDamageMod);
		maxDamageMod = (bPlayer.canUseSubElement(SubElement.BLUE_FIRE) ? (BlueFireAbility.getDamageFactor() * maxDamage - maxDamage) + maxDamageMod : maxDamageMod);
		rangeMod =  (bPlayer.canUseSubElement(SubElement.BLUE_FIRE) ? (BlueFireAbility.getRangeFactor() * range - range) + rangeMod : rangeMod);

		if (this.bPlayer.isAvatarState()) {
			this.chargeTime = getConfig().getLong("Abilities.Avatar.AvatarState.Fire.FireBlast.Charged.ChargeTime");
			this.minDamage = getConfig().getDouble("Abilities.Avatar.AvatarState.Fire.FireBlast.Charged.MinimumDamage");
			this.maxDamage = getConfig().getDouble("Abilities.Avatar.AvatarState.Fire.FireBlast.Charged.MaximumDamage");
		}

		this.chargeTime += chargeTimeMod;
		this.minDamage += minDamageMod;
		this.maxDamage += maxDamageMod;
		this.range += rangeMod;
	}

	public static boolean annihilateBlasts(final Location location, final double radius, final Player source) {
		boolean broke = false;
		for (final FireBlastCharged chargedBlast : getAbilities(FireBlastCharged.class)) {
			if (!chargedBlast.launched) {
				continue;
			}

			final Location fireBlastLocation = chargedBlast.location;
			if (location.getWorld().equals(fireBlastLocation.getWorld()) && !source.equals(chargedBlast.player)) {
				if (location.distanceSquared(fireBlastLocation) <= radius * radius) {
					chargedBlast.explode();
					broke = true;
				}
			}
		}
		return broke;
	}

	public static FireBlastCharged getFireball(final Entity entity) {
		return entity != null ? EXPLOSIONS.get(entity) : null;
	}

	public static boolean isCharging(final Player player) {
		for (final FireBlastCharged chargedBlast : getAbilities(player, FireBlastCharged.class)) {
			if (!chargedBlast.launched) {
				return true;
			}
		}
		return false;
	}

	public static void removeFireballsAroundPoint(final Location location, final double radius) {
		for (final FireBlastCharged fireball : getAbilities(FireBlastCharged.class)) {
			if (!fireball.launched) {
				continue;
			}
			final Location fireblastlocation = fireball.location;
			if (location.getWorld().equals(fireblastlocation.getWorld())) {
				if (location.distanceSquared(fireblastlocation) <= radius * radius) {
					fireball.remove();
				}
			}
		}
	}

	public void dealDamage(final Entity entity) {
		if (this.explosion == null) {
			return;
		}

		double distance = 0;
		if (entity.getWorld().equals(this.explosion.getWorld())) {
			distance = entity.getLocation().distance(this.explosion.getLocation());
		}
		if (distance > this.damageRadius) {
			return;
		} else if (distance < this.innerRadius) {
			DamageHandler.damageEntity(entity, this.maxDamage, this);
			return;
		}

		final double slope = -(this.maxDamage * .5) / (this.damageRadius - this.innerRadius);
		double damage = slope * (distance - this.innerRadius) + this.maxDamage;
		if (damage < this.minDamage) {
			damage = this.minDamage;
		}

		DamageHandler.damageEntity(entity, damage, this);
		AirAbility.breakBreathbendingHold(entity);
	}

	public void explode() {
		boolean explode = true;
		for (final Block block : GeneralMethods.getBlocksAroundPoint(this.location, 3)) {
			if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
				explode = false;
				break;
			}
		}

		if (explode) {
			final List<Entity> entities = GeneralMethods.getEntitiesAroundPoint(this.location, this.damageRadius);
			for (final Entity entity : entities) {
				if (entity instanceof LivingEntity) {
					final double slope = -(this.explosionMaxDamage * .5) / (this.damageRadius - this.innerRadius);
					double damage = 0;
					if (entity.getWorld().equals(this.location.getWorld())) {
						damage = slope * (entity.getLocation().distance(this.location) - this.innerRadius) + this.explosionMaxDamage;
					}
					if (damage < this.explosionMinDamage) {
						damage = this.explosionMinDamage;
					}

					DamageHandler.damageEntity(entity, damage, this);
					if (funThingPower != 0) {
						Vector vec = entity.getLocation().toVector().subtract(location.toVector());
						GeneralMethods.setVelocity(this, entity, vec.normalize().multiply(funThingPower));
					}
					if (funThingPower == 0 && funPlayers.contains(entity.getUniqueId())) {
						Vector vec = entity.getLocation().toVector().subtract(location.toVector());
						GeneralMethods.setVelocity(this, entity, vec.normalize().multiply(1.3));
					}
				}
			}

			if (this.canDamageBlocks && this.explosionRadius > 0) {
				for (final Block block : GeneralMethods.getBlocksAroundPoint(this.location, this.explosionRadius)) {
					if (explosionPower >= (double) block.getType().getBlastResistance()) {
						new TempBlock(block, Material.AIR.createBlockData(), damagedRevertTime);
					}
				}
			}

			this.location.getWorld().playSound(this.location, Sound.ENTITY_GENERIC_EXPLODE, 5, 1);
			ParticleEffect.EXPLOSION_HUGE.display(this.location, 1, 0, 0, 0);
		}

		this.ignite(this.location);
		this.remove();
	}

	@Override
	public boolean isExplosiveAbility() {
		return isCanDamageBlocks();
	}

	private void executeFireball() {
        if (coolSpiral) {
            Vector axis = this.direction.clone().normalize();
            if (axis.lengthSquared() < 1e-12) {
                axis.setX(0).setY(1).setZ(0);
            }

            Vector arbitrary = Math.abs(axis.getX()) < 0.9 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
            Vector u = axis.clone().crossProduct(arbitrary).normalize();
            if (u.lengthSquared() < 1e-12) u = new Vector(0, 0, 1);
            Vector v = axis.clone().crossProduct(u).normalize();
            this.spiralAngle += this.angularSpeed;

            int beamCount = 3;
            double r = this.collisionRadius;
            double base = this.spiralAngle;

            for (int k = 0; k < beamCount; k++) {
                double theta = base + (2.0 * Math.PI * k) / beamCount;
                double c = Math.cos(theta);
                double s = Math.sin(theta);

                Vector offset = u.clone().multiply(c * r).add(v.clone().multiply(s * r));
                Location beamLoc = this.location.clone().add(offset);

                playFirebendingParticles(beamLoc, 3, 0.1, 0.1, 0.1);
            }

            playFirebendingParticles(location, 3, 0.275, 0.275, 0.275);
        } else {
            for (final Block block : GeneralMethods.getBlocksAroundPoint(this.location, this.collisionRadius)) {
                playFirebendingParticles(block.getLocation(), 5, 0.5, 0.5, 0.5);
                if ((new Random()).nextInt(4) == 0) {
                    playFirebendingSound(this.location);
                }
            }
        }

		boolean exploded = false;
		for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.collisionRadius)) {
			if (entity.getEntityId() == this.player.getEntityId() || GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
				continue;
			}
			entity.setFireTicks((int) (this.fireTicks * 20));
			if (entity instanceof LivingEntity) {
				if (!exploded) {
					this.explode();
					exploded = true;
				}
				this.dealDamage(entity);
			}
		}
	}

	private void ignite(final Location location) {
		for (final Block block : GeneralMethods.getBlocksAroundPoint(location, this.fireRadius)) {
			if (isIgnitable(block) && block.getRelative(BlockFace.DOWN).getType().isSolid()) {
				createTempFire(block.getLocation());
			}
		}
	}

	@Override
	public void progress() {
		if (!this.bPlayer.canBendIgnoreCooldowns(this) && !this.launched) {
			this.remove();
			return;
		} else if (bPlayer.isOnCooldown("FireBlastCharged") && !this.launched) {
			this.remove();
			return;
		} else if (!this.player.isSneaking() && !this.charged) {
			this.remove();
			return;
		}

		if (System.currentTimeMillis() > this.getStartTime() + this.chargeTime) {
			this.charged = true;
		}
		if (!this.player.isSneaking() && !this.launched) {
			this.launched = true;
			this.location = this.player.getEyeLocation();
			this.origin = this.location.clone();
			this.direction = this.location.getDirection().normalize().multiply(this.collisionRadius/2);
		}

		if (System.currentTimeMillis() > this.time + this.interval) {
			if (this.launched) {
				if (GeneralMethods.isRegionProtectedFromBuild(this, this.location)) {
					this.remove();
					return;
				}
			}

			this.time = System.currentTimeMillis();

			if (!this.launched && !this.charged) {
				return;
			} else if (!this.launched) {
				playFirebendingParticles(this.player.getEyeLocation().clone().add(this.player.getEyeLocation().getDirection().clone()), 3, .001, .001, .001);
				return;
			}

			if (GeneralMethods.checkDiagonalWall(this.location, this.direction)) {
				this.explode();
				return;
			}
			for (double i = 0; i < collisionRadius; i += collisionRadius/2) {
				this.location = this.location.clone().add(this.direction);
				if (this.location.distanceSquared(this.origin) > this.range * this.range) {
					this.remove();
					return;
				}

				if (GeneralMethods.isSolid(this.location.getBlock())) {
					this.explode();
					return;
				} else if (this.location.getBlock().isLiquid()) {
					this.remove();
					return;
				}
				if (i >= collisionRadius / 2) this.executeFireball();
			}
		}
	}

	@Override
	public void remove() {
		super.remove();
		if (this.charged) {
			if (useFireBlastCooldown) {
				Ability ability = getAbility(FireBlast.class);
				this.bPlayer.addCooldown(ability, this.cooldown);
			} else {
				this.bPlayer.addCooldown("FireBlastCharged", this.cooldown);
			}
		}
	}

	@Override
	public void handleCollision(Collision collision) {
		super.handleCollision(collision);
		if (collision.isRemovingFirst()) explode();
	}

	public static void toggleFun(Player player, boolean activate) {
		UUID uuid = player.getUniqueId();
		if (funPlayers.contains(uuid) && !activate) funPlayers.remove(uuid);
		else if (!funPlayers.contains(uuid) && activate) funPlayers.add(uuid);
	}

	@Override
	public String getName() {
		return "FireBlast";
	}

	@Override
	public Location getLocation() {
		return this.location != null ? this.location : this.origin;
	}

	@Override
	public long getCooldown() {
		return this.cooldown;
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
	public boolean isCollidable() {
		return this.launched;
	}

	@Override
	public double getCollisionRadius() {
		return this.collisionRadius;
	}

	public boolean isCharged() {
		return this.charged;
	}

	public void setCharged(final boolean charged) {
		this.charged = charged;
	}

	public boolean isLaunched() {
		return this.launched;
	}

	public void setLaunched(final boolean launched) {
		this.launched = launched;
	}

	public boolean isCanDamageBlocks() {
		return this.canDamageBlocks;
	}

	public void setCanDamageBlocks(final boolean canDamageBlocks) {
		this.canDamageBlocks = canDamageBlocks;
	}

	public boolean isDissipate() {
		return this.dissipate;
	}

	public void setDissipate(final boolean dissipate) {
		this.dissipate = dissipate;
	}

	public long getTime() {
		return this.time;
	}

	public void setTime(final long time) {
		this.time = time;
	}

	public long getChargeTime() {
		return this.chargeTime;
	}

	public void setChargeTime(final long chargeTime) {
		this.chargeTime = chargeTime;
	}

	public long getInterval() {
		return this.interval;
	}

	public void setInterval(final long interval) {
		this.interval = interval;
	}

	public double getMaxDamage() {
		return this.maxDamage;
	}

	public void setMaxDamage(final double maxDamage) {
		this.maxDamage = maxDamage;
	}

	public double getMinDamage() {
		return this.minDamage;
	}

	public void setMinDamage(final double minDamage) {
		this.minDamage = minDamage;
	}

	public double getRange() {
		return this.range;
	}

	public void setRange(final double range) {
		this.range = range;
	}

	public void setCollisionRadius(final double collisionRadius) {
		this.collisionRadius = collisionRadius;
	}

	public double getDamageRadius() {
		return this.damageRadius;
	}

	public void setDamageRadius(final double damageRadius) {
		this.damageRadius = damageRadius;
	}

	public double getExplosionRadius() {
		return this.explosionRadius;
	}

	public void setExplosionRadius(final double explosionRadius) {
		this.explosionRadius = explosionRadius;
	}

	public double getInnerRadius() {
		return this.innerRadius;
	}

	public void setInnerRadius(final double innerRadius) {
		this.innerRadius = innerRadius;
	}

	public double getFireTicks() {
		return this.fireTicks;
	}

	public void setFireTicks(final double fireTicks) {
		this.fireTicks = fireTicks;
	}

	public TNTPrimed getExplosion() {
		return this.explosion;
	}

	public void setExplosion(final TNTPrimed explosion) {
		this.explosion = explosion;
	}

	public Location getOrigin() {
		return this.origin;
	}

	public void setOrigin(final Location origin) {
		this.origin = origin;
	}

	public Vector getDirection() {
		return this.direction;
	}

	public void setDirection(final Vector direction) {
		this.direction = direction;
	}

	public static Map<Entity, FireBlastCharged> getExplosions() {
		return EXPLOSIONS;
	}

	public void setLocation(final Location location) {
		this.location = location;
	}

}
