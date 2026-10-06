package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.Objects;
import java.util.UUID;

/** Detached motion/pose presentation. No simulation entity, world, inventory or mutable render state is retained. */
public record FabricRollbackPlayerView(UUID id, int entityId, Motion motion, Living living, CameraMotion cameraMotion, Health health) {
    /** Detached values used by the native heart/armor display; never a mutable player reference. */
    public record Health(float value, double maximum, float absorption, int armor, int regenerationDelay, boolean regenerating) {
        public Health { finite(value); finite(maximum); finite(absorption); }
    }
    public record Angle(float previous, float current) {
        public Angle { finite(previous); finite(current); }
        float sample(float delta) { return MathHelper.lerpAngleDegrees(delta, previous, current); }
    }
    public record Motion(Vec3d previous, Vec3d position, Box bounds, int age, float width, float height, float eyeHeight) {
        public Motion {
            Objects.requireNonNull(previous); Objects.requireNonNull(position); Objects.requireNonNull(bounds);
            for (double value : new double[]{previous.x, previous.y, previous.z, position.x, position.y, position.z,
                    bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, width, height, eyeHeight}) finite(value);
            if (width < 0 || height < 0 || eyeHeight < 0) throw new IllegalArgumentException("Render dimensions");
        }
        public Vec3d sample(float delta) { progress(delta); return previous.lerp(position, delta); }
        public Box cullingBounds() { return bounds.union(bounds.offset(previous.subtract(position))).expand(.5); }
    }
    public record Limbs(float previousSpeed, float speed, float animationProgress, float timeScale) {
        public Limbs { finite(previousSpeed); finite(speed); finite(animationProgress); finite(timeScale); }
        float amplitude(float delta) { return Math.min(MathHelper.lerp(delta, previousSpeed, speed), 1); }
        float position(float delta) { return (animationProgress - speed * (1 - delta)) * timeScale; }
    }
    /** Camera history uses native lastX/Y/Z; model interpolation separately uses lastRenderX/Y/Z. */
    public record CameraMotion(Vec3d previousPosition, Angle yaw, float eyeHeight, float distance) {
        public CameraMotion {
            Objects.requireNonNull(previousPosition); Objects.requireNonNull(yaw);
            finite(previousPosition.x); finite(previousPosition.y); finite(previousPosition.z); finite(eyeHeight); finite(distance);
            if (eyeHeight < 0 || distance < 0) throw new IllegalArgumentException("Camera dimensions");
        }
        Vec3d sample(Vec3d position, float previousEye, float delta) {
            progress(delta); return previousPosition.lerp(position, delta).add(0, MathHelper.lerp(delta, previousEye, eyeHeight), 0);
        }
    }
    public record Living(Angle bodyYaw, Angle headYaw, Angle pitch, Angle vehicleBodyYaw, Limbs limbs,
                         EntityPose pose, Direction sleepingDirection, float baseScale, float ageScale,
                         int hurtTime, int deathTime, boolean alive, boolean invisible, boolean invisibleToViewer,
                         boolean sneaking, boolean onFire, boolean shaking, boolean baby, boolean touchingWater,
                         boolean usingRiptide, boolean hasVehicle, boolean crouching, boolean gliding, boolean swimming) {
        public Living {
            Objects.requireNonNull(bodyYaw); Objects.requireNonNull(headYaw); Objects.requireNonNull(pitch);
            Objects.requireNonNull(limbs); Objects.requireNonNull(pose); finite(baseScale); finite(ageScale);
        }
    }
    public FabricRollbackPlayerView { Objects.requireNonNull(id); Objects.requireNonNull(motion); Objects.requireNonNull(living); Objects.requireNonNull(cameraMotion); Objects.requireNonNull(health); }

