package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableListMultimap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackPlayerFields.*;

/**
 * Detached intrinsic player state for constructing a private native player. Capture
 * only at a server tick boundary. This does not copy a connection, world, listeners,
 * statistics/advancement services, or ProjectKorra's separate bending state. Combat
 * relationships are detached to UUIDs and bound only after the complete roster is
 * constructed. Other active world interactions still need their own import phase.
 */
public final class PaperRollbackPlayerSeed {
    private static final Values ENTITY = new Values(Entity.class,
            "position", "blockPosition", "chunkPosition", "deltaMovement", "yRot", "xRot", "bb", "dimensions", "eyeHeight",
            "xo", "yo", "zo", "xOld", "yOld", "zOld", "yRotO", "xRotO", "onGround", "horizontalCollision", "verticalCollision",
            "verticalCollisionBelow", "minorHorizontalCollision", "hurtMarked", "stuckSpeedMultiplier", "moveDist", "flyDist",
            "fallDistance", "nextStep", "noPhysics", "tickCount", "remainingFireTicks", "wasTouchingWater", "wasEyeInWater",
            "invulnerableTime", "firstTick", "needsSync", "portalCooldown", "invulnerable", "hasGlowingTag", "pistonDeltasGameTime",
            "isInPowderSnow", "wasInPowderSnow", "mainSupportingBlockPos", "onGroundNoBlocks", "crystalSoundIntensity",
            "lastCrystalSoundPlayTick", "hasVisualFire", "lastKnownSpeed", "lastKnownPosition", "visualFire", "maxAirTicks",
            "lastDamageCancelled", "persistentInvisibility", "lastLavaContact", "numCollisions", "freezeLocked", "fixedPose",
            "totalEntityAge", "blocksBuilding", "boardingCooldown");
    private static final Values LIVING = new Values(LivingEntity.class,
            "swinging", "discardFriction", "swingingArm", "swingTime", "removeArrowTime", "removeStingerTime", "hurtTime",
            "hurtDuration", "deathTime", "oAttackAnim", "attackAnim", "attackStrengthTicker", "itemSwapTicker", "yBodyRot",
            "yBodyRotO", "yHeadRot", "yHeadRotO", "lastHurtByPlayerMemoryTime", "dead", "noActionTime", "lastHurt", "jumping",
            "xxa", "yya", "zza", "lerpYHeadRot", "lerpHeadSteps", "effectsDirty", "lastHurtByMobTimestamp", "lastHurtMobTimestamp",
            "speed", "noJumpDelay", "absorptionAmount", "useItemRemaining", "fallFlyTicks", "lastKineticHitFeedbackTime", "lastPos",
            "lastClimbablePos", "lastDamageStamp", "autoSpinAttackTicks", "autoSpinAttackDmg", "swimAmount", "swimAmountO",
            "skipDropExperience", "expToDrop", "collides", "bukkitPickUpLoot", "silentDeath", "frictionState", "invulnerableDuration",
            "clearEquipmentSlots", "totalEatTimeTicks");
    private static final Values PLAYER = new Values(Player.class,
            "jumpTriggerTime", "takeXpDelay", "sleepCounter", "wasUnderwater", "experienceLevel", "totalExperience",
            "experienceProgress", "enchantmentSeed", "lastLevelUpTime", "reducedDebugInfo", "hurtDir", "currentImpulseImpactPos",
            "ignoreFallDamageFromCurrentImpulse", "currentImpulseContextResetGraceTime", "affectsSpawning", "flyingFallDamage", "fauxSleeping", "oldLevel");
    private static final Values SERVER = new Values(ServerPlayer.class,
            "levitationStartPos", "levitationStartTime", "startingToFallPosition", "spawnExtraParticlesOnFall", "timeEntitySatOnShoulder");
    private static final Values FOOD = new Values(FoodData.class,
            "foodLevel", "saturationLevel", "exhaustionLevel", "tickTimer", "saturatedRegenRate", "unsaturatedRegenRate", "starvationRate");
    private static final Values WALK = new Values(WalkAnimationState.class, "speedOld", "speed", "position", "positionScale");
    private static final Values HEALTH = new Values(CraftPlayer.class, "health", "scaledHealth", "healthScale");
    private static final Field<EnumMap> LOCATION_ENCHANTMENTS = new Field<>(LivingEntity.class, "activeLocationDependentEnchantments", EnumMap.class);
    private static final Field<ArrayDeque> MOVEMENTS = new Field<>(Entity.class, "movementThisTick", ArrayDeque.class);

