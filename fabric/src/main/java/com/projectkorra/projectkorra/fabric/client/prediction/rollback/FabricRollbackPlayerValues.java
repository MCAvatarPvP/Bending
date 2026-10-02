package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Kind;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Native counterpart of Paper's captured value-field component. Shared semantic keys
 * bind once through fixed intermediary mappings; packets cannot select members.
 * Prepare checks every field before any native writes. Retained Paper/server fields
 * remain explicit data for the forthcoming native policy/maintenance adapter: applying
 * this component alone is not a complete player import or a claim of tick parity.
 */
public final class FabricRollbackPlayerValues {
    private enum Target {
        PLAYER, FOOD, WALK;
        Object instance(PlayerEntity player) {
            return switch (this) { case PLAYER -> player; case FOOD -> player.getHungerManager(); case WALK -> player.limbAnimator; };
        }
    }
    private static final class Field {
        final String key;
        final Target target;
        final Class<?> type;
        final MethodHandle get, set;
        Field(String key, Target target, Class<?> owner, String intermediaryOwner, String name, String descriptor) {
            this.key = key; this.target = target;
            try {
                String mapped = FabricLoader.getInstance().getMappingResolver().mapFieldName("intermediary", intermediaryOwner, name, descriptor);
                var field = owner.getDeclaredField(mapped);
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) throw new IllegalStateException("Native player value schema changed: " + key);
                type = field.getType();
                FabricRollbackNativeValues.kind(type);
                var lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                get = lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
                set = lookup.unreflectSetter(field).asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native player value binding failed: " + key, failure); }
        }
        Object read(PlayerEntity player) {
            try { return (Object) get.invokeExact(target.instance(player)); }
            catch (Throwable failure) { throw failed(failure); }
        }
        void write(PlayerEntity player, Object value) {
            try { set.invokeExact(target.instance(player), value); }
            catch (Throwable failure) { throw failed(failure); }
        }
    }
    // Minecraft 1.21.11 Mojang semantic names paired with Yarn's intermediary members.
    private static final List<Field> FIELDS = List.of(
            new Field("entity.position", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_22467", "Lnet/minecraft/class_243;"),
            new Field("entity.blockPosition", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_22468", "Lnet/minecraft/class_2338;"),
            new Field("entity.chunkPosition", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_35101", "Lnet/minecraft/class_1923;"),
            new Field("entity.deltaMovement", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_18276", "Lnet/minecraft/class_243;"),
            new Field("entity.yRot", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6031", "F"),
            new Field("entity.xRot", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5965", "F"),
            new Field("entity.bb", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6005", "Lnet/minecraft/class_238;"),
            new Field("entity.dimensions", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_18065", "Lnet/minecraft/class_4048;"),
            new Field("entity.eyeHeight", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_18066", "F"),
            new Field("entity.xo", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6014", "D"),
            new Field("entity.yo", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6036", "D"),
            new Field("entity.zo", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5969", "D"),
            new Field("entity.xOld", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6038", "D"),
            new Field("entity.yOld", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5971", "D"),
            new Field("entity.zOld", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5989", "D"),
            new Field("entity.yRotO", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5982", "F"),
            new Field("entity.xRotO", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6004", "F"),
            new Field("entity.onGround", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5952", "Z"),
            new Field("entity.horizontalCollision", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5976", "Z"),
            new Field("entity.verticalCollision", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5992", "Z"),
            new Field("entity.verticalCollisionBelow", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_36331", "Z"),
            new Field("entity.minorHorizontalCollision", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_34927", "Z"),
            new Field("entity.hurtMarked", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6037", "Z"),
            new Field("entity.stuckSpeedMultiplier", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_17046", "Lnet/minecraft/class_243;"),
            new Field("entity.moveDist", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5994", "F"),
            new Field("entity.flyDist", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_28627", "F"),
            new Field("entity.fallDistance", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6017", "D"),
            new Field("entity.nextStep", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6003", "F"),
            new Field("entity.noPhysics", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5960", "Z"),
            new Field("entity.tickCount", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6012", "I"),
            new Field("entity.remainingFireTicks", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5956", "I"),
            new Field("entity.wasTouchingWater", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5957", "Z"),
            new Field("entity.wasEyeInWater", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6000", "Z"),
            new Field("entity.invulnerableTime", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6008", "I"),
            new Field("entity.firstTick", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5953", "Z"),
            new Field("entity.needsSync", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_64356", "Z"),
            new Field("entity.portalCooldown", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6018", "I"),
            new Field("entity.invulnerable", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_6009", "Z"),
            new Field("entity.hasGlowingTag", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5958", "Z"),
            new Field("entity.pistonDeltasGameTime", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5996", "J"),
            new Field("entity.isInPowderSnow", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_27857", "Z"),
            new Field("entity.wasInPowderSnow", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_28628", "Z"),
            new Field("entity.mainSupportingBlockPos", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_44784", "Ljava/util/Optional;"),
            new Field("entity.onGroundNoBlocks", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_44873", "Z"),
            new Field("entity.crystalSoundIntensity", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_26997", "F"),
            new Field("entity.lastCrystalSoundPlayTick", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_26994", "I"),
            new Field("entity.hasVisualFire", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_33758", "Z"),
            new Field("entity.lastKnownSpeed", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_64357", "Lnet/minecraft/class_243;"),
            new Field("entity.lastKnownPosition", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_64358", "Lnet/minecraft/class_243;"),
            new Field("entity.blocksBuilding", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_23807", "Z"),
            new Field("entity.boardingCooldown", Target.PLAYER, net.minecraft.entity.Entity.class, "net.minecraft.class_1297", "field_5951", "I"),
            new Field("living.swinging", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6252", "Z"),
            new Field("living.discardFriction", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_30082", "Z"),
            new Field("living.swingingArm", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6266", "Lnet/minecraft/class_1268;"),
            new Field("living.swingTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6279", "I"),
            new Field("living.removeArrowTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6218", "I"),
            new Field("living.removeStingerTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_20347", "I"),
            new Field("living.hurtTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6235", "I"),
            new Field("living.hurtDuration", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6254", "I"),
            new Field("living.deathTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6213", "I"),
            new Field("living.oAttackAnim", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6229", "F"),
            new Field("living.attackAnim", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6251", "F"),
            new Field("living.attackStrengthTicker", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6273", "I"),
            new Field("living.itemSwapTicker", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_63291", "I"),
            new Field("living.yBodyRot", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6283", "F"),
            new Field("living.yBodyRotO", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6220", "F"),
            new Field("living.yHeadRot", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6241", "F"),
            new Field("living.yHeadRotO", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6259", "F"),
            new Field("living.lastHurtByPlayerMemoryTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6238", "I"),
            new Field("living.dead", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6272", "Z"),
            new Field("living.noActionTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6278", "I"),
            new Field("living.lastHurt", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6253", "F"),
            new Field("living.jumping", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6282", "Z"),
            new Field("living.xxa", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6212", "F"),
            new Field("living.yya", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6227", "F"),
            new Field("living.zza", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6250", "F"),
            new Field("living.lerpYHeadRot", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_45123", "D"),
            new Field("living.lerpHeadSteps", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6265", "I"),
            new Field("living.effectsDirty", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6285", "Z"),
            new Field("living.lastHurtByMobTimestamp", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6230", "I"),
            new Field("living.lastHurtMobTimestamp", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6270", "I"),
            new Field("living.speed", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6287", "F"),
            new Field("living.noJumpDelay", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6228", "I"),
            new Field("living.absorptionAmount", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6246", "F"),
            new Field("living.useItemRemaining", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6222", "I"),
            new Field("living.fallFlyTicks", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6239", "I"),
            new Field("living.lastKineticHitFeedbackTime", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_63950", "J"),
            new Field("living.lastPos", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6268", "Lnet/minecraft/class_2338;"),
            new Field("living.lastClimbablePos", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_22418", "Ljava/util/Optional;"),
            new Field("living.lastDamageStamp", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6226", "J"),
            new Field("living.autoSpinAttackTicks", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6261", "I"),
            new Field("living.autoSpinAttackDmg", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_51569", "F"),
            new Field("living.swimAmount", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6243", "F"),
            new Field("living.swimAmountO", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_6264", "F"),
            new Field("living.skipDropExperience", Target.PLAYER, net.minecraft.entity.LivingEntity.class, "net.minecraft.class_1309", "field_37421", "Z"),
            new Field("player.jumpTriggerTime", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7489", "I"),
            new Field("player.takeXpDelay", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7504", "I"),
            new Field("player.sleepCounter", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7487", "I"),
            new Field("player.wasUnderwater", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7490", "Z"),
            new Field("player.experienceLevel", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7520", "I"),
            new Field("player.totalExperience", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7495", "I"),
            new Field("player.experienceProgress", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7510", "F"),
            new Field("player.enchantmentSeed", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7494", "I"),
            new Field("player.lastLevelUpTime", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7508", "I"),
            new Field("player.reducedDebugInfo", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_7523", "Z"),
            new Field("player.hurtDir", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_41765", "F"),
            new Field("player.currentImpulseImpactPos", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_49989", "Lnet/minecraft/class_243;"),
            new Field("player.ignoreFallDamageFromCurrentImpulse", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_49991", "Z"),
            new Field("player.currentImpulseContextResetGraceTime", Target.PLAYER, net.minecraft.entity.player.PlayerEntity.class, "net.minecraft.class_1657", "field_52223", "I"),
            new Field("food.foodLevel", Target.FOOD, net.minecraft.entity.player.HungerManager.class, "net.minecraft.class_1702", "field_7756", "I"),
            new Field("food.saturationLevel", Target.FOOD, net.minecraft.entity.player.HungerManager.class, "net.minecraft.class_1702", "field_7753", "F"),
            new Field("food.exhaustionLevel", Target.FOOD, net.minecraft.entity.player.HungerManager.class, "net.minecraft.class_1702", "field_7752", "F"),
            new Field("food.tickTimer", Target.FOOD, net.minecraft.entity.player.HungerManager.class, "net.minecraft.class_1702", "field_7755", "I"),
            new Field("walk.speedOld", Target.WALK, net.minecraft.entity.LimbAnimator.class, "net.minecraft.class_8080", "field_42109", "F"),
            new Field("walk.speed", Target.WALK, net.minecraft.entity.LimbAnimator.class, "net.minecraft.class_8080", "field_42110", "F"),
            new Field("walk.position", Target.WALK, net.minecraft.entity.LimbAnimator.class, "net.minecraft.class_8080", "field_42111", "F"),
            new Field("walk.positionScale", Target.WALK, net.minecraft.entity.LimbAnimator.class, "net.minecraft.class_8080", "field_52449", "F")
    );
    private static final Map<String, Kind> RETAINED = Map.ofEntries(
            Map.entry("entity.visualFire", Kind.ENUM),
            Map.entry("entity.maxAirTicks", Kind.INT),
            Map.entry("entity.lastDamageCancelled", Kind.BOOLEAN),
            Map.entry("entity.persistentInvisibility", Kind.BOOLEAN),
            Map.entry("entity.lastLavaContact", Kind.BLOCK),
            Map.entry("entity.numCollisions", Kind.INT),
            Map.entry("entity.freezeLocked", Kind.BOOLEAN),
            Map.entry("entity.fixedPose", Kind.BOOLEAN),
            Map.entry("entity.totalEntityAge", Kind.INT),
            Map.entry("living.expToDrop", Kind.INT),
            Map.entry("living.collides", Kind.BOOLEAN),
            Map.entry("living.bukkitPickUpLoot", Kind.BOOLEAN),
            Map.entry("living.silentDeath", Kind.BOOLEAN),
            Map.entry("living.frictionState", Kind.ENUM),
            Map.entry("living.invulnerableDuration", Kind.INT),
            Map.entry("living.clearEquipmentSlots", Kind.BOOLEAN),
            Map.entry("living.totalEatTimeTicks", Kind.INT),
            Map.entry("player.affectsSpawning", Kind.BOOLEAN),
            Map.entry("player.flyingFallDamage", Kind.ENUM),
            Map.entry("player.fauxSleeping", Kind.BOOLEAN),
            Map.entry("player.oldLevel", Kind.INT),
            Map.entry("server.levitationStartPos", Kind.VECTOR),
            Map.entry("server.levitationStartTime", Kind.INT),
            Map.entry("server.startingToFallPosition", Kind.VECTOR),
            Map.entry("server.spawnExtraParticlesOnFall", Kind.BOOLEAN),
            Map.entry("server.timeEntitySatOnShoulder", Kind.LONG),
            Map.entry("food.saturatedRegenRate", Kind.INT),
            Map.entry("food.unsaturatedRegenRate", Kind.INT),
            Map.entry("food.starvationRate", Kind.INT),
            Map.entry("health.health", Kind.DOUBLE),
            Map.entry("health.scaledHealth", Kind.BOOLEAN),
            Map.entry("health.healthScale", Kind.DOUBLE)
    );

    private FabricRollbackPlayerValues() { }
    public static Prepared prepare(RollbackPlayerValues values) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare player values before replay");
        var remaining = new TreeMap<>(values.fields());
        var nativeValues = new ArrayList<Object>(FIELDS.size());
        for (Field field : FIELDS) nativeValues.add(FabricRollbackNativeValues.decode(field.type, RollbackPlayerValues.requireField(field.key, remaining.remove(field.key))));
        if (!remaining.keySet().equals(RETAINED.keySet())) throw new IllegalArgumentException("Player value fields changed");
        remaining.forEach((key, cell) -> {
            RollbackPlayerValues.requireField(key, cell);
            if (cell.kind() != RETAINED.get(key)) throw new IllegalArgumentException("Paper player value type changed: " + key);
        });
        return new Prepared(Collections.unmodifiableList(nativeValues), new RollbackPlayerValues(remaining));
    }

    public static final class Prepared {
        private final Thread owner = Thread.currentThread();
        private final List<Object> nativeValues;
        private final RollbackPlayerValues retained;
        private Prepared(List<Object> nativeValues, RollbackPlayerValues retained) { this.nativeValues = nativeValues; this.retained = retained; }
        /** The native policy/maintenance adapter still has to consume and evolve this state. */
        public RollbackPlayerValues retained() { return retained; }
        public void apply(FabricRollbackNativePlayerState state) {
            requireBootstrap();
            state.use(player -> {
                for (int i = 0; i < FIELDS.size(); i++) FIELDS.get(i).write(player, nativeValues.get(i));
                if (player instanceof FabricRollbackDamagePlayer owned)
                    owned.rollbackInvulnerableDuration = (Integer) retained.fields().get("living.invulnerableDuration").value();
                return null;
            });
        }
        /** Captures this component with its explicitly retained Paper fields for transfer/verification. */
        public RollbackPlayerValues capture(FabricRollbackNativePlayerState state) {
            requireBootstrap();
            return state.use(player -> {
                var result = new TreeMap<>(retained.fields());
                if (player instanceof FabricRollbackDamagePlayer owned)
                    result.put("living.invulnerableDuration", new RollbackPlayerValues.Cell(Kind.INT, owned.rollbackInvulnerableDuration));
                for (Field field : FIELDS) result.put(field.key, FabricRollbackNativeValues.encode(field.type, field.read(player)));
                return new RollbackPlayerValues(result);
            });
        }
        private void requireBootstrap() {
            if (Thread.currentThread() != owner || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Apply player values on their bootstrap thread before replay");
        }
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Native player value access failed", failure);
    }
}
