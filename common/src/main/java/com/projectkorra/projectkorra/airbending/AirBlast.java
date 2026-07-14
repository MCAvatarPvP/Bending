package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.lava.LavaFlow;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.Effect;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Lightable;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Door;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Switch;
import com.projectkorra.projectkorra.platform.mc.block.data.type.TrapDoor;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.BlockIterator;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.AbilityLagCompensator;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

public class AirBlast extends AirAbility {

    public static final Material[] DOORS = {Material.ACACIA_DOOR, Material.BIRCH_DOOR, Material.DARK_OAK_DOOR, Material.JUNGLE_DOOR, Material.OAK_DOOR, Material.SPRUCE_DOOR};
    public static final Material[] TDOORS = {Material.ACACIA_TRAPDOOR, Material.BIRCH_TRAPDOOR, Material.DARK_OAK_TRAPDOOR, Material.JUNGLE_TRAPDOOR, Material.OAK_TRAPDOOR, Material.SPRUCE_TRAPDOOR};
    public static final Material[] BUTTONS = {Material.ACACIA_BUTTON, Material.BIRCH_BUTTON, Material.DARK_OAK_BUTTON, Material.JUNGLE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON, Material.STONE_BUTTON};
    private static final int MAX_TICKS = 10000;
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
    private double speedStaminaScale;
    private double rangeStaminaScale;
    private long minimumAirBlastTime;
    private long slidingActivationDelay;
    private boolean requireSourceTouchingBlock;
    private double slideSpeed;
    private boolean staminaSliding;
    private boolean slidingConsumesStamina;
    private long scooterBlastCD;
    private long scooterThreshold;
    private boolean refundedThisSlide;
    private long lastWhirlSoundTime;

