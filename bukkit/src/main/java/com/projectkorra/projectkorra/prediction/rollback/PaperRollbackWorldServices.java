package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.*;

/** Captured native services shared by the private spatial and combat adapters. */
public final class PaperRollbackWorldServices implements PaperRollbackWorldAccess.Combat<PaperRollbackWorldServices.Snapshot>, PaperRollbackWorldQueries.Services<PaperRollbackWorldServices.Snapshot> {
    public interface Spatial<S> extends RollbackStateCell<S> {
        void advanceTick(long tick);
        WorldBorder border();
        EnvironmentAttributeReader environmentAttributes();
        boolean skyVisible(RollbackBlockStore terrain, BlockPos position);
    }
    public interface Events<S> extends RollbackStateCell<S> {
        void event(org.bukkit.event.Event event);
        void gameEvent(Object event, Object position, Object context);
        void output(PaperRollbackCombatAccess.Output output);
    }
    public static final class Snapshot {
        private final PaperRollbackWorldServices owner;
        private final long time, lastTick;
        private Snapshot(PaperRollbackWorldServices owner) { this.owner = owner; time = owner.time; lastTick = owner.lastTick; }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorldSettings settings;
    private final RollbackWorld logical;
    private final RegistryAccess.Frozen registries;
    private final Spatial<?> spatial;
    private final Events<?> events;
    private final Map<GameRule<?>, Object> rules;
    private final RandomSource random;
    private final RollbackRandom soundRandom;
    private final PaperRollbackWorldAccess.WorldPolicy worldPolicy;
    private long time, lastTick;

    public PaperRollbackWorldServices(RollbackWorldSettings settings, RollbackWorld logical, RegistryAccess.Frozen registries, FeatureFlagSet features,
                                      Spatial<?> spatial, Events<?> events) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare world services before replay");
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
        var nativeRules = new GameRules(Objects.requireNonNull(features));
        var available = nativeRules.availableRules().toList(); var keys = new HashSet<String>(); var values = new IdentityHashMap<GameRule<?>, Object>();
        for (var rule : available) {
            String key = rule.getIdentifier().toString(); keys.add(key); var value = settings.rules().get(key);
            if (value == null || !rule.valueClass().isInstance(value.value())) throw new IllegalArgumentException("Missing/mismatched rule: " + key);
            values.put(rule, value.value());
        }
        if (!keys.equals(settings.rules().keySet())) throw new IllegalArgumentException("Native feature/rule set differs from captured settings");
        rules = Collections.unmodifiableMap(values);
        time = settings.gameTime(); random = RandomSource.create(settings.randomSeed()); soundRandom = new RollbackRandom(settings.soundSeed());
        var p = settings.policy(); worldPolicy = new PaperRollbackWorldAccess.WorldPolicy(org.bukkit.World.Environment.valueOf(p.dimension().name()),
                p.voidDamage(), p.voidDamageAmount(), p.voidDamageHeightOffset(), p.netherCeilingHeight());
    }
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
    @Override public RegistryAccess.Frozen registries() { checkThread(); return registries; }
    @Override public RegistryAccess.Frozen registryAccess() { return registries(); }
    @Override public Difficulty difficulty() { checkThread(); return Difficulty.valueOf(logical.conditions().difficulty()); }
    @Override public RandomSource random() { checkThread(); return random; }
    @Override public Object rule(Object rule) {
        checkThread(); var value = rules.get(rule); if (value == null) throw new IllegalArgumentException("Rule absent from captured feature set"); return value;
    }
    @Override public long time() { checkThread(); return time; }
    @Override public long nextSoundSeed() { checkThread(); return soundRandom.nextLong(); }
    @Override public int seaLevel() { checkThread(); return settings.seaLevel(); }
    @Override public WorldBorder border() { checkThread(); return spatial.border(); }
    @Override public EnvironmentAttributeReader environmentAttributes() { checkThread(); return spatial.environmentAttributes(); }
    @Override public boolean skyVisible(RollbackBlockStore terrain, BlockPos position) { checkThread(); return spatial.skyVisible(terrain, position); }
    private RollbackWorldSettings.Policy policy() { checkThread(); return settings.policy(); }
    @Override public boolean skipVanillaDamageTickWhenShieldBlocked() { return policy().skipBlockedDamageTick(); }
    @Override public boolean updateEquipmentOnPlayerActions() { return policy().updateEquipmentOnActions(); }
    @Override public boolean allowNonPlayerEntitiesOnScoreboards() { return policy().nonPlayerScoreboards(); }
    @Override public boolean pvpAllowed() { return policy().pvp(); }
    @Override public boolean allowPlayerCrammingDamage() { return policy().playerCramming(); }
    @Override public int maximumEntityCollisions() { return policy().maximumCollisions(); }
    @Override public float jumpExhaustion(boolean sprinting) { return sprinting ? policy().jumpSprintingExhaustion() : policy().jumpWalkingExhaustion(); }
    @Override public PaperRollbackWorldAccess.WorldPolicy worldPolicy() { checkThread(); return worldPolicy; }
    @Override public int containerUpdateRate() { return policy().containerUpdateRate(); }
    @Override public float regenerationExhaustion() { return policy().regenerationExhaustion(); }
    @Override public boolean parrotsStayOnShoulder() { return policy().parrotsStayOnShoulder(); }
    @Override public void event(org.bukkit.event.Event event) { checkThread(); events.event(event); }
    @Override public void gameEvent(Object event, Object position, Object context) { checkThread(); events.gameEvent(event, position, context); }
    @Override public void output(PaperRollbackCombatAccess.Output output) { checkThread(); events.output(output); }
    @Override public Snapshot captureRollbackState() { checkThread(); return new Snapshot(this); }
    @Override public void restoreRollbackState(Snapshot snapshot) { checkThread(); if (snapshot.owner != this) throw new IllegalArgumentException("Foreign world checkpoint"); time = snapshot.time; lastTick = snapshot.lastTick; }
    @Override public List<?> rollbackReferences() { checkThread(); return List.of(logical, random, soundRandom, spatial, events); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private world services crossed threads"); }
}
