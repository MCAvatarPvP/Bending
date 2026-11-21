package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.*;
import com.projectkorra.projectkorra.configuration.*;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionTiming;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.util.DamageHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Real progress/collision/damage dispatch; the final native damage rule is an explicit fixture. */
class PrivateCombatRollbackTest {
    @TempDir Path directory;

    @Test void lateDefenceRewindsDamageKnockbackRemovalAndPrivateReceiptsWithoutCallingLiveListeners() throws Exception {
        var platform = platform();
        var liveCalls = new ArrayList<String>();
        Map<Field, Object> globals = new LinkedHashMap<>();
        for (Class<?> hook : List.of(CooldownSync.class, AbilityRemovalSync.class, VelocitySync.class, PredictedContactSync.class)) {
            Field field = field(hook, "listener"); globals.put(field, field.get(null));
        }
        Field timing = field(PredictionTiming.class, "provider"); globals.put(timing, timing.get(null));
        Config previousConfig = ConfigManager.defaultConfig;
        var graph = new RollbackStateGraph(value -> value instanceof World || value instanceof Config, ignored -> true, 20_000);
        var sourceState = graph.capture(List.of(), RollbackStateGraph.staticFields(PredictionConfigSync.class, ignored -> true));
        try (var platformScope = Platform.using(platform)) {
            ConfigManager.defaultConfig = new Config(directory.resolve("rollback.yml").toFile());
            ConfigManager.defaultConfig.set("Properties.DamageMultiplier", 1.0);
            ConfigManager.defaultConfig.set("Properties.Statistics", false);
            CooldownSync.install(new LiveClient(liveCalls));
            AbilityRemovalSync.install((ability, external) -> liveCalls.add("live removal"));
            VelocitySync.install((ability, target, velocity) -> liveCalls.add("live velocity"));
            PredictedContactSync.install((ability, target) -> liveCalls.add("live contact"));
            PredictionTiming.install(ability -> 300);

            var world = new World();
            var owner = player(world, 1);
            var target = player(world, 2);
            var bolt = new Moving(owner, target);
            var defence = new Boundary(target);
            assertTrue(PredictedContactSync.mark(bolt, target));
            assertEquals(List.of("live contact"), liveCalls);
            liveCalls.clear();
            assertEquals(700, PredictionTiming.alignDuration(bolt, 1_000));

            var sink = new Receipts();
            var bindings = PredictionServices.builder().bind(CooldownSync.Listener.class, sink)
                    .bind(AbilityRemovalSync.Listener.class, sink).bind(VelocitySync.Listener.class, sink).build();
            var roots = RollbackStateGraph.staticFields(CoreAbility.class, root -> root.getName().startsWith("INSTANCES")
                    || Set.of("currentTick", "idCounter", "ATTRIBUTE_FIELDS").contains(root.getName()));
            List<Field> shared = new ArrayList<>(roots);
            shared.addAll(RollbackStateGraph.staticFields(DamageHandler.class, root -> Map.class.isAssignableFrom(root.getType()) || Set.class.isAssignableFrom(root.getType())));
            var collisions = new CollisionManager();
            var domain = RollbackDomain.create(graph, shared, List.of(owner, target, bolt, defence, collisions), platform, null, bindings, () -> {
                clearInstances();
                attributes().put(Moving.class, Map.of()); attributes().put(Boundary.class, Map.of());
                setId(bolt, 1); setId(defence, 2);
                bolt.start(); defence.start();
                collisions.addCollision(new Collision(bolt, defence, true, false));
            });
            var engine = domain.call(() -> new RollbackEngine<>(new RollbackSimulation<RollbackDomain.Checkpoint, Double, String>() {
                @Override public RollbackDomain.Checkpoint snapshot() { return domain.capture(); }
                @Override public void restore(RollbackDomain.Checkpoint checkpoint) { domain.restore(checkpoint); }
                @Override public Double predict(UUID player, Double previous) { return previous; }
                @Override public void step(long tick, Map<UUID, Double> inputs, RollbackStep<String> effects) {
                    defence.location.setY(inputs.get(target.getUniqueId()));
                    sink.effects = effects;
                    try {
                        CoreAbility.progressAll();
                        collisions.detectCollisions();
                    } finally { sink.effects = null; }
                }
            }, Map.of(owner.getUniqueId(), 0D, target.getUniqueId(), 0D), new RollbackEngine.Limits(3, 1, 30, 50_000_000), 0));

            domain.call(() -> {
                assertTrue(CooldownSync.isAuthoritative());
                assertFalse(PredictedContactSync.mark(bolt, target));
                assertThrows(IllegalArgumentException.class, () -> PredictedContactSync.mark(bolt, new Player()));
                assertEquals(1_000, PredictionTiming.alignDuration(bolt, 1_000));
                engine.advance(); engine.advance();
                assertTrue(bolt.isRemoved());
                assertEquals(16, target.getHealth());
                assertEquals(1, target.getVelocity().getX());
                assertEquals(List.of("removed", "velocity", "cooldown"), sink.receipts);
                assertEquals(sink.receipts, engine.head().effects());
                return null;
            });
            assertTrue(liveCalls.isEmpty());
            assertFalse(CooldownSync.isAuthoritative());
            assertFalse(RollbackDomain.active());
            domain.call(() -> {
                engine.submit(target.getUniqueId(), 1, 8D);
                assertEquals(1, engine.reconcile().replayedFrom());
                assertFalse(bolt.isRemoved());
                assertEquals(20, target.getHealth());
                assertEquals(0, target.getVelocity().lengthSquared());
                assertTrue(sink.receipts.isEmpty());
                assertTrue(engine.head().effects().isEmpty());
                assertTrue(engine.advance().finalizedEffects().isEmpty());
                assertTrue(engine.advance().finalizedEffects().isEmpty());
                return null;
            });
            assertTrue(liveCalls.isEmpty());
            assertTrue(CoreAbility.getAbilitiesByInstances().stream().noneMatch(ability -> ability == bolt || ability == defence));

            UUID id = owner.getUniqueId();
            CooldownSync.runInputVeto(id, List.of("dynamic"), () -> CooldownSync.runInputLeniency(id, List.of("dynamic"), 100, () -> {
                assertTrue(CooldownSync.isInputVetoed(id, "dynamic"));
                domain.call(() -> {
                    assertFalse(CooldownSync.isInputVetoed(id, "dynamic"));
                    assertEquals(500, CooldownSync.effectiveInputTime(id, "dynamic", 500));
                    return null;
                });
                assertTrue(CooldownSync.isInputVetoed(id, "dynamic"));
                assertEquals(600, CooldownSync.effectiveInputTime(id, "dynamic", 500));
                return null;
            }));
        } finally {
            sourceState.restore();
            ConfigManager.defaultConfig = previousConfig;
            for (var entry : globals.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }

    @Test void missingPrivateServiceAbortsDomainAndRestoresOutsideAuthority() {
        var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 50), List.of(), List.of(), platform(), () -> { });
        assertThrows(IllegalStateException.class, () -> domain.call(() -> { CooldownSync.added(null, "dynamic", 5); return null; }));
        assertTrue(domain.failed());
        assertFalse(PredictionServices.active());
        assertFalse(RollbackDomain.active());
    }

