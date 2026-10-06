package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import com.projectkorra.projectkorra.event.AbilityCollisionEvent;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Full bending tick; native world ticking is explicitly a fixture, not a physics substitute. */
class RollbackCombatRuntimeTest {
    @TempDir Path directory;
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static final Input EMPTY = new Input(List.of(), false);
    private static final RollbackInputActions.Action CLICK = new RollbackInputActions.Action(11, 23, RollbackInputActions.Kind.RIGHT_CLICK, -1);
    private static final RollbackEngine.Limits LIMITS = new RollbackEngine.Limits(3, 1, 50, 50_000_000);

    @Test void completeTickAndScheduledActionsReplayThroughExistingInputAndCollisionHandlers() throws Exception {
        withConfig(() -> {
            var onTime = scenario();
            var delayed = scenario();
            var outsideManager = BendingManager.getInstance();
            var outsideCollisions = ProjectKorra.collisionManager;
            long outsideStep = ProjectKorra.time_step;
            assertEquals(RollbackEngine.Submission.ACCEPTED, onTime.runtime.submit(A, 1, new Input(List.of(CLICK), false)));
            onTime.runtime.advance();
            var expected = onTime.runtime.advance();
            delayed.runtime.advance();
            delayed.runtime.advance();
            assertFalse(delayed.fixture.history.stream().anyMatch(entry -> entry.startsWith("action")));

            assertEquals(RollbackEngine.Submission.ACCEPTED, delayed.runtime.submit(A, 1, new Input(List.of(CLICK), false)));
            var replayed = delayed.runtime.reconcile();
            assertEquals(1, replayed.replayedFrom());
            assertEquals(onTime.fixture.history, delayed.fixture.history);
            assertEquals(expected.head().effects(), replayed.head().effects());
            assertEquals(List.of("scheduled:11:23", "progress:2", "collision", "removed", "expired"), replayed.head().effects());
            assertEquals(List.of("begin:1", "input:1", "action:11:23", "input:2", "world:1", "progress:1", "end:50",
                    "begin:2", "scheduled:11:23", "input:1", "input:2", "world:2", "progress:2", "collision", "removed", "expired", "end:50"), delayed.fixture.history);
            assertSame(outsideManager, BendingManager.getInstance());
            assertSame(outsideCollisions, ProjectKorra.collisionManager);
            assertEquals(outsideStep, ProjectKorra.time_step);
            assertTrue(CoreAbility.getAbilitiesByInstances().stream().noneMatch(ability -> ability instanceof Pulse || ability instanceof Boundary));
            assertFalse(RollbackDomain.active());
            assertFalse(RollbackClock.active());
            assertNull(delayed.fixture.effects);

            delayed.runtime.advance();
            var first = delayed.runtime.advance();
            assertEquals(List.of("action:11:23", "progress:1"), first.finalizedEffects().stream().map(RollbackEngine.Effect::value).toList());
            var second = delayed.runtime.advance();
            assertEquals(expected.head().effects(), second.finalizedEffects().stream().map(RollbackEngine.Effect::value).toList());
            assertTrue(delayed.runtime.advance().finalizedEffects().isEmpty());
            assertEquals(1, delayed.fixture.history.stream().filter("action:11:23"::equals).count());
            assertEquals(1, delayed.fixture.history.stream().filter("scheduled:11:23"::equals).count());
        });
    }

    @Test void worldFailureUnbindsOutputsAndStopsTheWholeSessionBeforeCombatOrFinalization() throws Exception {
        withConfig(() -> {
            var scenario = scenario();
            scenario.runtime.submit(A, 1, new Input(List.of(CLICK), true));
            assertThrows(IllegalArgumentException.class, scenario.runtime::advance);
            assertTrue(scenario.runtime.failed());
            assertEquals(0, scenario.runtime.diagnostics().confirmedTick());
            assertNull(scenario.fixture.effects);
            assertFalse(scenario.fixture.history.stream().anyMatch(entry -> entry.startsWith("progress")));
            assertFalse(RollbackDomain.active());
            assertFalse(PredictionServices.active());
            assertFalse(RollbackClock.active());
            assertThrows(IllegalStateException.class, scenario.runtime::advance);
        });
    }

