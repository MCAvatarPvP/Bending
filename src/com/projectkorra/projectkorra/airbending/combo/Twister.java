package com.projectkorra.projectkorra.airbending.combo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.airbending.AirSuction;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.airbending.AirSurf;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.util.AbilityLagCompensator;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.AABB;

public class Twister extends AirAbility implements ComboAbility {

	public static enum AbilityState {
		CHARGING, TWISTER_MOVING, TWISTER_STATIONARY
	}

	private static final int CHARGE_STREAMS = 4;
	private static final int MOVING_STREAMS = 3;
	private static final long CHARGE_SOUND_INTERVAL = 200L;
	private static final long TWISTER_SOUND_INTERVAL = 350L;
	private static final String[] TRAPPED_PLAYER_ABILITIES = { "AirScooter", "AirSpout", "AirSurf" };

	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.CHARGE_DURATION)
	private long chargeTime;
	private long damageInterval;
	private long maxPullDuration;
	private long trappedAbilityCooldown;
	private long time;
	private long lastSoundTime;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute("PullZone" + Attribute.RADIUS)
	private double pullZoneRadius;
	@Attribute("Pull" + Attribute.SPEED)
	private double pullVelocity;
	@Attribute(Attribute.SPEED)
	private double speed;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.HEIGHT)
	private double twisterHeight;
	@Attribute(Attribute.RADIUS)
	private double twisterRadius;
	private double twisterDegreeParticles;
	private double twisterHeightParticles;
	private double twisterRemoveDelay;
	private double chargeAngle;
	private double vortexAngle;
	private long chargedDuration;
	private long lastChargeUpdateTime;
	private int lastKnownNoDamageTicks;
	private boolean spinPlayers;
	private AbilityState state;
	private Location origin;
	private Location currentLoc;
	private Vector direction;
	private Vector motion;
	private double distanceTravelled;
	private final Map<UUID, Long> lastDamageTimes;
	private final Map<UUID, Long> pullStartTimes;
	private final Map<UUID, Long> lastRestrictedTimes;
	private final Map<UUID, Entity> caughtEntities;
	private final Set<UUID> exhaustedPullEntities;
	private final Set<UUID> pulledEntitiesThisTick;
	private final AbilityLagCompensator lagCompensator;
	private final Set<AirBlast> handledBlasts;
	private final Set<AirSuction> handledSuctions;

	public Twister(final Player player) {
		super(player);

		this.lastDamageTimes = new HashMap<>();
		this.pullStartTimes = new HashMap<>();
		this.lastRestrictedTimes = new HashMap<>();
		this.caughtEntities = new HashMap<>();
		this.exhaustedPullEntities = new HashSet<>();
		this.pulledEntitiesThisTick = new HashSet<>();
		this.lagCompensator = new AbilityLagCompensator((p, snapshot) -> this.pullEntity(p, snapshot.getLocation()));
		this.handledBlasts = Collections.newSetFromMap(new HashMap<>());
		this.handledSuctions = Collections.newSetFromMap(new HashMap<>());

		if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
			return;
		}

		if (this.bPlayer.isOnCooldown(this)) {
			return;
		}

		if (CoreAbility.hasAbility(player, AirSpout.class)) {
			player.sendMessage(ChatColor.RED + "You can't use Twister while using AirSpout.");
			return;
		}

		this.range = getConfig().getDouble("Abilities.Air.Twister.Range");
		this.speed = getConfig().getDouble("Abilities.Air.Twister.Speed");
		this.cooldown = getConfig().getLong("Abilities.Air.Twister.Cooldown");
		this.chargeTime = getConfig().getLong("Abilities.Air.Twister.ChargeTime", 750L);
		this.damage = getConfig().getDouble("Abilities.Air.Twister.Damage", 0);
		this.damageInterval = getConfig().getLong("Abilities.Air.Twister.DamageInterval", 500L);
		this.maxPullDuration = getConfig().getLong("Abilities.Air.Twister.MaxPullDuration", 0L);
		this.trappedAbilityCooldown = getConfig().getLong("Abilities.Air.Twister.TrappedAbilityCooldown", 1500L);
		this.twisterHeight = getConfig().getDouble("Abilities.Air.Twister.Height");
		this.twisterRadius = getConfig().getDouble("Abilities.Air.Twister.Radius");
		this.pullZoneRadius = getConfig().getDouble("Abilities.Air.Twister.PullZoneRadius", this.twisterRadius + 1.75);
		this.pullVelocity = getConfig().getDouble("Abilities.Air.Twister.PullVelocity", Math.max(0.1, this.speed * 0.9));
		this.twisterDegreeParticles = getConfig().getDouble("Abilities.Air.Twister.DegreesPerParticle");
		this.twisterHeightParticles = getConfig().getDouble("Abilities.Air.Twister.HeightPerParticle");
		this.twisterRemoveDelay = getConfig().getLong("Abilities.Air.Twister.RemoveDelay");
		this.spinPlayers = getConfig().getBoolean("Abilities.Air.Twister.SpinPlayers", false);
		this.state = AbilityState.CHARGING;
		this.chargeAngle = 0;
		this.vortexAngle = 0;
		this.chargedDuration = 0L;
		this.lastChargeUpdateTime = 0L;
		this.lastKnownNoDamageTicks = this.player.getNoDamageTicks();
		this.lastSoundTime = 0L;
		this.motion = new Vector(0, 0, 0);
		this.distanceTravelled = 0;

		this.start();
	}

	@Override
	public String getName() {
		return "Twister";
	}

	@Override
	public void progress() {
		if (this.player.isDead() || !this.player.isOnline()) {
			this.remove();
			return;
		}

		if (this.state == AbilityState.CHARGING) {
			if (GeneralMethods.isRegionProtectedFromBuild(this, this.player.getLocation())) {
				this.remove();
				return;
			}
			if (!this.player.isSneaking()) {
				this.remove();
				return;
			}
			if (this.wasHitDuringCharge()) {
				this.remove();
				return;
			}

			this.updateChargeProgress();
			this.renderChargeAnimation();
			if (this.chargedDuration >= this.chargeTime) {
				this.deployTwister();
			}
			return;
		}

		if (this.currentLoc == null) {
			this.remove();
			return;
		} else if (GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) {
			this.remove();
			return;
		}

		if (System.currentTimeMillis() - this.time >= this.twisterRemoveDelay) {
			this.remove();
			return;
		}

		this.absorbAirControllerPushes();
		this.moveTwister();

		if (!exhaustedPullEntities.isEmpty()) {
			remove();
			return;
		}

		final Location groundedLocation = this.getGroundedTwisterLocation(this.currentLoc);
		if (groundedLocation == null) {
			this.remove();
			return;
		}
		this.currentLoc = groundedLocation;

		this.renderTwisterAnimation();
		this.pullEntitiesInsideTwister();
	}

	private void deployTwister() {
		final Vector facing = this.getHorizontalDirection();
		if (facing.lengthSquared() == 0) {
			this.remove();
			return;
		}

		final Location deployLocation = this.getGroundedTwisterLocation(this.player.getLocation().add(facing.clone().multiply(2)));
		if (deployLocation == null) {
			this.remove();
			return;
		}

		this.origin = deployLocation.clone();
		this.currentLoc = this.origin.clone();
		this.direction = facing;
		this.motion.zero();
		this.time = System.currentTimeMillis();
		this.state = AbilityState.TWISTER_STATIONARY;
		this.bPlayer.addCooldown(this);
	}

	private Vector getHorizontalDirection() {
		final Vector horizontal = this.player.getEyeLocation().getDirection().clone();
		horizontal.setY(0);

		if (horizontal.lengthSquared() == 0) {
			return new Vector(0, 0, 0);
		}
		return horizontal.normalize();
	}

	private void renderChargeAnimation() {
		final Vector facing = this.getHorizontalDirection();
		if (facing.lengthSquared() == 0) {
			return;
		}

		final Location target = this.getGroundedTwisterLocation(this.player.getLocation().add(facing.clone().multiply(2)));
		if (target == null) {
			return;
		}

		final double progress = this.chargeTime <= 0 ? 1.0 : Math.min(1.0, (double) this.chargedDuration / this.chargeTime);
		final Location source = this.player.getEyeLocation().add(facing.clone().multiply(0.75));
		final Location center = target.clone().add(0, 0.15, 0);
		final Vector toTarget = center.clone().subtract(source).toVector();

		this.chargeAngle += 18 + (progress * 10);

		for (int stream = 0; stream < CHARGE_STREAMS; stream++) {
			final double streamProgress = Math.max(0.0, Math.min(1.0, progress * 1.35 - (stream * 0.16)));
			if (streamProgress <= 0) {
				continue;
			}

			final Location blastLoc = source.clone().add(toTarget.clone().multiply(streamProgress));
			final Vector sideOffset = GeneralMethods.getOrthogonalVector(facing.clone(), this.chargeAngle + (stream * 90.0), (1.0 - streamProgress) * 0.85);
			blastLoc.add(sideOffset);

			this.renderAirBlastTrail(blastLoc, facing, 3 + (int) Math.round(progress * 3), 0.22);
		}

		final double degreeStep = Math.max(12.0, this.twisterDegreeParticles * 2.0);
		for (int layer = 0; layer < 2; layer++) {
			final double layerRadius = (0.55 + (layer * 0.28)) + (this.twisterRadius * 0.25 * progress);
			final double yOffset = 0.08 + (layer * (0.25 + progress * 0.35));

			for (double degrees = 0; degrees < 360; degrees += degreeStep) {
				final double angle = Math.toRadians(degrees + this.chargeAngle + (layer * 18.0));
				final Location ringLoc = center.clone().add(Math.cos(angle) * layerRadius, yOffset, Math.sin(angle) * layerRadius);
				playAirbendingParticles(ringLoc, 1, 0.02, 0.02, 0.02);
			}
		}

		for (double y = 0; y <= Math.max(0.4, this.twisterHeight * progress * 0.35); y += Math.max(0.3, this.twisterHeightParticles * 0.5)) {
			final double radius = Math.max(0.35, this.twisterRadius * 0.18 + (y * 0.18));
			for (double degrees = 0; degrees < 360; degrees += Math.max(30.0, degreeStep * 1.5)) {
				final double angle = Math.toRadians(degrees - this.chargeAngle * 1.2 + (y * 24.0));
				final Location formingLoc = center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
				playAirbendingParticles(formingLoc, 1, 0.01, 0.01, 0.01);
			}
		}

		if (System.currentTimeMillis() - this.lastSoundTime >= CHARGE_SOUND_INTERVAL) {
			playAirbendingSound(center, (float) (1.0 + (progress * 0.2)));
			this.lastSoundTime = System.currentTimeMillis();
		}
	}

	private void updateChargeProgress() {
		final long now = System.currentTimeMillis();
		if (this.lastChargeUpdateTime == 0L) {
			this.lastChargeUpdateTime = now;
			return;
		}

		this.chargedDuration += now - this.lastChargeUpdateTime;
		this.lastChargeUpdateTime = now;
	}

	private boolean wasHitDuringCharge() {
		final int currentNoDamageTicks = this.player.getNoDamageTicks();
		final boolean wasHit = currentNoDamageTicks > this.lastKnownNoDamageTicks && currentNoDamageTicks >= this.player.getMaximumNoDamageTicks() / 2;
		this.lastKnownNoDamageTicks = currentNoDamageTicks;
		return wasHit;
	}

	private Location getGroundedTwisterLocation(final Location base) {
		final Block topBlock = GeneralMethods.getTopBlock(base, 3, -3);
		if (topBlock == null) {
			return null;
		}

		final Location grounded = base.clone();
		grounded.setY(topBlock.getLocation().getY() + 1.0);
		return this.isTwisterSpaceClear(grounded) ? grounded : null;
	}

	private void renderTwisterAnimation() {
		final double heightStep = Math.max(0.4, this.twisterHeightParticles);
		final double spiralStep = Math.max(10.0, this.twisterDegreeParticles);
		final double movementFactor = this.state == AbilityState.TWISTER_MOVING ? 1.0 : 0.55;
		this.vortexAngle += Math.max(10.0, this.speed * 80.0 * movementFactor);

		for (double y = 0; y <= this.twisterHeight; y += heightStep) {
			final double heightFactor = this.twisterHeight <= 0 ? 1.0 : y / this.twisterHeight;
			final double radius = Math.max(0.35, this.twisterRadius * Math.max(0.12, heightFactor * 0.7));
			final double baseAngle = this.vortexAngle + (heightFactor * 180.0);

			for (int stream = 0; stream < MOVING_STREAMS; stream++) {
				final double angle = Math.toRadians(baseAngle + (stream * (360.0 / MOVING_STREAMS)));
				final Location spiralLoc = this.currentLoc.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
				final Vector tangent = new Vector(-Math.sin(angle), spiralStep / 180.0, Math.cos(angle)).normalize();

				this.renderAirBlastTrail(spiralLoc, tangent, 2, 0.18);
				playAirbendingParticles(spiralLoc, 1, 0.02, 0.02, 0.02);
			}
		}

		final Vector forward = this.getVisualDirection();
		for (int i = 0; i < 4; i++) {
			final double distance = 0.5 + (i * 0.45);
			final double angle = Math.toRadians(this.vortexAngle + (i * 35.0));
			final Vector sideOffset = GeneralMethods.getOrthogonalVector(forward.clone(), Math.toDegrees(angle), this.twisterRadius * 0.2);
			final Location frontLoc = this.currentLoc.clone().add(0, Math.min(this.twisterHeight - 0.5, 1.2 + (i * 0.35)), 0)
					.add(forward.clone().multiply(distance)).add(sideOffset);
			playAirbendingParticles(frontLoc, 1, 0.03, 0.03, 0.03);
		}

		if (System.currentTimeMillis() - this.lastSoundTime >= TWISTER_SOUND_INTERVAL) {
			playAirbendingSound(this.currentLoc);
			this.lastSoundTime = System.currentTimeMillis();
		}
	}

	private void renderAirBlastTrail(final Location start, final Vector direction, final int segments, final double spacing) {
		for (int i = 0; i < segments; i++) {
			final Location trail = start.clone().subtract(direction.clone().multiply(i * spacing));
			playAirbendingParticles(trail, i == 0 ? 2 : 1, 0.015, 0.015, 0.015);
		}
	}

	private void absorbAirControllerPushes() {
		this.absorbAirBlastPushes();
		this.absorbAirSuctionPushes();
	}

	private void absorbAirBlastPushes() {
		for (final AirBlast airBlast : getAbilities(AirBlast.class)) {
			if (this.handledBlasts.contains(airBlast) || !airBlast.isProgressing() || airBlast.getBendingPlayer() != bPlayer) {
				continue;
			}

			if (!this.isWithinAirControllerHitbox(airBlast.getLocation(), airBlast.getRadius())) {
				continue;
			}

			final Vector push = airBlast.getDirection();
			if (push == null) {
				continue;
			}

			final Vector horizontalPush = push.clone();
			horizontalPush.setY(0);
			if (horizontalPush.lengthSquared() == 0) {
				continue;
			}

			this.applyPush(horizontalPush.normalize(), airBlast.getSpeed());
			this.handledBlasts.add(airBlast);
		}
	}

	private void absorbAirSuctionPushes() {
		for (final AirSuction airSuction : getAbilities(AirSuction.class)) {
			if (this.handledSuctions.contains(airSuction) || !airSuction.isProgressing() || airSuction.getBendingPlayer() != bPlayer) {
				continue;
			}

			if (!this.isWithinAirControllerHitbox(airSuction.getLocation(), airSuction.getRadius())) {
				continue;
			}

			final Vector push = airSuction.getDirection();
			if (push == null) {
				continue;
			}

			final Vector horizontalPush = push.clone();
			horizontalPush.setY(0);
			if (horizontalPush.lengthSquared() == 0) {
				continue;
			}

			this.applyPush(horizontalPush.normalize(), airSuction.getSpeed());
			this.handledSuctions.add(airSuction);
		}
	}

	private boolean isWithinAirControllerHitbox(final Location abilityLocation, final double abilityRadius) {
		if (abilityLocation == null || abilityLocation.getWorld() != this.currentLoc.getWorld()) {
			return false;
		}

		final Location collisionCenter = this.currentLoc.clone().add(0, Math.min(1.0, this.twisterHeight * 0.18), 0);
		final double dx = abilityLocation.getX() - collisionCenter.getX();
		final double dz = abilityLocation.getZ() - collisionCenter.getZ();
		final double horizontalDistanceSq = (dx * dx) + (dz * dz);
		final double maxHorizontalDistance = Math.max(1.0, this.twisterRadius * 0.55 + abilityRadius);
		if (horizontalDistanceSq > maxHorizontalDistance * maxHorizontalDistance) {
			return false;
		}

		final double verticalDistance = Math.abs(abilityLocation.getY() - collisionCenter.getY());
		final double maxVerticalDistance = Math.max(1.0, Math.min(2.0, this.twisterHeight * 0.3));
		return verticalDistance <= maxVerticalDistance;
	}

	private void applyPush(final Vector pushDirection, final double airBlastSpeed) {
		final double baseSpeed = Math.max(0.12, this.speed);
		final double maxSpeed = Math.max(baseSpeed, this.speed * 1.35);
		final double blastFactor = Math.max(1.0, airBlastSpeed);
		final double pushStrength = Math.max(baseSpeed * 0.85, Math.min(maxSpeed, baseSpeed * blastFactor));
		final double retainedMomentum = Math.max(0.25, Math.min(0.72, 0.35 + (this.speed * 0.55)));

		if (this.motion.lengthSquared() == 0) {
			this.motion = pushDirection.clone().multiply(pushStrength);
		} else {
			this.motion = this.motion.clone().multiply(retainedMomentum).add(pushDirection.clone().multiply(pushStrength));
			if (this.motion.lengthSquared() > maxSpeed * maxSpeed) {
				this.motion.normalize().multiply(maxSpeed);
			}
		}

		this.direction = pushDirection.clone();
		this.state = AbilityState.TWISTER_MOVING;
	}

	private void moveTwister() {
		if (this.motion.lengthSquared() == 0) {
			this.state = AbilityState.TWISTER_STATIONARY;
			return;
		}

		final Vector direction = this.motion.clone().normalize();
		double remaining = this.motion.length();

		while (remaining > 0) {
			final double segment = Math.min(0.25, remaining);
			if (!this.advanceTwister(direction, segment)) {
				this.motion.zero();
				this.state = AbilityState.TWISTER_STATIONARY;
				return;
			}

			this.distanceTravelled += segment;
			if (this.distanceTravelled >= this.range) {
				this.remove();
				return;
			}

			remaining -= segment;
		}

		final double drag = Math.max(0.88, Math.min(0.97, 0.91 + (this.speed * 0.08)));
		this.motion.multiply(drag);
		if (this.motion.lengthSquared() < 0.0025) {
			this.motion.zero();
			this.state = AbilityState.TWISTER_STATIONARY;
		} else {
			this.direction = this.motion.clone().normalize();
			this.state = AbilityState.TWISTER_MOVING;
		}
	}

	private boolean advanceTwister(final Vector direction, final double distance) {
		if (GeneralMethods.checkDiagonalWall(this.currentLoc.clone().add(0, 0.5, 0), direction)) {
			return false;
		}

		final Location next = this.currentLoc.clone().add(direction.clone().multiply(distance));
		final Location grounded = this.getGroundedTwisterLocation(next);
		if (grounded == null) {
			return false;
		}

		this.currentLoc = grounded;
		return true;
	}

	private boolean isTwisterSpaceClear(final Location location) {
		final Block feet = location.getBlock();
		final Block body = location.clone().add(0, 1, 0).getBlock();
		return this.isTwisterPassable(feet) && this.isTwisterPassable(body);
	}

	private boolean isTwisterPassable(final Block block) {
		return block.isPassable() && !block.isLiquid();
	}

	private Vector getVisualDirection() {
		if (this.direction != null && this.direction.lengthSquared() > 0) {
			return this.direction.clone().normalize();
		}
		return new Vector(1, 0, 0);
	}

	private void pullEntitiesInsideTwister() {
		this.pulledEntitiesThisTick.clear();
		this.lagCompensator.addSnapshot(this.getLagCompensationCollider());

		final Location pullCenter = this.currentLoc.clone().add(0, this.twisterHeight / 2.0, 0);
		final double searchRadius = this.pullZoneRadius + 0.75;

		for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(pullCenter, searchRadius)) {
			if (entity.equals(this.player)) {
				continue;
			} else if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
				continue;
			} else if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
				continue;
			}

			if (entity instanceof Player) {
				this.lagCompensator.addPlayer((Player) entity);
				continue;
			}

			this.pullEntity(entity, this.currentLoc);
		}

		this.lagCompensator.update();
		this.pullCaughtEntities();
	}

	private AABB getLagCompensationCollider() {
		final Location center = this.currentLoc.clone().add(0, this.twisterHeight / 2.0, 0);
		return new AABB(center, this.pullZoneRadius, this.twisterHeight / 2.0 + 0.5);
	}

	private void pullEntity(final Entity entity, final Location twisterLocation) {
		if (!this.isInPullZone(entity, twisterLocation)) {
			return;
		}

		if (this.exhaustedPullEntities.contains(entity.getUniqueId())) {
			return;
		}

		this.caughtEntities.put(entity.getUniqueId(), entity);
		this.pulledEntitiesThisTick.add(entity.getUniqueId());

		final long now = System.currentTimeMillis();
		final long pullStart = this.pullStartTimes.computeIfAbsent(entity.getUniqueId(), uuid -> now);
		if (this.maxPullDuration > 0 && now - pullStart >= this.maxPullDuration) {
			if (this.exhaustedPullEntities.add(entity.getUniqueId())) {
				this.releaseEntity(entity, twisterLocation);
			}
			this.caughtEntities.remove(entity.getUniqueId());
			return;
		}

		this.applyPullToEntity(entity, twisterLocation);
	}

	private void pullCaughtEntities() {
		final ArrayList<UUID> toRemove = new ArrayList<>();

		for (final Map.Entry<UUID, Entity> entry : this.caughtEntities.entrySet()) {
			final UUID uuid = entry.getKey();
			if (this.pulledEntitiesThisTick.contains(uuid) || this.exhaustedPullEntities.contains(uuid)) {
				continue;
			}

			final Entity entity = entry.getValue();
			if (!this.shouldKeepCaughtEntity(entity)) {
				toRemove.add(uuid);
				continue;
			}

			final long pullStart = this.pullStartTimes.computeIfAbsent(uuid, ignored -> System.currentTimeMillis());
			if (this.maxPullDuration > 0 && System.currentTimeMillis() - pullStart >= this.maxPullDuration) {
				if (this.exhaustedPullEntities.add(uuid)) {
					this.releaseEntity(entity, this.currentLoc);
				}
				toRemove.add(uuid);
				continue;
			}

			this.pulledEntitiesThisTick.add(uuid);
			this.applyPullToEntity(entity, this.currentLoc);
		}

		for (final UUID uuid : toRemove) {
			this.caughtEntities.remove(uuid);
		}
	}

	private boolean shouldKeepCaughtEntity(final Entity entity) {
		if (entity == null || !entity.isValid() || entity.getWorld() != this.currentLoc.getWorld()) {
			return false;
		}
		if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
			return false;
		}
		if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
			return false;
		}

		final Location entityLoc = entity.getLocation();
		final double relativeY = entityLoc.getY() - this.currentLoc.getY();
		if (relativeY < -4.0 || relativeY > this.twisterHeight + 3.0) {
			return false;
		}

		final double dx = entityLoc.getX() - this.currentLoc.getX();
		final double dz = entityLoc.getZ() - this.currentLoc.getZ();
		final double horizontalLimit = this.pullZoneRadius + 2.0;
		return (dx * dx) + (dz * dz) <= horizontalLimit * horizontalLimit;
	}

	private void applyPullToEntity(final Entity entity, final Location twisterLocation) {
		final double pullStrength = Math.max(0.1, this.pullVelocity);
		final Location target = twisterLocation.clone().add(0, Math.max(1.8, this.twisterHeight * 0.45), 0);
		final Vector velocity = GeneralMethods.getDirection(entity.getLocation(), target).normalize().multiply(pullStrength);
		velocity.setY(Math.max(Math.min(1.2, pullStrength * 0.75), velocity.getY()));

		GeneralMethods.setVelocity(this, entity, velocity);
		entity.setFallDistance(0F);

		if (this.damage > 0 && entity instanceof LivingEntity && this.canDamage(entity)) {
			DamageHandler.damageEntity(entity, this.damage, this);
		}

		if (entity instanceof Player) {
			final Player player = (Player) entity;
			this.lockTrappedPlayerAbilities(player);
			if (this.spinPlayers) {
				this.spinPlayer(player);
			}
		}
	}

	private void releaseEntity(final Entity entity, final Location twisterLocation) {
		final Vector release = entity.getLocation().toVector().subtract(twisterLocation.toVector());
		release.setY(0);
		if (release.lengthSquared() == 0) {
			release.copy(this.getVisualDirection());
		} else {
			release.normalize();
		}

		release.multiply(Math.max(0.25, this.pullVelocity * 1.1));
		release.setY(Math.max(0.12, entity.getVelocity().getY() * 0.35));
		GeneralMethods.setVelocity(this, entity, release);
	}

	private boolean canDamage(final Entity entity) {
		final long now = System.currentTimeMillis();
		final Long lastDamageTime = this.lastDamageTimes.get(entity.getUniqueId());
		if (lastDamageTime != null && now - lastDamageTime < this.damageInterval) {
			return false;
		}

		this.lastDamageTimes.put(entity.getUniqueId(), now);
		return true;
	}

	private void spinPlayer(final Player player) {
		final float spinAmount = (float) Math.max(8.0, Math.min(45.0, this.speed * 90.0));
		player.setRotation(player.getLocation().getYaw() + spinAmount, player.getLocation().getPitch());
	}

	private void lockTrappedPlayerAbilities(final Player player) {
		if (this.trappedAbilityCooldown <= 0) {
			return;
		}

		final long now = System.currentTimeMillis();
		final long refreshInterval = Math.max(250L, this.trappedAbilityCooldown / 2L);
		final Long lastRestricted = this.lastRestrictedTimes.get(player.getUniqueId());
		if (lastRestricted != null && now - lastRestricted < refreshInterval) {
			return;
		}

		this.lastRestrictedTimes.put(player.getUniqueId(), now);

		final AirScooter scooter = CoreAbility.getAbility(player, AirScooter.class);
		if (scooter != null) {
			scooter.remove();
		}

		final AirSpout spout = CoreAbility.getAbility(player, AirSpout.class);
		if (spout != null) {
			spout.remove();
		}

		final AirSurf surf = CoreAbility.getAbility(player, AirSurf.class);
		if (surf != null) {
			surf.remove();
		}

		final BendingPlayer targetBPlayer = BendingPlayer.getBendingPlayer(player);
		if (targetBPlayer == null) {
			return;
		}

		for (final String abilityName : TRAPPED_PLAYER_ABILITIES) {
			targetBPlayer.addCooldown(abilityName, this.trappedAbilityCooldown);
		}
	}

	private boolean isInPullZone(final Entity entity, final Location twisterLocation) {
		if (!entity.getWorld().equals(twisterLocation.getWorld())) {
			return false;
		}

		final Location entityLoc = entity.getLocation();
		final double relativeY = entityLoc.getY() - twisterLocation.getY();
		if (relativeY < -3.0 || relativeY > this.twisterHeight + 1.5) {
			return false;
		}

		final double dx = entityLoc.getX() - twisterLocation.getX();
		final double dz = entityLoc.getZ() - twisterLocation.getZ();
		final double allowedRadius = this.pullZoneRadius;
		return (dx * dx) + (dz * dz) <= allowedRadius * allowedRadius;
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
		return this.cooldown;
	}

	public void setCooldown(final long cooldown) {
		this.cooldown = cooldown;
	}

	@Override
	public Location getLocation() {
		if (this.currentLoc != null) {
			return this.currentLoc;
		} else if (this.origin != null) {
			return this.origin;
		}
		return this.player != null ? this.player.getLocation() : null;
	}

	public void setLocation(final Location location) {
		this.origin = location;
	}

	@Override
	public Object createNewComboInstance(final Player player) {
		return new Twister(player);
	}

	@Override
	public ArrayList<AbilityInformation> getCombination() {
		return ComboUtil.generateCombinationFromList(this, ConfigManager.defaultConfig.get().getStringList("Abilities.Air.Twister.Combination"));
	}
}
