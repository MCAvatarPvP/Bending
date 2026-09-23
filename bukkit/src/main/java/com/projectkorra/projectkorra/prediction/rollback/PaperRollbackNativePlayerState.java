package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventoryPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/**
 * In-process state of an owned native Paper player. Its private Bukkit identity
 * wrapper allows native player/vehicle type checks without acquiring a live server.
 * Unknown Bukkit operations still fail. Audited ServerPlayer tick/doTick paths use
 * private services; complete world/item services, full session import and network
 * correction delivery are not supplied by this state owner.
 * Construct against the private world before replay; frozen registry metadata is
 * external, while mutable player state and its private RNG participate in capture.
 * Capture via the outer state graph so the world roster and terrain rewind too.
 */
public final class PaperRollbackNativePlayerState implements RollbackPlayerState.Source<PaperRollbackNativePlayerState.Checkpoint> {
    private final Thread thread = Thread.currentThread();
    private final Player player;
    private final PaperRollbackWorldAccess world;
    private final CraftPlayer bukkit;
    private final RandomSource random;
    private final RollbackStateGraph graph;
    private PaperRollbackPlayerControls playerControls;
    private final RollbackStateGraph healthGraph = new RollbackStateGraph(value -> false,
            field -> field.getDeclaringClass() == CraftPlayer.class && Set.of("health", "scaledHealth", "healthScale").contains(field.getName()), 10);
    private final PaperRollbackServerPlayerServices serverServices;
    private final BukkitState bukkitState = new BukkitState();
    private boolean using;
    private PaperRollbackLivingAccess livingAccess;

    private static final class BukkitState {
        org.bukkit.event.entity.EntityDamageEvent lastDamage;
        boolean menuInitialized;
    }

    /**
     * Construct a real ServerPlayer using exclusively owned constructor services.
     * Damage, ticking and audited packet outputs use private adapters. The session
     * supplies captured world policy and services; unknown calls still fail.
     */
    public static PaperRollbackNativePlayerState serverPlayer(PaperRollbackWorldAccess world, GameProfile profile,
            ClientInformation information, GameType gameType, long randomSeed, int maximumObjects) {
        return serverPlayer(world, profile, information, gameType, randomSeed, maximumObjects, PaperRollbackStatistics.Seed.fresh());
    }

    /** Includes captured native statistic policy and imported values, rather than reading global settings during replay. */
    public static PaperRollbackNativePlayerState serverPlayer(PaperRollbackWorldAccess world, GameProfile profile,
            ClientInformation information, GameType gameType, long randomSeed, int maximumObjects, PaperRollbackStatistics.Seed statistics) {
        return serverPlayer(world, profile, information, gameType, randomSeed, maximumObjects, statistics, PaperRollbackAdvancements.Seed.empty());
    }

    public static PaperRollbackNativePlayerState serverPlayer(PaperRollbackWorldAccess world, GameProfile profile,
            ClientInformation information, GameType gameType, long randomSeed, int maximumObjects,
            PaperRollbackStatistics.Seed statistics, PaperRollbackAdvancements.Seed advancements) {
        Objects.requireNonNull(world, "world").requirePlayerConstruction();
        var services = new PaperRollbackServerPlayerServices(world, statistics, advancements);
        var player = services.create(profile, information, gameType);
        return new PaperRollbackNativePlayerState(player, world, randomSeed, maximumObjects, services);
    }

