package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Launches a translucent BlockDisplay model of the caster. Its speed, recoil,
 * and impact knockback scale with the caster's velocity when the combo is
 * completed.
 */
public class SummonSelf extends AirAbility implements ComboAbility {
    private static final Material MODEL_MATERIAL = Material.WHITE_STAINED_GLASS;
    private static final int MODEL_TELEPORT_DURATION = 3;
    private static final List<ModelPart> MODEL_PARTS = List.of(
            new ModelPart("head", 0.0F, 0.675F, 0.0F, 0.45F, 0.45F, 0.45F),
            new ModelPart("torso", 0.0F, 0.1125F, 0.0F, 0.45F, 0.675F, 0.225F),
            new ModelPart("left_arm", -0.3375F, 0.1125F, 0.0F, 0.225F, 0.675F, 0.225F),
            new ModelPart("right_arm", 0.3375F, 0.1125F, 0.0F, 0.225F, 0.675F, 0.225F),
            new ModelPart("left_leg", -0.1125F, -0.5625F, 0.0F, 0.225F, 0.675F, 0.225F),
            new ModelPart("right_leg", 0.1125F, -0.5625F, 0.0F, 0.225F, 0.675F, 0.225F)
    );

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.SPEED)
    private double minimumSpeed;
    @Attribute(Attribute.SPEED)
    private double maximumSpeed;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.KNOCKBACK)
    private double minimumCasterKnockback;
    @Attribute(Attribute.KNOCKBACK)
    private double casterKnockbackFactor;
    @Attribute(Attribute.KNOCKBACK)
    private double minimumOpponentKnockback;
    @Attribute(Attribute.KNOCKBACK)
    private double opponentKnockbackFactor;
    private double movementSpeedFactor;
    private double collisionRadius;
    private double gravity;
    private double impactRingRadius;
    private double impactRingStep;
    private Location location;
    private Location origin;
    private Vector velocity;
    private double projectileSpeed;
    private float modelYaw;
    private float modelPitch;
    private final ArrayList<BlockDisplay> modelDisplays = new ArrayList<>(MODEL_PARTS.size());
    private static final Random random = new Random();

    public SummonSelf(final Player player) {
        super(player);

        if (hasAbility(player, SummonSelf.class) || !this.bPlayer.canBendIgnoreBindsCooldowns(this) || this.bPlayer.isOnCooldown(this)) {
            return;
        }

        final String path = "Abilities.Air.SummonSelf.";
        this.cooldown = getConfig().getLong(path + "Cooldown");
        this.minimumSpeed = getConfig().getDouble(path + "MinimumSpeed");
        this.maximumSpeed = Math.max(this.minimumSpeed, getConfig().getDouble(path + "MaximumSpeed"));
        this.movementSpeedFactor = getConfig().getDouble(path + "MovementSpeedFactor");
        this.range = getConfig().getDouble(path + "Range");
        this.collisionRadius = getConfig().getDouble(path + "CollisionRadius");
        this.gravity = getConfig().getDouble(path + "Gravity");
        this.minimumCasterKnockback = getConfig().getDouble(path + "CasterKnockback.Minimum");
        this.casterKnockbackFactor = getConfig().getDouble(path + "CasterKnockback.SpeedFactor");
        this.minimumOpponentKnockback = getConfig().getDouble(path + "OpponentKnockback.Minimum");
        this.opponentKnockbackFactor = getConfig().getDouble(path + "OpponentKnockback.SpeedFactor");
        this.impactRingRadius = getConfig().getDouble(path + "Impact.RingRadius");
        this.impactRingStep = Math.max(1, getConfig().getDouble(path + "Impact.RingStep"));

        this.projectileSpeed = getProjectileSpeed();
        this.velocity = this.player.getEyeLocation().getDirection().normalize().multiply(this.projectileSpeed);
        this.origin = this.player.getEyeLocation().add(this.velocity.clone().normalize().multiply(1.25));
        this.location = this.origin.clone();
        this.updateModelOrientation();

        this.applyCasterRecoil();
        this.bPlayer.addCooldown(this);
        this.start();
        this.createDisplayModel();
    }

    private double getProjectileSpeed() {
        final double directionalSpeed = player.getVelocity().clone().dot(player.getEyeLocation().getDirection()
                .normalize());

        return Math.min(this.maximumSpeed, Math.max(this.minimumSpeed,
                this.minimumSpeed + directionalSpeed * this.movementSpeedFactor));
    }

    private void applyCasterRecoil() {
        AirBlast airBlast = CoreAbility.getAbility(player, AirBlast.class);
        if (airBlast != null) airBlast.remove();

        final Vector playerVelocity = this.player.getVelocity().clone();

        if (playerVelocity.lengthSquared() > 1.0E-6) {
            final Vector projectileDirection = this.velocity.clone().normalize();

            final double forwardSpeed = playerVelocity.dot(projectileDirection);

            if (forwardSpeed > 0.0D) {
                final Vector forwardVelocity =
                        projectileDirection.clone().multiply(forwardSpeed);

                final Vector resultingVelocity =
                        playerVelocity.subtract(forwardVelocity);

                GeneralMethods.setVelocity(this, this.player, resultingVelocity);
            }
        }

        this.player.setFallDistance(0);
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.5f, 1.2f);
    }

    @Override
    public void progress() {
        if (this.location.getWorld() != this.player.getWorld()) {
            this.remove();
            return;
        }

        if (random.nextInt(4) == 0) {
            final double midPoint = maximumSpeed - minimumSpeed;

            if (projectileSpeed > midPoint) {
                playFastAirbendingSound(this.location);
            } else {
                playAirbendingSound(this.location);
            }
        }

        final int subSteps = Math.max(1, (int) Math.ceil(this.velocity.length() / 0.35));
        final Vector step = this.velocity.clone().multiply(1.0 / subSteps);
        for (int i = 0; i < subSteps; i++) {
            this.location.add(step);
            if (this.hasHitEntity()) {
                this.impact();
                return;
            }
            if (this.hasHitBlock() || this.origin.distanceSquared(this.location) >= this.range * this.range) {
                if (this.hasHitBlock()) {
                    this.impact();
                } else {
                    this.dissipate();
                }

                this.remove();
                return;
            }
        }

        this.velocity.setY(this.velocity.getY() - this.gravity);
        this.location.setDirection(this.velocity);
        this.updateModelOrientation();
        this.updateDisplayModel();
        this.renderAirTrail();
    }

    private boolean hasHitEntity() {
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.collisionRadius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(this.player) || !entity.isValid()) {
                continue;
            }
            if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                continue;
            }
            if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
                continue;
            }

            final double strength = Math.max(this.minimumOpponentKnockback, this.projectileSpeed * this.opponentKnockbackFactor);
            final Vector knockback = this.velocity.clone().normalize().multiply(strength);

            if (entity.isOnGround()) {
                knockback.setY(Math.max(knockback.getY(), 0.35D));
            }

            GeneralMethods.setVelocity(this, entity, knockback);

            new HorizontalVelocityTracker(entity, this.player, 200L, this);
            entity.setFallDistance(0);
            DamageHandler.damageEntity(entity, 1.0, this);
            return true;
        }
        return false;
    }

    private boolean hasHitBlock() {
        return !this.isTransparent(this.location.getBlock()) || GeneralMethods.isRegionProtectedFromBuild(this, this.location);
    }

    private void createDisplayModel() {
        if (this.location == null || this.location.getWorld() == null || !this.modelDisplays.isEmpty()) return;
        final Quaternionf modelRotation = this.modelRotation();
        for (final ModelPart part : MODEL_PARTS) {
            final Location partLocation = this.modelPartLocation(part, modelRotation);
            final BlockDisplay display = this.location.getWorld().spawn(partLocation, BlockDisplay.class);
            display.setBlock(MODEL_MATERIAL.createBlockData());
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0.0F);
            display.setShadowStrength(0.0F);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(MODEL_TELEPORT_DURATION);
            display.setViewRange(32.0F);
            display.setTransformation(this.modelPartTransformation(part, modelRotation));
            this.modelDisplays.add(display);
        }
    }

    private void updateDisplayModel() {
        if (this.modelDisplays.size() != MODEL_PARTS.size()) return;
        final Quaternionf modelRotation = this.modelRotation();
        for (int index = 0; index < MODEL_PARTS.size(); index++) {
            final BlockDisplay display = this.modelDisplays.get(index);
            final ModelPart part = MODEL_PARTS.get(index);
            if (display != null && display.isValid()) {
                display.teleport(this.modelPartLocation(part, modelRotation));
                display.setTransformation(this.modelPartTransformation(part, modelRotation));
            }
        }
    }

    private Location modelPartLocation(final ModelPart part, final Quaternionf rotation) {
        final Vector3f offset = new Vector3f(part.offsetX(), part.offsetY(), part.offsetZ());
        rotation.transform(offset);
        final Location center = this.location.clone().add(offset.x, offset.y, offset.z);
        // Transformation owns the model rotation. Keeping entity rotation at
        // zero prevents Bukkit/Fabric from applying the yaw a second time.
        center.setYaw(0.0F);
        center.setPitch(0.0F);
        return center;
    }

    private Transformation modelPartTransformation(final ModelPart part, final Quaternionf rotation) {
        final Vector3f translation = new Vector3f(
                part.width() * 0.5F,
                part.height() * 0.5F,
                part.depth() * 0.5F
        );
        rotation.transform(translation);
        translation.negate();
        return new Transformation(
                translation,
                rotation,
                new Vector3f(part.width(), part.height(), part.depth()),
                new Quaternionf()
        );
    }

    private Quaternionf modelRotation() {
        return new Quaternionf().rotateY(this.modelYaw).rotateX(this.modelPitch);
    }

    private void updateModelOrientation() {
        Vector facing = this.velocity == null ? null : this.velocity.clone();
        if (facing == null || facing.lengthSquared() < 0.001D) {
            facing = this.player.getLocation().getDirection();
        }
        if (facing.lengthSquared() < 0.001D) {
            facing = new Vector(0.0D, 0.0D, 1.0D);
        }
        facing.normalize();

        final double horizontal = Math.hypot(facing.getX(), facing.getZ());
        if (horizontal >= 0.001D) {
            this.modelYaw = (float) Math.atan2(facing.getX(), facing.getZ());
        }
        this.modelPitch = (float) Math.atan2(-facing.getY(), horizontal);
    }

    private void renderAirTrail() {
        if (this.velocity == null || this.velocity.lengthSquared() < 0.001D) return;
        final Vector behind = this.velocity.clone().normalize().multiply(-0.7D);
        final Location trail = this.location.clone().add(behind);
        playAirbendingParticles(trail, 3, 0.18D, 0.28D, 0.18D, 0.01D);
    }

    private void impact() {
        final Location center = this.location.clone().add(0, 0.4, 0);
        this.animateDirectionalImpact(center);
        this.remove();
    }

    private void animateDirectionalImpact(final Location origin) {
        final double midPoint = maximumSpeed - minimumSpeed;
        if (projectileSpeed <= midPoint) {
            return;
        }

        ParticleUtil.spawn(Particle.GUST, origin, 1, 0, 0, 0);
    }

    private void dissipate() {
        playAirbendingParticles(
                this.location,
                6,
                0.25D,
                0.35D,
                0.25D,
                0.01D
        );

        ParticleUtil.spawn(
                Particle.POOF,
                this.location,
                4,
                0.25D,
                0.25D,
                0.25D
        );
    }

    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        for (final BlockDisplay display : this.modelDisplays) {
            if (display != null && display.isValid()) display.remove();
        }
        this.modelDisplays.clear();
    }

    private record ModelPart(String name, float offsetX, float offsetY, float offsetZ,
                             float width, float height, float depth) {
    }

    @Override
    public String getName() {
        return "SummonSelf";
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
        return true;
    }

    @Override
    public double getCollisionRadius() {
        return this.collisionRadius;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public Location getLocation() {
        return this.location;
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new SummonSelf(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this,
                ConfigManager.defaultConfig.get().getStringList("Abilities.Air.SummonSelf.Combination"));
    }
}
