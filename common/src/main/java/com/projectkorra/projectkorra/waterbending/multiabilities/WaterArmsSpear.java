package com.projectkorra.projectkorra.waterbending.multiabilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms.Arm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class WaterArmsSpear extends WaterAbility {

    private static final Map<Block, Long> ICE_BLOCKS = new ConcurrentHashMap<Block, Long>();
    private static final Map<Block, TempBlock> TRACKED_BLOCKS = new ConcurrentHashMap<>();
    private final List<Location> spearLocations;
    private final Random random;
    private boolean hitEntity;
    private boolean canFreeze;
    private boolean usageCooldownEnabled;
    @Attribute("DamageEnabled")
    private boolean spearDamageEnabled;
    @Attribute("Length")
    private int spearLength;
    @Attribute(Attribute.RANGE)
    @DayNightFactor
    private double spearRange;
    private int spearRangeNight;
    private int spearRangeFullMoon;
    @Attribute("SphereRadius")
    @DayNightFactor
    private int spearSphereRadius;
    private int spearSphereNight;
    private int spearSphereFullMoon;
    private int distanceTravelled;
    @Attribute(Attribute.DURATION)
    @DayNightFactor
    private long spearDuration;
    private long spearDurationNight;
    private long spearDurationFullMoon;
    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long usageCooldown;
    @Attribute(Attribute.DAMAGE)
    @DayNightFactor
    private double spearDamage;
    private double spearRadius;
    private Arm arm;
    private Location location;
    private Location initLocation;
    private WaterArms waterArms;
    private List<TempBlock> waterBlocks = new ArrayList<>();
    private List<TempBlock> iceBlocks = new ArrayList<>();

    public WaterArmsSpear(final Player player, final boolean freeze) {
        super(player);
        this.canFreeze = freeze;
        this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());

        this.usageCooldownEnabled = getConfig().getBoolean("Abilities.Water.WaterArms.Arms.Cooldowns.UsageCooldown.Enabled");
        this.spearDamageEnabled = getConfig().getBoolean("Abilities.Water.WaterArms.Spear.DamageEnabled");
        this.spearLength = getConfig().getInt("Abilities.Water.WaterArms.Spear.Length");
        this.spearRange = getConfig().getDouble("Abilities.Water.WaterArms.Spear.Range");
        this.spearRangeNight = getConfig().getInt("Abilities.Water.WaterArms.Spear.NightAugments.Range.Normal");
        this.spearRangeFullMoon = getConfig().getInt("Abilities.Water.WaterArms.Spear.NightAugments.Range.FullMoon");
        this.spearSphereRadius = getConfig().getInt("Abilities.Water.WaterArms.Spear.SphereRadius");
        this.spearSphereNight = getConfig().getInt("Abilities.Water.WaterArms.Spear.NightAugments.Sphere.Normal");
        this.spearSphereFullMoon = getConfig().getInt("Abilities.Water.WaterArms.Spear.NightAugments.Sphere.FullMoon");
        this.spearDuration = getConfig().getLong("Abilities.Water.WaterArms.Spear.Duration");
        this.spearDurationNight = getConfig().getLong("Abilities.Water.WaterArms.Spear.NightAugments.Duration.Normal");
        this.spearDurationFullMoon = getConfig().getLong("Abilities.Water.WaterArms.Spear.NightAugments.Duration.FullMoon");
        this.usageCooldown = getConfig().getLong("Abilities.Water.WaterArms.Arms.Cooldowns.UsageCooldown.Spear");
        this.spearDamage = getConfig().getDouble("Abilities.Water.WaterArms.Spear.Damage");
        this.spearRadius = getConfig().getDouble("Abilities.Water.WaterArms.Spear.Radius");
        this.spearLocations = new ArrayList<>();

        this.createInstance();
    }

    public static boolean canThaw(final Block block) {
        final TempBlock layer = TRACKED_BLOCKS.get(block);
        return layer != null && !layer.isReverted() && isIce(layer.getBlockData().getMaterial())
                || TempBlockSync.hasAuthoritativeEffect(block, "WaterArmsSpear");
    }

    public static void thaw(final Block block) {
        if (!canThaw(block)) return;
        final TempBlock layer = TRACKED_BLOCKS.remove(block);
        ICE_BLOCKS.remove(block);
        if (layer != null) layer.revertBlock();
    }

    public static Map<Block, Long> getIceBlocks() {
        return ICE_BLOCKS;
    }

    public static Map<Block, TempBlock> getTrackedBlocks() {
        return Map.copyOf(TRACKED_BLOCKS);
    }

    public static void expireBlocks(final boolean ignoreTime) {
        final long now = System.currentTimeMillis();
        for (Map.Entry<Block, Long> entry : List.copyOf(ICE_BLOCKS.entrySet())) {
            if (!ignoreTime && now <= entry.getValue()) continue;
            final Block block = entry.getKey();
            final TempBlock layer = TRACKED_BLOCKS.remove(block);
            ICE_BLOCKS.remove(block, entry.getValue());
            if (layer != null) layer.revertBlock();
        }
    }

    /** Drops every old-world spear layer index without restoring a block. */
    public static void discardAllTracking() {
        ICE_BLOCKS.clear();
        TRACKED_BLOCKS.clear();
    }

    private static void track(final TempBlock layer, final long duration) {
        if (layer == null || layer.isReverted()) return;
        final Block block = layer.getBlock();
        final TempBlock previous = TRACKED_BLOCKS.put(block, layer);
        final long expiresAt = System.currentTimeMillis() + Math.max(1L, duration);
        ICE_BLOCKS.put(block, expiresAt);
        layer.setRevertTask(() -> {
            if (TRACKED_BLOCKS.remove(block, layer)) ICE_BLOCKS.remove(block);
        });
        layer.setRevertTime(Math.max(1L, duration));
        if (previous != null && previous != layer && !previous.isReverted()) previous.revertBlock();
    }

    private void createInstance() {
        this.waterArms = getAbility(this.player, WaterArms.class);
        if (this.waterArms != null) {
            this.waterArms.switchPreferredArm();
            this.arm = this.waterArms.getActiveArm();

            if (this.arm.equals(Arm.LEFT)) {
                if (this.waterArms.isLeftArmCooldown() || this.bPlayer.isOnCooldown("WaterArms_LEFT") || !this.waterArms.displayLeftArm()) {
                    return;
                } else {
                    if (this.usageCooldownEnabled) {
                        this.bPlayer.addCooldown("WaterArms_LEFT", this.usageCooldown);
                    }
                    this.waterArms.setLeftArmConsumed(true);
                    this.waterArms.setLeftArmCooldown(true);
                }
            }
            if (this.arm.equals(Arm.RIGHT)) {
                if (this.waterArms.isRightArmCooldown() || this.bPlayer.isOnCooldown("WaterArms_RIGHT") || !this.waterArms.displayRightArm()) {
                    return;
                } else {
                    if (this.usageCooldownEnabled) {
                        this.bPlayer.addCooldown("WaterArms_RIGHT", this.usageCooldown);
                    }
                    this.waterArms.setRightArmConsumed(true);
                    this.waterArms.setRightArmCooldown(true);
                }
            }
            final Vector dir = this.player.getLocation().getDirection();
            this.location = this.waterArms.getActiveArmEnd().add(dir.normalize().multiply(1));
            this.initLocation = this.location.clone();
        } else {
            return;
        }
        this.start();
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()) {
            this.remove();
            return;
        } else if (this.distanceTravelled > this.spearRange) {
            this.remove();
            return;
        }

        if (!this.hitEntity) {
            this.progressSpear();
        } else {
            this.createIceBall();
            this.remove();
        }

        if (!this.canPlaceBlock(this.location.getBlock())) {
            if (this.canFreeze) {
                this.createSpear();
            }
            this.remove();
            return;
        }
    }

    private void progressSpear() {
        for (int i = 0; i < 2; i++) {
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, spearRadius)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != this.player.getEntityId() && !(entity instanceof ArmorStand)) {
                    this.hitEntity = true;
                    this.location = entity.getLocation();

                    if (this.spearDamageEnabled) {
                        DamageHandler.damageEntity(entity, this.spearDamage, this);
                    }

                    return;
                }
            }

            if (!this.canPlaceBlock(this.location.getBlock())) {
                return;
            }

            final TempBlock water = new TempBlock(this.location.getBlock(), Material.WATER.createBlockData(), this);
            waterBlocks.add(water);
            track(water, 600L);
            final Vector direction = GeneralMethods.getDirection(this.initLocation, GeneralMethods.getTargetedLocation(this.player, this.spearRange, getTransparentMaterials())).normalize();

            this.location = this.location.add(direction.clone().multiply(1));
            this.spearLocations.add(this.location.clone());

            this.distanceTravelled++;
        }
    }

    private void createSpear() {
        waterBlocks.forEach(TempBlock::revertBlock);
        waterBlocks.clear();

        for (int i = this.spearLocations.size() - this.spearLength; i < this.spearLocations.size(); i++) {
            if (i >= 0) {
                final Block block = this.spearLocations.get(i).getBlock();
                if (this.canPlaceBlock(block)) {
                    playIcebendingSound(block.getLocation());
                    final TempBlock ice = new TempBlock(block, getIceData(), this).setCanSuffocate(false);
                    iceBlocks.add(ice);
                    track(ice, this.spearDuration + this.random.nextInt(500));
                }
            }
        }
    }

    private void createIceBall() {
        if (this.spearSphereRadius <= 0) {
            if (this.canFreeze) {
                this.createSpear();
            }
            return;
        }
        final List<Entity> trapped = GeneralMethods.getEntitiesAroundPoint(this.location, this.spearSphereRadius);
        ICE_SETTING:
        for (final Block block : GeneralMethods.getBlocksAroundPoint(this.location, this.spearSphereRadius)) {
            if (isTransparent(this.player, block) && !isIce(block) && !WaterArms.isUnbreakable(block)) {
                for (final Entity entity : trapped) {
                    if (entity instanceof Player) {
                        if (Commands.invincible.contains(((Player) entity).getName())) {
                            return;
                        }
                        if (!getConfig().getBoolean("Properties.Water.FreezePlayerHead") && GeneralMethods.playerHeadIsInBlock((Player) entity, block, true)) {
                            continue ICE_SETTING;
                        }
                        if (!getConfig().getBoolean("Properties.Water.FreezePlayerFeet") && GeneralMethods.playerFeetIsInBlock((Player) entity, block, true)) {
                            continue ICE_SETTING;
                        }
                    }
                }
                playIcebendingSound(block.getLocation());
                final TempBlock ice = new TempBlock(block, getIceData(), this).setCanSuffocate(false);
                iceBlocks.add(ice);
                track(ice, this.spearDuration + this.random.nextInt(500));
            }
        }
    }

    private boolean canPlaceBlock(final Block block) {
        if (!isTransparent(this.player, block) && !((isWater(block) || this.isIcebendable(block)) && (TempBlock.isTempBlock(block) && !getIceBlocks().containsKey(block)))) {
            return false;
        } else if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation()) || GeneralMethods.isSolid(block)) {
            return false;
        } else if (WaterArms.isUnbreakable(block) && !isWater(block)) {
            return false;
        }
        return true;
    }

    @Override
    public void remove() {
        super.remove();
        if (hasAbility(this.player, WaterArms.class)) {
            if (this.arm.equals(Arm.LEFT)) {
                this.waterArms.setLeftArmCooldown(false);
            } else {
                this.waterArms.setRightArmCooldown(false);
            }
            this.waterArms.setMaxUses(this.waterArms.getMaxUses() - 1);
        }
    }

    @Override
    public boolean isHiddenAbility() {
        return true;
    }

    @Override
    public String getName() {
        return "WaterArmsSpear";
    }

    @Override
    public Location getLocation() {
        if (this.location != null) {
            return this.location;
        } else {
            return this.initLocation;
        }
    }

    public void setLocation(final Location location) {
        this.location = location;
    }

    @Override
    public long getCooldown() {
        return this.usageCooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public boolean isHitEntity() {
        return this.hitEntity;
    }

    public void setHitEntity(final boolean hitEntity) {
        this.hitEntity = hitEntity;
    }

    public boolean isCanFreeze() {
        return this.canFreeze;
    }

    public void setCanFreeze(final boolean canFreeze) {
        this.canFreeze = canFreeze;
    }

    public boolean isUsageCooldownEnabled() {
        return this.usageCooldownEnabled;
    }

    public void setUsageCooldownEnabled(final boolean usageCooldownEnabled) {
        this.usageCooldownEnabled = usageCooldownEnabled;
    }

    public boolean isSpearDamageEnabled() {
        return this.spearDamageEnabled;
    }

    public void setSpearDamageEnabled(final boolean spearDamageEnabled) {
        this.spearDamageEnabled = spearDamageEnabled;
    }

    public int getSpearLength() {
        return this.spearLength;
    }

    public void setSpearLength(final int spearLength) {
        this.spearLength = spearLength;
    }

    public double getSpearRange() {
        return this.spearRange;
    }

    public void setSpearRange(final int spearRange) {
        this.spearRange = spearRange;
    }

    public int getSpearRangeNight() {
        return this.spearRangeNight;
    }

    public void setSpearRangeNight(final int spearRangeNight) {
        this.spearRangeNight = spearRangeNight;
    }

    public int getSpearRangeFullMoon() {
        return this.spearRangeFullMoon;
    }

    public void setSpearRangeFullMoon(final int spearRangeFullMoon) {
        this.spearRangeFullMoon = spearRangeFullMoon;
    }

    public int getSpearSphere() {
        return this.spearSphereRadius;
    }

    public void setSpearSphere(final int spearSphere) {
        this.spearSphereRadius = spearSphere;
    }

    public int getSpearSphereNight() {
        return this.spearSphereNight;
    }

    public void setSpearSphereNight(final int spearSphereNight) {
        this.spearSphereNight = spearSphereNight;
    }

    public int getSpearSphereFullMoon() {
        return this.spearSphereFullMoon;
    }

    public void setSpearSphereFullMoon(final int spearSphereFullMoon) {
        this.spearSphereFullMoon = spearSphereFullMoon;
    }

    public int getDistanceTravelled() {
        return this.distanceTravelled;
    }

    public void setDistanceTravelled(final int distanceTravelled) {
        this.distanceTravelled = distanceTravelled;
    }

    public long getSpearDuration() {
        return this.spearDuration;
    }

    public void setSpearDuration(final long spearDuration) {
        this.spearDuration = spearDuration;
    }

    public long getSpearDurationNight() {
        return this.spearDurationNight;
    }

    public void setSpearDurationNight(final long spearDurationNight) {
        this.spearDurationNight = spearDurationNight;
    }

    public long getSpearDurationFullMoon() {
        return this.spearDurationFullMoon;
    }

    public void setSpearDurationFullMoon(final long spearDurationFullMoon) {
        this.spearDurationFullMoon = spearDurationFullMoon;
    }

    public long getUsageCooldown() {
        return this.usageCooldown;
    }

    public void setUsageCooldown(final long usageCooldown) {
        this.usageCooldown = usageCooldown;
    }

    public double getSpearDamage() {
        return this.spearDamage;
    }

    public void setSpearDamage(final double spearDamage) {
        this.spearDamage = spearDamage;
    }

    public Arm getArm() {
        return this.arm;
    }

    public void setArm(final Arm arm) {
        this.arm = arm;
    }

    public Location getInitLocation() {
        return this.initLocation;
    }

    public void setInitLocation(final Location initLocation) {
        this.initLocation = initLocation;
    }

    public WaterArms getWaterArms() {
        return this.waterArms;
    }

    public void setWaterArms(final WaterArms waterArms) {
        this.waterArms = waterArms;
    }

    public List<Location> getSpearLocations() {
        return this.spearLocations;
    }
}
