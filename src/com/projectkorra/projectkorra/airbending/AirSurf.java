package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AirSurf extends AirAbility {

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
    private Block floorblock;
    private Random random;
    private ArrayList<Double> angles;
    private Slime slime;
    private Boolean useslime;
    private boolean requiresSprint;
    private boolean requiresJump;
    private boolean disableSprint;
    private boolean canFly, wasFlying;

    private double phi = 0;
    private List<Vector> ballOffsets;

    public AirSurf(final Player player) {
        super(player);

        this.requiresSprint = getConfig().getBoolean("Abilities.Air.AirSurf.RequiresSprint");
        this.requiresJump = getConfig().getBoolean("Abilities.Air.AirSurf.RequiresJump");

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

        AirSpout spout = CoreAbility.getAbility(player, AirSpout.class);
        if (spout != null) spout.remove();

        boolean cancelBlast = getConfig().getBoolean("Abilities.Air.AirSurf.CancelBlast");
        AirBlast blast = CoreAbility.getAbility(player, AirBlast.class);
        if (cancelBlast && blast != null && blast.isFromOtherOrigin()) blast.remove();

        if (isInAir(player.getLocation())) {
            return;
        }

        this.speed = getConfig().getDouble("Abilities.Air.AirSurf.Speed");
        this.interval = getConfig().getDouble("Abilities.Air.AirSurf.Interval");
        this.radius = getConfig().getDouble("Abilities.Air.AirSurf.Radius");
        this.cooldown = getConfig().getLong("Abilities.Air.AirSurf.Cooldown");
        this.duration = getConfig().getLong("Abilities.Air.AirSurf.Duration");
        this.maxHeightFromGround = getConfig().getDouble("Abilities.Air.AirSurf.MaxHeightFromGround");
        this.downPush = getConfig().getDouble("Abilities.Air.AirSurf.Push.Down");
        this.upPush = getConfig().getDouble("Abilities.Air.AirSurf.Push.Up");
        this.minHeight = getConfig().getDouble("Abilities.Air.AirSurf.Height.Minimum");
        this.midHeight = getConfig().getDouble("Abilities.Air.AirSurf.Height.Middle");
        this.maxHeight = getConfig().getDouble("Abilities.Air.AirSurf.Height.Maximum");
        this.useslime = getConfig().getBoolean("Abilities.Air.AirSurf.ShowSitting");
        this.disableSprint = getConfig().getBoolean("Abilities.Air.AirSurf.DisableSprint");
        this.random = new Random();
        this.angles = new ArrayList<>();

        this.flightHandler.createInstance(player, this.getName());
        wasFlying = player.isFlying();
        canFly = player.getAllowFlight();
        player.setAllowFlight(true);
        player.setFlying(true);

        if (disableSprint) player.setSprinting(false);
        player.setSneaking(false);

        for (int i = 0; i < 5; i++) {
            this.angles.add((double) (60 * i));
        }
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

        ballOffsets = new ArrayList<>();
        double radius = 0.6;
        int ySteps = 6;
        int circlePoints = 6;
        for (int yStep = 0; yStep < ySteps; yStep++) {
            double phi = Math.PI * (yStep / (double) (ySteps - 1));
            boolean isZeroRadiusCircle = yStep == 0 || yStep >= ySteps - 1;
            for (int j = 0; j < circlePoints; j++) {
                double theta = 2 * Math.PI * (j / (double) circlePoints);

                double x = radius * Math.cos(phi);
                double y = radius * Math.sin(phi) * Math.cos(theta);
                double z = radius * Math.sin(phi) * Math.sin(theta);

                ballOffsets.add(new Vector(x, y, z));
                if (isZeroRadiusCircle)
                    break;
            }
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
        final AirSurf scooter = getAbility(player, AirSurf.class);
        if (scooter != null) {
            scooter.remove();
            return true;
        }
        return false;
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
            this.remove();
            return;
        } else if (this.duration != 0 && System.currentTimeMillis() > this.getStartTime() + this.duration) {
            this.bPlayer.addCooldown(this);
            this.remove();
            return;
        }

        this.getFloor();
        if (this.floorblock == null) {
            this.remove();
            return;
        }

        if (this.player.isSneaking()) {
            this.bPlayer.addCooldown(this);
            this.remove();
            return;
        }

        if (this.useslime && (this.slime == null || !this.slime.getPassengers().contains(this.player))) {
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
            } else {
                if (this.player.getVelocity().length() < this.speed * 0.3) {
                    this.remove();
                    return;
                }
            }
            this.spinScooter();
        }
        /*
         * Checks for how far the ground is away from the player it elevates or
         * lowers the player based on their distance from the ground.
         */
        final double distance = this.player.getLocation().getY() - this.floorblock.getY();
        final double delta = midHeight - distance;
        double force = GeneralMethods.clamp(0.3 * delta, -1, 0.5);
        velocity.setY(force);

        final Vector v = velocity.clone().setY(0);
        final Block b = this.floorblock.getLocation().clone().add(v.multiply(1.2)).getBlock();
        if (!GeneralMethods.isSolid(b) && !ElementalAbility.isWater(b)) {
            velocity.add(new Vector(0, -0.1, 0));
        } else if (GeneralMethods.isSolid(b.getRelative(BlockFace.UP)) || ElementalAbility.isWater(b.getRelative(BlockFace.UP))) {
            velocity.add(new Vector(0, 0.7, 0));
        }

        final Location loc = this.player.getLocation();
        if (!ElementalAbility.isWater(this.player.getLocation().add(0, 2, 0).getBlock())) {
            loc.setY(this.floorblock.getY() + 1.5);
        } else {
            return;
        }

        if (disableSprint) this.player.setSprinting(false);
        this.player.removePotionEffect(PotionEffectType.SPEED);
        if (this.useslime) {
            GeneralMethods.setVelocity(this, this.slime, velocity);
        } else {
            GeneralMethods.setVelocity(this, this.player, velocity);
        }

        if (this.random.nextInt(4) == 0) {
            playAirbendingSound(this.player.getLocation());
        }
    }

    /*
     * Updates the players flight, also adds the cooldown.
     */
    @Override
    public void remove() {
        super.remove();
        if (this.slime != null) {
            this.slime.remove();
        }
        this.flightHandler.removeInstance(this.player, this.getName());
        this.player.setAllowFlight(canFly);
        this.player.setFlying(wasFlying);
        this.bPlayer.addCooldown(this);
    }

    /*
     * The particles used for AirSurf phi = how many rings of particles the
     * sphere has. theta = how dense the rings are. r = Radius of the sphere
     */
    private void spinScooter() {
        final Location base = this.player.getLocation().add(0, -0.25, 0);

        double yRotation = Math.toRadians(-base.getYaw());
        for (Vector ballOffset : ballOffsets) {
            Vector offset = ballOffset.clone().rotateAroundY(yRotation);
            Location loc = base.clone().add(offset);
            playAirbendingParticles(loc, 1, 0F, 0F, 0F);
        }
    }

    @Override
    public String getName() {
        return "AirSurf";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
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

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }
}
