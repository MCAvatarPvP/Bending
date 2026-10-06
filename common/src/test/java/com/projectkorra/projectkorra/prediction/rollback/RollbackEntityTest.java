package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RollbackEntityTest {
    private static final RollbackBlockStore.Box BOX = new RollbackBlockStore.Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    private static final RollbackEntityBody.Rules BODY_RULES = new RollbackEntityBody.Rules() {
        @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody entity, RollbackEntityBody.Pose destination) { return destination; }
        @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return true; }
    };

    @Test void apiViewsDoNotInheritStubMethodsThatSilentlyDropGameplayState() throws Exception {
        for (var method : Entity.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) continue;
            assertEquals(RollbackEntity.class, RollbackEntity.class.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(), method.toString());
        }
        for (var method : LivingEntity.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) continue;
            assertEquals(RollbackLivingEntity.class, RollbackLivingEntity.class.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(), method.toString());
        }
    }

    @Test void geometryMotionFlagsAndNativeStateAreDetachedAndRewindTogether() {
        World world = new World();
        RollbackEntity entity = new RollbackEntity(body(world, 1));
        Vector velocity = new Vector(1, 2, 3);
        entity.setVelocity(velocity);
        entity.setFireTicks(12);
        entity.setFallDistance(4);
        entity.setSilent(true);
        entity.body().nativeState(new byte[]{1, 2});
        var checkpoint = capture(entity);
        velocity.setX(100);
        entity.getVelocity().setY(100);
        entity.getLocation().setX(100);
        entity.getBoundingBox().getMin().setX(100);
        entity.body().nativeState()[0] = 100;
        assertEquals(1, entity.getVelocity().getX());
        assertEquals(2, entity.getVelocity().getY());
        assertEquals(0, entity.getLocation().getX());
        assertEquals(-0.3, entity.getBoundingBox().getMinX());
        assertArrayEquals(new byte[]{1, 2}, entity.body().nativeState());
        entity.teleport(new Location(world, 9, 3, 2));
        entity.setVelocity(new Vector(9, 0, 0));
        entity.setFireTicks(0);
        entity.setFallDistance(0);
        entity.setSilent(false);
        entity.setGravity(false);
        entity.body().nativeState(new byte[]{4});
        entity.remove();
        checkpoint.restore();
        assertTrue(entity.isValid());
        assertFalse(entity.isDead());
        assertEquals(0, entity.getLocation().getX());
        assertEquals(1, entity.getVelocity().getX());
        assertEquals(12, entity.getFireTicks());
        assertEquals(4, entity.getFallDistance());
        assertTrue(entity.body().silent());
        assertTrue(entity.body().gravity());
        assertArrayEquals(new byte[]{1, 2}, entity.body().nativeState());
    }

    @Test void passengerCyclesAreRejectedAndRemovalRestoresBothSidesOfTheRelationship() {
        World world = new World();
        RollbackEntity vehicle = new RollbackEntity(body(world, 1));
        RollbackEntity passenger = new RollbackEntity(body(world, 2));
        assertTrue(vehicle.addPassenger(passenger));
        assertFalse(passenger.addPassenger(vehicle));
        assertFalse(vehicle.addPassenger(passenger));
        assertFalse(vehicle.teleport(new Location(world, 10, 0, 0)));
        var checkpoint = capture(vehicle); // Passenger is reached through the body graph.
        passenger.remove();
        assertTrue(vehicle.getPassengers().isEmpty());
        checkpoint.restore();
        assertSame(passenger, vehicle.getPassengers().getFirst());
        assertTrue(passenger.body().insideVehicle());
        assertTrue(passenger.isValid());
        assertTrue(passenger.teleport(new Location(world, 10, 0, 0)));
        assertFalse(passenger.body().insideVehicle());
        assertTrue(vehicle.getPassengers().isEmpty());
        checkpoint.restore();
        assertEquals(0, passenger.getLocation().getX());
        assertTrue(passenger.body().insideVehicle());
        assertThrows(IllegalArgumentException.class, () -> vehicle.addPassenger(new Entity()));
    }

    @Test void metadataOwnersRemainOpaqueWhilePayloadAndDamageEventsRewind() {
        RollbackEntity entity = new RollbackEntity(body(new World(), 1));
        Object firstOwner = Thread.currentThread(); // Must not try to snapshot a plugin/owner's internals.
        Object secondOwner = new Object();
        int[] payload = {7};
        entity.setMetadata("state", new FixedMetadataValue(firstOwner, payload));
        entity.setMetadata("state", new FixedMetadataValue(secondOwner, "other"));
        var event = new EntityDamageEvent(entity, EntityDamageEvent.DamageCause.CUSTOM, 3);
        entity.setLastDamageCause(event);
        var checkpoint = capture(entity);
        payload[0] = 9;
        event.setDamage(100);
        entity.removeMetadata("state", firstOwner);
        assertEquals("other", entity.getMetadata("state").getFirst().value());
        checkpoint.restore();
        assertEquals(7, payload[0]);
        assertEquals(3, event.getDamage());
        assertEquals(2, entity.getMetadata("state").size());
        entity.setMetadata("copied", entity.getMetadata("state").getFirst());
        entity.removeMetadata("copied", secondOwner);
        assertTrue(entity.hasMetadata("copied"));
        entity.removeMetadata("copied", firstOwner);
        assertFalse(entity.hasMetadata("copied"));
    }

    @Test void registryRewindsSpawnRemovalAndMovedQueryGeometryWithStableViews() {
        World world = new World();
        var registry = new RollbackEntityRegistry(world, 3);
        RollbackEntity first = new RollbackEntity(body(world, 1));
        RollbackEntity second = new RollbackEntity(body(world, 2));
        registry.add(second);
        registry.add(first);
        BoundingBox query = new BoundingBox(new Vector(-1, -1, -1), new Vector(1, 2, 1));
        assertEquals(List.of(first, second), new ArrayList<>(registry.nearby(query, null)));
        var checkpoint = capture(registry);
        first.teleport(new Location(world, 20, 0, 0));
        second.remove();
        registry.pruneRemoved();
        RollbackEntity replacement = new RollbackEntity(body(world, 2));
        registry.add(replacement);
        assertSame(replacement, registry.get(2));
        assertEquals(List.of(replacement), new ArrayList<>(registry.nearby(query, null)));
        var replacementBranch = capture(registry);
        checkpoint.restore();
        assertFalse(replacement.isValid(), "spawned views from the discarded branch must be invalidated");
        assertSame(second, registry.get(2));
        assertEquals(List.of(first, second), registry.entities());
        assertEquals(List.of(first, second), new ArrayList<>(registry.nearby(query, null)));
        assertThrows(IllegalArgumentException.class, () -> registry.add(replacement));
        assertThrows(IllegalArgumentException.class, () -> registry.add(new RollbackEntity(body(new World(), 3))));
        assertThrows(IllegalArgumentException.class, () -> registry.add(new Entity()));
        replacementBranch.restore();
        assertTrue(replacement.isValid());
        assertFalse(second.isValid());
        assertSame(replacement, registry.get(2));
    }

    @Test void livingApiUsesTheBehaviorAdapterAndRestoresDeathAttributesAndPotionState() {
        var entity = living(new World(), 1);
        var maximum = entity.getAttribute(Attribute.MAX_HEALTH);
        var checkpoint = capture(entity);
        entity.damage(4);
        assertEquals(16, entity.getHealth());
        assertEquals(4, entity.getLastDamage());
        assertEquals(20, entity.getNoDamageTicks());
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 2));
        assertTrue(entity.hasPotionEffect(PotionEffectType.valueOf("SPEED")));
        assertEquals(2, entity.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        maximum.setValue(30);
        assertEquals(30, entity.getMaxHealth());
        entity.setRemainingAir(1);
        entity.damage(16);
        assertTrue(entity.isDead());
        checkpoint.restore();
        assertEquals(20, entity.getHealth());
        assertFalse(entity.isDead());
        assertEquals(20, maximum.getValue());
        assertEquals(0, entity.getNoDamageTicks());
        assertEquals(300, entity.getRemainingAir());
        assertFalse(entity.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(1.62, entity.getEyeLocation().getY());
        assertThrows(IllegalArgumentException.class, () -> entity.damage(1, new Entity()));
    }

    @Test void lateInputRetractsHealthKnockbackAndEntityRemovalFromTheSameFrame() {
        World world = new World();
        var registry = new RollbackEntityRegistry(world, 2);
        var target = living(world, 1);
        var projectile = new RollbackEntity(body(world, 2));
        registry.add(target);
        registry.add(projectile);
        UUID owner = target.getUniqueId();
        var engine = new RollbackEngine<>(new RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, String>() {
            @Override public RollbackStateGraph.Snapshot snapshot() { return capture(registry); }
            @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
            @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> effects) {
                if (inputs.get(owner)) target.teleport(new Location(world, 10, 0, 0));
                if (projectile.isValid() && target.getBoundingBox().overlaps(projectile.getBoundingBox())) {
                    target.damage(6);
                    target.setVelocity(new Vector(4, 1, 0));
                    projectile.remove();
                    registry.pruneRemoved();
                    effects.emit("contact");
                }
            }
        }, Map.of(owner, false), new RollbackEngine.Limits(2, 1, 10, 50_000_000), 0);
        engine.advance();
        engine.advance();
        assertEquals(14, target.getHealth());
        assertEquals(4, target.getVelocity().getX());
        assertNull(registry.get(2));
        engine.submit(owner, 1, true);
        engine.reconcile();
        assertEquals(20, target.getHealth());
        assertEquals(0, target.getVelocity().getX());
        assertSame(projectile, registry.get(2));
        assertTrue(projectile.isValid());
        assertTrue(engine.advance().finalizedEffects().isEmpty());
    }

    @Test void foreignCheckpointsAndCrossThreadEntityMutationAreRejected() {
        World world = new World();
        var first = new RollbackEntity(body(world, 1));
        var second = new RollbackEntity(body(world, 2));
        var saved = first.body().captureRollbackState();
        assertThrows(IllegalArgumentException.class, () -> second.body().restoreRollbackState(saved));
        assertThrows(IllegalStateException.class, () -> new RollbackEntity(first.body()));
        var failure = assertThrows(CompletionException.class,
                () -> CompletableFuture.runAsync(() -> first.setVelocity(new Vector(1, 0, 0))).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertThrows(IllegalArgumentException.class, () -> first.setVelocity(new Vector(Double.NaN, 0, 0)));
    }

    @Test void nativeAttributeTransitionsAndEffectivePotionOrderSurviveCheckpoints() {
        var entity = living(new World(), 1);
        entity.living().replaceAttributes(Map.of(Attribute.MAX_HEALTH.name(), 10D));
        // The native adapter can import an intermediate maximum-health change;
        // setHealth still obeys the public API's range check.
        assertEquals(20, entity.getHealth());
        assertEquals(10, entity.getMaxHealth());
        assertThrows(IllegalArgumentException.class, () -> entity.setHealth(11));
        entity.living().replacePotions(List.of(new PotionEffect(PotionEffectType.SPEED, 100, 1),
                new PotionEffect(PotionEffectType.SLOWNESS, 100, 1), new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 100, 1)));
        List<String> before = entity.getActivePotionEffects().stream().map(effect -> effect.getType().name()).toList();
        var checkpoint = capture(entity);
        entity.removePotionEffect(PotionEffectType.SPEED);
        entity.setHealth(5);
        checkpoint.restore();
        assertEquals(20, entity.getHealth());
        assertEquals(before, entity.getActivePotionEffects().stream().map(effect -> effect.getType().name()).toList());
    }

    @Test void discardingASpawnedVehicleCannotDetachAnAlreadyRestoredPassenger() {
        World world = new World();
        var registry = new RollbackEntityRegistry(world, 3);
        var originalVehicle = new RollbackEntity(body(world, 1));
        var passenger = new RollbackEntity(body(world, 2));
        registry.add(originalVehicle);
        registry.add(passenger);
        originalVehicle.addPassenger(passenger);
        // Restore the passenger/vehicle cells before the registry's membership cell.
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 10_000)
                .capture(List.of(passenger.body(), originalVehicle.body(), registry), List.of());
        var discardedVehicle = new RollbackEntity(body(world, 3));
        registry.add(discardedVehicle);
        discardedVehicle.addPassenger(passenger);
        checkpoint.restore();
        assertFalse(discardedVehicle.isValid());
        assertEquals(List.of(passenger), originalVehicle.getPassengers());
        assertTrue(passenger.body().insideVehicle());
        passenger.teleport(new Location(world, 5, 0, 0));
        assertTrue(originalVehicle.getPassengers().isEmpty(), "the passenger must still point to its restored vehicle");
    }

    private static RollbackStateGraph.Snapshot capture(Object root) {
        return new RollbackStateGraph(value -> false, field -> true, 10_000).capture(List.of(root), List.of());
    }
    private static RollbackEntityBody body(World world, int id) {
        return new RollbackEntityBody(new RollbackEntityBody.Identity(new UUID(0, id), id, "entity-" + id, EntityType.ZOMBIE),
                world, new RollbackEntityBody.Kinematics(new RollbackEntityBody.Pose(0, 0, 0, 0, 0),
                new RollbackEntityBody.Motion(0, 0, 0), BOX, 1.8, true, 0, false), BODY_RULES);
    }
    private static RollbackLivingEntity living(World world, int id) {
        // Test behavior only. Production must supply native damage/potion/attribute processing.
        var rules = new RollbackLivingState.Rules() {
            @Override public void damage(RollbackLivingState target, double amount, Entity source) {
                var v = target.vitals();
                target.vitals(new RollbackLivingState.Vitals(Math.max(0, v.health() - amount), v.absorption(), v.eyeHeight(),
                        v.remainingAir(), v.maximumAir(), v.maximumNoDamageTicks(), v.maximumNoDamageTicks(), amount, v.ai()));
            }
            @Override public boolean addPotion(RollbackLivingState target, PotionEffect effect, boolean force) {
                List<PotionEffect> values = new ArrayList<>(target.potions());
                values.removeIf(value -> value.getType().name().equals(effect.getType().name()));
                values.add(effect);
                target.replacePotions(values);
                return true;
            }
            @Override public void removePotion(RollbackLivingState target, PotionEffectType type) {
                target.replacePotions(target.potions().stream().filter(value -> !value.getType().name().equals(type.name())).toList());
            }
            @Override public void attribute(RollbackLivingState target, String name, double baseValue) {
                var values = new java.util.LinkedHashMap<>(target.attributes());
                values.put(name, baseValue);
                target.replaceAttributes(values);
            }
        };
        var state = new RollbackLivingState(body(world, id),
                new RollbackLivingState.Vitals(20, 0, 1.62, 300, 300, 0, 20, 0, true),
                Map.of(Attribute.MAX_HEALTH.name(), 20D), List.of(), new EmptyEquipment(), rules);
        return new RollbackLivingEntity(state);
    }
    /** Fixture has no equipment; never stands in for production inventory capture. */
    private static final class EmptyEquipment extends EntityEquipment implements RollbackStateCell<Void> {
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
}
