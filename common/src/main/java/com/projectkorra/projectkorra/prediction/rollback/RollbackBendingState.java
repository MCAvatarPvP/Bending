package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

/**
 * Transferred participant bending state and active-instance indices for domain bootstrap.
 * The loader supplies platform replacements and additional service roots to the same
 * transfer, so references shared with abilities remain shared in the private graph.
 * This does not discover addon static state, import live scheduler tasks, configure
 * event hooks, or install other services; the runtime bootstrap must provide those.
 */
public final class RollbackBendingState implements RollbackStateCell<Void> {
    private final Thread owner = Thread.currentThread();
    private final Map<UUID, BendingPlayer> players;
    private final CoreAbility.RollbackRegistry abilities;
    private final Manager.RollbackRegistry managers;
    private final CollisionManager collisions;
    private final List<OfflineBendingPlayer.RollbackTemporaryElement> temporaryElements;
    private final List<Object> services;
    private boolean installed;

    private RollbackBendingState(Map<UUID, BendingPlayer> players, CoreAbility.RollbackRegistry abilities,
                                 Manager.RollbackRegistry managers, CollisionManager collisions, List<OfflineBendingPlayer.RollbackTemporaryElement> temporaryElements,
                                 List<Object> services) {
        this.players = Collections.unmodifiableMap(players);
        this.abilities = abilities;
        this.managers = managers;
        this.collisions = collisions;
        this.temporaryElements = List.copyOf(temporaryElements);
        this.services = List.copyOf(services);
    }

    /** Run synchronously at the same tick boundary and clock epoch as native body import. */
    public static RollbackBendingState capture(Collection<BendingPlayer> participants, CollisionManager collisions,
                                               Collection<?> services, RollbackStateTransfer transfer) {
        Source source = source(participants, collisions, services);
        RollbackBendingState imported = fromRoots(source.roster.keySet(), transfer.copy(source.roots, source.projections));
        for (var entry : source.roster.entrySet()) {
            BendingPlayer player = imported.players.get(entry.getKey());
            if (player == entry.getValue() || player.getPlayer().handle() == entry.getValue().getPlayer().handle()) {
                throw new IllegalStateException("Bending import retained source player");
            }
        }
        if (imported.abilities == source.abilities || imported.managers == source.managers
                || imported.managers.instances().size() != source.managers.instances().size()
                || imported.managers.instances().stream().anyMatch(manager -> source.managers.instances().stream().anyMatch(live -> live == manager))) {
            throw new IllegalStateException("Bending import retained source registries/managers");
        }
        Set<CoreAbility> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(source.abilities.instances());
        if (imported.abilities.instances().stream().anyMatch(live::contains)) throw new IllegalStateException("Bending import retained source ability");
        return imported;
    }

    /** Capture one portable graph. Server and client decode these same bytes with their private bindings. */
    public static byte[] encode(Collection<BendingPlayer> participants, CollisionManager collisions,
                                Collection<?> services, RollbackGraphCodec codec) {
        Source source = source(participants, collisions, services);
        return codec.encode(source.roots, source.projections);
    }

    /** Export settled current domain state, replacing startup task/event roots with current registrations. */
    public byte[] exportState(RollbackGraphCodec codec) {
        if (Thread.currentThread() != owner || !installed || !RollbackDomain.active()
                || ProjectKorra.collisionManager != collisions || !BendingPlayer.getPlayers().keySet().equals(players.keySet())
                || players.entrySet().stream().anyMatch(entry -> BendingPlayer.getPlayers().get(entry.getKey()) != entry.getValue()))
            throw new IllegalStateException("Export bending state inside its owning installed domain");
        if (!(com.projectkorra.projectkorra.platform.Platform.scheduler() instanceof RollbackScheduler scheduler))
            throw new IllegalStateException("Export requires the private gameplay scheduler");
        var current = new ArrayList<Object>();
        var tasks = scheduler.exportTasks();
        var events = RollbackEventBindings.capture(com.projectkorra.projectkorra.platform.Platform.events());
        boolean foundTasks = false, foundEvents = false;
        for (Object service : services) {
            if (service instanceof RollbackTaskBindings) { if (!foundTasks) current.add(tasks); foundTasks = true; }
            else if (service instanceof RollbackEventBindings) { if (!foundEvents) current.add(events); foundEvents = true; }
            else current.add(service);
        }
        if (!foundTasks) current.add(tasks);
        if (!foundEvents) current.add(events);
        Source source = source(players.values(), collisions, current, true);
        return codec.encodeExport(source.roots, source.projections);
    }