    private boolean progressing;
    private Location location;
    private Location origin;
    private Vector direction;
    private AirBurst source;
    private Random random;
    private ArrayList<Block> affectedLevers;
    private ArrayList<Entity> affectedEntities;
    private double preShootStamina;
    private boolean usedStaminaThisShot;
    private boolean pushed;

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
        if (this.origin == null) {
            return;
        }
        this.start();
    }

    public AirBlast(final Player player, final Location location, final Vector direction, final double modifiedPushFactor, final AirBurst burst) {
        super(player);
        if (location.getBlock().isLiquid()) {
            return;
        }
        this.source = burst;
        this.origin = location.clone();
        this.direction = this.getValidDirection(direction);
        if (this.direction == null) {
            return;
        }
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
     * Shared activation gate used by both authoritative servers and exact
     * client prediction. Keeping this beside {@link #shoot()} prevents a
     * prediction repair/fallback from bypassing the configured stamina floor.
     */
    public static boolean hasSufficientStamina(final BendingPlayer bPlayer) {
        return bPlayer != null && bPlayer.getAirBlastDecay()
                > ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.DecayMinimum");
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
        return GeneralMethods.getTargetedLocation(player, Math.max(0, range), nonOpaque2);
    }

    public static int getSelectParticles(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getInt("Abilities.Air.AirBlast.SelectParticles");
    }

    public static double getSelectRange(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.SelectRange");
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
        this.speedStaminaScale = getConfig().getDouble("Abilities.Air.AirBlast.SpeedStaminaScale", 1.0);
        this.rangeStaminaScale = getConfig().getDouble("Abilities.Air.AirBlast.RangeStaminaScale", 1.0);
        this.minimumAirBlastTime = getConfig().getLong("Abilities.Air.AirBlast.MinimumAirBlastTime");
        this.slidingActivationDelay = getConfig().getLong("Abilities.Air.AirBlast.SlidingActivationDelay", 200L);
        this.requireSourceTouchingBlock = getConfig().getBoolean("Abilities.Air.AirBlast.RequireSourceTouchingBlock");
        this.slideSpeed = getConfig().getDouble("Abilities.Air.AirBlast.SlidingFactor", 0.6);
        this.staminaSliding = getConfig().getBoolean("Abilities.Air.AirBlast.StaminaSliding", true);
        this.slidingConsumesStamina = getConfig().getBoolean("Abilities.Air.AirBlast.SlidingConsumesStamina", false);
        this.scooterBlastCD = getConfig().getLong("Abilities.Air.AirBlast.ScooterBlastCD", 1000L);
        this.scooterThreshold = getConfig().getLong("Abilities.Air.AirBlast.ScooterThreshold", 500L);

        this.isFromOtherOrigin = false;
        this.showParticles = true;
        this.random = new Random();
        this.affectedLevers = new ArrayList<>();
        this.affectedEntities = new ArrayList<>();
        this.preShootStamina = bPlayer.getAirBlastDecay();
        this.usedStaminaThisShot = false;
        this.lastWhirlSoundTime = 0L;
    }

    public void selectOrigin() {
        final double maxSelectGroundDistance = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.MaxSelectGroundDistance");
        final Location candidate = getTargetedLocation(player, getSelectRange(bPlayer), getTransparentMaterials());
        if (candidate.getBlock().isLiquid() || GeneralMethods.isSolid(candidate.getBlock())) {
            return;
        } else if (RegionProtection.isRegionProtected(player, candidate, "AirBlast")) {
            return;
        } else if (maxSelectGroundDistance != 0 && GeneralMethods.getGroundBlock(candidate, maxSelectGroundDistance) == null) {
            return;
        } else if (this.requireSourceTouchingBlock && !this.isTouchingSourceBlock(candidate)) {
            return;
        }

        if (GeneralMethods.isSolid(candidate.getBlock().getRelative(BlockFace.DOWN))) {
            double y = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.SourceYOffset");
            candidate.add(0, y, 0);
        }
        this.origin = candidate;
    }

    private boolean isTouchingSourceBlock(final Location location) {
        final Block block = location.getBlock();
        final BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

        for (final BlockFace face : faces) {
            if (GeneralMethods.isSolid(block.getRelative(face))) {
                return true;
            }
        }

        return false;
    }

    private void advanceLocation() {
        this.direction = this.getValidDirection(this.direction);
        if (this.direction == null) {
            this.remove();
            return;
        }

        if (this.showParticles) {
            playAirbendingParticles(this.location, this.particles, 0.275F, 0.275F, 0.275F);
        }
        if (this.random.nextInt(4) == 0) {
            final double percentage = bPlayer.getAirBlastDecay();
            final float pitch = (float) (0.9 * percentage);
            playAirbendingSound(this.location, pitch);
        }

        BlockIterator blocks = new BlockIterator(this.getLocation().getWorld(), this.location.toVector(), this.direction, 0, (int) Math.ceil(this.direction.clone().multiply(speedFactor).length()));

        while (blocks.hasNext() && checkLocation(blocks.next())) ;

        this.location.add(this.direction.clone().multiply(speedFactor));
    }

    public boolean checkLocation(Block block) {
        if (GeneralMethods.checkDiagonalWall(block.getLocation(), this.direction)) {
            this.remove();
            return false;
        }

        if ((!GeneralMethods.isPassable(block) || block.isLiquid()) && !this.affectedLevers.contains(block)) {
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

        final boolean isUser = entity.getUniqueId().equals(this.player.getUniqueId());
        double knockback = this.pushFactorForOthers;

        if (isUser) {
            if (this.isFromOtherOrigin) {
                knockback = this.pushFactor;
            } else {
                return;
            }
        }

        boolean sliding = GeneralMethods.isSolid(entity.getLocation().clone().add(0, -0.5, 0).getBlock()) && this.source == null;
        if (sliding && System.currentTimeMillis() - this.getStartTime() < this.slidingActivationDelay) {
            sliding = false;
        }

        final boolean triggerStamina = !sliding || this.slidingConsumesStamina;
        final boolean consumeStamina = triggerStamina && isUser && (this.usedStaminaThisShot || (sliding && this.slidingConsumesStamina && !this.pushed));

        if (consumeStamina) {
            this.usedStaminaThisShot = false;
            this.bPlayer.increaseAirBlastDecay(this.decayAmount, this.decayMinimum);
            this.pushFactor *= this.bPlayer.getAirBlastDecay();
        }

        if (!this.pushed && triggerStamina) {
            this.bPlayer.resetAirBlast();
            this.pushed = true;
        }

        if (this.source != null) {
            knockback = this.pushFactor;
        }

        if (knockback == 0) {
            return;
        }

        Vector velocity = entity.getVelocity();
        final double max = 1.0 / this.pushFactorForOthers;
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

        if (sliding) {
            double speed = 1 - this.bPlayer.getAirBlastDecay();

            if (this.staminaSliding) {
                speed = 0;
            }

            knockback *= this.slideSpeed + speed;
        }

        /*
         * Rigorous push model:
         *
         * Let d be the final push axis and v be the entity's current velocity.
         * Decompose v into:
         *
         *     v_parallel = (v · d) d
         *     v_perp     = v - v_parallel
         *
         * The blast should guarantee a target velocity component along d, not add
         * a different amount depending on how many lag-compensated snapshots hit.
         *
         * So:
         *
         *     target = knockback
         *     current = v · d
         *     delta = max(0, target - current)
         *     v' = v + delta d
         *
         * After this:
         *
         *     v' · d = max(current, target)
         *
         * This is idempotent. Applying the same blast again during the same
         * overlap does not keep stacking velocity, which makes high-ping and
         * low-ping hits much more consistent.
         */
        double comp = velocity.dot(push.clone().normalize());
        if (comp > knockback) {
            velocity.multiply(.5);
            velocity.add(push.clone().normalize().multiply(velocity.clone().dot(push.clone().normalize())));
        } else if (comp + knockback * .5 > knockback) {
            velocity.add(push.clone().multiply(knockback - comp));
        } else {
            velocity.add(push.clone().multiply(knockback * .5));
        }

        GeneralMethods.setVelocity(this, entity, velocity);

        if (this.source != null) {
            new HorizontalVelocityTracker(entity, this.player, 200L, this.source);
        } else {
            new HorizontalVelocityTracker(entity, this.player, 200L, this);
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
                    final double[] dims = {toPlayer.getX(), toPlayer.getY(), toPlayer.getZ()};

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

        this.preShootStamina = bPlayer.getAirBlastDecay();
        this.usedStaminaThisShot = false;
        this.refundedThisSlide = false;

        if (!hasSufficientStamina(this.bPlayer)) {
            if (!isFromOtherOrigin) remove();
            return;
        }

        final boolean willConsumeStamina = isFromOtherOrigin;
        final double shotStamina = willConsumeStamina ? Math.max(decayMinimum, this.preShootStamina - decayAmount) : this.preShootStamina;
        this.speed = applyStaminaScaling(this.speed, shotStamina, this.speedStaminaScale);
        this.range = applyStaminaScaling(this.range, shotStamina, this.rangeStaminaScale);

        Location targetedLocation = getTargetedLocation(player, this.range);
        Block block = targetedLocation.getBlock();
        if (!GeneralMethods.isSolid(block) && GeneralMethods.isSolid(block.getRelative(BlockFace.DOWN))) {
            double y = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Air.AirBlast.SourceYOffset");
            targetedLocation.add(0, y, 0);
        }

        this.direction = this.getValidDirection(GeneralMethods.getDirection(origin, targetedLocation));
        if (this.direction == null) {
            if (!isFromOtherOrigin) {
                remove();
            }
            return;
        }

        if (isFromOtherOrigin) {
            if (System.currentTimeMillis() - bPlayer.getLastScooterUse() < scooterThreshold) {
                bPlayer.addCooldown("AirScooter", scooterBlastCD);
            }

            this.usedStaminaThisShot = willConsumeStamina;
        }

        this.location = this.origin.clone();
        this.progressing = true;
        this.bPlayer.addCooldown(this);
        this.lagCompensator = new AbilityLagCompensator((p, snapshot) -> affect(p, snapshot.getLocation()));
    }

    private Vector getValidDirection(final Vector direction) {
        Vector validDirection = direction == null ? null : direction.clone();
        if (validDirection == null || validDirection.lengthSquared() == 0 || !isFinite(validDirection)) {
            validDirection = this.player.getEyeLocation().getDirection();
        }
        if (validDirection == null || validDirection.lengthSquared() == 0 || !isFinite(validDirection)) {
            return null;
        }
        return validDirection.normalize();
    }

    private boolean isFinite(final Vector vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }

    private double applyStaminaScaling(final double value, final double stamina, final double scale) {
        final double clampedStamina = Math.max(0.0, Math.min(1.0, stamina));
        final double clampedScale = Math.max(0.0, scale);
        final double multiplier = Math.max(0.0, 1.0 - ((1.0 - clampedStamina) * clampedScale));
        return value * multiplier;
    }

    @Override
    public String getName() {
        return "AirBlast";
    }

    @Override
    public Location getLocation() {
        return this.location;
    }

    public void setLocation(final Location location) {
        this.location = location;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
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

    public int getParticles() {
        return this.particles;
    }

    public void setParticles(final int particles) {
        this.particles = particles;
    }
}