    private final UUID id;
    private final String name;
    private final int entityId;
    private final ClientInformation information;
    private final GameType mode;
    private final RollbackRosterData.Identity identity;
    private final long worldTime, capturedNanos;
    private final Object[] entity, living, player, server, food, walk, health;
    private final RollbackPlayerVitals vitals;
    private final RollbackPlayerItems items;
    private final RollbackPlayerContext context;
    private final PaperRollbackCombatSeed combat;

    public static PaperRollbackPlayerSeed capture(ServerPlayer source, long capturedNanos) {
        requireCaptureThread();
        Objects.requireNonNull(source, "source player");
        requireImportableBody(source);
        return new PaperRollbackPlayerSeed(source, capturedNanos);
    }

    @SuppressWarnings("unchecked")
    private PaperRollbackPlayerSeed(ServerPlayer source, long capturedNanos) {
        worldTime = source.level().getGameTime(); this.capturedNanos = capturedNanos;
        id = source.getUUID(); name = source.getGameProfile().name(); entityId = source.getId();
        information = source.clientInformation(); mode = source.gameMode();
        var properties = source.getGameProfile().properties().entries().stream().map(entry ->
                new RollbackRosterData.Property(entry.getKey(), entry.getValue().name(), entry.getValue().value(), entry.getValue().signature())).toList();
        identity = new RollbackRosterData.Identity(id, entityId, name, properties, RollbackRosterData.Mode.valueOf(mode.name()),
                new RollbackRosterData.Client(information.language(), information.viewDistance(), RollbackRosterData.Chat.valueOf(information.chatVisibility().name()),
                        information.chatColors(), information.modelCustomisation(), RollbackRosterData.Hand.valueOf(information.mainHand().name()),
                        information.textFilteringEnabled(), information.allowsListing(), RollbackRosterData.Particles.valueOf(information.particleStatus().name())));
        entity = ENTITY.capture(source); living = LIVING.capture(source); player = PLAYER.capture(source); server = SERVER.capture(source);
        food = FOOD.capture(source.getFoodData()); walk = WALK.capture(source.walkAnimation); health = HEALTH.capture(source.getBukkitEntity());
        vitals = PaperRollbackPlayerVitals.capture(source);
        items = PaperRollbackPlayerItems.capture(source);
        context = PaperRollbackPlayerContextData.capture(source, capturedNanos);
        combat = new PaperRollbackCombatSeed(source);
        if (source.level().getGameTime() != worldTime) throw new IllegalStateException("World advanced during player capture");
    }

    public UUID id() { return id; }
    public long worldTime() { return worldTime; }
    public long capturedNanos() { return capturedNanos; }
    public RollbackPlayerVitals vitals() { return vitals; }
    public RollbackPlayerItems items() { return items; }
    public RollbackPlayerContext context() { return context; }
    public RollbackPlayerCombatData combat() { return combat.data(); }
    int entityId() { return entityId; }

    public RollbackRosterData.Player portable(long randomSeed) {
        return new RollbackRosterData.Player(identity, randomSeed, values(), vitals, items, context, combat.data());
    }

    private GameProfile profile() {
        var properties = ImmutableListMultimap.<String, Property>builder();
        for (var property : identity.properties()) properties.put(property.key(), new Property(property.name(), property.value(), property.signature()));
        return new GameProfile(id, name, new PropertyMap(properties.build()));
    }

    /** Portable value-field component; all other native seed components must accompany it. */
    public RollbackPlayerValues values() {
        var fields = new TreeMap<String, RollbackPlayerValues.Cell>();
        ENTITY.export("entity", entity, fields); LIVING.export("living", living, fields); PLAYER.export("player", player, fields);
        SERVER.export("server", server, fields); FOOD.export("food", food, fields); WALK.export("walk", walk, fields); HEALTH.export("health", health, fields);
        return new RollbackPlayerValues(fields);
    }

    /** Validate/decode the entire component before modifying the private, unstarted replica. */
    public static void applyValues(PaperRollbackNativePlayerState state, RollbackPlayerValues values) {
        requireCaptureThread();
        var remaining = new TreeMap<>(values.fields());
        Object[] entity = ENTITY.prepare("entity", remaining), living = LIVING.prepare("living", remaining), player = PLAYER.prepare("player", remaining),
                server = SERVER.prepare("server", remaining), food = FOOD.prepare("food", remaining), walk = WALK.prepare("walk", remaining), health = HEALTH.prepare("health", remaining);
        if (!remaining.isEmpty()) throw new IllegalArgumentException("Unknown player value fields " + remaining.keySet());
        state.use(nativePlayer -> {
            if (!(nativePlayer instanceof ServerPlayer target)) throw new IllegalArgumentException("Player value target requires a private server player");
            ENTITY.apply(target, entity); LIVING.apply(target, living); PLAYER.apply(target, player); SERVER.apply(target, server);
            FOOD.apply(target.getFoodData(), food); WALK.apply(target.walkAnimation, walk); HEALTH.apply(target.getBukkitEntity(), health);
            return null;
        });
    }