    @SuppressWarnings("WrapperReferenceEquality") // World, native wrapper and RNG ownership are identity contracts.
    public PaperRollbackNativePlayerState(Object nativePlayer, PaperRollbackWorldAccess world, long randomSeed, int maximumObjects) {
        this(nativePlayer, world, randomSeed, maximumObjects, null);
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private PaperRollbackNativePlayerState(Object nativePlayer, PaperRollbackWorldAccess world, long randomSeed,
            int maximumObjects, PaperRollbackServerPlayerServices serverServices) {
        Player player = (Player) Objects.requireNonNull(nativePlayer, "player");
        this.player = player;
        this.world = Objects.requireNonNull(world, "world");
        this.serverServices = serverServices;
        if (RollbackClock.active()) throw new IllegalStateException("Construct native player state before replay");
        if (player instanceof ServerPlayer server && (serverServices == null || !serverServices.owns(server))) {
            throw new IllegalArgumentException("ServerPlayer requires owned constructor services");
        }
        if (player.level() != world.world()) throw new IllegalArgumentException("Native player belongs to another world");
        if (player.getBukkitEntityRaw() != null) throw new IllegalArgumentException("Native player already owns a Bukkit wrapper");
        world.requirePlayerRegistration(player);
        var attributes = new PaperRollbackAttributes(this, this::ownedPlayer);
        float initialHealth = player.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
        var wrapper = RollbackNativeQueryShell.create(CraftPlayer.class)
                .query(CraftPlayer::getUniqueId, null, args -> player.getUUID())
                .query(CraftPlayer::getEntityId, 0, args -> player.getId())
                .query(CraftPlayer::getName, "", args -> player.getGameProfile().name())
                .query(CraftPlayer::getMaxHealth, 0D, args -> (double) ownedPlayer().getMaxHealth())
                .query(value -> value.getAttribute(null), null, args -> attributes.get((org.bukkit.attribute.Attribute) args[0]))
                .query(CraftPlayer::getVelocity, null, args -> {
                    var velocity = player.getDeltaMovement();
                    return new org.bukkit.util.Vector(velocity.x, velocity.y, velocity.z);
                })
                .query(CraftPlayer::getLastDamageCause, null, args -> bukkitState.lastDamage)
                .outputQuery(value -> value.setLastDamageCause(null), args -> lastDamage(args[0]));
        if (serverServices != null) {
            var inventory = RollbackNativeQueryShell.create(CraftInventoryPlayer.class)
                    .query(CraftInventoryPlayer::getItemInMainHand, null, args -> CraftItemStack.asCraftMirror(ownedPlayer().getMainHandItem()))
                    .query(CraftInventoryPlayer::getItemInOffHand, null, args -> CraftItemStack.asCraftMirror(ownedPlayer().getOffhandItem()))
                    .instance();
            wrapper.query(CraftPlayer::getHandle, null, args -> (ServerPlayer) ownedPlayer())
                    .query(CraftPlayer::getInventory, null, args -> { ownedPlayer(); return inventory; })
                    .nativeQuery(CraftPlayer::getOpenInventory, null, args -> {
                        var server = (ServerPlayer) ownedPlayer();
                        if (server.containerMenu != server.inventoryMenu) throw new IllegalStateException("Private external menu services were not supplied");
                    })
                    .nativeQuery(CraftPlayer::getGameMode, null, args -> ownedPlayer())
                    .query(CraftPlayer::getScoreboard, null, args -> world.scoreboards().bukkitBoard((ServerPlayer) ownedPlayer()))
                    .query(value -> value.getAdvancementProgress(null), null, args -> {
                        ownedPlayer(); return serverServices.advancements().bukkitProgress((org.bukkit.advancement.Advancement) args[0]);
                    })
                    .nativeQuery(CraftPlayer::getHealth, 0D, args -> ownedPlayer())
                    .nativeQuery(CraftPlayer::getScaledHealth, 0F, args -> ownedPlayer())
                    .nativeQuery(CraftPlayer::getHealthScale, 0D, args -> ownedPlayer())
                    .nativeQuery(CraftPlayer::isHealthScaled, false, args -> ownedPlayer())
                    .nativeQuery(CraftPlayer::hasClientWorldBorder, false, args -> ownedPlayer())
                    .nativeQuery(CraftPlayer::getScaledMaxHealth, null, args -> ownedPlayer())
                    .nativeAction(value -> value.setHealth(20), args -> {
                        ownedPlayer();
                        // CraftLivingEntity.setHealth(0) directly invokes vanilla
                        // die/discard. Positive resets use Paper's own validation
                        // and health update; zero requires the private death route.
                        if ((float) (double) args[0] == 0) throw new IllegalStateException("Zero health requires private death handling");
                    })
                    .nativeAction(value -> value.setRealHealth(0), args -> ownedPlayer())
                    .nativeAction(value -> value.setHealthScale(20), args -> ownedPlayer())
                    .nativeAction(value -> value.setHealthScaled(false), args -> ownedPlayer())
                    .nativeAction(value -> value.updateScaledHealth(false), args -> ownedPlayer())
                    .nativeAction(CraftPlayer::updateScaledHealth, args -> ownedPlayer())
                    .nativeAction(CraftPlayer::sendHealthUpdate, args -> ownedPlayer())
                    .nativeAction(value -> value.sendHealthUpdate(20, 20, 5), args -> ownedPlayer());
        } else wrapper.query(CraftPlayer::getHealth, 0D, args -> (double) ownedPlayer().getHealth());
        bukkit = wrapper.instance();
        // Paper normally shares one RNG across native entities. Rewinding that RNG
        // would alter live entities and other duels, so this replica must own one.
        random = RandomSource.create(randomSeed);
        graph = new RollbackStateGraph(value -> value == this || value == world || otherEntity(value) || value == world.world() || world.ownsWrapper(value)
                || (serverServices != null && serverServices.external(value)) || PaperRollbackMetadata.frozen(value), field ->
                !(field.getDeclaringClass().getName().startsWith("it.unimi.dsi.fastutil.")
                        && Map.class.isAssignableFrom(field.getDeclaringClass())
                        && Collection.class.isAssignableFrom(field.getType())
                        && Set.of("entries", "keys", "values").contains(field.getName())), maximumObjects);
        PaperRollbackPrivateAccess.initializePlayer(player, bukkit, random);
        PaperRollbackPrivateAccess.initializeHealth(bukkit, initialHealth);
        world.registerPlayer(player, bukkit, this);
        if (serverServices != null) serverServices.advancements().initialize();
    }

    public void clientLoaded(boolean loaded) {
        use(value -> {
            if (serverServices == null) throw new IllegalStateException("Client loading requires an owned ServerPlayer");
            serverServices.connection().loaded(loaded); return null;
        });
    }

    @Override public Identity identity() {
        var value = ownedPlayer();
        return new Identity(value.getUUID(), value.getId(), value.getGameProfile().name(),
                com.projectkorra.projectkorra.platform.mc.entity.EntityType.PLAYER);
    }

    private PaperRollbackLivingAccess livingAccess() {
        ownedPlayer();
        // Derived accessors retain only this owner and frozen registry metadata.
        if (livingAccess == null) livingAccess = new PaperRollbackLivingAccess(this, world, this::ownedPlayer);
        return livingAccess;
    }
    @Override public RollbackLivingState.Vitals readVitals() { return livingAccess().read(); }
    @Override public void writeVitals(RollbackLivingState.Vitals value) { livingAccess().write(value); }
    @Override public Double attributeValue(String name) { return livingAccess().attributeValue(name); }
    @Override public Map<String, Double> readAttributes() { return livingAccess().attributes(); }
    @Override public PotionEffect readPotion(PotionEffectType type) { return livingAccess().potion(type); }
    @Override public Collection<PotionEffect> readPotions() { return livingAccess().potions(); }
    @Override public boolean dead() { return !ownedPlayer().isAlive(); }
    private PaperRollbackPlayerControls playerControls() {
        ownedPlayer();
        if (playerControls == null) playerControls = new PaperRollbackPlayerControls(this, world);
        return playerControls;
    }
    @Override public RollbackPlayerState.Controls readControls() { return playerControls().read(); }
    @Override public void changeControls(RollbackPlayerState target, RollbackPlayerState.Control changed, RollbackPlayerState.Controls proposed) { playerControls().write(target, changed, proposed); }
    @Override public boolean requestFlight(boolean flying, boolean cancelled) {
        return use(player -> world.requestFlight((net.minecraft.server.level.ServerPlayer) player, flying, cancelled));
    }
    @Override public boolean requestGlide() { return use(player -> world.requestGlide((net.minecraft.world.entity.player.Player) player)); }
    @Override public void damage(RollbackLivingState target, double amount, com.projectkorra.projectkorra.platform.mc.entity.Entity source) { livingAccess().damage(target, amount, source); }
    @Override public boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force) { return livingAccess().addPotion(target, effect, force); }
    @Override public void removePotion(RollbackLivingState target, PotionEffectType type) { livingAccess().removePotion(target, type); }
    @Override public void attribute(RollbackLivingState target, String name, double value) { livingAccess().attribute(target, name, value); }