    @Test void lateAbilityProgressUsesTheTransferredServerConfigAfterALiveReload() {
        withConfig(() -> {
            Config config = ConfigManager.defaultConfig;
            config.set("Rollback.Fixture.Speed", 1.25);
            var seed = RollbackConfiguration.captureData(PredictionConfigSync.sources()).encode();
            var onTime = scenario();
            config.set("Rollback.Fixture.Speed", 99);
            var received = RollbackConfiguration.prepare(RollbackConfiguration.Data.decode(seed), PredictionConfigSync.sources());
            var delayed = scenario(received);
            onTime.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            onTime.runtime.advance(); var expected = onTime.runtime.advance();
            delayed.runtime.advance(); delayed.runtime.advance();
            delayed.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            var replayed = delayed.runtime.reconcile();
            assertEquals(1, replayed.replayedFrom());
            assertEquals(onTime.fixture.history, delayed.fixture.history);
            assertEquals(expected.head().effects(), replayed.head().effects());
            assertTrue(replayed.head().effects().contains("position:3.75"));
            assertEquals(99, config.getDouble("Rollback.Fixture.Speed"));
        });
    }

    @Test void exportUsesSettledPrivateStateAndRestoresLiveRegistries() {
        withConfig(() -> {
            var scenario = scenario();
            var liveCollisions = ProjectKorra.collisionManager;
            var livePlayers = new HashMap<>(BendingPlayer.getPlayers());
            byte[] sourceBytes = {1, 2};
            var initial = scenario.runtime.exportState(() -> {
                assertTrue(RollbackDomain.active()); assertFalse(RollbackClock.active());
                assertNotSame(liveCollisions, ProjectKorra.collisionManager);
                assertSame(scenario.fixture.player, BendingPlayer.getPlayers().get(A).getPlayer());
                assertThrows(IllegalStateException.class, () -> scenario.runtime.exportState(() -> new byte[0]));
                return sourceBytes;
            });
            assertEquals(0, initial.tick()); assertEquals(0, initial.confirmedTick());
            sourceBytes[0] = 9;
            assertArrayEquals(new byte[]{1, 2}, initial.bytes());
            initial.bytes()[0] = 8;
            assertArrayEquals(new byte[]{1, 2}, initial.bytes());
            scenario.runtime.advance(); scenario.runtime.advance();
            scenario.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            assertThrows(IllegalStateException.class, () -> scenario.runtime.exportState(() -> {
                fail("Cannot export an invalidated branch"); return null;
            }));
            assertFalse(scenario.runtime.failed());
            var update = scenario.runtime.reconcile();
            var exported = scenario.runtime.exportState(() -> {
                assertTrue(scenario.fixture.history.contains("action:11:23"));
                assertTrue(scenario.fixture.history.contains("scheduled:11:23"));
                assertNull(scenario.fixture.effects);
                return String.join(",", scenario.fixture.history).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            });
            assertEquals(2, exported.tick());
            assertEquals(update.revision(), exported.revision());
            assertEquals(scenario.runtime.diagnostics().confirmedTick(), exported.confirmedTick());
            assertTrue(new String(exported.bytes(), java.nio.charset.StandardCharsets.UTF_8).contains("action:11:23"));
            assertSame(liveCollisions, ProjectKorra.collisionManager);
            assertEquals(livePlayers, BendingPlayer.getPlayers());
            assertFalse(RollbackDomain.active());
            scenario.runtime.advance();
        });
    }

    @Test void failedExportRestoresOutsideStateAndFailsTheRuntime() {
        withConfig(() -> {
            var scenario = scenario();
            var outside = ProjectKorra.collisionManager;
            assertThrows(IllegalArgumentException.class, () -> scenario.runtime.exportState(() -> {
                throw new IllegalArgumentException("Cannot encode native state");
            }));
            assertSame(outside, ProjectKorra.collisionManager);
            assertFalse(RollbackDomain.active());
            assertTrue(scenario.runtime.failed());
            assertThrows(IllegalStateException.class, () -> scenario.runtime.exportState(() -> new byte[0]));
        });
    }

