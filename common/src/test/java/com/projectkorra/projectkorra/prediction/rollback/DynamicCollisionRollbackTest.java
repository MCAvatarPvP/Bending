package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the existing dynamic collision registry and callbacks, not a second collision implementation. */
class DynamicCollisionRollbackTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);

    @Test @SuppressWarnings("unchecked")
    void lateInputReplaysRegisteredGeometryCallbacksAndRemovalOfExistingAbilityInstances() throws Exception {
        Field platform = field(Platform.class, "current");
        Object previousPlatform = platform.get(null);
        var attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        Object previousMoving = attributes.put(Moving.class, Map.of());
        Object previousBoundary = attributes.put(Boundary.class, Map.of());
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(),
                new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> null);
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("events")) return events;
                    throw new AssertionError(method);
                }));
        var graph = new RollbackStateGraph(value -> value instanceof World || value instanceof Player,
                ignored -> true, 20_000);
        var roots = RollbackStateGraph.staticFields(CoreAbility.class,
                root -> root.getName().startsWith("INSTANCES") || root.getName().equals("currentTick")
                        || root.getName().equals("idCounter"));
        var outside = graph.capture(List.of(), roots);
        try {
            // The runtime discovers these arbitrary classes through its normal collision registration API.
            World world = new World();
            Moving moving = new Moving(world);
            Boundary boundary = new Boundary(world);
            field(CoreAbility.class, "id").setInt(moving, 1);
            field(CoreAbility.class, "id").setInt(boundary, 2);
            moving.start();
            boundary.start();
            CollisionManager collisions = new CollisionManager();
            collisions.addCollision(new Collision(moving, boundary, true, false));
            List<Object> stateRoots = List.of(collisions, moving, boundary);
            RollbackSimulation<RollbackStateGraph.Snapshot, Double, String> simulation = new RollbackSimulation<>() {
                @Override public RollbackStateGraph.Snapshot snapshot() { return graph.capture(stateRoots, roots); }
                @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
                @Override public Double predict(UUID participant, Double previous) { return previous; }
                @Override public void step(long tick, Map<UUID, Double> inputs, RollbackStep<String> context) {
                    boundary.location.setY(inputs.get(B));
                    CoreAbility.progressAll();
                    collisions.detectCollisions();
                    if (moving.isRemoved()) context.emit("removed:" + moving.getId());
                }
            };
            var engine = new RollbackEngine<>(simulation, Map.of(A, 0.0, B, 0.0),
                    new RollbackEngine.Limits(3, 1, 20, 50_000_000L), 0);
            engine.advance();
            engine.advance(); // Moving accelerates at its second step and meets Boundary.
            assertTrue(moving.isRemoved());
            assertEquals(1, moving.contacts);
            assertEquals(1, boundary.contacts);
            assertFalse(engine.head().effects().isEmpty());

            assertEquals(RollbackEngine.Submission.ACCEPTED, engine.submit(B, 1, 8.0));
            engine.reconcile();
            assertFalse(moving.isRemoved());
            assertTrue(CoreAbility.getAbilities(Moving.class).contains(moving));
            assertEquals(0, moving.contacts);
            assertEquals(0, boundary.contacts);
            assertEquals(3.0, moving.location.getX()); // Same acceleration, different collision outcome.
            assertEquals(List.of(1.0, 3.0), moving.history);
            assertTrue(engine.head().effects().isEmpty());
            assertTrue(engine.advance().finalizedEffects().isEmpty());
            assertTrue(engine.advance().finalizedEffects().isEmpty());
        } finally {
            outside.restore();
            if (previousMoving == null) attributes.remove(Moving.class); else attributes.put(Moving.class, previousMoving);
            if (previousBoundary == null) attributes.remove(Boundary.class); else attributes.put(Boundary.class, previousBoundary);
            platform.set(null, previousPlatform);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private abstract static class Dynamic extends CoreAbility {
        final Location location;
        int contacts;
        double radius = 0.1;
        Dynamic(World world, UUID owner, double x) {
            super(null);
            location = new Location(world, x, 0, 0);
            player = new Player() { @Override public UUID getUniqueId() { return owner; } };
        }
        @Override public boolean isEnabled() { return true; }
        @Override public void recalculateAttributes() { }
        @Override public boolean isCollidable() { return true; }
        @Override public double getCollisionRadius() { return radius; }
        @Override public void handleCollision(Collision collision) { contacts++; super.handleCollision(collision); }
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
        final List<Double> history = new ArrayList<>();
        int age;
        double speed = 1;
        Moving(World world) { super(world, A, 0); }
        @Override public void progress() {
            if (++age == 2) speed = 2;
            location.add(speed, 0, 0);
            history.add(location.getX());
        }
    }

    private static final class Boundary extends Dynamic {
        Boundary(World world) { super(world, B, 3); }
        @Override public void progress() { radius += 0.02; }
    }
}