    void validateRoster(PaperRollbackWorldAccess world, Set<UUID> roster) {
        requireCaptureThread(); world.requirePlayerImport(id, entityId);
        if (world.world().getGameTime() != worldTime) throw new IllegalArgumentException("Player and world seeds belong to different ticks");
        combat.validate(roster, world.world().registryAccess());
    }

    /** Creates a fresh owned replica only. Imported values never write back to the source player. */
    public PaperRollbackNativePlayerState instantiate(PaperRollbackWorldAccess world, long initialNanos, long randomSeed, int maximumObjects,
            PaperRollbackStatistics.Seed statistics, PaperRollbackAdvancements.Seed advancements) {
        validateRoster(Objects.requireNonNull(world, "private world"), Set.of(id));
        var result = instantiateBody(world, initialNanos, randomSeed, maximumObjects, statistics, advancements);
        var player = (ServerPlayer) result.ownedPlayer();
        bindCombat(player, Map.of(id, player));
        return result;
    }

    PaperRollbackNativePlayerState instantiateBody(PaperRollbackWorldAccess world, long initialNanos, long randomSeed, int maximumObjects,
            PaperRollbackStatistics.Seed statistics, PaperRollbackAdvancements.Seed advancements) {
        requireCaptureThread(); world.requirePlayerImport(id, entityId);
        context.rebase(initialNanos); // Reject unrepresentable clocks before registering a native body.
        var result = PaperRollbackNativePlayerState.serverPlayer(world, profile(), information, mode,
                randomSeed, maximumObjects, statistics, advancements);
        apply((ServerPlayer) result.ownedPlayer(), initialNanos);
        return result;
    }

    void bindCombat(ServerPlayer target, Map<UUID, ServerPlayer> roster) {
        requireCaptureThread();
        if (!target.getUUID().equals(id)) throw new IllegalArgumentException("Combat seed belongs to another player");
        combat.apply(target, roster);
    }

    @SuppressWarnings("unchecked")
    private void apply(ServerPlayer target, long initialNanos) {
        target.setId(entityId);
        // Paper checks this flag in Player.isImmobile. The private roster owns the
        // replica; making it eligible for its native tick does not spawn it.
        target.valid = true;
        ENTITY.apply(target, entity); LIVING.apply(target, living); PLAYER.apply(target, player); SERVER.apply(target, server);
        FOOD.apply(target.getFoodData(), food); WALK.apply(target.walkAnimation, walk); HEALTH.apply(target.getBukkitEntity(), health);
        PaperRollbackPlayerVitals.applyTo(target, vitals);
        PaperRollbackPlayerItems.applyTo(target, items);
        PaperRollbackPlayerContextData.applyTo(target, context, initialNanos);
    }

    private static void requireCaptureThread() {
        if (!TickThread.isTickThread() || RollbackClock.active()) throw new IllegalStateException("Import players on the server tick thread before replay");
    }

    private static void requireImportableBody(ServerPlayer source) {
        if (!source.valid || source.isRemoved() || !source.isAlive()) throw new IllegalStateException("Capture an active living player");
        if (source.containerMenu != source.inventoryMenu || !source.inventoryMenu.getCarried().isEmpty()) {
            throw new IllegalStateException("Player has an active menu transaction during import");
        }
        if (source.isSleeping() || source.isPassenger() || !source.getPassengers().isEmpty() || source.getCamera() != source
                || source.fishing != null || !source.getEnderPearls().isEmpty() || source.portalProcess != null
                || source.isChangingDimension || !MOVEMENTS.get(source).isEmpty()) {
            throw new IllegalStateException("Player has active world/entity state requiring the world import phase");
        }
        for (Object value : LOCATION_ENCHANTMENTS.get(source).values()) {
            if (!((Map<?, ?>) value).isEmpty()) throw new IllegalStateException("Active location effects require world-state import");
        }
        // Crafting slots are not part of the player/equipment inventory.
        for (int slot = 0; slot < 5; slot++) if (!source.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
            throw new IllegalStateException("Player has an active crafting transaction during import");
        }
        if (!source.getShoulderEntityLeft().isEmpty() || !source.getShoulderEntityRight().isEmpty()) {
            throw new IllegalStateException("Shoulder entities require the entity import phase");
        }
    }
}
