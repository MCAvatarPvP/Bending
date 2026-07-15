package com.jedk1.jedcore.ability.airbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.EntityHitboxProvider;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.Sphere;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class AirBlade extends AirAbility implements AddonAbility, EntityHitboxProvider {

    private static final double ARC_STEP = 8.0;
    // Less air particles, but collision/head math still uses the full arc.
    private static final int PARTICLE_EVERY_N_POINTS = 2;
    // Persistent displays. These move with the blade instead of constantly spawning.
    private static final int HEAD_DISPLAY_COUNT = 11;
    // How much each display overlaps the next one on the blade head.
    private static final double HEAD_DISPLAY_COVERAGE_MULTIPLIER = 1.35;
    // Thin icy cutting-strip dimensions.
    private static final float HEAD_DISPLAY_THICKNESS = 0.035F;
    private static final float HEAD_DISPLAY_CUT_DEPTH = 0.28F;
    // Height is auto-scaled based on growth.
    private static final float MIN_HEAD_DISPLAY_HEIGHT = 0.18F;
    private static final float MAX_HEAD_DISPLAY_HEIGHT = 1.25F;
    private final List<BlockDisplay> bladeHeadDisplays = new ArrayList<>();
    private Location location;
    private Vector direction;
    private double travelled;
    @Attribute("Growth")
    private double growth = 1;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("CollisionRadius")
    private double entityCollisionRadius;
    private double growthRate = 0.125;

    public AirBlade(Player player) {
        super(player);

        if (!bPlayer.canBend(this)) {
            return;
        }

        setFields();

        this.location = player.getEyeLocation().clone();
        this.direction = player.getEyeLocation().getDirection().clone().normalize();

        start();

        if (!isRemoved()) {
            bPlayer.addCooldown(this);
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Air.AirBlade.Cooldown");
        range = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirBlade.Range");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirBlade.Damage");
        entityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirBlade.EntityCollisionRadius");
        growth = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirBlade.Growth");
        growthRate = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirBlade.GrowthRate");
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (travelled >= range) {
            remove();
            return;
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_LAND, 1f, 1f);
        playAirbendingSound(location);

        progressBlade();
    }

    private void progressBlade() {
        final boolean authoritative = CooldownSync.isAuthoritative();
        for (int j = 0; j < 2; j++) {
            location = location.add(direction.clone());
            travelled++;
            growth += growthRate;

            if (travelled >= range) {
                remove();
                return;
            }

            if (!isTransparent(location.getBlock())) {
                remove();
                return;
            }

            if (RegionProtection.isRegionProtected(player, location, this)) {
                remove();
                return;
            }

            double pitch = -location.getPitch();
            Location lastLoc = location.clone();
            int arcIndex = 0;

            for (double i = -90 + pitch; i <= 90 + pitch; i += ARC_STEP) {
                Location tempLoc = getArcLocation(i);

                if (arcIndex % PARTICLE_EVERY_N_POINTS == 0) {
                    playAirbendingParticles(
                            tempLoc,
                            1,
                            (float) Math.random() / 2,
                            (float) Math.random() / 2,
                            (float) Math.random() / 2
                    );
                }

                if (j == 0) {
                    if (!lastLoc.getBlock().getLocation().equals(tempLoc.getBlock().getLocation())) {
                        lastLoc = tempLoc;

                        boolean hit = CollisionDetector.checkEntityCollisions(player, new Sphere(tempLoc, entityCollisionRadius), entity -> {
                            if (!authoritative) return true;
                            DamageHandler.damageEntity(entity, damage, this);
                            final Location hitLocation = entity.getLocation().clone();
                            ConfirmedHitEffects.sound(this, entity, () -> {
                                hitLocation.getWorld().playSound(hitLocation, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.45F);
                                hitLocation.getWorld().playSound(hitLocation, Sound.ENTITY_BREEZE_HURT, 1f, 2f);
                            });
                            remove();
                            return true;
                        });

                        if (hit && authoritative) {
                            remove();
                            return;
                        }
                    }
                }

                arcIndex++;
            }
        }

        updateBladeHeadDisplays();
    }

    private Location getArcLocation(double angleDegrees) {
        Location tempLoc = location.clone();

        Vector visualForward = getHorizontalBladeDirection();

        double radians = Math.toRadians(angleDegrees);

        Vector horizontalOffset = visualForward.clone().multiply(growth * Math.cos(radians));
        double verticalOffset = growth * Math.sin(radians);

        tempLoc.add(horizontalOffset);
        tempLoc.setY(tempLoc.getY() + verticalOffset);

        return tempLoc;
    }

    private Vector getHorizontalBladeDirection() {
        Location yawOnly = location.clone();
        yawOnly.setPitch(0);

        Vector visualForward = yawOnly.getDirection().clone();
        visualForward.setY(0);

        if (visualForward.lengthSquared() < 0.0001) {
            visualForward = direction.clone();
            visualForward.setY(0);
        }

        if (visualForward.lengthSquared() < 0.0001) {
            visualForward = new Vector(1, 0, 0);
        }

        return visualForward.normalize();
    }

    private void updateBladeHeadDisplays() {
        ensureBladeHeadDisplays();

        double pitch = -location.getPitch();
        double startAngle = -90 + pitch;
        double endAngle = 90 + pitch;
        double angleStep = (endAngle - startAngle) / Math.max(1, HEAD_DISPLAY_COUNT - 1);

        float displayHeight = getAutoScaledHeadDisplayHeight(angleStep);
        Transformation transformation = createHeadDisplayTransformation(displayHeight);

        Vector forward = direction.clone().normalize();

        for (int index = 0; index < HEAD_DISPLAY_COUNT; index++) {
            BlockDisplay display = bladeHeadDisplays.get(index);

            if (display == null || display.isDead()) {
                display = createBladeHeadDisplay();
                bladeHeadDisplays.set(index, display);
            }

            double angle = startAngle + angleStep * index;
            Location displayLoc = getArcLocation(angle);

            displayLoc.setDirection(forward);

            display.teleport(displayLoc);
            display.setTransformation(transformation);
        }
    }

    private void ensureBladeHeadDisplays() {
        while (bladeHeadDisplays.size() < HEAD_DISPLAY_COUNT) {
            bladeHeadDisplays.add(createBladeHeadDisplay());
        }

        while (bladeHeadDisplays.size() > HEAD_DISPLAY_COUNT) {
            BlockDisplay old = bladeHeadDisplays.remove(bladeHeadDisplays.size() - 1);

            if (old != null && !old.isDead()) {
                old.remove();
            }
        }
    }

    private BlockDisplay createBladeHeadDisplay() {
        return location.getWorld().spawn(location, BlockDisplay.class, blockDisplay -> {
            blockDisplay.setBlock(Material.FROSTED_ICE.createBlockData());

            blockDisplay.setBillboard(Display.Billboard.FIXED);
            blockDisplay.setShadowRadius(0F);
            blockDisplay.setShadowStrength(0F);
            blockDisplay.setViewRange(32F);
            blockDisplay.setInterpolationDuration(1);
            blockDisplay.setBrightness(new Display.Brightness(15, 15));
        });
    }

    private float getAutoScaledHeadDisplayHeight(double angleStep) {
        double arcLength = growth * Math.toRadians(angleStep) * HEAD_DISPLAY_COVERAGE_MULTIPLIER;

        if (arcLength < MIN_HEAD_DISPLAY_HEIGHT) {
            arcLength = MIN_HEAD_DISPLAY_HEIGHT;
        }

        if (arcLength > MAX_HEAD_DISPLAY_HEIGHT) {
            arcLength = MAX_HEAD_DISPLAY_HEIGHT;
        }

        return (float) arcLength;
    }

    private Transformation createHeadDisplayTransformation(float displayHeight) {
        return new Transformation(
                new Vector3f(
                        -HEAD_DISPLAY_THICKNESS / 2F,
                        -displayHeight / 2F,
                        -HEAD_DISPLAY_CUT_DEPTH / 2F
                ),
                new AxisAngle4f(0F, 0F, 1F, 0F),
                new Vector3f(
                        HEAD_DISPLAY_THICKNESS,
                        displayHeight,
                        HEAD_DISPLAY_CUT_DEPTH
                ),
                new AxisAngle4f(0F, 0F, 1F, 0F)
        );
    }

    private void clearBladeHeadDisplays() {
        for (BlockDisplay display : bladeHeadDisplays) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }

        bladeHeadDisplays.clear();
    }

    @Override
    public void remove() {
        clearBladeHeadDisplays();
        super.remove();
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public List<Location> getLocations() {
        List<Location> locations = new ArrayList<>();

        double pitch = -location.getPitch();

        for (double i = -90 + pitch; i <= 90 + pitch; i += ARC_STEP) {
            locations.add(getArcLocation(i));
        }

        return locations;
    }

    @Override
    public double getCollisionRadius() {
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirBlade.AbilityCollisionRadius");
    }

    @Override
    public List<Location> getEntityHitLocations() {
        return getLocations();
    }

    @Override
    public double getEntityHitRadius() {
        return entityCollisionRadius;
    }

    public long getCooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public String getName() {
        return "AirBlade";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Air.AirBlade.Description");
    }

    public Vector getDirection() {
        return direction;
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public double getTravelled() {
        return travelled;
    }

    public void setTravelled(double travelled) {
        this.travelled = travelled;
    }

    public double getGrowth() {
        return growth;
    }

    public void setGrowth(double growth) {
        this.growth = growth;
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public double getEntityCollisionRadius() {
        return entityCollisionRadius;
    }

    public void setEntityCollisionRadius(double entityCollisionRadius) {
        this.entityCollisionRadius = entityCollisionRadius;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
        if (bladeHeadDisplays != null) {
            return;
        }

        clearBladeHeadDisplays();
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Air.AirBlade.Enabled");
    }
}
