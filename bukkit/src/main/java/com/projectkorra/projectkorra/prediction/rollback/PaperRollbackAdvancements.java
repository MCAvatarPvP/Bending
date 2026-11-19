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
    public static final class Seed {
        private final List<AdvancementHolder> definitions;
        private final Map<Identifier, Map<String, Instant>> obtained;
        private boolean ownsDefinitions;
        public Seed(List<AdvancementHolder> definitions, Map<Identifier, Map<String, Instant>> obtained) {
            this.definitions = List.copyOf(definitions);
            var values = new LinkedHashMap<Identifier, Map<String, Instant>>();
            obtained.forEach((id, criteria) -> values.put(id, Map.copyOf(criteria)));
            this.obtained = Map.copyOf(values);
            if (definitions.size() > 10_000) throw new IllegalArgumentException("Advancement definition budget exceeded");
        }
        public List<AdvancementHolder> definitions() { return definitions; }
        public Map<Identifier, Map<String, Instant>> obtained() { return obtained; }
        private List<AdvancementHolder> detach(com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
            if (ownsDefinitions) {
                ownsDefinitions = false;
                return definitions;
            }
            return definitions.stream().map(holder -> EncodedDefinition.capture(holder, ops).decode(ops)).toList();
        }
        public static Seed capture(ServerPlayer player) {
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread() || RollbackDomain.active() || RollbackClock.active())
                throw new IllegalStateException("Capture advancements on the live server thread");
            var manager = player.level().getServer().getAdvancements();
            var definitions = manager.getAllAdvancements().stream().sorted(Comparator.comparing(AdvancementHolder::id)).toList();
            var obtained = new LinkedHashMap<Identifier, Map<String, Instant>>();
            PaperRollbackPrivateAccess.advancementProgress(player.getAdvancements()).forEach((holder, progress) -> {
                if (manager.get(holder.id()) != holder) throw new IllegalStateException("Advancement definition changed during capture");
                if (!progress.hasProgress()) return;
                var criteria = new TreeMap<String, Instant>();
                for (var name : progress.getCompletedCriteria()) criteria.put(name, progress.getCriterion(name).getObtained());
                obtained.put(holder.id(), criteria);
            });
            return new Seed(definitions, obtained);
        }

        public static Seed empty() { return new Seed(List.of(), Map.of()); }
    }
    /** Only registry identifiers, serialized values and display coordinates cross to the decoder. */
    private record EncodedDefinition(Identifier id, com.google.gson.JsonElement value, com.google.gson.JsonElement name,
            float x, float y) {
        static EncodedDefinition capture(AdvancementHolder holder, com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
            var original = holder.value();
            var display = original.display().orElse(null);
            return new EncodedDefinition(holder.id(), Advancement.CODEC.encodeStart(ops, original).getOrThrow(),
                    original.name().map(component -> ComponentSerialization.CODEC.encodeStart(ops, component).getOrThrow()).orElse(null),
                    display == null ? 0 : display.getX(), display == null ? 0 : display.getY());
        }
        AdvancementHolder decode(com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
            var copy = Advancement.CODEC.parse(ops, value).getOrThrow();
            copy.display().ifPresent(display -> display.setLocation(x, y));
            var component = Optional.ofNullable(name).map(value -> ComponentSerialization.CODEC.parse(ops, value).getOrThrow());
            return new AdvancementHolder(id, new Advancement(copy.parent(), copy.display(), copy.rewards(), copy.criteria(),
                    copy.requirements(), copy.sendsTelemetryEvent(), component));
        }
    }

    static java.util.concurrent.CompletableFuture<Map<UUID, PaperRollbackRosterSeed.Services>> prepareRoster(
            Map<UUID, PaperRollbackRosterSeed.Services> services, net.minecraft.core.RegistryAccess registries) {
        var source = Map.copyOf(services);
        var definitions = source.values().iterator().next().advancements().definitions();
        for (var service : source.values()) {
            var other = service.advancements().definitions();
            if (other.size() != definitions.size()) throw new IllegalStateException("Advancement catalogue changed during roster capture");
            for (int i = 0; i < definitions.size(); i++) if (definitions.get(i) != other.get(i))
                throw new IllegalStateException("Advancement catalogue changed during roster capture");
        }
        // Snapshot mutable live definitions exactly once at ownership acquisition. Worker threads
        // decode only this detached image against the same frozen native registries.
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var encoded = definitions.stream().map(holder -> EncodedDefinition.capture(holder, ops)).toList();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var result = new TreeMap<UUID, PaperRollbackRosterSeed.Services>();
            var input = registries.createSerializationContext(JsonOps.INSTANCE);
            source.forEach((id, service) -> {
                var privateSeed = new Seed(encoded.stream().map(row -> row.decode(input)).toList(), service.advancements().obtained());
                privateSeed.ownsDefinitions = true;
                result.put(id, new PaperRollbackRosterSeed.Services(service.randomSeed(), service.maximumObjects(), service.statistics(), privateSeed));
            });
            return Map.copyOf(result);
        });
    }
    public record Announcement(UUID player, String component) implements PaperRollbackCombatAccess.Output { }

    private final Thread thread = Thread.currentThread();
    private final PaperRollbackWorldAccess world;
    private final PlayerAdvancements tracker;
    private final State state = new State();
    private final Map<Identifier, AdvancementHolder> definitions = new LinkedHashMap<>();
    private final Set<Object> boundaries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Object> checkpointRoots;
    private List<CriterionProgress> criteria = List.of();
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
        var roots = new ArrayList<Object>();
        for (var detached : seed.detach(ops)) {
            if (definitions.containsKey(detached.id())) throw new IllegalArgumentException("Duplicate advancement: " + detached.id());
            var copy = detached.value();
            // Each listener refers directly to its catalogue predicate as well as to the holder.
            // Conditions are evaluated against private player state. They may retain tag lookups
            // into the live registry and must never become checkpoint roots.
            for (var criterion : copy.criteria().values()) {
                requireTrigger(criterion.trigger());
                boundaries.add(criterion.triggerInstance());
            }
            definitions.put(detached.id(), detached);
            boundaries.add(detached);
            boundaries.add(copy.requirements());
            // These detached values have mutable public interfaces (display coordinates/icons,
            // message components and cached reward functions). Preserve their private state.
            copy.display().ifPresent(roots::add);
            copy.name().ifPresent(roots::add);
            copy.rewards().function().ifPresent(roots::add);
        }
        checkpointRoots = List.copyOf(roots);
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
        // Native registerListeners initializes every catalogue row. Award, revoke and flush
        // retain its criterion objects and change their obtained times and tracker memberships.
        var slots = new ArrayList<CriterionProgress>();
        for (var holder : definitions.values()) {
            var progress = Objects.requireNonNull(state.progress.get(holder), "Missing initialized native advancement");
            for (var name : holder.value().criteria().keySet()) slots.add(Objects.requireNonNull(progress.getCriterion(name)));
        }
        criteria = List.copyOf(slots);
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
        long time = RollbackTiming.start();
        var dates = new Instant[criteria.size()];
        for (int index = 0; index < dates.length; index++) dates[index] = criteria.get(index).getObtained();
        var listeners = new ArrayList<ListenerGroup>(state.listeners.size());
        state.listeners.forEach((trigger, group) -> listeners.add(new ListenerGroup(trigger, group, List.copyOf(group))));
        var presentation = graph.capture(checkpointRoots, List.of());
        var result = new Checkpoint(this, presentation, dates, listeners, List.copyOf(state.visible),
                List.copyOf(state.changed), List.copyOf(state.roots), PaperRollbackPrivateAccess.advancementFirstPacket(tracker));
        RollbackTiming.finish(time, "advancements.checkpoint", presentation.objectCount());
        return result;
    }

    @Override public void restoreRollbackState(Checkpoint saved) {
        requirePlayer();
        if (Objects.requireNonNull(saved, "checkpoint").owner != this) throw new IllegalArgumentException("Advancement checkpoint belongs to another player");
        long time = RollbackTiming.start();
        for (int index = 0; index < saved.dates.length; index++) {
            var criterion = criteria.get(index);
            var obtained = saved.dates[index];
            if (!Objects.equals(criterion.getObtained(), obtained)) PaperRollbackPrivateAccess.obtainCriterion(criterion, obtained);
        }
        // Sets are native aliases that triggers can retain. Reinstall the original instances.
        state.listeners.clear();
        for (var group : saved.listeners) {
            restoreSet(group.target, group.members);
            state.listeners.put(group.trigger, group.target);
        }
        restoreSet(state.visible, saved.visible);
        restoreSet(state.changed, saved.changed);
        restoreSet(state.roots, saved.roots);
        saved.presentation.restore();
        PaperRollbackPrivateAccess.advancementFirstPacket(tracker, saved.firstPacket);
        RollbackTiming.finish(time, "advancements.restore", saved.presentation.objectCount());
    }

    private static <T> void restoreSet(Set<T> target, List<T> members) {
        if (target.size() == members.size()) {
            var current = target.iterator();
            boolean same = true;
            for (var member : members) if (current.next() != member) { same = false; break; }
            if (same) return;
        }
        target.clear();
        target.addAll(members);
    }

    private record ListenerGroup(SimpleCriterionTrigger<?> trigger, Set<CriterionTrigger.Listener<?>> target,
                                 List<CriterionTrigger.Listener<?>> members) { }

    @Override public List<?> rollbackReferences() { check(); return List.of(world); }

    public static final class Checkpoint {
        private final PaperRollbackAdvancements owner;
        private final RollbackStateGraph.Snapshot presentation;
        private final Instant[] dates;
        private final List<ListenerGroup> listeners;
        private final List<AdvancementHolder> visible, changed;
        private final List<AdvancementNode> roots;
        private final boolean firstPacket;

        private Checkpoint(PaperRollbackAdvancements owner, RollbackStateGraph.Snapshot presentation, Instant[] dates,
                           List<ListenerGroup> listeners, List<AdvancementHolder> visible, List<AdvancementHolder> changed,
                           List<AdvancementNode> roots, boolean firstPacket) {
            this.owner = owner;
            this.presentation = presentation;
            this.dates = dates;
            this.listeners = listeners;
            this.visible = visible;
            this.changed = changed;
            this.roots = roots;
            this.firstPacket = firstPacket;
        }
    }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Private native advancement operation failed", failure);
    }
}
