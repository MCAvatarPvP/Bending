package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.serialization.JsonOps;
import com.mojang.serialization.JavaOps;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.adventure.WrapperAwareSerializer;
import net.minecraft.advancements.*;
import net.minecraft.advancements.criterion.ImpossibleTrigger;
import net.minecraft.advancements.criterion.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Instant;
import java.util.*;

/** Native advancement progress, listeners and events over detached definitions and owned player state. */
public final class PaperRollbackAdvancements implements RollbackStateCell<PaperRollbackAdvancements.Checkpoint> {
    /** Existing logged-in player state. Import does not rerun login rewards or completed criteria. */
    public record Seed(List<AdvancementHolder> definitions, Map<Identifier, Map<String, Instant>> obtained) {
        public Seed {
            definitions = List.copyOf(definitions);
            var values = new LinkedHashMap<Identifier, Map<String, Instant>>();
            obtained.forEach((id, criteria) -> values.put(id, Map.copyOf(criteria)));
            obtained = Map.copyOf(values);
            if (definitions.size() > 10_000) throw new IllegalArgumentException("Advancement definition budget exceeded");
        }
        public static Seed empty() { return new Seed(List.of(), Map.of()); }
    }
    public record Announcement(UUID player, String component) implements PaperRollbackCombatAccess.Output { }

    private final Thread thread = Thread.currentThread();
    private final PaperRollbackWorldAccess world;
    private final PlayerAdvancements tracker;
    private final State state = new State();
    private final Map<Identifier, AdvancementHolder> definitions = new LinkedHashMap<>();
    private final Set<Object> boundaries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final RollbackStateGraph graph;
    private final WrapperAwareSerializer components;
    private final MethodHandle award, revoke, register;
    private ServerPlayer player;
    private boolean initialized;

    private static final class State {
        final AdvancementTree tree = new AdvancementTree();
        final Map<AdvancementHolder, AdvancementProgress> progress = new LinkedHashMap<>();
        final Set<AdvancementHolder> visible = new HashSet<>(), changed = new HashSet<>();
        final Set<AdvancementNode> roots = new HashSet<>();
        final Map<SimpleCriterionTrigger<?>, Set<CriterionTrigger.Listener<?>>> listeners = new IdentityHashMap<>();
    }

