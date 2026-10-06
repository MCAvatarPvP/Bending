package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.StatisticsManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackManagerImportTest {
    @org.junit.jupiter.api.BeforeAll static void initializeLiveMaterialCaches() throws Exception {
        try (var world = new com.projectkorra.projectkorra.support.AbilityWorld()) {
            com.projectkorra.projectkorra.ability.ElementalAbility.getTransparentMaterials();
        }
    }

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), OTHER = new UUID(0, 3);
    private static final long EPOCH = 10_000;

    @Test
    void flightExpiryAndStatisticsReplayWithPrivateManagersAndPreserveSharedReferences() throws Exception {
        var graph = new RollbackStateGraph(value -> value instanceof World, ignored -> true, 30_000);
        var shared = RollbackBendingState.sharedFields();
        var outside = graph.capture(List.of(), shared);
        try {
            var liveA = new LivePlayer(A);
            var liveB = new LivePlayer(B);
            var other = new LivePlayer(OTHER);
            FlightHandler sourceFlight = construct(FlightHandler.class);
            StatisticsManager sourceStats = construct(StatisticsManager.class);
            managers().clear();
            managers().put(FlightHandler.class, sourceFlight);
            managers().put(StatisticsManager.class, sourceStats);
            try (var clock = RollbackClock.at(EPOCH, 0, 50_000_000)) {
                sourceFlight.createInstance(liveA, liveB, 100, "timed");
                sourceFlight.createInstance(liveB, "permanent");
                sourceFlight.createInstance(other, 50, "outside");
            }
            liveA.setAllowFlight(true);
            liveA.setFlying(true);
            seedStatistics(sourceStats, A, 10);
            seedStatistics(sourceStats, B, 20);
            seedStatistics(sourceStats, OTHER, 99);
            sourceStats.getKeysByName().put("hits_dynamic", 1);
            sourceStats.getKeysById().put(1, "hits_dynamic");

            var controls = (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(),
                    new Class<?>[]{RollbackPlayerState.Rules.class}, (proxy, method, args) -> {
                        if (method.getName().equals("controls")) return args[1];
                        throw new AssertionError(method);
                    });
            var privateA = PrivateCombatRollbackTest.player(new World(), 1, controls);
            var privateB = PrivateCombatRollbackTest.player(new World(), 2);
            privateA.setAllowFlight(true);
            privateA.setFlying(true);
            var replacements = new IdentityHashMap<Object, Object>();
            replacements.put(liveA, privateA);
            replacements.put(liveB, privateB);
            var services = List.of(sourceFlight, sourceFlight.getInstance(liveA), sourceStats.getStatisticsMap(A));
            var imported = RollbackBendingState.capture(List.of(new BendingPlayer(liveA), new BendingPlayer(liveB)),
                    new CollisionManager(), services, transfer(replacements));
            var scheduler = new RollbackScheduler(10, 10);
            var platform = platform(scheduler);
            var domain = RollbackDomain.create(graph, shared, List.of(imported, scheduler), platform, imported::install);
            assertEquals(1, scheduler.pendingTasks()); // No duplicate live activation/listeners or statistics I/O timer.
            domain.call(() -> {
                var flight = Manager.getManager(FlightHandler.class);
                var stats = Manager.getManager(StatisticsManager.class);
                assertNotSame(sourceFlight, flight);
                assertSame(flight, imported.services().get(0));
                assertSame(flight.getInstance(privateA), imported.services().get(1));
                assertSame(privateA, flight.getInstance(privateA).getPlayer());
                assertSame(privateB, flight.getInstance(privateA).getSource());
                assertNull(flight.getInstance(other));
                assertSame(stats.getStatisticsMap(A), imported.services().get(2));
                assertNotSame(sourceStats.getStatisticsMap(A), stats.getStatisticsMap(A));
                assertEquals(Map.of("hits_dynamic", 1), stats.getKeysByName());
                assertThrows(IllegalStateException.class, imported::install);
                assertEquals(1, scheduler.pendingTasks());
                return null;
            });
            var engine = domain.call(() -> new RollbackEngine<>(new RollbackSimulation<RollbackDomain.Checkpoint, Boolean, String>() {
                @Override public RollbackDomain.Checkpoint snapshot() { return domain.capture(); }
                @Override public void restore(RollbackDomain.Checkpoint state) { domain.restore(state); }
                @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
                @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> effects) {
                    var flight = Manager.getManager(FlightHandler.class);
                    if (inputs.get(A)) flight.createInstance(privateA, "new-grant");
                    scheduler.advance(tick);
                    Manager.getManager(StatisticsManager.class).addStatistic(A, 1, inputs.get(A) ? 2 : 1);
                    effects.emit("flying:" + privateA.isFlying());
                }
            }, Map.of(A, false, B, false), new RollbackEngine.Limits(3, 1, 20, 50_000_000), EPOCH));

            domain.call(engine::advance);
            assertTrue(privateA.isFlying());
            domain.call(engine::advance);
            assertFalse(privateA.isFlying());
            assertFalse(privateA.getAllowFlight());
            domain.call(() -> {
                assertNull(Manager.getManager(FlightHandler.class).getInstance(privateA));
                assertEquals(12, Manager.getManager(StatisticsManager.class).getStatisticCurrent(A, 1));
                return null;
            });
            assertEquals(RollbackEngine.Submission.ACCEPTED, domain.call(() -> engine.submit(A, 1, true)));
            domain.call(engine::reconcile);
            assertTrue(privateA.isFlying());
            assertTrue(privateA.getAllowFlight());
            domain.call(() -> {
                assertTrue(Manager.getManager(FlightHandler.class).hasOtherInstance(privateA, "timed"));
                assertEquals(14, Manager.getManager(StatisticsManager.class).getStatisticCurrent(A, 1));
                assertEquals(4, Manager.getManager(StatisticsManager.class).getStatisticDelta(A, 1));
                return null;
            });
            assertEquals(1, scheduler.pendingTasks());
            domain.call(() -> {
                assertTrue(scheduler.exportTasks().bindings().entries().isEmpty(), "Private flight maintenance must not duplicate the live timer");
                assertEquals(1, scheduler.pendingTasks(), "Export must leave private cleanup running until ownership ends");
                return null;
            });
            assertSame(sourceFlight, Manager.getManager(FlightHandler.class));
            assertSame(sourceStats, Manager.getManager(StatisticsManager.class));
            assertNotNull(sourceFlight.getInstance(liveA));
            assertNotNull(sourceFlight.getInstance(other));
            assertFalse(sourceFlight.hasOtherInstance(liveA, "timed"));
            assertTrue(liveA.isFlying());
            assertTrue(liveA.getAllowFlight());
            assertEquals(10, sourceStats.getStatisticCurrent(A, 1));
            assertEquals(0, sourceStats.getStatisticDelta(A, 1));
            assertEquals(99, sourceStats.getStatisticCurrent(OTHER, 1));

            // Restore current replay state together with aliases held by the gameplay graph.
            var outgoing = domain.call(Manager::exportRollbackRegistry);
            var aliases = domain.call(() -> List.of(Manager.getManager(FlightHandler.class),
                    Manager.getManager(FlightHandler.class).getInstance(privateA),
                    Manager.getManager(StatisticsManager.class).getStatisticsMap(A)));
            var liveScheduler = new RollbackScheduler(10, 10);
            try (var live = Platform.using(platform(liveScheduler))) {
                var bindings = new IdentityHashMap<Object, Object>();
                bindings.put(privateA, liveA); bindings.put(privateB, liveB);
                var plan = outgoing.restorationSources(Set.of(A, B), bindings::put);
                var copied = transfer(bindings).copy(List.of(plan.roots(), aliases));
                var commit = plan.prepare((List<?>) copied.get(0));
                var restoredAliases = (List<?>) copied.get(1);
                var originalFlight = sourceFlight.getInstance(liveA);
                var outsideFlight = sourceFlight.getInstance(other);
                var outsideStatistics = sourceStats.getStatisticsMap(OTHER);
                assertSame(originalFlight, sourceFlight.getInstance(liveA));
                assertEquals(10, sourceStats.getStatisticCurrent(A, 1));
                // All definitions are checked before the first manager writes anything.
                sourceStats.getKeysByName().put("changed", 2);
                assertThrows(IllegalStateException.class, commit::commit);
                assertSame(originalFlight, sourceFlight.getInstance(liveA));
                assertEquals(10, sourceStats.getStatisticCurrent(A, 1));
                sourceStats.getKeysByName().remove("changed");
                managers().put(FlightHandler.class, construct(FlightHandler.class));
                assertThrows(IllegalStateException.class, commit::commit);
                managers().put(FlightHandler.class, sourceFlight);
                commit.commit();
                assertSame(sourceFlight, restoredAliases.get(0));
                assertSame(restoredAliases.get(1), sourceFlight.getInstance(liveA));
                assertSame(liveA, sourceFlight.getInstance(liveA).getPlayer());
                assertSame(liveB, sourceFlight.getInstance(liveA).getSource());
                assertTrue(sourceFlight.hasOtherInstance(liveA, "timed"));
                assertSame(restoredAliases.get(2), sourceStats.getStatisticsMap(A));
                assertEquals(14, sourceStats.getStatisticCurrent(A, 1));
                assertEquals(4, sourceStats.getStatisticDelta(A, 1));
                assertSame(outsideFlight, sourceFlight.getInstance(other));
                assertSame(outsideStatistics, sourceStats.getStatisticsMap(OTHER));
                assertEquals(99, sourceStats.getStatisticCurrent(OTHER, 1));
                assertEquals(0, liveScheduler.pendingTasks(), "Restoration must not start duplicate service timers");
                sourceStats.addStatistic(A, 1, 1);
                commit.commit();
                assertEquals(15, sourceStats.getStatisticCurrent(A, 1), "Retry cannot overwrite continued live progress");
            }
        } finally { outside.restore(); }
    }

    @Test
    void privateStatisticsRejectPersistenceAndUncapturedLookupsBeforeAccessingStorage() throws Exception {
        var graph = new RollbackStateGraph(value -> value instanceof World, ignored -> true, 10_000);
        var shared = RollbackBendingState.sharedFields();
        var outside = graph.capture(List.of(), shared);
        try {
            var source = new LivePlayer(A);
            var stats = construct(StatisticsManager.class);
            seedStatistics(stats, A, 10);
            seedStatistics(stats, OTHER, 99);
            managers().clear();
            managers().put(StatisticsManager.class, stats);
            var replacements = new IdentityHashMap<Object, Object>();
            replacements.put(source, PrivateCombatRollbackTest.player(new World(), 1));
            var imported = RollbackBendingState.capture(List.of(new BendingPlayer(source)), new CollisionManager(), List.of(), transfer(replacements));
            var domain = RollbackDomain.create(graph, shared, List.of(imported), platform(new RollbackScheduler(10, 10)), imported::install);
            domain.call(() -> {
                var local = Manager.getManager(StatisticsManager.class);
                assertThrows(IllegalStateException.class, local::onActivate);
                assertThrows(IllegalStateException.class, local::setupStatistics);
                assertThrows(IllegalStateException.class, () -> local.load(A));
                assertThrows(IllegalStateException.class, () -> local.save(A, false));
                assertThrows(IllegalStateException.class, () -> local.save(A, true));
                assertThrows(IllegalStateException.class, local::run);
                assertThrows(IllegalStateException.class, () -> local.getStatisticCurrent(OTHER, 1));
                assertThrows(IllegalStateException.class, () -> local.getStatisticsMap(OTHER));
                assertThrows(IllegalStateException.class, () -> local.addStatistic(OTHER, 1, 2));
                assertEquals(10, local.getStatisticCurrent(A, 1));
                return null;
            });
        } finally { outside.restore(); }
    }

    @Test
    void unknownManagerAndForeignFlightSourceRejectImportWithoutChangingLiveRegistry() throws Exception {
        var saved = new HashMap<>(managers());
        try {
            managers().clear();
            var unsupported = new UnsupportedManager();
            managers().put(UnsupportedManager.class, unsupported);
            var source = new LivePlayer(A);
            var replacements = new IdentityHashMap<Object, Object>();
            replacements.put(source, PrivateCombatRollbackTest.player(new World(), 1));
            assertThrows(UnsupportedOperationException.class, () -> RollbackBendingState.capture(List.of(new BendingPlayer(source)),
                    new CollisionManager(), List.of(), transfer(replacements)));
            assertSame(unsupported, Manager.getManager(UnsupportedManager.class));
            managers().clear();
            var flight = construct(FlightHandler.class);
            managers().put(FlightHandler.class, flight);
            flight.createInstance(source, new LivePlayer(OTHER), "foreign-source");
            assertThrows(IllegalArgumentException.class, () -> RollbackBendingState.capture(List.of(new BendingPlayer(source)),
                    new CollisionManager(), List.of(), transfer(replacements)));
            assertSame(flight, Manager.getManager(FlightHandler.class));
            assertNotNull(flight.getInstance(source));
        } finally { managers().clear(); managers().putAll(saved); }
    }

    private static RollbackStateTransfer transfer(IdentityHashMap<Object, Object> replacements) {
        return new RollbackStateTransfer(type -> type.getName().startsWith("com.projectkorra."), value -> {
            if (replacements.containsKey(value)) return new RollbackStateTransfer.Replacement(replacements.get(value));
            if (value instanceof Player || value instanceof World) throw new IllegalArgumentException("Foreign live handle");
            return null;
        }, new RollbackStateTransfer.Limits(10_000, 100_000));
    }
    @SuppressWarnings("unchecked")
    private static void seedStatistics(StatisticsManager stats, UUID id, long value) throws Exception {
        ((Map<UUID, Map<Integer, Long>>) field(StatisticsManager.class, "STATISTICS").get(stats)).put(id, new HashMap<>(Map.of(1, value)));
        ((Map<UUID, Map<Integer, Long>>) field(StatisticsManager.class, "DELTA").get(stats)).put(id, new HashMap<>(Map.of(1, 0L)));
    }
    @SuppressWarnings("unchecked")
    private static Map<Class<? extends Manager>, Manager> managers() throws Exception {
        return (Map<Class<? extends Manager>, Manager>) field(Manager.class, "MANAGERS").get(null);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static <T> T construct(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
    private static ProjectKorraPlatform platform(RollbackScheduler scheduler) {
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("scheduler")) return scheduler;
                    throw new AssertionError("Import called live activation/service: " + method);
                });
    }
    private static final class UnsupportedManager extends Manager { }
    private static final class LivePlayer extends Player {
        private final UUID id;
        private boolean flying, allowed;
        LivePlayer(UUID id) { this.id = id; }
        @Override public UUID getUniqueId() { return id; }
        @Override public boolean isFlying() { return flying; }
        @Override public boolean getAllowFlight() { return allowed; }
        @Override public void setFlying(boolean value) { flying = value; }
        @Override public void setAllowFlight(boolean value) { allowed = value; }
    }
}
