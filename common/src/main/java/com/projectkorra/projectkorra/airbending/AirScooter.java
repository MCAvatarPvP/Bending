package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Difficulty;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Slime;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AirScooter extends AirAbility {

    private static final int TERRAIN_PROFILE_SAMPLES = 5;
    private static final int TERRAIN_STEP_RANGE = 1;
    private static final double TERRAIN_LOOK_AHEAD_TICKS = 3.0;
    private static final double MIN_TERRAIN_LOOK_AHEAD = 1.25;
    private static final double MAX_TERRAIN_LOOK_AHEAD = 3.0;

    public boolean stunned;
    private long stunCooldown;
    @Attribute(Attribute.SPEED)
    private double speed;
    private double interval;
    @Attribute(Attribute.RADIUS)
    private double radius;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.HEIGHT)
    private double maxHeightFromGround;
    private double downPush;
    private double upPush;
    private double minHeight;
    private double midHeight;
    private double maxHeight;
    private double strength;
    private double staminaDrainRate;
    private double decayMinimum;
    private long lastStaminaDrainTime;
    private Block floorblock;
    private Random random;
    private List<Vector> ballOffsets;
    private Slime slime;
    private Boolean useslime;
    private boolean requiresSprint;
    private boolean requiresJump;
    private boolean disableSprint;
    private boolean canFly, wasFlying;
    private boolean disableSpout;
    private boolean disableSpeed;
    private boolean oldScooter;
    private double phi = 0;
    private String removalReason = "external removal";

    public AirScooter(final Player player) {
        super(player);

        this.oldScooter = this.bPlayer.isOldScooterEnabled();
        final String settings = this.oldScooter ? "Abilities.Air.AirScooter" : "Abilities.Air.AirSurf";
        this.requiresSprint = getConfig().getBoolean(settings + ".RequiresSprint");
        this.requiresJump = getConfig().getBoolean(settings + ".RequiresJump");
        this.disableSpout = !this.oldScooter || getConfig().getBoolean(settings + ".DisableSpout");
        this.disableSpeed = getConfig().getBoolean(settings + ".DisableSpeed");
        this.staminaDrainRate = getConfig().getDouble(settings + ".StaminaDrainRate", 0.1);
        this.decayMinimum = getConfig().getDouble("Abilities.Air.AirBlast.DecayMinimum");

        if (check(player)) {
            return;
        } else if (!player.isSprinting() && requiresSprint || GeneralMethods.isSolid(player.getEyeLocation().getBlock()) || ElementalAbility.isWater(player.getEyeLocation().getBlock())) {
            return;
        } else if (GeneralMethods.isSolid(player.getLocation().add(0, -.5, 0).getBlock()) && requiresJump) {
            return;
        } else if (player.isSneaking()) {
            return;
        } else if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        if (this.staminaDrainRate > 0 && this.bPlayer.getAirBlastDecay() <= this.decayMinimum) {
            return;
        }

        AirSpout spout = CoreAbility.getAbility(player, AirSpout.class);
        if (spout != null && disableSpout) spout.remove();
        final AirGlider glider = CoreAbility.getAbility(player, AirGlider.class);
        if (glider != null) glider.remove();

        boolean cancelBlast = getConfig().getBoolean(settings + ".CancelBlast");
        AirBlast blast = CoreAbility.getAbility(player, AirBlast.class);
        if (cancelBlast && blast != null && blast.isFromOtherOrigin()) blast.remove();

        if (isInAir(player.getLocation())) {
            return;
        }

        this.speed = getConfig().getDouble(settings + ".Speed");
        this.interval = getConfig().getDouble(settings + ".Interval");
        this.radius = getConfig().getDouble(settings + ".Radius");
        this.cooldown = getConfig().getLong(settings + ".Cooldown");
        this.duration = getConfig().getLong(settings + ".Duration");
        this.maxHeightFromGround = getConfig().getDouble(settings + ".MaxHeightFromGround");
        this.downPush = getConfig().getDouble(settings + ".Push.Down");
        this.upPush = getConfig().getDouble(settings + ".Push.Up");
        this.minHeight = getConfig().getDouble(settings + ".Height.Minimum");
        this.midHeight = getConfig().getDouble(settings + ".Height.Middle");
        this.stunCooldown = getConfig().getLong(settings + ".StunCooldown", 1000);
        this.maxHeight = getConfig().getDouble(settings + ".Height.Maximum");
        this.strength = getConfig().getDouble(settings + ".Strength", 0.15);
        this.useslime = getConfig().getBoolean(settings + ".ShowSitting");
        this.disableSprint = getConfig().getBoolean(settings + ".DisableSprint");
        this.random = new Random();

        this.recalculateAttributes();

        this.flightHandler.createInstance(player, this.getName());
        wasFlying = player.isFlying();
        canFly = player.getAllowFlight();
        player.setAllowFlight(true);
        player.setFlying(true);

        if (disableSprint) player.setSprinting(false);
        player.setSneaking(false);

        if (player.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            this.useslime = false;
        }
        if (this.useslime) {
            this.slime = (Slime) player.getWorld().spawnEntity(player.getLocation(), EntityType.SLIME);
            if (this.slime != null) {
                this.slime.setSize(1);
                this.slime.setSilent(true);
                this.slime.setInvulnerable(true);
                this.slime.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, true, false));
                this.slime.addPassenger(player);
            } else {
                this.useslime = false;
            }
        }
        if (!this.oldScooter) {
            this.initializeNewScooterParticles();
        }

        this.start();
    }

    /**
     * Checks if player has an instance already and removes if they do.
     *
     * @param player The player to check
     * @return false If player doesn't have an instance
     */
    public static boolean check(final Player player) {
        final AirScooter scooter = getAbility(player, AirScooter.class);
        if (scooter != null) {
            scooter.remove();
            return true;
        }
        return false;
    }

    private void initializeNewScooterParticles() {
        this.ballOffsets = new ArrayList<>();
        final double radius = 0.6;
        final int ySteps = 6;
        final int circlePoints = 6;
        for (int yStep = 0; yStep < ySteps; yStep++) {
            final double phi = Math.PI * (yStep / (double) (ySteps - 1));
            final boolean pole = yStep == 0 || yStep >= ySteps - 1;
            for (int point = 0; point < circlePoints; point++) {
                final double theta = 2 * Math.PI * (point / (double) circlePoints);
                this.ballOffsets.add(new Vector(radius * Math.cos(phi), radius * Math.sin(phi) * Math.cos(theta),
                        radius * Math.sin(phi) * Math.sin(theta)));
                if (pole) break;
            }
        }
    }

    private boolean isInAir(Location location) {
        for (int i = 0; i < 7; i++) {
            Block block = location.clone().add(0, -i, 0).getBlock();
            if (GeneralMethods.isSolid(block) || ElementalAbility.isWater(block)) {
                return false;
            }
        }
        return true;
    }

    /*
     * Looks for a block under the player and sets "floorBlock" to a block under
     * the player if it within the maximum height
     */
    private void getFloor() {
        this.floorblock = null;
        for (int i = 0; i <= this.maxHeightFromGround; i++) {
            final Block block = this.player.getEyeLocation().getBlock().getRelative(BlockFace.DOWN, i);
            if (GeneralMethods.isSolid(block) || ElementalAbility.isWater(block)) {
                this.floorblock = block;
                return;
            }
        }
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.removalReason = "canBendIgnoreBindsCooldowns returned false";
            this.remove();
            return;
        } else if (this.duration != 0 && System.currentTimeMillis() > this.getStartTime() + this.duration) {
            this.bPlayer.addCooldown(this);
            this.removalReason = "duration expired";
            this.remove();
            if (this.isRemoved()) return;
        }

        if (!this.consumeStamina()) {
            this.removalReason = "air stamina was exhausted";
            this.remove();
            if (this.isRemoved()) return;
        }

        this.getFloor();
        if (this.floorblock == null) {
            this.removalReason = "no solid floor was found within MaxHeightFromGround";
            this.remove();
            return;
        }

        if (this.player.isSneaking()) {
            this.removalReason = "player started sneaking";
            this.bPlayer.addCooldown(this);
            this.remove();
            return;
        }

        if (this.useslime && (this.slime == null || !this.slime.getPassengers().contains(this.player))) {
            this.removalReason = "the scooter passenger entity was lost";
            this.bPlayer.addCooldown(this);
            this.remove();
            return;
        }

        Vector velocity = this.player.getEyeLocation().getDirection().clone().normalize();
        velocity = velocity.setY(0);
        velocity = velocity.clone().normalize().multiply(this.speed);
        /*
         * checks the players speed and ends the move if they are going too slow
         */
        if (System.currentTimeMillis() > this.getStartTime() + this.interval) {
            if (this.useslime) {
                if (this.slime.getVelocity().length() < this.speed * 0.3) {
                    this.remove();
                    return;
                }
            }
            if (this.oldScooter) {
                this.spinOldScooter();
            } else {
                this.spinNewScooter();
            }
        }
        /*
         * Checks for how far the ground is away from the player it elevates or
         * lowers the player based on their distance from the ground.
         */
        final double playerY = this.player.getLocation().getY();
        final double distance = playerY - this.floorblock.getY();
        if (this.oldScooter) {
            final double dx = Math.abs(distance - this.midHeight);
            if (distance > this.maxHeight) {
                velocity.setY(this.downPush * dx * dx);
            } else if (distance < this.minHeight) {
                velocity.setY(this.upPush * dx * dx);
            } else {
                velocity.setY(0);
            }
            this.applyLegacyTerrainImpulse(velocity);
        } else {
            final double smoothedFloorY = this.getSmoothedTerrainHeight(velocity);
            velocity.setY(AirScooterTerrain.verticalVelocity(
                    playerY, smoothedFloorY, this.midHeight, this.strength));
        }

        final Location loc = this.player.getLocation();
        if (!ElementalAbility.isWater(this.player.getLocation().add(0, 2, 0).getBlock())) {
            loc.setY(this.floorblock.getY() + 1.5);
        } else {
            return;
        }

        if (disableSprint) this.player.setSprinting(false);
        if (disableSpeed) this.player.removePotionEffect(PotionEffectType.SPEED);
        if (this.useslime) {
            GeneralMethods.setVelocity(this, this.slime, velocity);
        } else {
            GeneralMethods.setVelocity(this, this.player, velocity);
        }

        if (this.random.nextInt(4) == 0) {
            playAirbendingSound(this.player.getLocation());
        }
    }

    /** Keeps the original step impulses exclusive to legacy AirScooter mode. */
    private void applyLegacyTerrainImpulse(final Vector velocity) {
        final Vector horizontal = velocity.clone().setY(0);
        final Block ahead = this.floorblock.getLocation().clone().add(horizontal.multiply(1.2)).getBlock();
        if (!isScooterFloor(ahead)) {
            velocity.add(new Vector(0, -0.1, 0));
        } else if (isScooterFloor(ahead.getRelative(BlockFace.UP))) {
            velocity.add(new Vector(0, 0.7, 0));
        }
    }

    /**
     * Samples a short strip of terrain in front of modern AirScooter. Averaging
     * the surface heights turns a one-block edge into a gradual virtual ramp,
     * which the existing proportional spring can follow without a fixed jolt.
     */
    private double getSmoothedTerrainHeight(final Vector horizontalVelocity) {
        final double currentFloorY = this.floorblock.getY();
        final double[] profile = new double[TERRAIN_PROFILE_SAMPLES];
        profile[0] = currentFloorY;

        final Vector direction = horizontalVelocity.clone().setY(0);
        final double horizontalSpeed = direction.length();
        if (horizontalSpeed <= 1.0E-9) return currentFloorY;
        direction.normalize();

        final double lookAhead = GeneralMethods.clamp(
                horizontalSpeed * TERRAIN_LOOK_AHEAD_TICKS,
                MIN_TERRAIN_LOOK_AHEAD,
                MAX_TERRAIN_LOOK_AHEAD);
        final Location sampleOrigin = this.player.getLocation().clone();
        sampleOrigin.setY(currentFloorY);

        for (int index = 1; index < TERRAIN_PROFILE_SAMPLES; index++) {
            final double distance = lookAhead * index / (TERRAIN_PROFILE_SAMPLES - 1.0);
            final Block column = sampleOrigin.clone().add(direction.clone().multiply(distance)).getBlock();
            final Block surface = this.findNearbySurface(column);
            profile[index] = surface == null ? currentFloorY : surface.getY();
        }
        return AirScooterTerrain.averageHeight(profile);
    }

    private Block findNearbySurface(final Block column) {
        for (int offset = TERRAIN_STEP_RANGE; offset >= -TERRAIN_STEP_RANGE; offset--) {
            final Block candidate = offset > 0
                    ? column.getRelative(BlockFace.UP, offset)
                    : offset < 0 ? column.getRelative(BlockFace.DOWN, -offset) : column;
            if (isScooterFloor(candidate)
                    && !isScooterFloor(candidate.getRelative(BlockFace.UP))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isScooterFloor(final Block block) {
        return block != null && (GeneralMethods.isSolid(block) || ElementalAbility.isWater(block));
    }

    private boolean consumeStamina() {
        if (this.staminaDrainRate <= 0) {
            return true;
        }

        final long now = System.currentTimeMillis();
        if (this.lastStaminaDrainTime == 0) {
            this.lastStaminaDrainTime = now;
            this.bPlayer.resetAirBlast();
            return this.bPlayer.getAirBlastDecay() > this.decayMinimum;
        }

        final double elapsedSeconds = (now - this.lastStaminaDrainTime) / 1000.0;
        this.lastStaminaDrainTime = now;
        if (elapsedSeconds > 0) {
            this.bPlayer.increaseAirBlastDecay(this.staminaDrainRate * elapsedSeconds, this.decayMinimum);
        }
        this.bPlayer.resetAirBlast();
        return this.bPlayer.getAirBlastDecay() > this.decayMinimum;
    }

    /**
     *
     * MAKE SUMMON SELF RECODE!!@!@#!@!@
     */

    /*
     * Updates the players flight, also adds the cooldown.
     */
    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        if (this.slime != null) {
            this.slime.remove();
        }
        this.flightHandler.removeInstance(this.player, this.getName());
        this.player.setAllowFlight(canFly);
        this.player.setFlying(wasFlying);
        if (stunned) {
            this.bPlayer.addCooldown(this, stunCooldown);
        } else {
            this.bPlayer.addCooldown(this);
        }
        this.bPlayer.setLastScooterUse(System.currentTimeMillis());
    }

    private void spinOldScooter() {
        final Location origin = this.player.getLocation();
        final Location origin2 = this.player.getLocation();
        this.phi += Math.PI / 10 * 4;
        for (double theta = 0; theta <= 2 * Math.PI; theta += Math.PI / 10) {
            final double r = 0.6;
            final double x = r * Math.cos(theta) * Math.sin(this.phi);
            final double y = r * Math.cos(this.phi);
            final double z = r * Math.sin(theta) * Math.sin(this.phi);
            origin.add(x, y, z);
            playAirbendingParticles(origin, 1, 0F, 0F, 0F);
            origin.subtract(x, y, z);
            origin2.subtract(x, y, z);
            playAirbendingParticles(origin2, 1, 0F, 0F, 0F);
            origin2.add(x, y, z);
        }
    }

    private void spinNewScooter() {
        final Location base = this.player.getLocation().add(0, -0.25, 0);
        final double yRotation = Math.toRadians(-base.getYaw());
        for (final Vector ballOffset : this.ballOffsets) {
            if (this.random.nextDouble() > 0.4) continue;
            playAirbendingParticles(base.clone().add(ballOffset.clone().rotateAroundY(yRotation)), 1, 0F, 0F, 0F);
        }
    }

    public boolean isUsingOldScooter() {
        return this.oldScooter;
    }

    @Override
    public String getName() {
        return "AirScooter";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
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
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public double getCollisionRadius() {
        return this.getRadius();
    }

    public double getSpeed() {
        return this.speed;
    }

    public void setSpeed(final double speed) {
        this.speed = speed;
    }

    public double getInterval() {
        return this.interval;
    }

    public void setInterval(final double interval) {
        this.interval = interval;
    }

    public double getRadius() {
        return this.radius;
    }

    public void setRadius(final double radius) {
        this.radius = radius;
    }

    public double getMaxHeightFromGround() {
        return this.maxHeightFromGround;
    }

    public void setMaxHeightFromGround(final double maxHeightFromGround) {
        this.maxHeightFromGround = maxHeightFromGround;
    }

    public Block getFloorblock() {
        return this.floorblock;
    }

    public void setFloorblock(final Block floorblock) {
        this.floorblock = floorblock;
    }
}