    PaperRollbackAdvancements(PaperRollbackWorldAccess world, Seed seed) {
        if (RollbackClock.active()) throw new IllegalStateException("Construct native advancements before replay");
        this.world = Objects.requireNonNull(world, "world");
        components = new WrapperAwareSerializer(() -> world.world().registryAccess().createSerializationContext(JavaOps.INSTANCE));
        Objects.requireNonNull(seed, "seed");
        var ops = world.world().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (var holder : seed.definitions()) {
            if (definitions.containsKey(holder.id())) throw new IllegalArgumentException("Duplicate advancement: " + holder.id());
            // The data-pack codec detaches predicates, items, requirements and
            // mutable display data; retain native registry/trigger identities only.
            var original = holder.value();
            var copy = Advancement.CODEC.parse(ops, Advancement.CODEC.encodeStart(ops, original).getOrThrow()).getOrThrow();
            if (original.display().isPresent()) {
                var display = original.display().orElseThrow();
                copy.display().orElseThrow().setLocation(display.getX(), display.getY());
            }
            var name = original.name().map(value -> ComponentSerialization.CODEC.parse(ops,
                    ComponentSerialization.CODEC.encodeStart(ops, value).getOrThrow()).getOrThrow());
            copy = new Advancement(copy.parent(), copy.display(), copy.rewards(), copy.criteria(), copy.requirements(), copy.sendsTelemetryEvent(), name);
            for (var criterion : copy.criteria().values()) requireTrigger(criterion.trigger());
            definitions.put(holder.id(), new AdvancementHolder(holder.id(), copy));
        }
        validateParents();
        state.tree.addAll(definitions.values());
        for (var entry : seed.obtained().entrySet()) {
            var holder = definition(entry.getKey());
            var progress = new AdvancementProgress();
            progress.update(holder.value().requirements());
            for (var criterion : entry.getValue().entrySet()) {
                var value = progress.getCriterion(criterion.getKey());
                if (value == null) throw new IllegalArgumentException("Imported criterion is absent: " + entry.getKey() + "/" + criterion.getKey());
                PaperRollbackPrivateAccess.obtainCriterion(value, criterion.getValue());
            }
            state.progress.put(holder, progress);
            state.changed.add(holder);
            state.roots.add(state.tree.get(holder).root());
        }
        var playerList = RollbackNativeQueryShell.create(PlayerList.class)
                .outputQuery(value -> value.broadcastSystemMessage(Component.empty(), false), args -> announce((Component) args[0]))
                .instance();
        boundaries.add(playerList);
        var methods = new RollbackNativeMethods();
        world.bindEvents(methods);
        world.bindAdvancementRewards(methods);
        try {
            Set<String> privateBodies = Set.of("markForVisibilityUpdate", "unregisterListeners", "registerListener", "removeListener");
            for (var method : PlayerAdvancements.class.getDeclaredMethods()) {
                if (privateBodies.contains(method.getName())) methods.copy(method);
                if (method.getName().startsWith("lambda$award$")) methods.copyLambda(method);
            }
            methods.replace(PaperAdventure.class.getMethod("asAdventure", Component.class),
                    MethodHandles.lookup().findVirtual(PaperRollbackAdvancements.class, "adventure",
                            MethodType.methodType(net.kyori.adventure.text.Component.class, Component.class)).bindTo(this));
            methods.replace(PaperAdventure.class.getMethod("asVanilla", net.kyori.adventure.text.Component.class),
                    MethodHandles.lookup().findVirtual(PaperRollbackAdvancements.class, "vanilla",
                            MethodType.methodType(Component.class, net.kyori.adventure.text.Component.class)).bindTo(this));
            var awardMethod = PlayerAdvancements.class.getMethod("award", AdvancementHolder.class, String.class);
            var revokeMethod = PlayerAdvancements.class.getMethod("revoke", AdvancementHolder.class, String.class);
            var registerMethod = PlayerAdvancements.class.getDeclaredMethod("registerListeners", AdvancementHolder.class);
            methods.copy(awardMethod).copy(revokeMethod).copy(registerMethod)
                    .copy(AdvancementProgress.class.getMethod("grantProgress", String.class))
                    .copy(CriterionProgress.class.getMethod("grant"))
                    .replace(Instant.class.getMethod("now"), MethodHandles.lookup().findStatic(PaperRollbackAdvancements.class,
                            "now", MethodType.methodType(Instant.class)));
            var copied = methods.build();
            award = copied.get(awardMethod); revoke = copied.get(revokeMethod); register = copied.get(registerMethod);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native advancement methods changed", failure); }
        tracker = RollbackNativeQueryShell.create(PlayerAdvancements.class)
                .query(value -> value.award(null, ""), false, args -> change((AdvancementHolder) args[0], (String) args[1], true))
                .query(value -> value.revoke(null, ""), false, args -> change((AdvancementHolder) args[0], (String) args[1], false))
                .nativeQuery(value -> value.getOrStartProgress(null), null, args -> { check(); requireDefinition((AdvancementHolder) args[0]); })
                .nativeAction(value -> value.flushDirty(null, true), args -> {
                    requirePlayer();
                    if (args[0] != player) throw new IllegalArgumentException("Foreign advancement recipient");
                })
                .instance();
        PaperRollbackPrivateAccess.initializeAdvancements(tracker, playerList, state.tree, state.progress, state.visible, state.changed, state.roots, state.listeners);
        boundaries.add(tracker);
        graph = new RollbackStateGraph(value -> boundaries.contains(value) || PaperRollbackMetadata.frozen(value)
                || value instanceof Instant || triggerMetadata(value), field ->
                !(field.getDeclaringClass().getName().startsWith("it.unimi.dsi.fastutil.")
                        && Map.class.isAssignableFrom(field.getDeclaringClass()) && Collection.class.isAssignableFrom(field.getType())
                        && Set.of("entries", "keys", "values").contains(field.getName())), 200_000);
    }

    PlayerAdvancements tracker() { check(); return tracker; }
    void bindPlayer(ServerPlayer player) {
        check();
        if (this.player != null || player.level() != world.world()) throw new IllegalArgumentException("Advancements already bound or foreign player");
        this.player = player; PaperRollbackPrivateAccess.advancementPlayer(tracker, player);
    }
    void initialize() {
        check(); requirePlayer();
        if (initialized) throw new IllegalStateException("Advancement listeners already initialized");
        for (var holder : definitions.values()) {
            try { register.invokeExact(tracker, holder); }
            catch (Throwable failure) { throw failed(failure); }
        }
        initialized = true;
    }
    AdvancementHolder definition(Identifier id) {
        check();
        var holder = definitions.get(Objects.requireNonNull(id, "advancement identifier"));
        if (holder == null) throw new IllegalArgumentException("Advancement is absent from the captured catalogue: " + id);
        return holder;
    }
    boolean award(Identifier id, String criterion) { return tracker.award(definition(id), criterion); }
    boolean revoke(Identifier id, String criterion) { return tracker.revoke(definition(id), criterion); }
    Map<String, Instant> obtained(Identifier id) {
        requirePlayer();
        var progress = tracker.getOrStartProgress(definition(id));
        var obtained = new LinkedHashMap<String, Instant>();
        for (var criterion : progress.getCompletedCriteria()) obtained.put(criterion, progress.getCriterion(criterion).getObtained());
        return Map.copyOf(obtained);
    }
    org.bukkit.advancement.AdvancementProgress bukkitProgress(org.bukkit.advancement.Advancement advancement) {
        requirePlayer();
        if (!(advancement instanceof org.bukkit.craftbukkit.advancement.CraftAdvancement craft)) {
            throw new IllegalArgumentException("Advancement does not belong to the native catalogue");
        }
        requireDefinition(craft.getHandle());
        return new View(craft);
    }
    private final class View implements org.bukkit.advancement.AdvancementProgress, RollbackStateCell<Void> {
        private final org.bukkit.craftbukkit.advancement.CraftAdvancement advancement;
        private View(org.bukkit.craftbukkit.advancement.CraftAdvancement advancement) { this.advancement = advancement; }
        private org.bukkit.craftbukkit.advancement.CraftAdvancementProgress current() {
            requirePlayer(); requireDefinition(advancement.getHandle());
            return new org.bukkit.craftbukkit.advancement.CraftAdvancementProgress(advancement, tracker, tracker.getOrStartProgress(advancement.getHandle()));
        }
        @Override public org.bukkit.advancement.Advancement getAdvancement() { return current().getAdvancement(); }
        @Override public boolean isDone() { return current().isDone(); }
        @Override public boolean awardCriteria(String criterion) { return current().awardCriteria(criterion); }
        @Override public boolean revokeCriteria(String criterion) { return current().revokeCriteria(criterion); }
        @Override public java.util.Date getDateAwarded(String criterion) { return current().getDateAwarded(criterion); }
        @Override public Collection<String> getRemainingCriteria() { return current().getRemainingCriteria(); }
        @Override public Collection<String> getAwardedCriteria() { return current().getAwardedCriteria(); }
        @Override public Void captureRollbackState() { requirePlayer(); return null; }
        @Override public void restoreRollbackState(Void saved) { check(); }
        @Override public List<?> rollbackReferences() { check(); return List.of(PaperRollbackAdvancements.this); }
    }
    private void requireDefinition(AdvancementHolder holder) {
        if (holder == null || definitions.get(holder.id()) != holder) throw new IllegalArgumentException("Foreign advancement definition");
    }
    private boolean change(AdvancementHolder holder, String criterion, boolean grant) {
        check(); requirePlayer(); requireDefinition(holder); Objects.requireNonNull(criterion, "criterion");
        try { return (boolean) (grant ? award : revoke).invokeExact(tracker, holder, criterion); }
        catch (Throwable failure) { throw failed(failure); }
    }
    private void announce(Component component) {
        requirePlayer();
        var ops = world.world().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        world.output(new Announcement(player.getUUID(), ComponentSerialization.CODEC.encodeStart(ops, component).getOrThrow().toString()));
    }
    private net.kyori.adventure.text.Component adventure(Component component) {
        check();
        return component == null ? net.kyori.adventure.text.Component.empty() : components.deserialize(component);
    }
    private Component vanilla(net.kyori.adventure.text.Component component) {
        check();
        return component == null ? null : components.serialize(component);
    }
    private static Instant now() {
        if (!RollbackClock.active()) throw new IllegalStateException("Advancement awards require simulation time");
        return Instant.ofEpochMilli(RollbackClock.millis());
    }
    private void requirePlayer() {
        check();
        if (player == null || !world.ownsPlayer(player) || player.level() != world.world()) throw new IllegalArgumentException("Advancement player is not registered in this world");
    }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Native advancements crossed threads"); }
    private static void requireTrigger(CriterionTrigger<?> trigger) {
        if (BuiltInRegistries.TRIGGER_TYPES.getResourceKey(trigger).isEmpty()
                || !(trigger instanceof SimpleCriterionTrigger<?> || trigger.getClass() == ImpossibleTrigger.class)) {
            throw new IllegalArgumentException("Trigger needs an audited private listener adapter: " + trigger.getClass().getName());
        }
    }
    private static boolean triggerMetadata(Object value) {
        if (value instanceof CriterionTrigger<?> trigger) { requireTrigger(trigger); return true; }
        return false;
    }
    private void validateParents() {
        var resolved = new HashSet<Identifier>();
        for (var holder : definitions.values()) {
            var visited = new HashSet<Identifier>();
            var current = holder;
            while (!resolved.contains(current.id())) {
                if (!visited.add(current.id())) throw new IllegalArgumentException("Advancement parent cycle: " + holder.id());
                var parent = current.value().parent();
                if (parent.isEmpty()) break;
                current = definition(parent.orElseThrow());
            }
            resolved.addAll(visited);
        }
    }
    @Override public Checkpoint captureRollbackState() {
        requirePlayer();
        if (!initialized) throw new IllegalStateException("Advancement listeners are not initialized");
        return new Checkpoint(this, graph.capture(List.of(state), List.of()), PaperRollbackPrivateAccess.advancementFirstPacket(tracker));
    }
    @Override public void restoreRollbackState(Checkpoint saved) {
        requirePlayer();
        if (Objects.requireNonNull(saved, "checkpoint").owner != this) throw new IllegalArgumentException("Advancement checkpoint belongs to another player");
        saved.state.restore();
        PaperRollbackPrivateAccess.advancementFirstPacket(tracker, saved.firstPacket);
    }
    @Override public List<?> rollbackReferences() { check(); return List.of(world); }
    public static final class Checkpoint {
        private final PaperRollbackAdvancements owner;
        private final RollbackStateGraph.Snapshot state;
        private final boolean firstPacket;
        private Checkpoint(PaperRollbackAdvancements owner, RollbackStateGraph.Snapshot state, boolean firstPacket) {
            this.owner = owner; this.state = state; this.firstPacket = firstPacket;
        }
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Private native advancement operation failed", failure);
    }
}
