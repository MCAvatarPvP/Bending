package com.projectkorra.projectkorra.firebending.combo;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.colliders.AABB;

import java.util.ArrayList;
import java.util.List;

public class FireWheel extends FireAbility implements ComboAbility {

    private Location origin;
    private Location location;
    private Vector direction;
    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    @DayNightFactor
    private double range;
    @Attribute(Attribute.HEIGHT)
    @DayNightFactor
    private double height;
    private double hitboxWidth;
    private double hitboxHeight;
    private int heightRadius;
    private double circleRadius;
    @Attribute(Attribute.SPEED)
    @DayNightFactor
    private double speed;
    @Attribute(Attribute.FIRE_TICK)
    @DayNightFactor
    private double fireTicks;
    @Attribute(Attribute.DAMAGE)
    @DayNightFactor
    private double damage;
    private ArrayList<LivingEntity> affectedEntities;

    public FireWheel(final Player player) {
        super(player);

        if (this.bPlayer.isOnCooldown("FireWheel") && !this.bPlayer.isAvatarState()) {
            this.remove();
            return;
        }

        this.damage = getConfig().getDouble("Abilities.Fire.FireWheel.Damage");
        this.range = getConfig().getDouble("Abilities.Fire.FireWheel.Range");
        this.speed = getConfig().getDouble("Abilities.Fire.FireWheel.Speed");
        this.cooldown = getConfig().getLong("Abilities.Fire.FireWheel.Cooldown");
        this.fireTicks = getConfig().getDouble("Abilities.Fire.FireWheel.FireTicks");
        this.height = getConfig().getInt("Abilities.Fire.FireWheel.Height");
        final double legacyRadius = applyModifiers(getConfig().getDouble("Abilities.Fire.FireWheel.Radius"));
        this.hitboxWidth = applyModifiers(getConfig().getDouble("Abilities.Fire.FireWheel.HitboxWidth", legacyRadius + 0.5));
        this.hitboxHeight = applyModifiers(getConfig().getDouble("Abilities.Fire.FireWheel.HitboxHeight", this.height / 2.0 + 0.5));
        this.heightRadius = getConfig().getInt("Abilities.Fire.FireWheel.HeightRadius");
        this.circleRadius = applyModifiers(getConfig().getDouble("Abilities.Fire.FireWheel.CircleRadius"));

        this.bPlayer.addCooldown(this);
        this.affectedEntities = new ArrayList<LivingEntity>();

        if (GeneralMethods.getTopBlock(player.getLocation(), 3, 3) == null) {
            this.remove();
            return;
        }

        this.location = player.getLocation().clone();
        this.location.setPitch(0);
        this.direction = this.location.getDirection().clone().normalize();
        this.direction.setY(0);

        this.origin = player.getLocation().clone().add(0, this.height / 2.0, 0);

        this.start();
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new FireWheel(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this, ConfigManager.defaultConfig.get().getStringList("Abilities.Fire.FireWheel.Combination"));
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this) || GeneralMethods.isRegionProtectedFromBuild(this.player, this.location)) {
            this.remove();
            return;
        }
        if (this.location.distanceSquared(this.origin) > this.range * this.range) {
            this.remove();
            return;
        }

        Block topBlock = GeneralMethods.getTopBlock(this.location.clone().add(0, -circleRadius / 2, 0), this.heightRadius - 1, this.heightRadius + 1);
        if (topBlock.getType().equals(Material.SNOW)) {
            topBlock.breakNaturally();
            topBlock = topBlock.getRelative(BlockFace.DOWN);
        }
        if (isWater(topBlock) && !canPassThroughWater(topBlock)) {
            this.remove();
            return;
        } else if (topBlock.getType() == Material.FIRE) {
            topBlock = topBlock.getRelative(BlockFace.DOWN);
        } else if (ElementalAbility.isPlant(topBlock)) {
            topBlock.breakNaturally();
            topBlock = topBlock.getRelative(BlockFace.DOWN);
        } else if (ElementalAbility.isAir(topBlock.getType())) {
            this.remove();
            return;
        } else if ((!this.isEarthSmashTempBlock(topBlock.getRelative(BlockFace.UP)) && GeneralMethods.isSolid(topBlock.getRelative(BlockFace.UP))) || (isWater(topBlock.getRelative(BlockFace.UP)) && !canPassThroughWater(topBlock.getRelative(BlockFace.UP)))) {
            this.remove();
            return;
        }
        this.location.setY(topBlock.getY() + this.height);

        for (double i = -180; i <= 180; i += 3) {
            final Location tempLoc = this.location.clone();
            final Vector newDir = this.direction.clone().multiply(this.circleRadius * Math.cos(Math.toRadians(i)));
            tempLoc.add(newDir);
            tempLoc.setY(tempLoc.getY() + (this.circleRadius * Math.sin(Math.toRadians(i))));
            playFirebendingParticles(tempLoc, 0, 0, 0, 0);
            emitFirebendingLight(tempLoc);
        }

        for (final Entity entity : new AABB(this.location, this.hitboxWidth, this.hitboxHeight).getEntities(Entity.class::isInstance)) {
            if (entity instanceof LivingEntity && !entity.equals(this.player)) {
                if (!this.affectedEntities.contains(entity)) {
                    this.affectedEntities.add((LivingEntity) entity);
                    DamageHandler.damageEntity(entity, this.damage, this);
                    entity.setFireTicks((int) (this.fireTicks * 20));
                    new FireDamageTimer(entity, this.player, this);
                }
            }
        }

        this.location = this.location.add(this.direction.clone().multiply(this.speed));
        this.location.getWorld().playSound(this.location, Sound.BLOCK_FIRE_AMBIENT, 1, 1);
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public String getName() {
        return "FireWheel";
    }

    @Override
    public Location getLocation() {
        return this.location;
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
        return this.location != null;
    }

    @Override
    public void handleCollision(final Collision collision) {
        super.handleCollision(collision);
    }

    @Override
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();
        if (this.location == null) {
            return locations;
        }

        locations.add(this.location.clone());
        for (double angle = -180; angle < 180; angle += 15) {
            final Location tempLoc = this.location.clone();
            final Vector offset = this.direction.clone().multiply(this.circleRadius * Math.cos(Math.toRadians(angle)));
            tempLoc.add(offset);
            tempLoc.setY(tempLoc.getY() + (this.circleRadius * Math.sin(Math.toRadians(angle))));
            locations.add(tempLoc);
        }
        return locations;
    }

    @Override
    public double getCollisionRadius() {
        return Math.max(0.75, this.hitboxWidth / 2.0);
    }

    public ArrayList<LivingEntity> getAffectedEntities() {
        return this.affectedEntities;
    }

    private boolean isEarthSmashTempBlock(final Block block) {
        return TempBlock.isTempBlock(block) && TempBlock.get(block).getAbility().orElse(null) instanceof EarthSmash;
    }
}
