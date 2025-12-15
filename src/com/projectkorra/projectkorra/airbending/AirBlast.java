package com.projectkorra.projectkorra.airbending;

import java.util.*;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.AbilityLagCompensator;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.earthbending.lava.LavaFlow;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;

public class AirBlast extends AirAbility {

	private static final int MAX_TICKS = 10000;
	public static final Material[] DOORS = { Material.ACACIA_DOOR, Material.BIRCH_DOOR, Material.DARK_OAK_DOOR, Material.JUNGLE_DOOR, Material.OAK_DOOR, Material.SPRUCE_DOOR };
	public static final Material[] TDOORS = { Material.ACACIA_TRAPDOOR, Material.BIRCH_TRAPDOOR, Material.DARK_OAK_TRAPDOOR, Material.JUNGLE_TRAPDOOR, Material.OAK_TRAPDOOR, Material.SPRUCE_TRAPDOOR };
	public static final Material[] BUTTONS = { Material.ACACIA_BUTTON, Material.BIRCH_BUTTON, Material.DARK_OAK_BUTTON, Material.JUNGLE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON, Material.STONE_BUTTON };

	private boolean canFlickLevers;
	private boolean canOpenDoors;
	private boolean canPressButtons;
	private boolean canCoolLava;
	private boolean isFromOtherOrigin;
	private boolean showParticles;
	private int ticks;
	private int particles;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	private double speedFactor;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.KNOCKBACK)
	private double pushFactor;
	@Attribute(Attribute.KNOCKBACK + "Others")
	private double pushFactorForOthers;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(Attribute.SPEED)
	private double speed;
	@Attribute(Attribute.RADIUS)
	private double radius;

	private double decayAmount;
	private double decayMinimum;
	private long minimumAirBlastTime;
	private double slideSpeed;
	private boolean staminaSliding;
	private long scooterBlastCD;
	private long scooterThreshold;

	private boolean progressing;
	private Location location;
	private Location origin;
	private Vector direction;
	private AirBurst source;
	private Random random;
	private ArrayList<Block> affectedLevers;
	private ArrayList<Entity> affectedEntities;

	private AbilityLagCompensator lagCompensator;

	public AirBlast(final Player player) {
		super(player);

		Collection<AirBlast> blasts = getAbilities(player, AirBlast.class);
		for (AirBlast blast : blasts) {
			if (blast.getSource() != null || blast.isProgressing()) {
				continue;
			}

			blast.selectOrigin();
			return;
		}

		this.setFields();

		selectOrigin();
		this.start();
	}

	public AirBlast(final Player player, final Location location, final Vector direction, final double modifiedPushFactor, final AirBurst burst) {
		super(player);
		if (location.getBlock().isLiquid()) {
			return;
		}
		this.source = burst;
		this.origin = location.clone();
		this.direction = direction.clone();
		this.location = location.clone();

		this.setFields();

		this.progressing = true;
		this.affectedLevers = new ArrayList<>();
		this.affectedEntities = new ArrayList<>();

		// prevent the airburst related airblasts from triggering doors/levers/buttons.
		this.canOpenDoors = false;
		this.canPressButtons = false;
		this.canFlickLevers = false;

		if (this.bPlayer.isAvatarState()) {
			this.pushFactor = getConfig().getDouble("Abilities.Avatar.AvatarState.Air.AirBlast.Push.Self");
			this.pushFactorForOthers = getConfig().getDouble("Abilities.Avatar.AvatarState.Air.AirBlast.Push.Entities");
		}

		this.pushFactor = modifiedPushFactor;

		this.lagCompensator = new AbilityLagCompensator((p, snapshot) -> affect(p, snapshot.getLocation()));
		this.start();
	}

	private void setFields() {
		this.particles = getConfig().getInt("Abilities.Air.AirBlast.Particles");
		this.cooldown = getConfig().getLong("Abilities.Air.AirBlast.Cooldown");
		this.range = getConfig().getDouble("Abilities.Air.AirBlast.Range");
		this.speed = getConfig().getDouble("Abilities.Air.AirBlast.Speed");
		this.range = getConfig().getDouble("Abilities.Air.AirBlast.Range");
		this.radius = getConfig().getDouble("Abilities.Air.AirBlast.Radius");
		this.pushFactor = getConfig().getDouble("Abilities.Air.AirBlast.Push.Self");
		this.pushFactorForOthers = getConfig().getDouble("Abilities.Air.AirBlast.Push.Entities");
		this.canFlickLevers = getConfig().getBoolean("Abilities.Air.AirBlast.CanFlickLevers");
		this.canOpenDoors = getConfig().getBoolean("Abilities.Air.AirBlast.CanOpenDoors");
		this.canPressButtons = getConfig().getBoolean("Abilities.Air.AirBlast.CanPressButtons");
		this.canCoolLava = getConfig().getBoolean("Abilities.Air.AirBlast.CanCoolLava");
		this.decayAmount = getConfig().getDouble("Abilities.Air.AirBlast.DecayAmount");
		this.decayMinimum = getConfig().getDouble("Abilities.Air.AirBlast.DecayMinimum");
		this.minimumAirBlastTime = getConfig().getLong("Abilities.Air.AirBlast.MinimumAirBlastTime");
		this.slideSpeed = getConfig().getDouble("Abilities.Air.AirBlast.SlidingFactor", 0.6);
		this.staminaSliding = getConfig().getBoolean("Abilities.Air.AirBlast.StaminaSliding", true);
		this.scooterBlastCD = getConfig().getLong("Abilities.Air.AirBlast.ScooterBlastCD", 1000L);
		this.scooterThreshold = getConfig().getLong("Abilities.Air.AirBlast.ScooterThreshold", 500L);

		this.isFromOtherOrigin = false;
		this.showParticles = true;
		this.random = new Random();
		this.affectedLevers = new ArrayList<>();
		this.affectedEntities = new ArrayList<>();
	}

	public void selectOrigin() {
		double maxSelectGroundDistance = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.MaxSelectGroundDistance");
		origin = getTargetedLocation(player, getSelectRange(bPlayer), getTransparentMaterials());
		if (origin.getBlock().isLiquid() || GeneralMethods.isSolid(origin.getBlock())) {
			return;
		} else if (RegionProtection.isRegionProtected(player, origin, "AirBlast")) {
			return;
		} else if (maxSelectGroundDistance != 0 && GeneralMethods.getGroundBlock(origin, maxSelectGroundDistance) == null) {
			return;
		}

		if (GeneralMethods.isSolid(origin.getBlock().getRelative(BlockFace.DOWN))) {
			double y = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.SourceYOffset");
			origin.add(0, y, 0);
		}
	}

	private void advanceLocation() {
		if (this.showParticles) {
			playAirbendingParticles(this.location, this.particles, 0.275F, 0.275F, 0.275F);
		}
		if (this.random.nextInt(4) == 0) {
			final double percentage = bPlayer.getAirBlastDecay();
			final float pitch = (float) (0.9 * percentage);
			playAirbendingSound(this.location, pitch);
		}

		BlockIterator blocks = new BlockIterator(this.getLocation().getWorld(), this.location.toVector(), this.direction, 0, (int) Math.ceil(this.direction.clone().multiply(speedFactor).length()));

		while (blocks.hasNext() && checkLocation(blocks.next()));
		
		this.location.add(this.direction.clone().multiply(speedFactor));
	}

	public boolean checkLocation(Block block) {
		if (GeneralMethods.checkDiagonalWall(block.getLocation(), this.direction)) {
			this.remove();
			return false;
		}

		if ((!block.isPassable() || block.isLiquid()) && !this.affectedLevers.contains(block)) {
			if (block.getType() == Material.LAVA && this.canCoolLava) {
				if (LavaFlow.isLavaFlowBlock(block)) {
					LavaFlow.removeBlock(block); // TODO: Make more generic for future lava generating moves.
				} else if (block.getBlockData() instanceof Levelled && ((Levelled) block.getBlockData()).getLevel() == 0) {
					new TempBlock(block, Material.OBSIDIAN);
				} else {
					new TempBlock(block, Material.COBBLESTONE);
				}
			}
			this.remove();
			return false;
		}
		
		return true;
	}

	private void affect(final Entity entity) {
		affect(entity, this.location);
	}

	private void affect(final Entity entity, final Location location) {
		if (entity instanceof Player) {
			if (Commands.invincible.contains(((Player) entity).getName())) {
				return;
			}
		}
			
		final boolean isUser = entity.getUniqueId() == this.player.getUniqueId();
		double knockback = this.pushFactorForOthers;

		if (isUser) {
			if (isFromOtherOrigin) {
				knockback = this.pushFactor;
			} else {
				return;
			}
		}

		if (source != null) knockback = this.pushFactor;

		if (knockback == 0) return;

		Vector velocity = entity.getVelocity();
		final double max = 1.0 / pushFactorForOthers;

		final Vector push = this.direction.clone();
		if (Math.abs(push.getY()) > max && !isUser) {
			if (push.getY() < 0) {
				push.setY(-max);
			} else {
				push.setY(max);
			}
		}

		if (location.getWorld().equals(this.origin.getWorld())) {
			knockback *= 1 - location.distance(this.origin) / (2 * this.range);
		}
		
		if (GeneralMethods.isSolid(entity.getLocation().add(0, -0.5, 0).getBlock()) && source == null) {
			//change to .5 from .85

			double speed = 1 - bPlayer.getAirBlastDecay();
			if (staminaSliding)
				speed = 0;
			knockback *= slideSpeed + speed;
		}

		//add double comp and calculations & change factor to knockback
		double comp = velocity.dot(push.clone().normalize());
		if (comp > knockback) {
			velocity.multiply(.5);
			velocity.add(push.clone().normalize().multiply(velocity.clone().dot(push.clone().normalize())));
		} else if (comp + knockback * .5 > knockback) {
			velocity.add(push.clone().multiply(knockback - comp));
		} else {
			velocity.add(push.clone().multiply(knockback * .5));
		}
		
//		push.normalize().multiply(knockback);
//
//		if (Math.abs(entity.getVelocity().dot(push)) > knockback && entity.getVelocity().angle(push) > Math.PI / 3) {
//			push.normalize().add(entity.getVelocity()).multiply(knockback);
//		}
		GeneralMethods.setVelocity(this, entity, velocity);
		
		if (this.source != null) {
			new HorizontalVelocityTracker(entity, this.player, 200l, this.source);
		} else {
			new HorizontalVelocityTracker(entity, this.player, 200l, this);
		}

		if (this.damage > 0 && entity instanceof LivingEntity && !entity.equals(this.player) && !this.affectedEntities.contains(entity)) {
			if (this.source != null) {
				DamageHandler.damageEntity(entity, this.damage, this.source);
			} else {
				DamageHandler.damageEntity(entity, this.damage, this);
			}
			
			this.affectedEntities.add(entity);
		}

		if (entity.getFireTicks() > 0) {
			entity.getWorld().playEffect(entity.getLocation(), Effect.EXTINGUISH, 0);
		}

		entity.setFireTicks(0);
		breakBreathbendingHold(entity);
	}

	@Override
	public void progress() {
		if (this.player.isDead() || !this.player.isOnline()) {
			this.remove();
			return;
		} else if (RegionProtection.isRegionProtected(this, this.location)) {
			this.remove();
			return;
		}

		if (!progressing) {
			if (!origin.getWorld().equals(player.getWorld())) {
				this.remove();
				return;
			} else if (!bPlayer.canBendIgnoreCooldowns(getAbility("AirBlast"))) {
				this.remove();
				return;
			} else if (origin.distanceSquared(player.getEyeLocation()) > getSelectRange(bPlayer) * getSelectRange(bPlayer)) {
				this.remove();
				return;
			}

			playAirbendingParticles(bPlayer, origin, getSelectParticles(bPlayer));
			return;
		}

		this.speedFactor = this.speed * 0.05;
		this.ticks++;

		if (this.ticks > MAX_TICKS) {
			this.remove();
			return;
		}

		final Block block = this.location.getBlock();

		for (final Block testblock : GeneralMethods.getBlocksAroundPoint(this.location, this.radius)) {
			if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
				continue;
			} else if (FireAbility.isFire(testblock.getType())) {
				testblock.setType(Material.AIR);
				testblock.getWorld().playEffect(testblock.getLocation(), Effect.EXTINGUISH, 0);
				continue;
			} else if (this.affectedLevers.contains(testblock)) {
				continue;
			}

			if (Arrays.asList(DOORS).contains(testblock.getType())) {
				if (testblock.getBlockData() instanceof Door) {
					final Door door = (Door) testblock.getBlockData();
					final BlockFace face = door.getFacing();
					final Vector toPlayer = GeneralMethods.getDirection(block.getLocation(), this.player.getLocation().getBlock().getLocation());
					final double[] dims = { toPlayer.getX(), toPlayer.getY(), toPlayer.getZ() };

					for (int i = 0; i < 3; i++) {
						if (i == 1) {
							continue;
						}

						final BlockFace bf = GeneralMethods.getBlockFaceFromValue(i, dims[i]);

						if (bf == face) {
							if (!door.isOpen()) {
								this.remove();
								return;
							}
						} else if (bf.getOppositeFace() == face) {
							if (door.isOpen()) {
								this.remove();
								return;
							}
						}
					}

					door.setOpen(!door.isOpen());
					testblock.setBlockData(door);
					testblock.getWorld().playSound(testblock.getLocation(), Sound.valueOf("BLOCK_WOODEN_DOOR_" + (door.isOpen() ? "OPEN" : "CLOSE")), 0.5f, 0);
					this.affectedLevers.add(testblock);
				}
			} else if (Arrays.asList(TDOORS).contains(testblock.getType())) {
				if (testblock.getBlockData() instanceof TrapDoor) {
					final TrapDoor tDoor = (TrapDoor) testblock.getBlockData();

					if (this.origin.getY() < block.getY()) {
						if (!tDoor.isOpen()) {
							this.remove();
							return;
						}
					} else {
						if (tDoor.isOpen()) {
							this.remove();
							return;
						}
					}

					tDoor.setOpen(!tDoor.isOpen());
					testblock.setBlockData(tDoor);
					testblock.getWorld().playSound(testblock.getLocation(), Sound.valueOf("BLOCK_WOODEN_TRAPDOOR_" + (tDoor.isOpen() ? "OPEN" : "CLOSE")), 0.5f, 0);
				}
			} else if (Arrays.asList(BUTTONS).contains(testblock.getType())) {
				if (testblock.getBlockData() instanceof Switch) {
					final Switch button = (Switch) testblock.getBlockData();
					if (!button.isPowered()) {
						button.setPowered(true);
						testblock.setBlockData(button);
						this.affectedLevers.add(testblock);

						new BukkitRunnable() {

							@Override
							public void run() {
								button.setPowered(false);
								testblock.setBlockData(button);
								AirBlast.this.affectedLevers.remove(testblock);
								testblock.getWorld().playSound(testblock.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_OFF, 0.5f, 0);
							}

						}.runTaskLater(ProjectKorra.plugin, 15);
					}

					testblock.getWorld().playSound(testblock.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.5f, 0);
				}
			} else if (testblock.getType() == Material.LEVER) {
				if (testblock.getBlockData() instanceof Switch) {
					final Switch lever = (Switch) testblock.getBlockData();
					lever.setPowered(!lever.isPowered());
					testblock.setBlockData(lever);
					this.affectedLevers.add(testblock);
					testblock.getWorld().playSound(testblock.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 0);
				}
			} else if (testblock.getType().toString().contains("CANDLE") || testblock.getType().toString().contains("CAMPFIRE") || testblock.getType() == Material.REDSTONE_WALL_TORCH) {
				if (testblock.getBlockData() instanceof Lightable) {
					final Lightable lightable = (Lightable) testblock.getBlockData();
					if (lightable.isLit()) {
						lightable.setLit(false);
						testblock.setBlockData(lightable);
						testblock.getWorld().playEffect(testblock.getLocation(), Effect.EXTINGUISH, 0);
					}
				}
			}
		}

		/*
		 * If a player presses shift and AirBlasts straight down then the
		 * AirBlast's location gets messed up and reading the distance returns
		 * Double.NaN. If we don't remove this instance then the AirBlast will
		 * never be removed.
		 */
		double dist = 0;
		if (this.location.getWorld().equals(this.origin.getWorld())) {
			dist = this.location.distance(this.origin);
		}
		if (Double.isNaN(dist) || dist > this.range) {
			this.remove();
			return;
		}

		this.lagCompensator.addSnapshot(this.location, this.radius);

		for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.radius)) {
			if (RegionProtection.isRegionProtected(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
				continue;
			}

			if (entity instanceof Player) {
				this.lagCompensator.addPlayer((Player) entity);
				continue;
			}

			this.affect(entity);

		}

		this.lagCompensator.update();

		this.advanceLocation();
		return;
	}

	public void shoot() {
		if (this.bPlayer.isOnCooldown(this)) {
			if (!isFromOtherOrigin) remove();
			return;
		} else if (player.getEyeLocation().getBlock().isLiquid()) {
			if (!isFromOtherOrigin) remove();
			return;
		}

		Location targetedLocation = getTargetedLocation(player, this.range);
		Block block = targetedLocation.getBlock();
		if (!GeneralMethods.isSolid(block) && GeneralMethods.isSolid(block.getRelative(BlockFace.DOWN))) {
			double y = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.SourceYOffset");
			targetedLocation.add(0, y, 0);
		}

		this.direction = GeneralMethods.getDirection(origin, targetedLocation).normalize();

		if (isFromOtherOrigin) {
			if (System.currentTimeMillis() - bPlayer.getLastScooterUse() < scooterThreshold) {
				bPlayer.addCooldown("AirScooter", scooterBlastCD);
				bPlayer.addCooldown("AirSurf", scooterBlastCD);
			}

			if (System.currentTimeMillis() - bPlayer.getLastAirBlastTime() < minimumAirBlastTime) {
				bPlayer.increaseAirBlastDecay(decayAmount, decayMinimum);
			}

			bPlayer.resetAirBlast();
			this.pushFactor *= bPlayer.getAirBlastDecay();
		}

		if(!Double.isFinite(this.direction.getX()) || !Double.isFinite(this.direction.getY()) || !Double.isFinite(this.direction.getZ())) {
			if (!isFromOtherOrigin) remove();
			return;
		}

		this.location = this.origin.clone();
		this.progressing = true;
		this.bPlayer.addCooldown(this);
		this.lagCompensator = new AbilityLagCompensator((p, snapshot) -> affect(p, snapshot.getLocation()));
	}

	public static void shoot(final Player player) {
		for (AirBlast airBlast : CoreAbility.getAbilities(player, AirBlast.class)) {
			if (airBlast.getSource() != null || airBlast.isProgressing()) {
				continue;
			}

			airBlast.setFromOtherOrigin(true);
			airBlast.shoot();
			return;
		}

		AirBlast blast = new AirBlast(player);
		blast.setOrigin(player.getEyeLocation());
		blast.shoot();
	}

	/**
	 * This method was used for the old collision detection system. Please see
	 * {@link Collision} for the new system.
	 */
	@Deprecated
	public static boolean removeAirBlastsAroundPoint(final Location location, final double radius) {
		boolean removed = false;
		for (final AirBlast airBlast : getAbilities(AirBlast.class)) {
			final Location airBlastlocation = airBlast.location;
			if (location.getWorld() == airBlastlocation.getWorld()) {
				if (location.distanceSquared(airBlastlocation) <= radius * radius) {
					airBlast.remove();
				}
				removed = true;
			}
		}
		return removed;
	}

	public static Location getTargetedLocation(final Player player, final double range, final Material... nonOpaque2) {
		final Location origin = player.getEyeLocation();
		final Vector direction = origin.getDirection();

		final HashSet<Material> trans = new HashSet<>();
		trans.add(Material.AIR);
		trans.add(Material.CAVE_AIR);
		trans.add(Material.VOID_AIR);

		if (nonOpaque2 != null) {
			Collections.addAll(trans, nonOpaque2);
		}

		final Block block = player.getTargetBlock(trans, (int) range + 1);
		double distance = block.getLocation().distance(origin) - 1.5;
		return origin.add(direction.multiply(distance > range ? range - 0.1 : distance));
	}

	@Override
	public String getName() {
		return "AirBlast";
	}

	@Override
	public Location getLocation() {
		return this.location;
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
	public double getCollisionRadius() {
		return this.getRadius();
	}

	public boolean isProgressing() {
		return progressing;
	}

	public void setProgressing(final boolean progressing) {
		this.progressing = progressing;
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

	public int getTicks() {
		return this.ticks;
	}

	public void setTicks(final int ticks) {
		this.ticks = ticks;
	}

	public double getSpeedFactor() {
		return this.speedFactor;
	}

	public void setSpeedFactor(final double speedFactor) {
		this.speedFactor = speedFactor;
	}

	public double getRange() {
		return this.range;
	}

	public void setRange(final double range) {
		this.range = range;
	}

	public double getPushFactor() {
		return this.pushFactor;
	}

	public void setPushFactor(final double pushFactor) {
		this.pushFactor = pushFactor;
	}

	public double getPushFactorForOthers() {
		return this.pushFactorForOthers;
	}

	public void setPushFactorForOthers(final double pushFactorForOthers) {
		this.pushFactorForOthers = pushFactorForOthers;
	}

	public double getDamage() {
		return this.damage;
	}

	public void setDamage(final double damage) {
		this.damage = damage;
	}

	public double getSpeed() {
		return this.speed;
	}

	public void setSpeed(final double speed) {
		this.speed = speed;
	}

	public double getRadius() {
		return this.radius;
	}

	public void setRadius(final double radius) {
		this.radius = radius;
	}

	public boolean isCanFlickLevers() {
		return this.canFlickLevers;
	}

	public void setCanFlickLevers(final boolean canFlickLevers) {
		this.canFlickLevers = canFlickLevers;
	}

	public boolean isCanOpenDoors() {
		return this.canOpenDoors;
	}

	public void setCanOpenDoors(final boolean canOpenDoors) {
		this.canOpenDoors = canOpenDoors;
	}

	public boolean isCanPressButtons() {
		return this.canPressButtons;
	}

	public void setCanPressButtons(final boolean canPressButtons) {
		this.canPressButtons = canPressButtons;
	}

	public boolean isCanCoolLava() {
		return this.canCoolLava;
	}

	public void setCanCoolLava(final boolean canCoolLava) {
		this.canCoolLava = canCoolLava;
	}

	public boolean isFromOtherOrigin() {
		return this.isFromOtherOrigin;
	}

	public void setFromOtherOrigin(final boolean isFromOtherOrigin) {
		this.isFromOtherOrigin = isFromOtherOrigin;
	}

	public boolean isShowParticles() {
		return this.showParticles;
	}

	public void setShowParticles(final boolean showParticles) {
		this.showParticles = showParticles;
	}

	public AirBurst getSource() {
		return this.source;
	}

	public void setSource(final AirBurst source) {
		this.source = source;
	}

	public ArrayList<Block> getAffectedLevers() {
		return this.affectedLevers;
	}

	public ArrayList<Entity> getAffectedEntities() {
		return this.affectedEntities;
	}

	public void setLocation(final Location location) {
		this.location = location;
	}

	public void setCooldown(final long cooldown) {
		this.cooldown = cooldown;
	}

	public int getParticles() {
		return this.particles;
	}

	public void setParticles(final int particles) {
		this.particles = particles;
	}

	public static int getSelectParticles(BendingPlayer bPlayer) {
		return ConfigManager.getConfig(bPlayer).getInt("Abilities.Air.AirBlast.SelectParticles");
	}

	public static double getSelectRange(BendingPlayer bPlayer) {
		return ConfigManager.getConfig(bPlayer).getInt("Abilities.Air.AirBlast.SelectRange");
	}

}
