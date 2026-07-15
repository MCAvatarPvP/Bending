package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BlockIterator;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.ConfirmedHitEffects;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.colliders.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.projectkorra.projectkorra.util.DisplayBlockUtils.randomHorizontalVector;
import static com.projectkorra.projectkorra.util.DisplayBlockUtils.spawnEarthFragment;

public class EarthSmash extends EarthAbility {

    @Attribute("AllowGrab")
    private boolean allowGrab;
    @Attribute("AllowFlight")
    private boolean allowFlight;
    private boolean redirectOnCd;
    private boolean ignoreBinds;
    private int animationCounter;
    private int progressCounter;
    private int requiredBendableBlocks;
    private int maxBlocksToPassThrough;
    private long delay;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.CHARGE_DURATION)
    private long chargeTime;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute("Flight" + Attribute.DURATION)
    private long flightDuration;
    private long flightStartTime;
    private long shootAnimationInterval;
    private long flightAnimationInterval;
    private long liftAnimationInterval;
    @Attribute(Attribute.SELECT_RANGE)
    private double selectRange;
    @Attribute("GrabRange")
    private double grabRange;
    @Attribute("ShootRange")
    private double shootRange;
    @Attribute("Min" + Attribute.DAMAGE)
    private double minDamage;
    @Attribute(Attribute.DAMAGE)
    private double maxDamage;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;
    @Attribute(Attribute.KNOCKUP)
    private double knockup;
    private double liftKnockup;
    private double liftRange;
    @Attribute(Attribute.SPEED)
    private double flightSpeed;
    private double grabbedDistance;
    private double grabDetectionRadius;
    private double hitRadius;
    private double flightDetectionRadius;
    private double flightDetectionHeight;
    private State state;
    private Block origin;
    private Location location;
    private Location destination;
    private ArrayList<Entity> affectedEntities;
    private ArrayList<BlockRepresenter> currentBlocks;
    private ArrayList<TempBlock> affectedBlocks;
    public EarthSmash(final Player player, final ClickType type) {
        super(player);

        this.state = State.START;
        this.requiredBendableBlocks = getConfig().getInt("Abilities.Earth.EarthSmash.RequiredBendableBlocks");
        this.maxBlocksToPassThrough = getConfig().getInt("Abilities.Earth.EarthSmash.MaxBlocksToPassThrough");
        this.setFields();
        this.affectedEntities = new ArrayList<>();
        this.currentBlocks = new ArrayList<>();
        this.affectedBlocks = new ArrayList<>();

        if (type == ClickType.SHIFT_DOWN || type == ClickType.SHIFT_UP && !player.isSneaking()) {
            final EarthSmash flySmash = flyingInSmashCheck(player);
            if (flySmash != null) {
                flySmash.state = State.FLYING;
                flySmash.player = player;
                flySmash.setFields();
                flySmash.flightStartTime = System.currentTimeMillis();
                return;
            }

            EarthSmash grabbedSmash = this.aimingAtSmashCheck(player, State.LIFTED);
            if (grabbedSmash == null) {
                if (!redirectOnCd && this.bPlayer.isOnCooldown(this)) {
                    return;
                }
                grabbedSmash = this.aimingAtSmashCheck(player, State.SHOT);
            }

            if (grabbedSmash != null) {
                grabbedSmash.state = State.GRABBED;
                grabbedSmash.grabbedDistance = 0;
                if (grabbedSmash.location.getWorld().equals(player.getWorld())) {
                    grabbedSmash.grabbedDistance = grabbedSmash.location.distance(player.getEyeLocation());
                }
                grabbedSmash.player = player;
                grabbedSmash.setFields();
                return;
            }

            this.start();
        } else if (type == ClickType.LEFT_CLICK && player.isSneaking()) {
            for (final EarthSmash smash : getAbilities(EarthSmash.class)) {
                if (smash.state == State.GRABBED && smash.player == player) {
                    smash.state = State.SHOT;
                    smash.destination = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().normalize().multiply(smash.shootRange));
                    playEarthbendingSound(smash.location);
                }
            }
            return;
        } else if (type == ClickType.RIGHT_CLICK && player.isSneaking()) {
            final EarthSmash grabbedSmash = this.aimingAtSmashCheck(player, State.GRABBED);
            if (grabbedSmash != null) {
                player.teleport(grabbedSmash.location.clone().add(0, 2, 0));
                grabbedSmash.state = State.FLYING;
                grabbedSmash.player = player;
                grabbedSmash.setFields();
                grabbedSmash.flightStartTime = System.currentTimeMillis();
            }
            return;
        }
    }

    /**
     * Switches the Sand Material and Gravel to SandStone and stone
     * respectively, since gravel and sand cannot be bent due to gravity.
     */
    public static Material selectMaterial(final Material mat) {
        if (mat == Material.SAND) {
            return Material.SANDSTONE;
        } else if (mat == Material.GRAVEL) {
            return Material.STONE;
        } else {
            return mat;
        }
    }

    /**
     * Determines whether or not a player is trying to fly ontop of an
     * EarthSmash. A player is considered "flying" if they are standing ontop of
     * the earthsmash and holding shift.
     */
    private static EarthSmash flyingInSmashCheck(final Player player) {
        for (final EarthSmash smash : getAbilities(EarthSmash.class)) {
            if (!smash.allowFlight) {
                continue;
            }
            // Check to see if the player is standing on top of the smash.
            if (smash.state == State.LIFTED && smash.location.getWorld().equals(player.getWorld())) {
                double detectionHeight = smash.flightDetectionHeight * 0.5;
                AABB flyingSmashReq = new AABB(smash.location.clone().add(0, detectionHeight + 1.0, 0), smash.flightDetectionRadius, detectionHeight);
                if (flyingSmashReq.intersects(new AABB(player.getWorld(), player.getBoundingBox()))) {
                    return smash;
                }
            }
        }
        return null;
    }

    public void setFields() {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(this.player);
        this.shootAnimationInterval = getConfig().getLong("Abilities.Earth.EarthSmash.Shoot.AnimationInterval");
        this.flightAnimationInterval = getConfig().getLong("Abilities.Earth.EarthSmash.Flight.AnimationInterval");
        this.liftAnimationInterval = getConfig().getLong("Abilities.Earth.EarthSmash.LiftAnimationInterval");
        this.grabDetectionRadius = getConfig().getDouble("Abilities.Earth.EarthSmash.Grab.DetectionRadius");
        this.flightDetectionRadius = getConfig().getDouble("Abilities.Earth.EarthSmash.Flight.DetectionRadius");
        this.flightDetectionHeight = getConfig().getDouble("Abilities.Earth.EarthSmash.Flight.DetectionHeight");
        this.hitRadius = getConfig().getDouble("Abilities.Earth.EarthSmash.Shoot.CollisionRadius");
        this.allowGrab = getConfig().getBoolean("Abilities.Earth.EarthSmash.Grab.Enabled");
        this.allowFlight = getConfig().getBoolean("Abilities.Earth.EarthSmash.Flight.Enabled");
        this.redirectOnCd = getConfig().getBoolean("Abilities.Earth.EarthSmash.RedirectOnCooldown");
        this.ignoreBinds = getConfig().getBoolean("Abilities.Earth.EarthSmash.Flight.IgnoreBinds");
        this.selectRange = getConfig().getDouble("Abilities.Earth.EarthSmash.SelectRange");
        this.grabRange = getConfig().getDouble("Abilities.Earth.EarthSmash.Grab.Range");
        this.shootRange = getConfig().getDouble("Abilities.Earth.EarthSmash.Shoot.Range");
        this.minDamage = getConfig().getDouble("Abilities.Earth.EarthSmash.MinimumDamage");
        this.maxDamage = getConfig().getDouble("Abilities.Earth.EarthSmash.MaximumDamage");
        this.knockback = getConfig().getDouble("Abilities.Earth.EarthSmash.Knockback");
        this.knockup = getConfig().getDouble("Abilities.Earth.EarthSmash.Knockup");
        this.liftKnockup = getConfig().getDouble("Abilities.Earth.EarthSmash.Lift.Knockup");
        this.liftRange = getConfig().getDouble("Abilities.Earth.EarthSmash.Lift.Range");
        this.flightSpeed = getConfig().getDouble("Abilities.Earth.EarthSmash.Flight.Speed");
        this.chargeTime = getConfig().getLong("Abilities.Earth.EarthSmash.ChargeTime");
        this.cooldown = getConfig().getLong("Abilities.Earth.EarthSmash.Cooldown");
        this.flightDuration = getConfig().getLong("Abilities.Earth.EarthSmash.Flight.Duration");
        this.duration = getConfig().getLong("Abilities.Earth.EarthSmash.Duration");
    }

    @Override
    public void progress() {
        this.progressCounter++;
        if (this.state == State.LIFTED && this.duration > 0 && System.currentTimeMillis() - this.getStartTime() > this.duration) {
            this.remove();
            return;
        }

        if (this.state == State.START) {
            if (!this.bPlayer.canBend(this)) {
                this.remove();
                return;
            }
        } else if (this.state == State.FLYING || this.state == State.GRABBED) {
            if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
                this.remove();
                return;
            }
            if (!bPlayer.getBoundAbilityName().equalsIgnoreCase(getName()) && !ignoreBinds) {
                this.remove();
                return;
            }
        }

        if (this.state == State.START && this.progressCounter > 1) {
            if (!this.player.isSneaking()) {
                if (System.currentTimeMillis() - this.getStartTime() >= this.chargeTime) {
                    this.origin = this.getVisibleEarthSourceBlock();
                    if (this.origin == null) {
                        this.remove();
                        return;
                    } else if (TempBlock.isTempBlock(this.origin)
                            && !TempBlock.isTopLayerOwnedBy(this.origin.getWorld(), this.origin.getX(), this.origin.getY(),
                            this.origin.getZ(), this.player.getUniqueId())
                            && !isBendableEarthTempBlock(this.origin)) {
                        this.remove();
                        return;
                    }
                    this.bPlayer.addCooldown(this);
                    this.location = this.origin.getLocation();
                    this.state = State.LIFTING;
                    this.minDamage = applyMetalPowerFactor(this.minDamage, this.origin);
                    this.maxDamage = applyMetalPowerFactor(this.maxDamage, this.origin);
                } else {
                    this.remove();
                    return;
                }
            } else if (System.currentTimeMillis() - this.getStartTime() > this.chargeTime) {
                final Location tempLoc = this.player.getEyeLocation().add(this.player.getEyeLocation().getDirection().normalize().multiply(1.2));
                tempLoc.add(0, 0.3, 0);
                ParticleEffect.SMOKE_NORMAL.display(tempLoc, 4, 0.3, 0.1, 0.3, 0);
            }
        } else if (this.state == State.LIFTING) {
            if (System.currentTimeMillis() - this.delay >= this.liftAnimationInterval) {
                this.delay = System.currentTimeMillis();
                this.animateLift();
            }
        } else if (this.state == State.GRABBED) {
            if (this.player.isSneaking()) {
                this.revert();
                final Location oldLoc = this.location.clone();
                this.location = this.player.getEyeLocation().add(this.player.getEyeLocation().getDirection().normalize().multiply(this.grabbedDistance));
                List<Block> blocks = this.getBlocks();
                Location loc = player.getEyeLocation();
                BlockIterator iterator = new BlockIterator(loc.getWorld(), loc.toVector(), loc.getDirection(), 0, (int) Math.ceil(this.grabbedDistance));
                while (iterator.hasNext()) {
                    blocks.add(iterator.next());
                }

                // Check to make sure the new location is available to move to.
                for (final Block block : blocks) {
                    if (!ElementalAbility.isAir(this.visibleType(block)) && !this.isVisibleTransparent(block)) {
                        this.location = oldLoc;
                        break;
                    }
                }
                this.draw();
                return;
            } else {
                this.state = State.LIFTED;
                return;
            }
        } else if (this.state == State.SHOT) {
            if (System.currentTimeMillis() - this.delay >= this.shootAnimationInterval) {
                this.delay = System.currentTimeMillis();
                if (GeneralMethods.isRegionProtectedFromBuild(this, this.location)) {
                    this.remove();
                    return;
                }

                this.revert();
                final Vector travelDirection = GeneralMethods.getDirection(this.location, this.destination).normalize();
                this.location.add(travelDirection.clone().multiply(1));
                if (this.location.distanceSquared(this.destination) < 4) {
                    this.remove();
                    return;
                }

                // If an earthsmash runs into too many blocks we should remove it.
                int badBlocksFound = 0;
                for (final Block block : this.getBlocks()) {
                    final Material visibleType = this.visibleType(block);
                    if (!ElementalAbility.isAir(visibleType) && (!this.isVisibleTransparent(block) || visibleType == Material.WATER)) {
                        badBlocksFound++;
                    }
                }

                if (badBlocksFound > this.maxBlocksToPassThrough) {
                    this.playBreakApartEffect(this.location, travelDirection.clone().multiply(-1));
                    this.remove();
                    return;
                }
                this.shootingCollisionDetection();
                this.draw();
                this.smashToSmashCollisionDetection();
            }
            return;
        } else if (this.state == State.FLYING) {
            if (!this.player.isSneaking()) {
                this.remove();
                return;
            } else if (System.currentTimeMillis() - this.delay >= this.flightAnimationInterval) {
                this.delay = System.currentTimeMillis();
                if (GeneralMethods.isRegionProtectedFromBuild(this, this.location)) {
                    this.remove();
                    return;
                }
                this.revert();
                this.destination = this.player.getEyeLocation().clone().add(this.player.getEyeLocation().getDirection().normalize().multiply(this.shootRange));
                final Vector direction = GeneralMethods.getDirection(this.location, this.destination).normalize();

                final List<Entity> entities = GeneralMethods.getEntitiesAroundPoint(this.location.clone().add(0, 2, 0), this.flightDetectionRadius);
                if (entities.size() == 0) {
                    this.remove();
                    return;
                }
                for (final Entity entity : entities) {
                    if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                        continue;
                    }
                    GeneralMethods.setVelocity(this, entity, direction.clone().multiply(this.flightSpeed));
                }

                // These values tend to work well when dealing with a person aiming upward or downward.
                if (direction.getY() < -0.35) {
                    this.location = this.player.getLocation().clone().add(0, -3.2, 0);
                } else if (direction.getY() > 0.35) {
                    this.location = this.player.getLocation().clone().add(0, -1.7, 0);
                } else {
                    this.location = this.player.getLocation().clone().add(0, -2.2, 0);
                }
                this.draw();
            }
            if (System.currentTimeMillis() - this.flightStartTime > this.flightDuration) {
                this.remove();
                return;
            }
        }
    }

    /**
     * Begins animating the EarthSmash from the ground. The lift animation
     * consists of 3 steps, and each one has to design the shape in the ground
     * that removes the Earthbendable material. We also need to make sure that
     * there is a clear path for the EarthSmash to rise, and that there is
     * enough Earthbendable material for it to be created.
     */
    public void animateLift() {
        if (this.animationCounter < 4) {
            this.revert();
            this.location.add(0, 1, 0);
            // Remove the blocks underneath the rising smash.
            if (this.animationCounter == 0) {
                // Check all of the blocks and make sure that they can be removed AND make sure there is enough dirt.
                int totalBendableBlocks = 0;
                for (int x = -1; x <= 1; x++) {
                    for (int y = -2; y <= -1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            final Block block = this.location.clone().add(x, y, z).getBlock();
                            if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
                                this.remove();
                                return;
                            }
                            if (this.isEarthbendable(block)) {
                                totalBendableBlocks++;
                            }
                        }
                    }
                }
                if (totalBendableBlocks < this.requiredBendableBlocks) {
                    this.remove();
                    return;
                }
                // Make sure there is a clear path upward otherwise remove.
                for (int y = 0; y <= 3; y++) {
                    final Block tempBlock = this.location.clone().add(0, y, 0).getBlock();
                    if (!this.isVisibleTransparent(tempBlock) && !ElementalAbility.isAir(this.visibleType(tempBlock))) {
                        this.remove();
                        return;
                    }
                }
                // Design what this EarthSmash looks like by using BlockRepresenters.
                final Location tempLoc = this.location.clone().add(0, -2, 0);
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            if ((Math.abs(x) + Math.abs(y) + Math.abs(z)) % 2 == 0) {
                                final Block block = tempLoc.clone().add(x, y, z).getBlock();
                                final BlockData visibleData = this.visibleData(block);
                                this.currentBlocks.add(new BlockRepresenter(x, y, z,
                                        this.selectMaterialForRepresenter(visibleData.getMaterial()), visibleData));
                            }
                        }
                    }
                }

                // Remove the design of the second level of removed dirt.
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        if ((Math.abs(x) + Math.abs(z)) % 2 == 1) {
                            final Block block = this.location.clone().add(x, -2, z).getBlock();
                            if (this.isEarthbendable(block) && bPlayer.areSourceHolesOn()) {
                                addTempAirBlock(block);
                            }
                        }

                        // Remove the first level of dirt.
                        final Block block = this.location.clone().add(x, -1, z).getBlock();
                        if (this.isEarthbendable(block) && bPlayer.areSourceHolesOn()) {
                            addTempAirBlock(block);
                        }
                    }
                }

                /*
                 * We needed to calculate all of the blocks based on the
                 * location being 1 above the initial bending block, however we
                 * want to animate it starting from the original bending block.
                 * We must readjust the location back to what it originally was.
                 */
                this.location.add(0, -1, 0);

                // Move any entities that are above the rock.
                final List<Entity> entities = GeneralMethods.getEntitiesAroundPoint(this.location, this.liftRange);
                for (final Entity entity : entities) {
                    final Vector velocity = entity.getVelocity();
                    entity.setVelocity(velocity.add(new Vector(0, this.liftKnockup, 0)));
                }
            }

            playEarthbendingSound(this.location);
            this.draw();
        } else {
            this.state = State.LIFTED;
        }
        this.animationCounter++;
    }

    /**
     * Redraws the blocks for this instance of EarthSmash.
     */
    public void draw() {
        if (this.currentBlocks.size() == 0) {
            this.remove();
            return;
        }
        for (final BlockRepresenter blockRep : this.currentBlocks) {
            final Block block = this.location.clone().add(blockRep.getX(), blockRep.getY(), blockRep.getZ()).getBlock();
            if (block.getType().equals(Material.SAND) || block.getType().equals(Material.GRAVEL)) { // Check if block can be affected by gravity.

            }
            if (this.player != null && this.isVisibleTransparent(block)) {
                this.affectedBlocks.add(new TempBlock(block, blockRep.getData(), this));
                getPreventEarthbendingBlocks().add(block);
            }
        }
    }

    public void revert() {
        this.checkRemainingBlocks();
        final List<TempBlock> retiring = List.copyOf(this.affectedBlocks);
        this.affectedBlocks.clear();
        for (final TempBlock tblock : retiring) {
            getPreventEarthbendingBlocks().remove(tblock.getBlock());
            tblock.revertBlock();
        }
    }

    private Block getVisibleEarthSourceBlock() {
        final Location eye = this.player.getEyeLocation();
        final Vector direction = eye.getDirection().clone().normalize();
        for (double distance = 0; distance <= this.selectRange; distance += 0.2) {
            final Block candidate = eye.clone().add(direction.clone().multiply(distance)).getBlock();
            if (RegionProtection.isRegionProtected(this.player, candidate.getLocation(), this)) continue;
            if (this.isEarthbendable(candidate)) return candidate;
            if (!this.isVisibleTransparent(candidate)) return null;
        }
        return null;
    }

    private BlockData visibleData(final Block block) {
        final BlockData data = TempBlock.getVisibleData(block, this.player.getUniqueId());
        return data == null ? block.getBlockData() : data;
    }

    private Material visibleType(final Block block) {
        return this.visibleData(block).getMaterial();
    }

    private boolean isVisibleTransparent(final Block block) {
        return ElementalAbility.getTransparentMaterialSet().contains(this.visibleType(block))
                && !RegionProtection.isRegionProtected(this.player, block.getLocation(), this);
    }

    /**
     * Checks to see which of the blocks are still attached to the EarthSmash,
     * remember that blocks can be broken or used in other abilities so we need
     * to double check and remove any that are not still attached.
     * <p>
     * Also when we remove the blocks from instances, movedearth, or tempair we
     * should do it on a delay because tempair takes a couple seconds before the
     * block shows up in that map.
     */
    public void checkRemainingBlocks() {
        if (animationCounter < 4 && !bPlayer.areSourceHolesOn()) return;
        for (int i = 0; i < this.currentBlocks.size(); i++) {
            final BlockRepresenter brep = this.currentBlocks.get(i);
            final Block block = this.location.clone().add(brep.getX(), brep.getY(), brep.getZ()).getBlock();
            // The registered client TempBlock is the visual source of truth.
            // A hidden or delayed physical server packet must never make a
            // live smash delete pieces from its model.
            TempBlock smashLayer = null;
            final List<TempBlock> layers = TempBlock.getAll(block);
            if (layers != null) {
                for (int layerIndex = layers.size() - 1; layerIndex >= 0; layerIndex--) {
                    final TempBlock candidate = layers.get(layerIndex);
                    if (candidate.getAbility().orElse(null) == this) {
                        smashLayer = candidate;
                        break;
                    }
                }
            }
            final Material visible = smashLayer == null
                    ? block.getType() : smashLayer.getBlockData().getMaterial();
            // Check for grass because sometimes the dirt turns into grass.
            if (visible != brep.getType() && visible != Material.GRASS_BLOCK && visible != Material.COBBLESTONE) {
                this.currentBlocks.remove(i);
                i--;
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
        this.state = State.REMOVED;
        this.revert();
    }

    /**
     * Gets the blocks surrounding the EarthSmash's loc. This method ignores the
     * blocks that should be Air, and only returns the ones that are dirt.
     */
    public List<Block> getBlocks() {
        final List<Block> blocks = new ArrayList<Block>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if ((Math.abs(x) + Math.abs(y) + Math.abs(z)) % 2 == 0) { // Give it the cool shape.
                        if (this.location != null) {
                            blocks.add(this.location.getWorld().getBlockAt(this.location.clone().add(x, y, z)));
                        }
                    }
                }
            }
        }
        return blocks;
    }

    /**
     * Gets the blocks surrounding the EarthSmash's loc. This method returns all
     * the blocks surrounding the loc, including dirt and air.
     */
    public List<Block> getBlocksIncludingInner() {
        final List<Block> blocks = new ArrayList<Block>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (this.location != null) {
                        blocks.add(this.location.getWorld().getBlockAt(this.location.clone().add(x, y, z)));
                    }
                }
            }
        }
        return blocks;
    }

    public Material selectMaterialForRepresenter(final Material mat) {
        EarthCosmetic cosmetic = bPlayer.getEarthCosmetic();
        final Material tempMat = selectMaterial(EarthCosmetic.canReplace(cosmetic, mat) ? cosmetic.getMaterial() : mat);
        if (!isEarthbendable(tempMat, true, true, true) && !this.isMetalbendable(tempMat)) {
            if (this.currentBlocks.size() < 1) {
                return Material.DIRT;
            } else {
                // This material is part of the actual TempBlock shape, not merely a
                // cosmetic. Wall-clock-seeded RNG made client and server construct
                // different boulders and caused authority handoff on the first echo.
                long hash = this.player.getUniqueId().getMostSignificantBits()
                        ^ this.player.getUniqueId().getLeastSignificantBits()
                        ^ Double.doubleToLongBits(this.origin == null ? 0.0 : this.origin.getX())
                        ^ Long.rotateLeft(Double.doubleToLongBits(this.origin == null ? 0.0 : this.origin.getY()), 17)
                        ^ Long.rotateLeft(Double.doubleToLongBits(this.origin == null ? 0.0 : this.origin.getZ()), 37)
                        ^ this.currentBlocks.size() * 0x9E3779B97F4A7C15L;
                return this.currentBlocks.get(Math.floorMod(hash, this.currentBlocks.size())).getType();
            }
        }
        return tempMat;
    }

    /**
     * Determines if a player is trying to grab an EarthSmash. A player is
     * trying to grab an EarthSmash if they are staring at it and holding shift.
     */
    private EarthSmash aimingAtSmashCheck(final Player player, final State reqState) {
        if (!this.allowGrab) {
            return null;
        }

        final List<Block> blocks = GeneralMethods.getBlocksAroundPoint(GeneralMethods.getTargetedLocation(player, this.grabRange, getTransparentMaterials()), 1);
        for (final EarthSmash smash : getAbilities(EarthSmash.class)) {
            if (reqState == null || smash.state == reqState) {
                for (final Block block : blocks) {
                    if (block == null || smash.getLocation() == null) {
                        continue;
                    }
                    if (block.getLocation().getWorld() == smash.location.getWorld() && block.getLocation().distanceSquared(smash.location) <= Math.pow(this.grabDetectionRadius, 2)) {
                        return smash;
                    }
                }
            }
        }
        return null;
    }

    /**
     * This method handles any collision between an EarthSmash and the
     * surrounding entities, the method only applies to earthsmashes that have
     * already been shot.
     */
    public void shootingCollisionDetection() {
        final List<Entity> entities = GeneralMethods.getEntitiesAroundPoint(this.location, this.hitRadius);
        for (final Entity entity : entities) {
            if (entity instanceof LivingEntity && !entity.equals(this.player) && !this.affectedEntities.contains(entity)) {
                if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                    continue;
                }
                this.affectedEntities.add(entity);
                double damage = this.currentBlocks.size() / 13.0 * this.maxDamage;
                if (damage < this.minDamage) {
                    damage = this.minDamage;
                }

                DamageHandler.damageEntity(entity, damage, this);
                final Location hitLocation = entity.getLocation().clone();
                ConfirmedHitEffects.sound(this, entity,
                        () -> this.playEntityHitSoundEffect(hitLocation));
                final Vector travelVec = GeneralMethods.getDirection(this.location, entity.getLocation());
                GeneralMethods.setVelocity(this, entity, travelVec.setY(this.knockup).normalize().multiply(this.knockback));
            }
        }
    }

    private void playEntityHitSoundEffect(final Location hitLocation) {
        if (hitLocation == null || hitLocation.getWorld() == null) {
            return;
        }

        hitLocation.getWorld().playSound(hitLocation, Sound.ITEM_MACE_SMASH_GROUND, 10, 1f);
    }

    /**
     * EarthSmash to EarthSmash collision can only happen when one of the
     * Smashes have been shot by a player. If we find out that one of them have
     * collided then we want to return since a smash can only remove 1 at a
     * time.
     */
    public void smashToSmashCollisionDetection() {
        for (final EarthSmash smash : getAbilities(EarthSmash.class)) {
            if (smash.location != null && smash != this && smash.location.getWorld() == this.location.getWorld() && smash.location.distanceSquared(this.location) < Math.pow(this.flightDetectionRadius, 2)) {
                final Vector travelDirection = this.getSmashTravelDirection(smash.location);
                smash.playBreakApartEffect(this.location, travelDirection);
                this.playBreakApartEffect(smash.location, travelDirection.clone().multiply(-1));
                smash.remove();
                this.remove();
                return;
            }
        }
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst() && collision.isRemovingSecond()) {
            final Location impactLocation = collision.getLocationFirst() != null ? collision.getLocationFirst() : this.location;
            this.playBreakApartEffect(impactLocation, this.getCollisionBreakDirection(collision));
        }
        super.handleCollision(collision);
    }

    private void playBreakApartEffect(
            final Location impactLocation,
            final Vector suppliedBreakDirection) {

        if (ProjectKorra.plugin == null
                || !Platform.scheduler().isPrimaryThread()
                || impactLocation == null
                || impactLocation.getWorld() == null
                || this.currentBlocks.isEmpty()) {
            return;
        }

        final World world = impactLocation.getWorld();
        final Random random = new Random();

        /*
         * Preserve the original center behavior, but make sure it is in the
         * same world as the impact.
         */
        Location centerLocation = this.location != null
                ? this.location.clone()
                : impactLocation.clone();

        if (centerLocation.getWorld() == null
                || !world.equals(centerLocation.getWorld())) {
            centerLocation = impactLocation.clone();
        }

        final Location center = centerLocation;

        /*
         * Do not normalize or otherwise modify the Vector passed by the caller.
         */
        Vector calculatedDirection = suppliedBreakDirection == null
                ? null
                : suppliedBreakDirection.clone();

        if (calculatedDirection == null
                || calculatedDirection.lengthSquared() < 0.01D) {
            calculatedDirection = center.toVector()
                    .subtract(impactLocation.toVector());
        }

        if (calculatedDirection.lengthSquared() < 0.01D) {
            calculatedDirection = new Vector(
                    random.nextDouble() - 0.5D,
                    0.1D,
                    random.nextDouble() - 0.5D
            );
        }

        final Vector breakDirection = calculatedDirection.normalize();

        final BlockData soundData =
                this.currentBlocks.get(random.nextInt(this.currentBlocks.size()))
                        .getData();

        world.playEffect(
                impactLocation,
                Effect.STEP_SOUND,
                soundData.getMaterial()
        );

        for (final BlockRepresenter blockRep : this.currentBlocks) {
            final BlockData blockData = blockRep.getData();

            if (blockData == null || blockData.getMaterial().isAir()) {
                continue;
            }

            final Location spawnLocation = center.clone().add(
                    blockRep.getX() * 0.55D + 0.5D,
                    blockRep.getY() * 0.55D + 0.5D,
                    blockRep.getZ() * 0.55D + 0.5D
            );

            if (spawnLocation.getWorld() == null
                    || !world.equals(spawnLocation.getWorld())) {
                continue;
            }

            /*
             * Larger than the small debris from spawnBlockBurst.
             *
             * Values around 0.75-1.05 make the fragments look like heavy
             * chunks instead of particles.
             */
            final float scale = 0.78F + random.nextFloat() * 0.27F;

            final Vector chunkOffset = spawnLocation.toVector()
                    .subtract(center.toVector());

            Vector radialDirection = chunkOffset.clone();

            if (radialDirection.lengthSquared() < 0.001D) {
                radialDirection = randomHorizontalVector(random);
            } else {
                radialDirection.normalize();
            }

            /*
             * Chunks facing the impact direction receive more of the main force.
             */
            final double alignment = radialDirection.dot(breakDirection);
            final double exposedForce = Math.max(
                    0.65D,
                    Math.min(1.35D, 0.95D + alignment * 0.35D)
            );

            Vector scatter = new Vector(
                    random.nextDouble() - 0.5D,
                    random.nextDouble() * 0.35D,
                    random.nextDouble() - 0.5D
            );

            if (scatter.lengthSquared() > 0.001D) {
                scatter.normalize();
            }

            final double primarySpeed =
                    (0.15D + random.nextDouble() * 0.16D) * exposedForce;

            final double radialSpeed =
                    0.04D + random.nextDouble() * 0.11D;

            final double scatterSpeed =
                    0.015D + random.nextDouble() * 0.055D;

            /*
             * Larger fragments accelerate slightly less.
             * This is an artistic mass approximation, not real-world mass-based
             * gravity. Gravity itself remains equal for every fragment.
             */
            final double mass = Math.max(
                    0.7D,
                    scale * scale * scale
            );

            final double launchMassFactor = 1.0D / Math.sqrt(mass);

            final Vector initialVelocity = breakDirection.clone()
                    .multiply(primarySpeed)
                    .add(radialDirection.multiply(radialSpeed))
                    .add(scatter.multiply(scatterSpeed))
                    .multiply(launchMassFactor);

            /*
             * Add a strong upward impulse while retaining any upward component
             * already present in the break direction.
             */
            initialVelocity.setY(
                    initialVelocity.getY()
                            + 0.18D
                            + random.nextDouble() * 0.22D
            );

            final Vector spinAxis = new Vector(
                    random.nextDouble() * 2.0D - 1.0D,
                    random.nextDouble() * 2.0D - 1.0D,
                    random.nextDouble() * 2.0D - 1.0D
            );

            if (spinAxis.lengthSquared() < 0.001D) {
                spinAxis.setX(0.0D);
                spinAxis.setY(1.0D);
                spinAxis.setZ(0.0D);
            } else {
                spinAxis.normalize();
            }

            final float initialRotation =
                    random.nextFloat() * (float) (Math.PI * 2.0D);

            /*
             * Heavy chunks rotate more slowly than small chunks.
             */
            final float initialAngularVelocity =
                    (0.12F + random.nextFloat() * 0.22F)
                            / Math.max(0.8F, scale);

            final int maximumLifetime =
                    34 + random.nextInt(13);

            spawnEarthFragment(
                    spawnLocation,
                    blockData,
                    initialVelocity,
                    spinAxis,
                    initialRotation,
                    initialAngularVelocity,
                    scale,
                    maximumLifetime
            );

            ParticleEffect.BLOCK_CRACK.display(
                    spawnLocation,
                    4,
                    scale * 0.22D,
                    scale * 0.22D,
                    scale * 0.22D,
                    0.04D,
                    blockData
            );
        }
    }

    private Vector getCollisionBreakDirection(final Collision collision) {
        if (collision.getLocationFirst() != null && collision.getLocationSecond() != null && collision.getLocationFirst().getWorld() == collision.getLocationSecond().getWorld()) {
            final Vector awayFromOtherAbility = collision.getLocationFirst().toVector().subtract(collision.getLocationSecond().toVector());
            if (awayFromOtherAbility.lengthSquared() > 0.01) {
                return awayFromOtherAbility.normalize();
            }
        }
        return this.getSmashTravelDirection(collision.getLocationSecond());
    }

    private Vector getSmashTravelDirection(final Location fallbackTarget) {
        if (this.location != null && this.destination != null && this.location.getWorld() == this.destination.getWorld()) {
            final Vector travelDirection = GeneralMethods.getDirection(this.location, this.destination);
            if (travelDirection.lengthSquared() > 0.01) {
                return travelDirection.normalize();
            }
        }
        if (this.location != null && fallbackTarget != null && this.location.getWorld() == fallbackTarget.getWorld()) {
            final Vector fallbackDirection = GeneralMethods.getDirection(this.location, fallbackTarget);
            if (fallbackDirection.lengthSquared() > 0.01) {
                return fallbackDirection.normalize();
            }
        }
        return new Vector(0, 0.2, 0);
    }

    @Override
    public String getName() {
        return "EarthSmash";
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
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();
        for (final TempBlock tblock : this.affectedBlocks) {
            locations.add(tblock.getLocation());
        }
        return locations;
    }

    public boolean isAllowGrab() {
        return this.allowGrab;
    }

    public void setAllowGrab(final boolean allowGrab) {
        this.allowGrab = allowGrab;
    }

    public boolean isAllowFlight() {
        return this.allowFlight;
    }

    public void setAllowFlight(final boolean allowFlight) {
        this.allowFlight = allowFlight;
    }

    public int getAnimationCounter() {
        return this.animationCounter;
    }

    public void setAnimationCounter(final int animationCounter) {
        this.animationCounter = animationCounter;
    }

    public int getProgressCounter() {
        return this.progressCounter;
    }

    public void setProgressCounter(final int progressCounter) {
        this.progressCounter = progressCounter;
    }

    public int getRequiredBendableBlocks() {
        return this.requiredBendableBlocks;
    }

    public void setRequiredBendableBlocks(final int requiredBendableBlocks) {
        this.requiredBendableBlocks = requiredBendableBlocks;
    }

    public int getMaxBlocksToPassThrough() {
        return this.maxBlocksToPassThrough;
    }

    public void setMaxBlocksToPassThrough(final int maxBlocksToPassThrough) {
        this.maxBlocksToPassThrough = maxBlocksToPassThrough;
    }

    public long getDelay() {
        return this.delay;
    }

    public void setDelay(final long delay) {
        this.delay = delay;
    }

    public long getChargeTime() {
        return this.chargeTime;
    }

    public void setChargeTime(final long chargeTime) {
        this.chargeTime = chargeTime;
    }

    public long getDuration() {
        return this.duration;
    }

    public void setDuration(final long duration) {
        this.duration = duration;
    }

    public long getFlightDuration() {
        return this.flightDuration;
    }

    public void setFlightDuration(final long flightDuration) {
        this.flightDuration = flightDuration;
    }

    public long getFlightStartTime() {
        return this.flightStartTime;
    }

    public void setFlightStartTime(final long flightStartTime) {
        this.flightStartTime = flightStartTime;
    }

    public long getShootAnimationInterval() {
        return this.shootAnimationInterval;
    }

    public void setShootAnimationInterval(final long shootAnimationInterval) {
        this.shootAnimationInterval = shootAnimationInterval;
    }

    public long getFlightAnimationInterval() {
        return this.flightAnimationInterval;
    }

    public void setFlightAnimationInterval(final long flightAnimationInterval) {
        this.flightAnimationInterval = flightAnimationInterval;
    }

    public long getLiftAnimationInterval() {
        return this.liftAnimationInterval;
    }

    public void setLiftAnimationInterval(final long liftAnimationInterval) {
        this.liftAnimationInterval = liftAnimationInterval;
    }

    public double getGrabRange() {
        return this.grabRange;
    }

    public void setGrabRange(final double grabRange) {
        this.grabRange = grabRange;
    }

    public double getSelectRange() {
        return this.selectRange;
    }

    public void setSelectRange(final double selectRange) {
        this.selectRange = selectRange;
    }

    public double getShootRange() {
        return this.shootRange;
    }

    public void setShootRange(final double shootRange) {
        this.shootRange = shootRange;
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

    public double getKnockback() {
        return this.knockback;
    }

    public void setKnockback(final double knockback) {
        this.knockback = knockback;
    }

    public double getKnockup() {
        return this.knockup;
    }

    public void setKnockup(final double knockup) {
        this.knockup = knockup;
    }

    public double getFlightSpeed() {
        return this.flightSpeed;
    }

    public void setFlightSpeed(final double flightSpeed) {
        this.flightSpeed = flightSpeed;
    }

    public double getGrabbedDistance() {
        return this.grabbedDistance;
    }

    public void setGrabbedDistance(final double grabbedDistance) {
        this.grabbedDistance = grabbedDistance;
    }

    public double getGrabDetectionRadius() {
        return this.grabDetectionRadius;
    }

    public void setGrabDetectionRadius(final double grabDetectionRadius) {
        this.grabDetectionRadius = grabDetectionRadius;
    }

    public double getFlightDetectionRadius() {
        return this.flightDetectionRadius;
    }

    public void setFlightDetectionRadius(final double flightDetectionRadius) {
        this.flightDetectionRadius = flightDetectionRadius;
    }

    public State getState() {
        return this.state;
    }

    public void setState(final State state) {
        this.state = state;
    }

    public Block getOrigin() {
        return this.origin;
    }

    public void setOrigin(final Block origin) {
        this.origin = origin;
    }

    public Location getDestination() {
        return this.destination;
    }

    public void setDestination(final Location destination) {
        this.destination = destination;
    }

    public ArrayList<Entity> getAffectedEntities() {
        return this.affectedEntities;
    }

    public ArrayList<BlockRepresenter> getCurrentBlocks() {
        return this.currentBlocks;
    }

    public ArrayList<TempBlock> getAffectedBlocks() {
        return this.affectedBlocks;
    }

    public static enum State {
        START, LIFTING, LIFTED, GRABBED, SHOT, FLYING, REMOVED
    }

    /**
     * A BlockRepresenter is used to keep track of each of the individual types
     * of blocks that are attached to an EarthSmash. Without the representer
     * then an EarthSmash can only be made up of 1 material at a time. For
     * example, an ESmash that is entirely dirt, coalore, or sandstone. Using
     * the representer will allow all the materials to be mixed together.
     */
    public class BlockRepresenter {
        private int x, y, z;
        private Material type;
        private BlockData data;

        public BlockRepresenter(final int x, final int y, final int z, final Material type, final BlockData data) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.data = data.getMaterial() == type ? data : type.createBlockData();
        }

        public int getX() {
            return this.x;
        }

        public void setX(final int x) {
            this.x = x;
        }

        public int getY() {
            return this.y;
        }

        public void setY(final int y) {
            this.y = y;
        }

        public int getZ() {
            return this.z;
        }

        public void setZ(final int z) {
            this.z = z;
        }

        public Material getType() {
            return this.type;
        }

        public void setType(final Material type) {
            this.type = type;
        }

        public BlockData getData() {
            return this.data;
        }

        public void setData(final BlockData data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return this.x + ", " + this.y + ", " + this.z + ", " + this.type.toString();
        }
    }

    public class Pair<F, S> {
        private F first; // first member of pair.
        private S second; // second member of pair.

        public Pair(final F first, final S second) {
            this.first = first;
            this.second = second;
        }

        public F getFirst() {
            return this.first;
        }

        public void setFirst(final F first) {
            this.first = first;
        }

        public S getSecond() {
            return this.second;
        }

        public void setSecond(final S second) {
            this.second = second;
        }
    }
}
