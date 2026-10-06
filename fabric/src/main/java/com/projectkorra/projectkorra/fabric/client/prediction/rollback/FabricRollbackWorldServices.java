package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.attribute.EnvironmentAttributeAccess;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRules;

import java.util.*;

/** Client services from the same authoritative rule/policy seed. Paper-only policy is retained for its native adapters. */
public final class FabricRollbackWorldServices implements FabricRollbackWorldQueries.Services<FabricRollbackWorldServices.Snapshot>, com.projectkorra.projectkorra.prediction.rollback.RollbackTimedExecutionServices.WorldTime<FabricRollbackWorldServices.Snapshot> {
    public interface Spatial<S> extends RollbackStateCell<S> {
        void advanceTick(long tick);
        WorldBorder border();
        Scoreboard scoreboard();
        EnvironmentAttributeAccess environmentAttributes();
        boolean skyVisible(RollbackBlockStore terrain, BlockPos position);
    }
    public interface Events<S> extends RollbackStateCell<S> {
        void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter);
        void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity);
        void output(FabricRollbackWorldAccess.Output output);
        default RollbackHandSwap swapHands(UUID player, RollbackItemData main, RollbackItemData off) {
            throw new UnsupportedOperationException("Private swap-event policy is not bound");
        }
        default boolean flightAllowed(UUID player, boolean flying, boolean cancelled) {
            throw new UnsupportedOperationException("Private flight-event policy is not bound");
        }
        default boolean glideAllowed(UUID player, boolean gliding, boolean cancelled) {
            throw new UnsupportedOperationException("Private glide-event policy is not bound");
        }
    }
    public static final class Snapshot {
        private final FabricRollbackWorldServices owner;
        private final long time, lastTick;
        private Snapshot(FabricRollbackWorldServices owner) { this.owner = owner; time = owner.time; lastTick = owner.lastTick; }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorldSettings settings;
    private final RollbackWorld logical;
    private final DynamicRegistryManager.Immutable registries;
    private final Spatial<?> spatial;
    private final Events<?> events;
    private final Map<GameRule<?>, Object> rules;
    private final Random random;
    private final RollbackRandom soundRandom;
    private long time, lastTick;

    public FabricRollbackWorldServices(RollbackWorldSettings settings, RollbackWorld logical, DynamicRegistryManager.Immutable registries, FeatureSet features,
                                       Spatial<?> spatial, Events<?> events) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare world services before replay");
        this.settings = Objects.requireNonNull(settings); this.registries = Objects.requireNonNull(registries);
        this.logical = Objects.requireNonNull(logical);
        var environment = switch (settings.policy().dimension()) {
            case NORMAL -> com.projectkorra.projectkorra.platform.mc.World.Environment.NORMAL;
            case NETHER -> com.projectkorra.projectkorra.platform.mc.World.Environment.NETHER;
            case THE_END -> com.projectkorra.projectkorra.platform.mc.World.Environment.THE_END;
        };
        if (!logical.conditions().difficulty().equals(settings.difficulty().name()) || logical.getEnvironment() != environment) {
            throw new IllegalArgumentException("Logical world differs from captured settings");
        }
        this.spatial = Objects.requireNonNull(spatial); this.events = Objects.requireNonNull(events);
        var nativeRules = new GameRules(Objects.requireNonNull(features)); var available = nativeRules.streamRules().toList();
        var keys = new HashSet<String>(); var values = new IdentityHashMap<GameRule<?>, Object>();
        for (var rule : available) {
            String key = rule.getId().toString(); keys.add(key); var value = settings.rules().get(key);
            if (value == null || !rule.getValueClass().isInstance(value.value())) throw new IllegalArgumentException("Missing/mismatched rule: " + key);
            values.put(rule, value.value());
        }
        if (!keys.equals(settings.rules().keySet())) throw new IllegalArgumentException("Native feature/rule set differs from captured settings");
        rules = Collections.unmodifiableMap(values);
        time = settings.gameTime(); random = new FabricRollbackPaperRandom(settings.randomSeed()); soundRandom = new RollbackRandom(settings.soundSeed());
    }
    public RollbackWorld logicalWorld() { checkThread(); return logical; }
    public RollbackWorldSettings settings() { checkThread(); return settings; }
    /** The session owner advances this once per world tick, before ticking its roster. */
    public void advanceTick() { advanceTick(Math.incrementExact(lastTick)); }
    /** Idempotent for this replay tick; skipped/backward ticks require restoring a checkpoint. */
    public void advanceTick(long tick) {
        checkThread();
        if (tick < 1) throw new IllegalArgumentException("Simulation ticks start at one");
        if (tick == lastTick) return;
        if (tick != Math.incrementExact(lastTick)) throw new IllegalStateException("World tick skipped or moved backwards without rewind");
        long next = Math.incrementExact(time);
        var previous = logical.conditions();
        long day = (Boolean) rules.get(GameRules.ADVANCE_TIME) ? Math.incrementExact(previous.fullTime()) : previous.fullTime();
        var conditions = new RollbackWorld.Conditions(Math.floorMod(day, 24_000L), day,
                previous.difficulty(), previous.storm(), previous.loadedChunks());
        spatial.advanceTick(next);
        logical.conditions(conditions);
        time = next; lastTick = tick;
    }
    @Override public DynamicRegistryManager.Immutable registries() { checkThread(); return registries; }
    @Override public EnvironmentAttributeAccess environmentAttributes() { checkThread(); return spatial.environmentAttributes(); }
    @Override public WorldBorder border() { checkThread(); return spatial.border(); }
    @Override public Scoreboard scoreboard() { checkThread(); return spatial.scoreboard(); }
    @Override public long time() { checkThread(); return time; }
    @Override public int seaLevel() { checkThread(); return settings.seaLevel(); }
    @Override @SuppressWarnings("unchecked") public <T> T gameRule(GameRule<T> rule) {
        checkThread(); var value = rules.get(rule); if (value == null) throw new IllegalArgumentException("Rule absent from captured feature set"); return (T) value;
    }
    @Override public boolean skyVisible(RollbackBlockStore terrain, BlockPos position) { checkThread(); return spatial.skyVisible(terrain, position); }
    @Override public Random random() { checkThread(); return random; }
    @Override public long nextSoundSeed() { checkThread(); return soundRandom.nextLong(); }
    @Override public void output(FabricRollbackWorldAccess.Output output) { checkThread(); events.output(output); }
    @Override public void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter) { checkThread(); events.gameEvent(event, position, emitter); }
    @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity) { checkThread(); events.waypoint(action, entity); }
    @Override public RollbackHandSwap swapHands(UUID player, RollbackItemData main, RollbackItemData off) {
        checkThread(); return events.swapHands(player, main, off);
    }
    @Override public boolean updateEquipmentOnActions() { checkThread(); return settings.policy().updateEquipmentOnActions(); }
    @Override public boolean flightAllowed(UUID player, boolean flying, boolean cancelled) { checkThread(); return events.flightAllowed(player, flying, cancelled); }
    @Override public boolean glideAllowed(UUID player, boolean gliding, boolean cancelled) { checkThread(); return events.glideAllowed(player, gliding, cancelled); }
    @Override public Snapshot captureRollbackState() { checkThread(); return new Snapshot(this); }
    @Override public void restoreRollbackState(Snapshot snapshot) { checkThread(); if (snapshot.owner != this) throw new IllegalArgumentException("Foreign world checkpoint"); time = snapshot.time; lastTick = snapshot.lastTick; }
    @Override public List<?> rollbackReferences() { checkThread(); return List.of(logical, random, soundRandom, spatial, events); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private world services crossed threads"); }
}
