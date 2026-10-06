package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.StatisticsManager;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackBendingStateTest {
    @org.junit.jupiter.api.BeforeAll static void initializeLiveMaterialCaches() throws Exception {
        try (var world = new com.projectkorra.projectkorra.support.AbilityWorld()) {
            com.projectkorra.projectkorra.ability.ElementalAbility.getTransparentMaterials();
        }
    }

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), OUTSIDE = new UUID(0, 3);

    @ParameterizedTest @ValueSource(booleans = {false, true}) @SuppressWarnings("unchecked")
    void importedActiveRegistryReplaysDynamicCollisionsAndLeavesLivePlayersAbilitiesAndExpiryQueueUntouched(boolean portable) throws Exception {
        var graph = new RollbackStateGraph(value -> value instanceof World || value instanceof Element, ignored -> true, 30_000);
        var shared = RollbackBendingState.sharedFields();
        var outside = graph.capture(List.of(), shared);
        var attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        var sourceAttribute = new AttributeCache(field(Pulse.class, "speed"), "Speed");
        var previousAttributes = new HashMap<>(attributes);
        attributes.clear(); // This fixture negotiates its own complete shared definition catalog.
        attributes.put(Pulse.class, new HashMap<>(Map.of("Speed", sourceAttribute)));
        attributes.put(Guard.class, Map.of());
        var events = new ArrayList<String>();
        var platform = platform(events);
        RollbackLiveOwnership.Lease liveOwnership;
        try (var scope = Platform.using(platform)) { liveOwnership = RollbackLiveOwnership.prepare(Set.of(A, B)); }
        try (var scope = Platform.using(platform)) {
            liveOwnership.acquire();
            World liveWorld = new World(), privateWorld = new World();
            Player liveA = player(A), liveB = player(B), other = player(OUTSIDE);
            var bendingA = new BendingPlayer(liveA);
            var bendingB = new BendingPlayer(liveB);
            var bendingOther = new BendingPlayer(other);
            var statisticsConstructor = StatisticsManager.class.getDeclaredConstructor();
            statisticsConstructor.setAccessible(true);
            var liveStatistics = statisticsConstructor.newInstance();
            var managers = (Map<Class<? extends Manager>, Manager>) field(Manager.class, "MANAGERS").get(null);
            managers.clear(); managers.put(StatisticsManager.class, liveStatistics);
            var statisticValues = (Map<UUID, Map<Integer, Long>>) field(StatisticsManager.class, "STATISTICS").get(liveStatistics);
            var statisticDelta = (Map<UUID, Map<Integer, Long>>) field(StatisticsManager.class, "DELTA").get(liveStatistics);
            statisticValues.put(A, new HashMap<>(Map.of(1, 10L)));
            statisticValues.put(OUTSIDE, new HashMap<>(Map.of(1, 99L)));
            statisticDelta.put(A, new HashMap<>(Map.of(1, 0L)));
            liveStatistics.getKeysByName().put("hits", 1); liveStatistics.getKeysById().put(1, "hits");
            var unrelatedStatistics = statisticValues.get(OUTSIDE);
            BendingPlayer.getPlayers().putAll(Map.of(A, bendingA, B, bendingB, OUTSIDE, bendingOther));
            BendingPlayer.getOfflinePlayers().putAll(BendingPlayer.getPlayers());
            bendingA.getAbilities().put(1, "Pulse");
            bendingA.getCooldowns().put("prior", new Cooldown(System.currentTimeMillis() + 1000, false));
            field(CoreAbility.class, "currentTick").setLong(null, 40);
            field(CoreAbility.class, "idCounter").setInt(null, 4);
            Pulse livePulse = new Pulse(bendingA, liveWorld, 1);
            var earthField = field(com.projectkorra.projectkorra.ability.ElementalAbility.class, "EARTH_BLOCKS");
            livePulse.materialAlias = (Set<String>) earthField.get(null);
            livePulse.materialAlias.add("SOURCE_MATERIAL");
            var comboField = field(com.projectkorra.projectkorra.ability.util.ComboManager.class, "RECENTLY_USED");
            var liveHistory = (Map<String, ArrayList<com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation>>) comboField.get(null);
            livePulse.comboAlias = new ArrayList<>();
            liveHistory.put(liveA.getName(), livePulse.comboAlias);
            var outsiderHistory = new ArrayList<com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation>();
            liveHistory.put(other.getName(), outsiderHistory);
            var pending = (Map<UUID, Set<com.projectkorra.projectkorra.util.ClickType>>) field(
                    com.projectkorra.projectkorra.ability.util.ComboManager.class, "SCHEDULED_COMBO_ABILITY").get(null);
            livePulse.pendingAlias = new HashSet<>(); pending.put(A, livePulse.pendingAlias);
            livePulse.comboDefinition = new com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo(
                    "FixtureCombo", new ArrayList<>(), Pulse.class);
            com.projectkorra.projectkorra.ability.util.ComboManager.getComboAbilities().put("FixtureCombo", livePulse.comboDefinition);
            Guard liveGuard = new Guard(bendingB, liveWorld, 2);
            Pulse unrelatedPulse = new Pulse(bendingOther, liveWorld, 3);
            livePulse.start();
            liveGuard.start();
            unrelatedPulse.start();
            livePulse.linkedCache = sourceAttribute;
            livePulse.linkedValues = sourceAttribute.getInitialValues();
            unrelatedPulse.linkedCache = sourceAttribute;
            unrelatedPulse.linkedValues = sourceAttribute.getInitialValues();
            var collisions = new CollisionManager();
            collisions.addCollision(new Collision(livePulse, liveGuard, true, false));
            var liveTask = new BukkitRunnable() { @Override public void run() { throw new AssertionError("Live task ran"); } };
            collisions.setDetectionRunnable(liveTask);
            ProjectKorra.collisionManager = collisions;
            var temporary = (PriorityQueue<Pair<Player, Long>>) field(OfflineBendingPlayer.class, "TEMP_ELEMENTS").get(null);
            temporary.add(Pair.of(liveA, 1234L));
            temporary.add(Pair.of(other, 500L));
            var sourceService = new HashMap<CoreAbility, String>();
            sourceService.put(livePulse, "shared service entry");
            var liveRules = new CapturedRule(livePulse);
            var liveBus = new RollbackEventBus(100, 10);
            liveBus.registerListener(liveRules);
            var liveCallback = new CapturedTask(livePulse);
            liveCallback.handle = new com.projectkorra.projectkorra.platform.PKTask() {
                @Override public void cancel() { fail("Imported callback cancelled a live task"); }
                @Override public boolean cancelled() { return false; }
                @Override public int legacyId() { return 44; }
            };
            var taskCapture = RollbackTaskBindings.capture(List.of(new RollbackTaskBindings.Pending(
                    liveCallback.handle, (com.projectkorra.projectkorra.platform.PKRunnable) liveCallback::run, 1, 2, livePulse, 71, 93)));
            var abilityReservation = CoreAbility.reserveRollbackIds(List.of(A, B), 10);
            var expectedLiveAbilities = CoreAbility.captureRollbackRegistry(List.of(A, B), abilityReservation);
            var serviceRoots = List.of(sourceService, RollbackEventBindings.capture(liveBus), taskCapture, liveCallback, abilityReservation, liveStatistics, liveStatistics.getStatisticsMap(A));
            var privateA = PrivateCombatRollbackTest.player(privateWorld, 1);
            var privateB = PrivateCombatRollbackTest.player(privateWorld, 2);
            Map<Object, Object> replacements = new IdentityHashMap<>();
            replacements.put(liveA, privateA);
            replacements.put(liveB, privateB);
            replacements.put(liveWorld, privateWorld);
            var transfer = new RollbackStateTransfer(type -> type.getName().startsWith("com.projectkorra."), value -> {
                if (replacements.containsKey(value)) return new RollbackStateTransfer.Replacement(replacements.get(value));
                if (value instanceof Element) return new RollbackStateTransfer.Replacement(value);
                if (value instanceof Player || value instanceof World) throw new IllegalArgumentException("Foreign live handle");
                return null;
            }, new RollbackStateTransfer.Limits(20_000, 100_000));
            int eventsBefore = events.size(), constructorsBefore = Dynamic.constructions;
            RollbackBendingState imported;
            if (portable) {
                Portable codecs = portableTransfer(liveA, liveB, liveWorld, privateA, privateB, privateWorld, sourceAttribute);
                byte[] bytes = RollbackBendingState.encode(List.of(bendingB, bendingA), collisions, serviceRoots, codecs.sender());
                imported = RollbackBendingState.decode(List.of(B, A), bytes, codecs.receiver());
                assertThrows(IllegalStateException.class, () -> RollbackBendingState.decode(List.of(A, OUTSIDE), bytes, codecs.receiver()));
                assertThrows(IllegalArgumentException.class, () -> RollbackBendingState.decode(List.of(A, A), bytes, codecs.receiver()));
                assertThrows(IllegalArgumentException.class, () -> RollbackBendingState.decode(List.of(A, B), codecs.sender().encode(List.of("wrong root")), codecs.receiver()));
            } else imported = RollbackBendingState.capture(List.of(bendingB, bendingA), collisions, serviceRoots, transfer);
            assertEquals(eventsBefore, events.size());
            assertEquals(constructorsBefore, Dynamic.constructions);
            assertEquals(List.of(A, B), new ArrayList<>(imported.players().keySet()));
            assertEquals(2, imported.abilities().size());
            Pulse pulse = (Pulse) imported.abilities().stream().filter(Pulse.class::isInstance).findFirst().orElseThrow();
            Guard guard = (Guard) imported.abilities().stream().filter(Guard.class::isInstance).findFirst().orElseThrow();
            assertNotSame(livePulse, pulse);
            assertNotSame(livePulse.materialAlias, pulse.materialAlias);
            assertNotSame(livePulse.comboAlias, pulse.comboAlias);
            assertTrue(pulse.materialAlias.contains("SOURCE_MATERIAL"));
            assertTrue(pulse.isStarted());
            assertEquals(livePulse.getStartTime(), pulse.getStartTime());
            assertEquals(40, pulse.getStartTick());
            assertEquals(livePulse.getId(), pulse.getId());
            assertEquals("shared service entry", ((Map<?, ?>) imported.services().getFirst()).get(pulse));
            assertNull(imported.collisions().getDetectionRunnable());
            assertSame(liveTask, collisions.getDetectionRunnable());
            assertThrows(IllegalStateException.class, imported::install);

            var removals = new ArrayList<String>();
            var prediction = PredictionServices.builder().bind(AbilityRemovalSync.Listener.class,
                    (ability, external) -> removals.add(ability.getId() + ":" + external)).build();
            var privateBus = new RollbackEventBus(100, 10);
            var privateScheduler = new RollbackScheduler(100, 100);
            var privatePlatform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                    new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("events")) return privateBus;
                        if (method.getName().equals("scheduler")) return privateScheduler;
                        throw new AssertionError(method);
                    });
            var domain = RollbackDomain.create(graph, shared, List.of(imported, privateBus, privateScheduler), privatePlatform, null, prediction, imported::install);
            var copiedTask = (CapturedTask) imported.services().get(3);
            assertInstanceOf(RollbackCallback.class, ((RollbackTaskBindings) imported.services().get(2)).entries().getFirst().callback());
            assertSame(pulse, copiedTask.ability); assertEquals(1, privateScheduler.pendingTasks());
            var copiedRules = (CapturedRule) privateBus.commonRegistrations().getFirst().listener();
            assertNotSame(liveRules, copiedRules);
            assertSame(pulse, copiedRules.ability);
            assertEquals(eventsBefore, events.size()); // Registry installation does not replay activation hooks.
            domain.call(() -> {
                assertEquals(pulse.materialAlias, new HashSet<>(com.projectkorra.projectkorra.ability.ElementalAbility.getEarthbendableBlocks()));
                assertSame(pulse, CoreAbility.getAbility(privateA, Pulse.class));
                assertSame(imported.players().get(A), BendingPlayer.getBendingPlayer(privateA));
                assertFalse(BendingPlayer.getPlayers().containsKey(OUTSIDE));
                assertEquals(40, CoreAbility.getCurrentTick());
                assertEquals(1, temporary.size());
                assertSame(privateA, temporary.peek().getLeft());
                assertEquals(1234, temporary.peek().getRight());
                AttributeCache privateAttribute = CoreAbility.getAttributeCache(pulse).get("Speed");
                assertNotSame(sourceAttribute, privateAttribute);
                assertSame(privateAttribute, pulse.linkedCache);
                assertSame(privateAttribute.getInitialValues(), pulse.linkedValues);
                assertSame(sourceAttribute.getField(), privateAttribute.getField());
                assertEquals(Set.of(pulse), privateAttribute.getInitialValues().keySet());
                assertFalse(privateAttribute.getInitialValues().containsKey(unrelatedPulse));
                assertNotSame(sourceAttribute.getCurrentModifications().get(livePulse), privateAttribute.getCurrentModifications().get(pulse));
                pulse.speed = 99;
                pulse.recalculateAttributes();
                assertEquals(1, pulse.speed);
                return null;
            });
            var engine = domain.call(() -> new RollbackEngine<>(new RollbackSimulation<RollbackDomain.Checkpoint, Double, String>() {
                @Override public RollbackDomain.Checkpoint snapshot() { return domain.capture(); }
                @Override public void restore(RollbackDomain.Checkpoint checkpoint) { domain.restore(checkpoint); }
                @Override public Double predict(UUID participant, Double previous) { return previous; }
                @Override public void step(long tick, Map<UUID, Double> inputs, RollbackStep<String> effects) {
                    pulse.materialAlias.add(inputs.get(B) == 0 ? "PREDICTED_MATERIAL" : "CORRECTED_MATERIAL");
                    assertTrue(com.projectkorra.projectkorra.ability.ElementalAbility.getEarthbendableBlocks()
                            .contains(inputs.get(B) == 0 ? "PREDICTED_MATERIAL" : "CORRECTED_MATERIAL"));
                    com.projectkorra.projectkorra.ability.util.ComboManager.addRecentAbility(privateA,
                            new com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation(
                                    inputs.get(B) == 0 ? "Predicted" : "Corrected", com.projectkorra.projectkorra.util.ClickType.LEFT_CLICK,
                                    RollbackClock.millis()));
                    com.projectkorra.projectkorra.ability.util.ComboManager.scheduleComboAbility(privateA,
                            inputs.get(B) == 0 ? com.projectkorra.projectkorra.util.ClickType.RIGHT_CLICK : com.projectkorra.projectkorra.util.ClickType.LEFT_CLICK);
                    privateScheduler.advance(tick);
                    Manager.getManager(StatisticsManager.class).addStatistic(A, 1, inputs.get(B).longValue() + 1);
                    guard.location.setY(inputs.get(B));
                    CoreAbility.progressAll();
                    ProjectKorra.collisionManager.detectCollisions();
                    if (pulse.isRemoved()) effects.emit("collision:" + pulse.getId());
                }
            }, Map.of(A, 0.0, B, 0.0), new RollbackEngine.Limits(3, 1, 20, 50_000_000), System.currentTimeMillis()));
            domain.call(engine::advance);
            assertFalse(domain.call(engine::advance).head().effects().isEmpty());
            assertTrue(pulse.isRemoved());
            assertEquals(1, pulse.contacts);
            assertEquals(1, copiedRules.collisions);
            assertEquals(0, liveRules.collisions);
            assertEquals(List.of("1:true"), removals);
            assertFalse(livePulse.isRemoved());
            assertEquals(0, livePulse.location.getX());
            assertEquals(RollbackEngine.Submission.ACCEPTED, domain.call(() -> engine.submit(B, 1, 8.0)));
            assertTrue(domain.call(engine::reconcile).head().effects().isEmpty());
            assertEquals(List.of("Corrected", "Corrected"), pulse.comboAlias.stream().map(
                    com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation::getAbilityName).toList());
            assertTrue(livePulse.comboAlias.isEmpty());
            assertTrue(livePulse.pendingAlias.isEmpty());
            assertEquals(Set.of(com.projectkorra.projectkorra.util.ClickType.LEFT_CLICK), pulse.pendingAlias);
            assertSame(outsiderHistory, liveHistory.get(other.getName()));
            assertFalse(pulse.materialAlias.contains("PREDICTED_MATERIAL"));
            assertTrue(pulse.materialAlias.contains("CORRECTED_MATERIAL"));
            assertSame(livePulse.materialAlias, earthField.get(null));
            assertFalse(livePulse.materialAlias.contains("PREDICTED_MATERIAL"));
            assertFalse(livePulse.materialAlias.contains("CORRECTED_MATERIAL"));
            assertFalse(pulse.isRemoved());
            assertEquals(0, pulse.contacts);
            assertEquals(0, copiedRules.collisions);
            assertTrue(removals.isEmpty());
            assertEquals(List.of(1.0, 3.0), pulse.history);
            assertEquals(1, copiedTask.calls); assertTrue(copiedTask.handle.cancelled());
            assertEquals(0, liveCallback.calls); assertFalse(liveCallback.handle.cancelled());
            domain.call(() -> { assertSame(pulse, CoreAbility.getAbility(privateA, Pulse.class)); return null; });

            assertSame(livePulse, CoreAbility.getAbility(liveA, Pulse.class));
            assertSame(unrelatedPulse, CoreAbility.getAbility(other, Pulse.class));
            assertEquals(0, unrelatedPulse.location.getX());
            assertSame(bendingA, BendingPlayer.getBendingPlayer(liveA));
            assertSame(bendingOther, BendingPlayer.getBendingPlayer(other));
            assertSame(collisions, ProjectKorra.collisionManager);
            assertEquals(40, CoreAbility.getCurrentTick());
            assertEquals(2, temporary.size());
            assertSame(other, temporary.peek().getLeft());
            assertEquals("Pulse", bendingA.getAbilities().get(1));
            assertTrue(sourceService.containsKey(livePulse));
            assertFalse(sourceService.containsKey(pulse));
            assertEquals(Set.of(livePulse, unrelatedPulse), sourceAttribute.getInitialValues().keySet());
            assertEquals(1.0, sourceAttribute.getInitialValues().get(livePulse));
            domain.call(() -> {
                assertEquals(1.0, CoreAbility.getAttributeCache(pulse).get("Speed").getInitialValues().get(pulse));
                return null;
            });
            assertThrows(IllegalStateException.class, () -> imported.exportState(null));
            Portable[] outgoingCodec = new Portable[1], liveRestorationCodec = new Portable[1];
            int constructorsAtExport = Dynamic.constructions + 1;
            byte[] outgoingBytes = domain.call(() -> {
                try {
                    guard.remove();
                    Pulse createdDuringReplay = new Pulse(imported.players().get(A), privateWorld, 9);
                    createdDuringReplay.start();
                    field(CoreAbility.class, "idCounter").setInt(null, 10); // Fixture constructed explicit ID 9.
                    privateBus.unregisterAll(copiedRules);
                    privateBus.registerListener(new CapturedRule(createdDuringReplay));
                    temporary.clear(); temporary.add(Pair.of(privateB, 4567L));
                    imported.players().get(A).getAbilities().put(2, "WaterManipulation");
                    AttributeCache currentAttribute = CoreAbility.getAttributeCache(pulse).get("Speed");
                    Portable outgoing = portableTransfer(privateA, privateB, privateWorld, privateA, privateB, privateWorld, currentAttribute);
                    outgoingCodec[0] = outgoing;
                    liveRestorationCodec[0] = portableTransfer(privateA, privateB, privateWorld, liveA, liveB, liveWorld, currentAttribute);
                    return imported.exportState(outgoing.sender());
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            var exported = RollbackBendingState.decode(List.of(A, B), outgoingBytes, outgoingCodec[0].receiver());
            assertEquals(constructorsAtExport, Dynamic.constructions);
            assertEquals(Set.of(1, 9), exported.abilities().stream().map(CoreAbility::getId).collect(java.util.stream.Collectors.toSet()));
            Pulse exportedPulse = (Pulse) exported.abilities().stream().filter(ability -> ability.getId() == 1).findFirst().orElseThrow();
            assertNotSame(pulse, exportedPulse); assertEquals(List.of(1.0, 3.0), exportedPulse.history);
            assertEquals("WaterManipulation", exported.players().get(A).getAbilities().get(2));
            var exportedTasks = exported.services().stream().filter(RollbackTaskBindings.class::isInstance)
                    .map(RollbackTaskBindings.class::cast).findFirst().orElseThrow();
            assertTrue(exportedTasks.entries().isEmpty(), "Completed startup work must not be resurrected");
            var exportedRules = exported.services().stream().filter(RollbackEventBindings.class::isInstance)
                    .map(RollbackEventBindings.class::cast).findFirst().orElseThrow();
            assertEquals(1, exportedRules.registrations().size());
            var rule = (CapturedRule) exportedRules.registrations().getFirst().listener();
            assertEquals(9, rule.ability.getId());
            assertSame(exported.abilities().stream().filter(ability -> ability.getId() == 9).findFirst().orElseThrow(), rule.ability);

            var exportedCallback = (CapturedTask) exported.services().get(3);
            assertEquals(1, exportedCallback.calls); assertTrue(exportedCallback.handle.cancelled());
            assertSame(exportedPulse, exportedCallback.ability);
            var expiry = (List<?>) field(RollbackBendingState.class, "temporaryElements").get(exported);
            assertEquals(1, expiry.size());
            assertSame(privateB, field(OfflineBendingPlayer.RollbackTemporaryElement.class, "player").get(expiry.getFirst()));
            assertEquals(4567L, field(OfflineBendingPlayer.RollbackTemporaryElement.class, "expiry").getLong(expiry.getFirst()));
            assertSame(liveGuard, CoreAbility.getAbility(liveB, Guard.class));
            assertEquals(2, temporary.size());
            assertNull(bendingA.getAbilities().get(2));
            var restored = RollbackBendingState.decodeRestoration(Map.of(A, liveA, B, liveB), outgoingBytes, liveRestorationCodec[0].receiver());
            assertEquals(14, restored.abilities().idLimit());
            assertEquals(10, restored.abilities().nextId());
            assertSame(liveA, restored.players().get(A).getPlayer());
            assertNotSame(bendingA, restored.players().get(A));
            assertEquals("WaterManipulation", restored.players().get(A).getAbilities().get(2));
            Pulse restoredPulse = (Pulse) restored.abilities().instances().stream().filter(ability -> ability.getId() == 1).findFirst().orElseThrow();
            assertSame(livePulse.materialAlias, restoredPulse.materialAlias);
            assertSame(livePulse.comboDefinition, restoredPulse.comboDefinition);
            assertFalse(restoredPulse.materialAlias.contains("CORRECTED_MATERIAL"));
            assertSame(restored.players().get(A), restoredPulse.getBendingPlayer());
            assertSame(liveA, restoredPulse.getPlayer());
            assertSame(sourceAttribute, restoredPulse.linkedCache);
            assertSame(sourceAttribute.getInitialValues(), restoredPulse.linkedValues);
            assertSame(unrelatedPulse.linkedValues, restoredPulse.linkedValues);
            assertFalse(sourceAttribute.getInitialValues().containsKey(restoredPulse));
            assertSame(restoredPulse, ((CapturedTask) restored.services().get(3)).ability);
            assertSame(bendingA, BendingPlayer.getBendingPlayer(liveA), "Decoding must not install live registries");
            assertSame(livePulse, CoreAbility.getAbility(liveA, Pulse.class));
            assertEquals(constructorsAtExport, Dynamic.constructions);
            assertThrows(IllegalStateException.class, () -> RollbackBendingState.decodeRestoration(
                    Map.of(A, liveA, B, liveB), outgoingBytes, outgoingCodec[0].receiver()));
            assertThrows(IllegalArgumentException.class, () -> RollbackBendingState.decodeRestoration(
                    Map.of(A, privateA, B, privateB), outgoingBytes, outgoingCodec[0].receiver()));
            assertThrows(IllegalStateException.class, () -> RollbackBendingState.decode(
                    List.of(A, B), outgoingBytes, liveRestorationCodec[0].receiver()));
            var liveBackend = new RollbackLiveSchedulerTest.Backend();
            var restorePlatform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                    new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("scheduler")) return liveBackend;
                        if (method.getName().equals("events")) return platform.events();
                        throw new AssertionError(method);
                    });
            try (var restorationScope = Platform.using(restorePlatform)) {
                assertThrows(IllegalStateException.class, () -> restored.prepareAbilities(abilityReservation, expectedLiveAbilities));
                field(CoreAbility.class, "currentTick").setLong(null, restored.abilities().tick()); // Owner aligns to the live tick before handoff.
                var commit = restored.prepareCommit(Map.of(A, bendingA, B, bendingB), abilityReservation, expectedLiveAbilities);
                liveHistory.put(liveA.getName(), new ArrayList<>(livePulse.comboAlias));
                assertThrows(IllegalStateException.class, commit::commit);
                liveHistory.put(liveA.getName(), livePulse.comboAlias);
                var unexpectedInput = new com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation(
                        "Unexpected", com.projectkorra.projectkorra.util.ClickType.RIGHT_CLICK, 100);
                livePulse.comboAlias.add(unexpectedInput);
                assertThrows(IllegalStateException.class, commit::commit);
                livePulse.comboAlias.remove(unexpectedInput);
                liveStatistics.getKeysByName().put("changed", 2);
                assertThrows(IllegalStateException.class, commit::commit);
                assertSame(bendingA, BendingPlayer.getBendingPlayer(liveA));
                assertSame(livePulse, CoreAbility.getAbility(liveA, Pulse.class));
                assertEquals(Set.of(livePulse, unrelatedPulse), sourceAttribute.getInitialValues().keySet());
                assertEquals(10, liveStatistics.getStatisticCurrent(A, 1));
                liveStatistics.getKeysByName().remove("changed");
                var instances = (Collection<CoreAbility>) field(CoreAbility.class, "INSTANCES").get(null);
                instances.remove(liveGuard);
                assertThrows(IllegalStateException.class, commit::commit);
                assertSame(livePulse, CoreAbility.getAbility(liveA, Pulse.class));
                instances.add(liveGuard);
                assertSame(bendingA, BendingPlayer.getBendingPlayer(liveA));
                var liveAttributeDefinitions = (Map<String, AttributeCache>) attributes.get(Pulse.class);
                liveAttributeDefinitions.put("Speed", new AttributeCache(sourceAttribute.getField(), sourceAttribute.getAttribute()));
                assertThrows(IllegalStateException.class, commit::commit);
                assertSame(bendingA, BendingPlayer.getBendingPlayer(liveA), "Failed validation must precede all registry writes");
                assertSame(livePulse, CoreAbility.getAbility(liveA, Pulse.class));
                assertEquals(Set.of(livePulse, unrelatedPulse), sourceAttribute.getInitialValues().keySet());
                liveAttributeDefinitions.put("Speed", sourceAttribute);
                CoreAbility.progressAll();
                collisions.detectCollisions();
                assertTrue(livePulse.history.isEmpty(), "Live enrolled abilities must remain frozen");
                assertEquals(0, livePulse.contacts, "Live collisions must exclude the owned roster");
                assertEquals(1, unrelatedPulse.history.size(), "Unrelated abilities continue normally");
                field(CoreAbility.class, "currentTick").setLong(null, restored.abilities().tick());
                int eventsAtCommit = events.size(), constructorsAtCommit = Dynamic.constructions;
                liveOwnership.restoreAndRelease(commit::commit);
                assertFalse(RollbackLiveOwnership.blocks(A)); assertFalse(RollbackLiveOwnership.blocks(B));
                commit.commit();
                field(CoreAbility.class, "currentTick").setLong(null, restored.abilities().tick() + 1);
                assertSame(restoredPulse.comboAlias, liveHistory.get(liveA.getName()));
                assertSame(restoredPulse.pendingAlias, pending.get(A));
                assertSame(outsiderHistory, liveHistory.get(other.getName()));
                restoredPulse.comboAlias.add(unexpectedInput);
                commit.commit(); // Cleanup retries after another live tick do not reinstall indices.
                assertSame(unexpectedInput, liveHistory.get(liveA.getName()).getLast());
                assertEquals(eventsAtCommit, events.size()); assertEquals(constructorsAtCommit, Dynamic.constructions);
                assertSame(liveStatistics, Manager.getManager(StatisticsManager.class));
                assertTrue(restored.services().stream().anyMatch(service -> service == liveStatistics));
                assertTrue(restored.services().stream().anyMatch(service -> service == liveStatistics.getStatisticsMap(A)));
                assertEquals(28, liveStatistics.getStatisticCurrent(A, 1));
                assertSame(unrelatedStatistics, liveStatistics.getStatisticsMap(OUTSIDE));
                assertEquals(99, liveStatistics.getStatisticCurrent(OUTSIDE, 1));
                assertNull(CoreAbility.getAbility(liveB, Guard.class));
                assertTrue(CoreAbility.getAbilities(liveA, Pulse.class).contains(restoredPulse));
                assertEquals(Set.of(1, 9), CoreAbility.getAbilities(liveA, Pulse.class).stream()
                        .map(CoreAbility::getId).collect(java.util.stream.Collectors.toSet()));
                assertSame(unrelatedPulse, CoreAbility.getAbility(other, Pulse.class));
                assertEquals(14, field(CoreAbility.class, "idCounter").getInt(null));

                assertFalse(sourceAttribute.getInitialValues().containsKey(livePulse));
                assertTrue(sourceAttribute.getInitialValues().containsKey(restoredPulse));
                assertTrue(sourceAttribute.getInitialValues().containsKey(unrelatedPulse));
                assertSame(sourceAttribute.getInitialValues(), unrelatedPulse.linkedValues);
                assertEquals(1.0, restoredPulse.linkedValues.get(restoredPulse));

                assertSame(restored.players().get(A), BendingPlayer.getBendingPlayer(liveA));
                assertSame(bendingOther, BendingPlayer.getBendingPlayer(other));
                assertEquals(2, temporary.size());
                assertTrue(temporary.contains(Pair.of(liveB, 4567L)));
                assertSame(unrelatedPulse, CoreAbility.getAbility(other, Pulse.class));
            }



        } finally {
            try (var scope = Platform.using(platform)) { liveOwnership.restoreAndRelease(() -> { }); }
            outside.restore();
            attributes.clear();
            attributes.putAll(previousAttributes);
        }
    }

    @Test void missingPlatformReplacementCannotInstallLiveBendingState() {
        var source = new BendingPlayer(player(A));
        var transfer = new RollbackStateTransfer(type -> type.getName().startsWith("com.projectkorra."),
                value -> value instanceof Player ? new RollbackStateTransfer.Replacement(value) : null,
                new RollbackStateTransfer.Limits(1000, 10_000));
        assertThrows(IllegalStateException.class, () -> RollbackBendingState.capture(List.of(source), new CollisionManager(), List.of(), transfer));
        assertThrows(IllegalArgumentException.class, () -> RollbackBendingState.capture(List.of(source, source), new CollisionManager(), List.of(), transfer));
    }

    private static Player player(UUID id) { return new Player() { @Override public UUID getUniqueId() { return id; } @Override public String getName() { return "player-" + id.getLeastSignificantBits(); } }; }
    private record Portable(RollbackGraphCodec sender, RollbackGraphCodec receiver) { }
    private static Portable portableTransfer(Player liveA, Player liveB, World liveWorld,
                                                                 Player privateA, Player privateB, World privateWorld,
                                                                 AttributeCache sourceAttribute) throws Exception {
        List<Class<?>> objects = List.of(BendingPlayer.class, Pulse.class, Guard.class, Location.class, Cooldown.class,
                com.projectkorra.projectkorra.ability.util.ComboManager.RollbackRegistry.class, com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation.class, com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo.class,
                CoreAbility.RollbackRegistry.class, com.projectkorra.projectkorra.ability.ElementalAbility.RollbackMaterialRegistry.class, Manager.RollbackRegistry.class, StatisticsManager.class, CollisionManager.class, Collision.class,
                OfflineBendingPlayer.RollbackTemporaryElement.class, AttributeCache.class,
                CapturedRule.class, CapturedTask.class, RollbackCallback.class, RollbackTaskBindings.class, RollbackTaskBindings.Entry.class, RollbackTaskBindings.Handle.class,
                RollbackEventBindings.class, PKEventBus.Registration.class,
                com.projectkorra.projectkorra.util.IndexedMap.class, field(CoreAbility.class, "predictionAncestry").getType(),
                sourceAttribute.getCurrentModifications().values().stream().map(value -> ((TreeSet<?>) value).comparator().getClass()).findFirst().orElseThrow());
        var symbols = new ArrayList<Class<?>>();
        symbols.add(RollbackBendingStateTest.class);
        symbols.add(com.projectkorra.projectkorra.platform.mc.Material.class);
        symbols.add(com.projectkorra.projectkorra.util.ClickType.class);
        for (var field : OfflineBendingPlayer.class.getDeclaredFields()) if (field.getType().isEnum()) symbols.add(field.getType());
        var targetAttribute = new AttributeCache(sourceAttribute.getField(), sourceAttribute.getAttribute());
        var sourceBindings = List.of(new RollbackGraphCodec.Binding("player/A", Player.class, liveA),
                new RollbackGraphCodec.Binding("player/B", Player.class, liveB),
                new RollbackGraphCodec.Binding("world", World.class, liveWorld),
                sourceAttribute.rollbackMetadataBinding(Pulse.class.getName() + "#Speed"));
        assertTrue(CoreAbility.rollbackAttributeBindings().contains(sourceBindings.getLast()));
        var targetBindings = List.of(new RollbackGraphCodec.Binding("player/A", Player.class, privateA),
                new RollbackGraphCodec.Binding("player/B", Player.class, privateB),
                new RollbackGraphCodec.Binding("world", World.class, privateWorld),
                targetAttribute.rollbackMetadataBinding(Pulse.class.getName() + "#Speed"));
        var limits = new RollbackGraphCodec.Limits(20_000, 100_000, 1_048_576, 65_536);
        var installed = new ArrayList<>(RollbackGameplayCatalog.installed(RollbackBendingStateTest.class.getClassLoader()));
        installed.addAll(objects); installed.addAll(symbols); // Locally registered test addon types.
        var sender = new RollbackGraphCodec(RollbackGameplayCatalog.create(installed, sourceBindings), limits);
        var receiver = new RollbackGraphCodec(RollbackGameplayCatalog.create(installed, targetBindings), limits);
        return new Portable(sender, receiver);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static ProjectKorraPlatform platform(List<String> events) {
        var scheduler = new RollbackLiveSchedulerTest.Backend();
        PKEventBus bus = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class},
                (proxy, method, args) -> { events.add(method.getName()); return null; });
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (proxy, method, args) -> { if (method.getName().equals("events")) return bus; if (method.getName().equals("scheduler")) return scheduler; throw new AssertionError(method); });
    }
    private static final class CapturedTask implements Runnable {
        final Pulse ability;
        com.projectkorra.projectkorra.platform.PKTask handle;
        int calls;
        CapturedTask(Pulse ability) { this.ability = ability; }
        @Override public void run() {
            assertSame(ability, com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext.current());
            assertEquals(71, com.projectkorra.projectkorra.prediction.action.PredictionDeterminism.currentAction());
            assertEquals(93, com.projectkorra.projectkorra.prediction.action.PredictionDeterminism.currentSeed());
            calls++; handle.cancel();
        }
    }

    private static final class CapturedRule {
        final Pulse ability;
        int collisions;
        CapturedRule(Pulse ability) { this.ability = ability; }
        @com.projectkorra.projectkorra.platform.mc.event.EventHandler
        public void collided(com.projectkorra.projectkorra.event.AbilityCollisionEvent event) { collisions++; }
    }
    private abstract static class Dynamic extends CoreAbility {
        static int constructions;
        final Location location;
        int contacts;
        Dynamic(BendingPlayer bending, World world, int id, double x) throws Exception {
            super(null);
            constructions++;
            player = bending.getPlayer();
            bPlayer = bending;
            location = new Location(world, x, 0, 0);
            field(CoreAbility.class, "id").setInt(this, id);
        }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isCollidable() { return true; }
        @Override public double getCollisionRadius() { return 0.1; }
        @Override public void handleCollision(Collision collision) { contacts++; super.handleCollision(collision); }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return getClass().getSimpleName(); }
        @Override public Element getElement() { return null; } // This fixture tests import/replay, without native particle services.
        @Override public Location getLocation() { return location; }
    }
    private static final class Pulse extends Dynamic {
        ArrayList<com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation> comboAlias;
        Set<com.projectkorra.projectkorra.util.ClickType> pendingAlias;
        com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo comboDefinition;
        Set<String> materialAlias;
        AttributeCache linkedCache;
        Map<CoreAbility, Object> linkedValues;
        final List<Double> history = new ArrayList<>();
        int age;
        double speed = 1;
        Pulse(BendingPlayer player, World world, int id) throws Exception { super(player, world, id, 0); }
        @Override public void progress() { if (++age == 2) speed = 2; location.add(speed, 0, 0); history.add(location.getX()); }
    }
    private static final class Guard extends Dynamic {
        Guard(BendingPlayer player, World world, int id) throws Exception { super(player, world, id, 3); }
        @Override public void progress() { }
    }
}