    private static final class Receipts implements CooldownSync.Listener, AbilityRemovalSync.Listener, VelocitySync.Listener, RollbackStateCell<List<String>> {
        final List<String> receipts = new ArrayList<>();
        RollbackStep<String> effects;
        private void emit(String value) { receipts.add(value); effects.emit(value); }
        @Override public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiry) { emit("cooldown"); }
        @Override public void onRemoved(BendingPlayer player, String ability) { emit("cooldown removed"); }
        @Override public void onRemoved(CoreAbility ability, boolean external) { emit("removed"); }
        @Override public void onVelocity(com.projectkorra.projectkorra.ability.Ability ability, Entity target, Vector velocity) { emit("velocity"); }
        @Override public List<String> captureRollbackState() { if (effects != null) throw new IllegalStateException("Snapshot inside output callback"); return List.copyOf(receipts); }
        @Override public void restoreRollbackState(List<String> state) { receipts.clear(); receipts.addAll(state); }
    }
    private record LiveClient(List<String> calls) implements CooldownSync.Listener {
        @Override public boolean isAuthoritative() { return false; }
        @Override public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiry) { calls.add("live cooldown"); }
        @Override public void onRemoved(BendingPlayer player, String ability) { calls.add("live cooldown removed"); }
    }
    private abstract static class Dynamic extends CoreAbility {
        final Location location;
        Dynamic(RollbackPlayer owner, double x) { super(null); player = owner; location = new Location(owner.getWorld(), x, 0, 0); }
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
    private static final class Moving extends Dynamic {
        final RollbackPlayer target;
        int age;
        Moving(RollbackPlayer owner, RollbackPlayer target) { super(owner, 0); this.target = target; }
        @Override public void progress() { location.add(++age == 1 ? 1 : 2, 0, 0); }
        @Override public void handleCollision(Collision collision) {
            super.handleCollision(collision);
            // These are the existing common combat entry points, not copied formulas.
            GeneralMethods.setVelocity(this, target, new Vector(1, -0.2, 0));
            DamageHandler.damageEntity(target, getPlayer(), 4, this, false);
            CooldownSync.added(new BendingPlayer(getPlayer()), "dynamic", RollbackClock.millis() + 500);
        }
        @Override public Element getElement() { return Element.FIRE; }
    }
    private static final class Boundary extends Dynamic {
        Boundary(RollbackPlayer owner) { super(owner, 3); }
        @Override public void progress() { }
    }

    static RollbackPlayer player(World world, int id) {
        return player(world, id, noCalls(RollbackPlayerState.Rules.class));
    }

    static RollbackPlayer player(World world, int id, RollbackPlayerState.Rules playerRules) {
        var body = new RollbackEntityBody(new RollbackEntityBody.Identity(new UUID(0, id), id, "player-" + id, EntityType.PLAYER), world,
                new RollbackEntityBody.Kinematics(new RollbackEntityBody.Pose(0, 0, 0, 0, 0), new RollbackEntityBody.Motion(0, 0, 0),
                        new RollbackBlockStore.Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3), 1.8, true, 0, false), noCalls(RollbackEntityBody.Rules.class));
        var inventory = RollbackInventoryTest.inventory();
        // Explicit final-damage fixture: this test verifies routing/rewind, not native armor or immunity.
        var damage = (RollbackLivingState.Rules) Proxy.newProxyInstance(RollbackLivingState.Rules.class.getClassLoader(),
                new Class<?>[]{RollbackLivingState.Rules.class}, (proxy, method, args) -> {
                    if (method.getName().equals("damage")) { var living = (RollbackLivingState) args[0]; living.health(living.vitals().health() - (double) args[1]); return null; }
                    throw new AssertionError(method);
                });
        var living = new RollbackLivingState(body, new RollbackLivingState.Vitals(20, 0, 1.62, 300, 300, 0, 20, 0, true),
                Map.of(Attribute.MAX_HEALTH.name(), 20D), List.of(), new RollbackEquipment(inventory), damage);
        return new RollbackPlayer(new RollbackPlayerState(living, inventory, new RollbackPlayerState.Controls(Set.of(), 0.1F, 0, 0),
                new RollbackPlayerState.Profile("player-" + id, "SURVIVAL", "RIGHT", true, false, true, 100),
                Set.of(), new Scoreboard(), playerRules));
    }
    private ProjectKorraPlatform platform() {
        PKEventBus events = noOpEvents();
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolder" -> directory;
            case "logger" -> Logger.getLogger("RollbackTest");
            case "events" -> events;
            default -> throw new AssertionError(method);
        });
    }
    private static PKEventBus noOpEvents() { return (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> null); }
    private static <T> T noCalls(Class<T> type) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> { throw new AssertionError(method); })); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    @SuppressWarnings("unchecked") private static Map<Class<?>, Object> attributes() {
        try { return (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void setId(CoreAbility ability, int id) { try { field(CoreAbility.class, "id").setInt(ability, id); } catch (Exception failure) { throw new AssertionError(failure); } }
    private static void clearInstances() {
        try {
            for (Field field : RollbackStateGraph.staticFields(CoreAbility.class, value -> value.getName().startsWith("INSTANCES"))) {
                field.setAccessible(true); Object value = field.get(null);
                if (value instanceof Map<?, ?> map) map.clear(); else ((Collection<?>) value).clear();
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