    /** The expected roster comes from the session contract, never from an unvalidated object graph. */
    public static RollbackBendingState decode(Collection<UUID> participants, byte[] bytes, RollbackGraphCodec codec) {
        return fromRoots(participants, codec.decode(bytes));
    }

    /** Decode outgoing state onto the original live roster without installing or invoking gameplay. */
    public static Restoration decodeRestoration(Map<UUID, Player> livePlayers, byte[] bytes, RollbackGraphCodec codec) {
        var expected = Map.copyOf(livePlayers);
        for (var entry : expected.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getUniqueId()) || entry.getValue() instanceof RollbackPlayer)
                throw new IllegalArgumentException("Restoration requires original live player bindings");
        }
        var roots = codec.decode(bytes);
        var initial = fromRoots(expected.keySet(), roots, expected);
        var bindings = initial.abilities.liveAttributeBindings();
        var projections = new IdentityHashMap<Object, RollbackStateTransfer.Replacement>();
        var definitions = new ArrayList<AttributeCache>();
        var updates = new ArrayList<Object>();
        for (var binding : bindings.entrySet()) {
            var source = binding.getKey(); var target = binding.getValue(); definitions.add(source);
            projections.put(source, new RollbackStateTransfer.Replacement(target));
            projections.put(target, new RollbackStateTransfer.Replacement(target));
            projections.put(source.getInitialValues(), new RollbackStateTransfer.Replacement(target.getInitialValues()));
            projections.put(source.getCurrentModifications(), new RollbackStateTransfer.Replacement(target.getCurrentModifications()));
            // Distinct snapshot maps retain outgoing values while aliases to the cache maps rebind live.
            updates.add(List.of(target, new WeakHashMap<>(source.getInitialValues()), new WeakHashMap<>(source.getCurrentModifications())));
        }
        var managers = initial.managers.restorationSources(expected.keySet(),
                (source, target) -> projections.put(source, new RollbackStateTransfer.Replacement(target)));
        var combined = new ArrayList<Object>(roots); combined.add(updates); combined.add(managers.roots());
        var rebound = codec.rebind(combined, projections::get);
        return new Restoration(fromRoots(expected.keySet(), rebound.subList(0, roots.size()), expected),
                definitions, (List<?>) rebound.get(roots.size()), managers.prepare((List<?>) rebound.getLast()));
    }

    /** Detached restored graph. Its owner must commit all services before releasing gameplay gates. */
    public static final class Restoration {
        private final RollbackBendingState state;
        private final List<AttributeCache> definitions;
        private final List<?> attributeUpdates;
        private boolean attributesCommitted;
        private final Manager.RestorationStep managerCommit;
        private Restoration(RollbackBendingState state, List<AttributeCache> definitions, List<?> updates, Manager.RestorationStep managerCommit) {
            this.managerCommit = managerCommit;
            this.state = state; this.definitions = List.copyOf(definitions); attributeUpdates = List.copyOf(updates);
        }
        /** Validate every cache before any live registry is changed. */
        private void validateAttributes() {
            if (Thread.currentThread() != state.owner || RollbackDomain.active() || RollbackClock.active()
                    || !com.projectkorra.projectkorra.platform.Platform.scheduler().isPrimaryThread())
                throw new IllegalStateException("Restore attributes on the live main thread");
            var current = state.abilities.liveAttributeBindings();
            for (int i = 0; i < attributeUpdates.size(); i++) {
                var update = (List<?>) attributeUpdates.get(i); var cache = (AttributeCache) update.get(0);
                if (current.get(cache) != cache || !definitions.get(i).sameRollbackDefinition(cache))
                    throw new IllegalStateException("Live attribute ownership changed before restoration");
                for (int map = 1; map <= 2; map++) for (Object key : ((Map<?, ?>) update.get(map)).keySet()) {
                    if (!(key instanceof CoreAbility ability) || ability.getPlayer() == null
                            || state.players.get(ability.getPlayer().getUniqueId()) != ability.getBendingPlayer())
                        throw new IllegalStateException("Restored attribute entry is outside the participant graph");
                }
            }
        }
        /** Merge only participant entries into canonical cache maps; the owner retains gameplay gates. */
        @SuppressWarnings("unchecked")
        public void commitAttributes() {
            validateAttributes();
            if (attributesCommitted) return;
            for (Object value : attributeUpdates) {
                var update = (List<?>) value; var cache = (AttributeCache) update.get(0);
                removeParticipantAttributes(cache.getInitialValues());
                removeParticipantAttributes(cache.getCurrentModifications());
                cache.getInitialValues().putAll((Map<CoreAbility, Object>) update.get(1));
                cache.getCurrentModifications().putAll((Map) update.get(2));
            }
            attributesCommitted = true;
        }
        private void removeParticipantAttributes(Map<CoreAbility, ?> entries) {
            entries.keySet().removeIf(ability -> ability != null && ability.getPlayer() != null
                    && state.players.containsKey(ability.getPlayer().getUniqueId()));
        }
        public Map<UUID, BendingPlayer> players() { return state.players; }
        public CoreAbility.RollbackRegistry abilities() { return state.abilities; }
        public Manager.RollbackRegistry managers() { return state.managers; }
        public CollisionManager collisions() { return state.collisions; }
        public List<Object> services() { return state.services; }
        public void commitManagers() { managerCommit.commit(); }

        /** Prepare the common gameplay commit while the loader still holds whole-roster ownership. */
        public LiveCommit prepareCommit(Map<UUID, BendingPlayer> expectedPlayers,
                CoreAbility.RollbackIdReservation reservation, CoreAbility.RollbackRegistry expectedAbilities) {
            return new LiveCommit(preparePlayers(expectedPlayers), prepareAbilities(reservation, expectedAbilities));
        }

        /**
         * Validate all common registries before writing any of them. Native bodies, terrain,
         * listeners and scheduled work must also be restored before the loader releases its
         * gameplay gates. A failed commit retains those gates; retry uses the same plan.
         */
        public final class LiveCommit {
            private final OfflineBendingPlayer.RollbackPlayerRestoration playerCommit;
            private final CoreAbility.RollbackAbilityRestoration abilityCommit;
            private LiveCommit(OfflineBendingPlayer.RollbackPlayerRestoration players,
                    CoreAbility.RollbackAbilityRestoration abilities) {
                playerCommit = players; abilityCommit = abilities;
                validate();
            }
            public void validate() {
                playerCommit.requireCurrent();
                abilityCommit.requireCurrent();
                validateAttributes();
                managerCommit.validate();
            }
            public void commit() {
                validate();
                // No gameplay hooks run in these steps. Each completed step is idempotent.
                commitManagers();
                commitAttributes();
                playerCommit.commit();
                abilityCommit.commit();
            }
        }

        public CoreAbility.RollbackAbilityRestoration prepareAbilities(CoreAbility.RollbackIdReservation reservation,
                CoreAbility.RollbackRegistry expected) {
            if (Thread.currentThread() != state.owner) throw new IllegalStateException("Restoration crossed threads");
            return CoreAbility.prepareRollbackAbilityRestoration(reservation, expected, state.abilities, state.players);
        }
        public OfflineBendingPlayer.RollbackPlayerRestoration preparePlayers(Map<UUID, BendingPlayer> expected) {
            if (Thread.currentThread() != state.owner) throw new IllegalStateException("Restoration crossed threads");
            return OfflineBendingPlayer.prepareRollbackPlayerRestoration(expected, state.players, state.temporaryElements);
        }
    }

    private record Source(SortedMap<UUID, BendingPlayer> roster, CoreAbility.RollbackRegistry abilities,
                          Manager.RollbackRegistry managers, List<Object> roots,
                          Function<Object, RollbackStateTransfer.Replacement> projections) { }

    private static Source source(Collection<BendingPlayer> participants, CollisionManager collisions, Collection<?> services) {
        return source(participants, collisions, services, false);
    }

    private static Source source(Collection<BendingPlayer> participants, CollisionManager collisions, Collection<?> services, boolean exporting) {
        Objects.requireNonNull(collisions, "collisions");
        var roster = new TreeMap<UUID, BendingPlayer>();
        for (BendingPlayer player : participants) {
            if (player.getPlayer() == null || !player.getUUID().equals(player.getPlayer().getUniqueId())
                    || roster.putIfAbsent(player.getUUID(), player) != null) throw new IllegalArgumentException("Bending import roster");
        }
        if (roster.isEmpty() || roster.size() > 128) throw new IllegalArgumentException("Bending import participant count");
        var reservations = services.stream().filter(CoreAbility.RollbackIdReservation.class::isInstance)
                .map(CoreAbility.RollbackIdReservation.class::cast).toList();
        if (reservations.size() > 1 || (exporting && !reservations.isEmpty())) throw new IllegalArgumentException("Duplicate or exported live ability reservation");
        CoreAbility.RollbackRegistry registry = exporting ? CoreAbility.exportRollbackRegistry(roster.keySet())
                : reservations.isEmpty() ? CoreAbility.captureRollbackRegistry(roster.keySet())
                : CoreAbility.captureRollbackRegistry(roster.keySet(), reservations.getFirst());
        Manager.RollbackRegistry managerRegistry = exporting ? Manager.exportRollbackRegistry() : Manager.captureRollbackRegistry();
        var roots = new ArrayList<Object>(roster.values());
        roots.add(registry);
        roots.add(collisions.rollbackImportSource());
        roots.add(exporting ? OfflineBendingPlayer.exportRollbackTemporaryElements(roster.keySet())
                : OfflineBendingPlayer.captureRollbackTemporaryElements(roster.keySet()));
        roots.add(managerRegistry);
        for (Object service : services) if (!(service instanceof CoreAbility.RollbackIdReservation))
            roots.add(service instanceof RollbackTaskBindings.Capture tasks ? tasks.bindings() : service);
        // Attribute definitions exist for future activations too. Keep their metadata,
        // but import only this roster's per-instance cache entries. Project the maps,
        // not the cache objects, to preserve references held by arbitrary ability fields.
        var projections = new IdentityHashMap<Object, RollbackStateTransfer.Replacement>();
        for (Object service : services) if (service instanceof RollbackTaskBindings.Capture tasks) tasks.projectSources(projections::put);
        java.util.function.BiConsumer<Object, Object> projectManager = (source, view) ->
                projections.put(source, RollbackStateTransfer.Replacement.fromProjection(view));
        if (exporting) managerRegistry.projectCurrentSources(roster.keySet(), projectManager);
        else managerRegistry.projectSources(roster.keySet(), projectManager);
        for (AttributeCache cache : registry.attributes()) {
            projections.put(cache, RollbackStateTransfer.Replacement.fromProjection(cache));
            projectAttributeEntries(cache.getInitialValues(), roster.keySet(), projections);
            projectAttributeEntries(cache.getCurrentModifications(), roster.keySet(), projections);
        }
        return new Source(roster, registry, managerRegistry, roots, value -> {
            if (AttributeCache.isRollbackMetadata(value)) return new RollbackStateTransfer.Replacement(value);
            var projection = projections.get(value);
            if (projection != null) return projection;
            for (Object service : services) if (service instanceof RollbackTaskBindings.Capture tasks) {
                var task = tasks.replacement(value); if (task != null) return task;
            }
            return RollbackCallback.project(value);
        });
    }

    private static RollbackBendingState fromRoots(Collection<UUID> participants, List<Object> copied) {
        return fromRoots(participants, copied, null);
    }

    private static RollbackBendingState fromRoots(Collection<UUID> participants, List<Object> copied, Map<UUID, Player> livePlayers) {
        var roster = new TreeSet<>(participants);
        if (roster.isEmpty() || roster.size() > 128 || roster.size() != participants.size() || copied.size() < roster.size() + 4) {
            throw new IllegalArgumentException("Bending import roster/roots");
        }
        var players = new LinkedHashMap<UUID, BendingPlayer>();
        int index = 0;
        for (UUID id : roster) {
            BendingPlayer player = root(copied.get(index++), BendingPlayer.class);
            Player body = player.getPlayer();
            if (!id.equals(player.getUUID()) || body == null || !id.equals(body.getUniqueId()))
                throw new IllegalStateException("Bending import player identity");
            if (livePlayers == null ? !(body instanceof RollbackPlayer)
                    : body instanceof RollbackPlayer || body.handle() != livePlayers.get(id).handle())
                throw new IllegalStateException("Bending import player binding differs from its destination");
            players.put(id, player);
        }
        var abilities = root(copied.get(index++), CoreAbility.RollbackRegistry.class);
        var collisions = root(copied.get(index++), CollisionManager.class);
        if (collisions.getDetectionRunnable() != null) throw new IllegalStateException("Bending import retained live collision scheduler");
        List<?> temporary = root(copied.get(index++), List.class);
        var temporaryElements = temporary.stream()
                .map(value -> root(value, OfflineBendingPlayer.RollbackTemporaryElement.class)).toList();
        var managers = root(copied.get(index++), Manager.RollbackRegistry.class);
        for (CoreAbility ability : abilities.instances()) {
            BendingPlayer player = ability.getPlayer() == null ? null : players.get(ability.getPlayer().getUniqueId());
            if (player == null || ability.getBendingPlayer() != player || ability.getPlayer().handle() != player.getPlayer().handle()) {
                throw new IllegalStateException("Imported ability does not reference the participant bending graph");
            }
        }
        return new RollbackBendingState(players, abilities, managers, collisions, temporaryElements, copied.subList(index, copied.size()));
    }

    private static <T> T root(Object value, Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("Bending import root type " + type.getName());
        return type.cast(value);
    }

    public Map<UUID, BendingPlayer> players() { return players; }
    public List<CoreAbility> abilities() { return abilities.instances(); }
    public CollisionManager collisions() { return collisions; }
    public List<Object> services() { return services; }

    /** Include these roots in addition to every other shared service used by the session. */
    public static List<Field> sharedFields() {
        var fields = new ArrayList<Field>();
        fields.addAll(RollbackStateGraph.staticFields(OfflineBendingPlayer.class,
                field -> Set.of("PLAYERS", "ONLINE_PLAYERS", "TEMP_ELEMENTS").contains(field.getName())));
        fields.addAll(RollbackStateGraph.staticFields(CoreAbility.class, field -> field.getName().startsWith("INSTANCES")
                || Set.of("idCounter", "idLimit", "currentTick", "ATTRIBUTE_FIELDS").contains(field.getName())));
        fields.addAll(RollbackStateGraph.staticFields(ProjectKorra.class, field -> field.getName().equals("collisionManager")));
        fields.addAll(RollbackStateGraph.staticFields(Manager.class, field -> field.getName().equals("MANAGERS")));
        return List.copyOf(fields);
    }

    /** Called once from domain bootstrap; subsequent swaps use ordinary checkpoints. */
    public void install() {
        if (Thread.currentThread() != owner || !RollbackDomain.active() || installed) {
            throw new IllegalStateException("Bending import requires one bootstrap on its owning thread");
        }
        var events = services.stream().filter(RollbackEventBindings.class::isInstance)
                .map(RollbackEventBindings.class::cast).toList();
        if (events.size() > 1) throw new IllegalArgumentException("Duplicate event registration roots");
        if (!events.isEmpty()) {
            if (!(com.projectkorra.projectkorra.platform.Platform.events() instanceof RollbackEventBus bus))
                throw new IllegalStateException("Imported event rules require a private event bus");
            events.getFirst().install(bus);
        }
        var tasks = services.stream().filter(RollbackTaskBindings.class::isInstance)
                .map(RollbackTaskBindings.class::cast).toList();
        if (tasks.size() > 1) throw new IllegalArgumentException("Duplicate task binding roots");
        if (!tasks.isEmpty()) {
            if (!(com.projectkorra.projectkorra.platform.Platform.scheduler() instanceof RollbackScheduler scheduler))
                throw new IllegalStateException("Imported callbacks require a private scheduler");
            tasks.getFirst().install(scheduler);
        }
        abilities.install();
        OfflineBendingPlayer.installRollbackPlayers(players, temporaryElements);
        ProjectKorra.collisionManager = collisions;
        managers.install();
        installed = true;
    }

    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    @Override public Collection<?> rollbackReferences() {
        var roots = new ArrayList<Object>(players.values());
        roots.add(abilities);
        roots.add(managers);
        roots.add(collisions);
        roots.addAll(temporaryElements);
        roots.addAll(services);
        return roots;
    }

    private static <V> void projectAttributeEntries(Map<CoreAbility, V> source, Set<UUID> participants,
                                                     IdentityHashMap<Object, RollbackStateTransfer.Replacement> projections) {
        var selected = new WeakHashMap<CoreAbility, V>();
        source.forEach((ability, value) -> {
            if (ability == null || ability.getPlayer() == null || participants.contains(ability.getPlayer().getUniqueId())) {
                selected.put(ability, value);
            }
        });
        projections.put(source, RollbackStateTransfer.Replacement.fromProjection(selected));
    }
}