    @Test void actionsValidateTheirShapeAndCannotRunOutsideReplay() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackInputActions.Action(0, 1, RollbackInputActions.Kind.SWING, -1));
        assertThrows(IllegalArgumentException.class, () -> new RollbackInputActions.Action(1, 0, RollbackInputActions.Kind.SWING, -1));
        assertThrows(IllegalArgumentException.class, () -> new RollbackInputActions.Action(1, 1, RollbackInputActions.Kind.SWING, 0));
        assertThrows(IllegalArgumentException.class, () -> new RollbackInputActions.Action(1, 1, RollbackInputActions.Kind.SLOT_CHANGE, 9));
        assertThrows(IllegalStateException.class, () -> RollbackInputActions.dispatch(null, CLICK));
    }

    @Test void lateDefenceRegistrationCancelsTheRealCollisionOnTheReplayedPrivatePlatform() {
        withConfig(() -> {
            var onTime = scenario(); var delayed = scenario();
            var attack = new Input(List.of(CLICK), false);
            var defence = new Input(List.of(), false, true);
            onTime.runtime.submit(A, 1, attack); delayed.runtime.submit(A, 1, attack);
            onTime.runtime.submit(B, 1, defence);
            onTime.runtime.advance(); delayed.runtime.advance();
            var expected = onTime.runtime.advance(); delayed.runtime.advance();
            assertFalse(onTime.fixture.history.contains("collision"));
            assertTrue(delayed.fixture.history.contains("collision"));
            delayed.runtime.submit(B, 1, defence);
            var actual = delayed.runtime.reconcile();
            assertEquals(1, actual.replayedFrom());
            assertEquals(expected.head().effects(), actual.head().effects());
            assertEquals(onTime.fixture.history, delayed.fixture.history);
            assertFalse(delayed.fixture.history.contains("collision"));
            assertEquals(1, delayed.fixture.defence.collisions);
            assertEquals(1, onTime.fixture.defence.collisions);
        });
    }

    @Test void lateDefenceRewindsRoundDecisionsAndOnlyConfirmedDefeatsReachLiveCallbacks() {
        withConfig(() -> {
            var delayed = scenario(RollbackConfiguration.capture(), true, false);
            delayed.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            delayed.runtime.advance(); delayed.runtime.advance();
            assertEquals(2, delayed.fixture.round.tick());
            assertTrue(delayed.fixture.round.ended());
            var delivered = new ArrayList<RollbackRound.Defeat>();
            delayed.runtime.deliverConfirmedDefeats(delivered::add);
            assertTrue(delivered.isEmpty());
            delayed.runtime.submit(B, 1, new Input(List.of(), false, true));
            delayed.runtime.reconcile();
            assertFalse(delayed.fixture.round.ended());
            assertTrue(delayed.fixture.round.provisionalDefeats().isEmpty());
            for (int i = 0; i < 3; i++) delayed.runtime.advance();
            delayed.runtime.deliverConfirmedDefeats(delivered::add);
            assertTrue(delivered.isEmpty());

            var landed = scenario(RollbackConfiguration.capture(), true, false);
            landed.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            for (int i = 0; i < 5; i++) landed.runtime.advance();
            landed.runtime.deliverConfirmedDefeats(defeat -> {
                assertFalse(RollbackDomain.active()); assertFalse(RollbackClock.active());
                assertThrows(IllegalStateException.class, landed.runtime::advance);
                delivered.add(defeat);
            });
            assertEquals(List.of(new RollbackRound.Defeat(new UUID(0, 99), 2, B, A)), delivered);
            landed.runtime.deliverConfirmedDefeats(delivered::add);
            landed.runtime.advance();
            landed.runtime.deliverConfirmedDefeats(delivered::add);
            assertEquals(1, delivered.size());
        });
    }

    @Test void failedDeliveryStopsCombatAndClientReplicaCannotPublishLiveResults() {
        withConfig(() -> {
            var server = scenario(RollbackConfiguration.capture(), true, false);
            server.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            for (int i = 0; i < 5; i++) server.runtime.advance();
            assertThrows(IllegalStateException.class, () -> server.runtime.deliverConfirmedDefeats(defeat -> {
                throw new IllegalStateException("match callback failed");
            }));
            assertTrue(server.runtime.failed());
            assertThrows(IllegalStateException.class, server.runtime::advance);
            assertThrows(IllegalStateException.class, () -> server.runtime.submit(A, 6, EMPTY));
            assertThrows(IllegalStateException.class, () -> server.runtime.deliverConfirmedDefeats(defeat -> fail("retried result")));
            var client = scenario(RollbackConfiguration.capture(), true, true);
            client.runtime.submit(A, 1, new Input(List.of(CLICK), false));
            client.runtime.advance(); client.runtime.advance();
            assertTrue(client.fixture.round.ended());
            client.runtime.confirm(2);
            assertThrows(IllegalStateException.class, () -> client.runtime.deliverConfirmedDefeats(defeat -> fail("client live result")));
        });
    }

    private Scenario scenario() {
        return scenario(RollbackConfiguration.capture());
    }

    private Scenario scenario(RollbackConfiguration configuration) { return scenario(configuration, false, false); }

    private Scenario scenario(RollbackConfiguration configuration, boolean match, boolean replica) {
        var fixture = new Fixture();
        if (match) fixture.round = new RollbackRound(new UUID(0, 99), Map.of(A, A, B, B));
        var scheduler = new RollbackScheduler(50, 50);
        var shared = new ArrayList<Field>();
        shared.addAll(RollbackStateGraph.staticFields(CoreAbility.class, root -> root.getName().startsWith("INSTANCES")
                || Set.of("currentTick", "idCounter", "ATTRIBUTE_FIELDS").contains(root.getName())));
        // Explicit roots for this fixture. Production must also register every participating addon/service.
        for (Class<?> type : List.of(OfflineBendingPlayer.class, AbilityActivationManager.class, ComboManager.class,
                MovementHandler.class, TempPotionEffect.class, RevertChecker.class, HorizontalVelocityTracker.class,
                TempArmor.class, TempFallingBlock.class, TempArmorStand.class, TempBlock.class)) {
            shared.addAll(RollbackStateGraph.staticFields(type, field -> !field.getName().equals("DISCOVERED")
                    && (type != OfflineBendingPlayer.class || Set.of("ONLINE_PLAYERS", "PLAYERS").contains(field.getName()))
                    && (Map.class.isAssignableFrom(field.getType()) || Collection.class.isAssignableFrom(field.getType()))));
        }
        var prediction = PredictionServices.builder().bind(CooldownSync.Listener.class, fixture).bind(AbilityRemovalSync.Listener.class,
                (ability, external) -> fixture.record("removed")).build();
        var platform = RollbackPlatformTest.platform(directory, fixture.world, List.of(fixture.player, fixture.defender), scheduler);
        var environment = new RollbackCombatRuntime.Environment(graph(), shared, List.of(), platform, null, prediction, configuration);
        Runnable bootstrap = () -> {
            clearCollections(shared);
            BendingPlayer.getPlayers().put(A, new BendingPlayer(fixture.player));
            ProjectKorra.collisionManager = new CollisionManager();
            var boundary = new Boundary(fixture);
            boundary.start();
            ProjectKorra.collisionManager.addCollision(new Collision(new Pulse(fixture), boundary, true, false));
            AbilityActivationManager.registerGlobal(ClickType.RIGHT_CLICK, context -> {
                fixture.record("action:" + PredictionDeterminism.currentAction() + ":" + PredictionDeterminism.currentSeed());
                new Pulse(fixture).start();
                BendingPlayer.getBendingPlayer(fixture.player).getCooldowns().put("fixture", new Cooldown(RollbackClock.millis() + 50, false));
                Platform.scheduler().runNow(() -> fixture.record("scheduled:" + PredictionDeterminism.currentAction() + ":" + PredictionDeterminism.currentSeed()));
                return true;
            });
        };
        var runtime = !match ? RollbackCombatRuntime.create(environment, fixture, bootstrap, Map.of(B, EMPTY, A, EMPTY), LIMITS, 1_000, 10_000)
                : replica ? RollbackCombatRuntime.createMatchReplica(environment, fixture, bootstrap, Map.of(B, EMPTY, A, EMPTY), LIMITS, 1_000, 10_000, fixture.round)
                : RollbackCombatRuntime.createMatch(environment, fixture, bootstrap, Map.of(B, EMPTY, A, EMPTY), LIMITS, 1_000, 10_000, fixture.round);
        return new Scenario(fixture, runtime);
    }

    private record Scenario(Fixture fixture, RollbackCombatRuntime<Input, String> runtime) { }
    private record Input(List<RollbackInputActions.Action> actions, boolean failWorld, boolean defend) {
        Input(List<RollbackInputActions.Action> actions, boolean failWorld) { this(actions, failWorld, false); }
        Input { actions = List.copyOf(actions); }
    }
    private static final class Fixture implements RollbackCombatRuntime.Execution<Input, String>, RollbackStateCell<List<String>>, CooldownSync.Listener {
        final RollbackWorld world = RollbackWorldTest.world(Map.of());
        final RollbackPlayer player = PrivateCombatRollbackTest.player(world, 1);
        final RollbackPlayer defender = PrivateCombatRollbackTest.player(player.getWorld(), 2);
        final Defence defence = new Defence();
        Fixture() { world.entities().add(player); world.entities().add(defender); }
        final List<String> history = new ArrayList<>();
        RollbackStep<String> effects;
        RollbackRound round;
        boolean failWorld;
        void record(String value) { history.add(value); effects.emit(value); }
        @Override public Input predict(UUID participant, Input previous) { return EMPTY; }
        @Override public void begin(RollbackStep<String> effects) { this.effects = effects; failWorld = false; history.add("begin:" + effects.tick()); }
        @Override public void input(UUID participant, Input input) {
            history.add("input:" + participant.getLeastSignificantBits());
            failWorld |= input.failWorld();
            if (input.defend()) Platform.events().registerListener(defence);
            for (var action : input.actions()) RollbackInputActions.dispatch(participant.equals(A) ? player : defender, action);
        }
        @Override public void tickWorld(long tick) { history.add("world:" + tick); if (failWorld) throw new IllegalArgumentException("native fixture failed"); }
        @Override public void end() { history.add("end:" + ProjectKorra.time_step); effects = null; }
        @Override public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiry) { record("cooldown"); }
        @Override public void onRemoved(BendingPlayer player, String ability) { record("expired"); }
        @Override public List<String> captureRollbackState() { assertNull(effects); return List.copyOf(history); }
        @Override public void restoreRollbackState(List<String> state) { history.clear(); history.addAll(state); }
        @Override public Collection<?> rollbackReferences() { return List.of(player, defender, defence); }
    }

    private static final class Defence {
        int collisions;
        @EventHandler public void collision(AbilityCollisionEvent event) { collisions++; event.setCancelled(true); }
    }

    private abstract static class Dynamic extends CoreAbility {
        final Fixture fixture;
        final Location location;
        Dynamic(Fixture fixture, double x) { super(); this.fixture = fixture; player = fixture.player; location = new Location(player.getWorld(), x, 0, 0); }
        @Override public boolean isEnabled() { return true; }
        @Override public void recalculateAttributes() { }
        @Override public double getCollisionRadius() { return 0.1; }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return getClass().getSimpleName(); }
        @Override public Element getElement() { return null; }
        @Override public Location getLocation() { return location; }
    }
    private static final class Pulse extends Dynamic {
        int age;
        Pulse(Fixture fixture) { super(fixture, 0); }
        @Override public void progress() {
            double speed = ConfigManager.getConfig().getDouble("Rollback.Fixture.Speed", 1);
            location.add(++age * speed, 0, 0); fixture.record("progress:" + age);
            if (speed != 1) fixture.record("position:" + location.getX());
        }
        @Override public void handleCollision(Collision collision) {
            fixture.record("collision");
            if (fixture.round != null) fixture.round.damage(B, A, 4, 6, false, false);
            super.handleCollision(collision);
        }
    }
    private static final class Boundary extends Dynamic {
        Boundary(Fixture fixture) { super(fixture, 3); player = fixture.defender; }
        @Override public void progress() { }
    }

    private void withConfig(Runnable test) {
        Config previous = ConfigManager.defaultConfig;
        var saved = graph().capture(List.of(), RollbackStateGraph.staticFields(PredictionConfigSync.class, field -> true));
        try (var scope = Platform.using(platform(new RollbackScheduler(50, 50)))) {
            ConfigManager.defaultConfig = new Config(directory.resolve("combat.yml").toFile());
            test.run();
        } finally { ConfigManager.defaultConfig = previous; saved.restore(); }
    }
    private static RollbackStateGraph graph() { return new RollbackStateGraph(value -> (value instanceof World && !(value instanceof RollbackWorld))
            || value instanceof Config || Proxy.isProxyClass(value.getClass()), field -> true, 20_000); }
    private ProjectKorraPlatform platform(RollbackScheduler scheduler) {
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> null);
        PKServer server = (PKServer) Proxy.newProxyInstance(PKServer.class.getClassLoader(), new Class<?>[]{PKServer.class}, (proxy, method, args) -> {
            if (method.getName().equals("minecraftVersion")) return "1.21.11";
            throw new AssertionError(method);
        });
        PKWorlds worlds = new PKWorlds() { @Override public <W> Collection<W> worlds() { return List.of(); } };
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolder" -> directory;
            case "logger" -> Logger.getLogger("RollbackCombatTest");
            case "events" -> events;
            case "worlds" -> worlds;
            case "scheduler" -> scheduler;
            case "materials" -> (PKMaterials) material -> false;
            case "server" -> server;
            default -> throw new AssertionError(method);
        });
    }
    private static void clearCollections(List<Field> shared) {
        try {
            for (Field field : shared) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?> map) map.clear();
                else if (value instanceof Collection<?> collection) collection.clear();
            }
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
}