    static FabricRollbackPlayerView capture(PlayerEntity player, PlayerEntity viewer) {
        var limbs = player.limbAnimator;
        var vehicle = player.getVehicle() instanceof LivingEntity body ? new Angle(body.lastBodyYaw, body.bodyYaw) : null;
        var sleeping = player.getSleepingDirection();
        float distance = player.getScale() * (float) player.getAttributeValue(EntityAttributes.CAMERA_DISTANCE);
        if (player.getVehicle() instanceof LivingEntity mount) distance = Math.max(distance, mount.getScale() * (float) mount.getAttributeValue(EntityAttributes.CAMERA_DISTANCE));
        return new FabricRollbackPlayerView(player.getUuid(), player.getId(),
                new Motion(new Vec3d(player.lastRenderX, player.lastRenderY, player.lastRenderZ), player.getEntityPos(), player.getBoundingBox(),
                        player.age, player.getWidth(), player.getHeight(), sleeping == null ? player.getStandingEyeHeight() : player.getEyeHeight(EntityPose.STANDING)),
                new Living(new Angle(player.lastBodyYaw, player.bodyYaw), new Angle(player.lastHeadYaw, player.headYaw),
                        new Angle(player.lastPitch, player.getPitch()), vehicle,
                        new Limbs(limbs.lastSpeed, limbs.speed, limbs.animationProgress, limbs.timeScale), player.getPose(), sleeping,
                        player.getScale(), player.getScaleFactor(), player.hurtTime, player.deathTime, player.isAlive(), player.isInvisible(),
                        player.isInvisible() && player.isInvisibleTo(viewer), player.isSneaky(), player.doesRenderOnFire(), player.isFrozen(), player.isBaby(),
                        player.isTouchingWater(), player.isUsingRiptide(), player.hasVehicle(), player.isInSneakingPose(), player.isGliding(), player.isInSwimmingPose()),
                new CameraMotion(new Vec3d(player.lastX, player.lastY, player.lastZ), new Angle(player.getYaw(0), player.getYaw(1)), player.getStandingEyeHeight(), distance),
                new Health(player.getHealth(), player.getAttributeValue(EntityAttributes.MAX_HEALTH), player.getAbsorptionAmount(),
                        player.getArmor(), player.timeUntilRegen, player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.REGENERATION)));
    }
    public BlockPos blockPos() { return BlockPos.ofFloored(motion.position()); }
    public BlockPos lightPos(float delta) { return BlockPos.ofFloored(motion.sample(delta).add(0, motion.eyeHeight(), 0)); }

    /** Runs after native model extraction but before native shadow extraction, against the newly allocated render state only. */
    public void apply(EntityRenderState state, float delta, Vec3d camera, int light) {
        Objects.requireNonNull(state); Objects.requireNonNull(camera); progress(delta);
        var position = motion.sample(delta);
        state.x = position.x; state.y = position.y; state.z = position.z; state.age = motion.age() + delta;
        state.width = motion.width(); state.height = motion.height(); state.standingEyeHeight = motion.eyeHeight();
        state.squaredDistanceToCamera = position.squaredDistanceTo(camera);
        state.light = living.onFire() ? LightmapTextureManager.pack(15, LightmapTextureManager.getSkyLightCoordinates(light)) : light;
        state.invisible = living.invisible(); state.sneaking = living.sneaking(); state.onFire = living.onFire();
        // The private motion is already in world space; an older server vehicle offset cannot be added to it.
        state.positionOffset = null;
        if (state instanceof LivingEntityRenderState target) {
            float head = living.headYaw().sample(delta), body = living.bodyYaw().sample(delta);
            if (living.vehicleBodyYaw() != null) {
                float difference = MathHelper.clamp(MathHelper.wrapDegrees(head - living.vehicleBodyYaw().sample(delta)), -85, 85);
                body = head - difference; if (Math.abs(difference) > 50) body += difference * .2F;
            }
            target.bodyYaw = body; target.relativeHeadYaw = MathHelper.wrapDegrees(head - body); target.pitch = MathHelper.lerp(delta, living.pitch().previous(), living.pitch().current());
            if (target.flipUpsideDown) { target.relativeHeadYaw *= -1; target.pitch *= -1; }
            boolean animate = !living.hasVehicle() && living.alive();
            target.limbSwingAmplitude = animate ? living.limbs().amplitude(delta) : 0;
            target.limbSwingAnimationProgress = animate ? living.limbs().position(delta) : 0;
            target.headItemAnimationProgress = target.limbSwingAnimationProgress;
            target.baseScale = living.baseScale(); target.ageScale = living.ageScale(); target.pose = living.pose(); target.sleepingDirection = living.sleepingDirection();
            target.hurt = living.hurtTime() > 0 || living.deathTime() > 0; target.deathTime = living.deathTime() > 0 ? living.deathTime() + delta : 0;
            target.invisibleToPlayer = living.invisibleToViewer(); target.shaking = living.shaking(); target.baby = living.baby();
            target.touchingWater = living.touchingWater(); target.usingRiptide = living.usingRiptide();
        }
        if (state instanceof BipedEntityRenderState target) {
            target.isInSneakingPose = living.crouching(); target.isGliding = living.gliding(); target.isSwimming = living.swimming(); target.hasVehicle = living.hasVehicle();
        }
    }
    private static void progress(float delta) { if (!Float.isFinite(delta) || delta < 0 || delta > 1) throw new IllegalArgumentException("Render tick progress"); }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite player render state"); }
}