    @Override public Kinematics readKinematics() {
        var value = ownedPlayer();
        var box = value.getBoundingBox(); var velocity = value.getDeltaMovement();
        double x = value.getX(), y = value.getY(), z = value.getZ();
        return new Kinematics(new Pose(x, y, z, value.getYRot(), value.getXRot()),
                new Motion(velocity.x, velocity.y, velocity.z),
                new Box(box.minX - x, box.minY - y, box.minZ - z, box.maxX - x, box.maxY - y, box.maxZ - z),
                value.getBbHeight(), value.onGround(), value.fallDistance, value.hurtMarked);
    }

    @Override public void writeKinematics(Kinematics next) {
        var value = ownedPlayer();
        Objects.requireNonNull(next, "kinematics");
        var current = readKinematics();
        // Pose/dimension changes belong to native pose processing, not an arbitrary
        // replacement box. Logical teleports, rotations and velocity retain that box.
        if (next.height() != current.height() || !next.bounds().equals(current.bounds())) {
            throw new IllegalArgumentException("Change player dimensions through native pose processing");
        }
        var p = next.pose(); var b = next.bounds(); var v = next.velocity();
        var box = new Box(p.x() + b.minX(), p.y() + b.minY(), p.z() + b.minZ(),
                p.x() + b.maxX(), p.y() + b.maxY(), p.z() + b.maxZ());
        boolean moved = p.x() != current.pose().x() || p.y() != current.pose().y() || p.z() != current.pose().z();
        if (moved) {
            value.setPos(p.x(), p.y(), p.z());
            value.setBoundingBox(new net.minecraft.world.phys.AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        }
        if (p.yaw() != current.pose().yaw()) value.setYRot(p.yaw());
        if (p.pitch() != current.pose().pitch()) value.setXRot(p.pitch());
        if (!v.equals(current.velocity())) value.setDeltaMovement(v.x(), v.y(), v.z());
        if (moved || next.onGround() != current.onGround()) value.setOnGround(next.onGround());
        value.fallDistance = next.fallDistance();
        value.hurtMarked = next.velocityChanged();
    }

    /** Native values stay inside the adapter. Only detached outputs may escape. */
    public <R> R use(Function<Object, R> operation) {
        requireIdle();
        Objects.requireNonNull(operation, "operation");
        using = true;
        try { return operation.apply(player); }
        finally { using = false; }
    }

    /** Native Paper damage/resurrection through this world's private event and effect routes. */
    public boolean damage(Object nativeSource, float amount) {
        return use(entity -> world.damage(entity, nativeSource, amount));
    }

    public void movementInput(RollbackMovementInput input) {
        Objects.requireNonNull(input, "input");
        var player = ownedPlayer();
        player.xxa = input.strafe(); player.zza = input.forward(); player.yya = 0;
        player.setJumping(input.jump()); player.setYRot(input.yaw()); player.setXRot(input.pitch());
    }

    /** The native aiStep phase, not the surrounding ServerPlayer/world/network tick. */
    public void stepMovement() {
        use(entity -> { world.stepMovement((Player) entity); return null; });
    }

    /** Native Player.tick plus previous pose/age, excluding ServerPlayer maintenance and network tick. */
    public void tickPlayerBody() {
        use(entity -> { world.tickPlayerBody((Player) entity); return null; });
    }

    /** Owned ServerPlayer tick/doTick phases; transport receipt and world scheduling remain outside. */
    public void tick() {
        if (serverServices == null) throw new IllegalStateException("Native server ticking requires an owned ServerPlayer");
        use(entity -> {
            var serverPlayer = (ServerPlayer) entity;
            if (!serverPlayer.connection.hasClientLoaded()) throw new IllegalStateException("Player must load before private ticking");
            if (!bukkitState.menuInitialized) { serverServices.initializeMenu(); bukkitState.menuInitialized = true; }
            world.tickPlayer(serverPlayer); return null;
        });
    }

    /** Native statistic event/counter update followed by Paper's tracked-objective update. */
    public void awardStatistic(Object nativeStatistic, int amount) {
        use(entity -> { world.statistic(entity, nativeStatistic, amount, false); return null; });
    }

    public void resetStatistic(Object nativeStatistic) {
        use(entity -> { world.statistic(entity, nativeStatistic, 0, true); return null; });
    }

    public boolean awardAdvancement(String id, String criterion) {
        return use(entity -> advancements().award(net.minecraft.resources.Identifier.parse(id), criterion));
    }
    public boolean revokeAdvancement(String id, String criterion) {
        return use(entity -> advancements().revoke(net.minecraft.resources.Identifier.parse(id), criterion));
    }
    public Map<String, java.time.Instant> advancementProgress(String id) {
        return use(entity -> advancements().obtained(net.minecraft.resources.Identifier.parse(id)));
    }
    private PaperRollbackAdvancements advancements() {
        if (serverServices == null) throw new IllegalStateException("Native ServerPlayer advancement services were not supplied");
        return serverServices.advancements();
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void lastDamage(Object value) {
        var event = (org.bukkit.event.entity.EntityDamageEvent) value;
        if (event != null && event.getEntity() != bukkit) throw new IllegalArgumentException("Damage event belongs to another player");
        bukkitState.lastDamage = event;
    }

    @Override public Checkpoint captureRollbackState() {
        requireIdle();
        world.sealPlayers();
        var roots = serverServices == null ? List.of(player, bukkitState)
                : List.of(player, bukkitState, serverServices.statistics(), serverServices.advancements(), serverServices.connection());
        return new Checkpoint(this, graph.capture(roots, List.of()), healthGraph.capture(List.of(bukkit), List.of()));
    }

    @Override public void restoreRollbackState(Checkpoint checkpoint) {
        requireIdle();
        if (Objects.requireNonNull(checkpoint, "checkpoint").owner != this) throw new IllegalArgumentException("Native player checkpoint belongs to another replica");
        checkpoint.state.restore();
        checkpoint.health.restore();
        if (serverServices != null) serverServices.restored();
    }

    @Override public List<?> rollbackReferences() { requireIdle(); return List.of(world); }

    /** Retained logical item mirrors may outlive their inventory slot. */
    RollbackStateGraph.Snapshot captureItem(net.minecraft.world.item.ItemStack item) {
        requireIdle();
        return graph.capture(List.of(item), List.of());
    }

    public static final class Checkpoint {
        private final PaperRollbackNativePlayerState owner;
        private final RollbackStateGraph.Snapshot state;
        private final RollbackStateGraph.Snapshot health;
        private Checkpoint(PaperRollbackNativePlayerState owner, RollbackStateGraph.Snapshot state, RollbackStateGraph.Snapshot health) {
            this.owner = owner; this.state = state; this.health = health;
        }
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requireIdle() {
        ownedPlayer();
        if (using) throw new IllegalStateException("Reentrant native player operation or checkpoint");
    }

    @SuppressWarnings("WrapperReferenceEquality")
    Player ownedPlayer() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native player state crossed threads");
        if (player.level() != world.world() || player.getBukkitEntityRaw() != bukkit || player.random != random) {
            throw new IllegalStateException("Native player ownership changed");
        }
        if (serverServices != null) serverServices.check();
        return player;
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private boolean otherEntity(Object value) {
        if (value == player || !(value instanceof Entity)) return false;
        if (!world.ownsPlayer(value)) throw new IllegalStateException("Native player references an unowned entity");
        // The outer world graph captures every registered player's state cell.
        // Cross-player target/passenger references keep their identity here rather
        // than recursively treating a second player's private wrapper as state.
        return true;
    }

}
