package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.util.MovementHandler;
import com.projectkorra.projectkorra.util.TempArmor;
import com.projectkorra.projectkorra.util.TempPotionEffect;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackStatusOwnershipTest {
    @Test void frozenStatusUpdatesPreserveOwnedStateAndAdvanceEveryUnrelatedHandler() throws Exception {
        var graph = new RollbackStateGraph(value -> false, field -> true, 10_000);
        var shared = new ArrayList<java.lang.reflect.Field>();
        for (var type : List.of(MovementHandler.class, TempPotionEffect.class, TempArmor.class, HorizontalVelocityTracker.class))
            shared.addAll(RollbackStateGraph.staticFields(type, field -> !field.getType().isPrimitive()));
        var outside = graph.capture(List.of(), shared);
        var scheduler = new RollbackLiveSchedulerTest.Backend();
        var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduler")) return scheduler;
                    throw new AssertionError(method);
                });
        try (var scope = Platform.using(platform)) {
            var owned = new StatusPlayer(51); var other = new StatusPlayer(52); var third = new StatusPlayer(53);
            var lease = RollbackLiveOwnership.prepare(Set.of(owned.getUniqueId()));
            try {
                var ownedMovement = movement(owned); var otherMovement = movement(other); var thirdMovement = movement(third);
                var effect = new PotionEffect(PotionEffectType.SPEED, 40, 1);
                new TempPotionEffect(owned, effect, 0); new TempPotionEffect(other, effect, 0);
                try (var clock = RollbackClock.at(0, 0, 50_000_000)) {
                    new TempArmor(owned, 1, null, new ItemStack[4]); new TempArmor(other, 1, null, new ItemStack[4]);
                }
                var targetTracker = tracker(owned, other); var sourceTracker = tracker(third, owned); var otherTracker = tracker(other, other);
                HorizontalVelocityTracker.instances.put(owned, targetTracker);
                HorizontalVelocityTracker.instances.put(third, sourceTracker);
                HorizontalVelocityTracker.instances.put(other, otherTracker);
                lease.acquire();
                MovementHandler.tickAll(); TempPotionEffect.progressAll(); TempArmor.cleanup(); HorizontalVelocityTracker.updateAll();
                assertTrue(MovementHandler.handlers.contains(ownedMovement));
                assertFalse(MovementHandler.handlers.contains(otherMovement)); assertFalse(MovementHandler.handlers.contains(thirdMovement));
                assertEquals(0, owned.resets); assertEquals(1, other.resets); assertEquals(1, third.resets);
                assertTrue(owned.effects.isEmpty()); assertEquals(List.of(effect), other.effects);
                assertTrue(TempArmor.hasTempArmor(owned)); assertFalse(TempArmor.hasTempArmor(other));
                assertEquals(0, targetTracker.updates); assertEquals(0, sourceTracker.updates); assertEquals(1, otherTracker.updates);
                lease.restoreAndRelease(() -> { });
                MovementHandler.tickAll(); TempPotionEffect.progressAll(); TempArmor.cleanup(); HorizontalVelocityTracker.updateAll();
                assertFalse(MovementHandler.handlers.contains(ownedMovement)); assertEquals(1, owned.resets);
                assertEquals(List.of(effect), owned.effects); assertEquals(1, other.effects.size());
                assertFalse(TempArmor.hasTempArmor(owned));
                assertEquals(1, targetTracker.updates); assertEquals(1, sourceTracker.updates); assertEquals(2, otherTracker.updates);
            } finally { lease.restoreAndRelease(() -> { }); }
        } finally { outside.restore(); }
    }
    private static MovementHandler movement(StatusPlayer player) {
        try (var clock = RollbackClock.at(0, 0, 50_000_000)) {
            var handler = new MovementHandler(player, null);
            handler.setResetTask(() -> player.resets++);
            MovementHandler.handlers.add(handler);
            return handler;
        }
    }
    private static TrackingVelocity tracker(Player entity, Player instigator) throws Exception {
        var tracker = new ObjenesisStd().newInstance(TrackingVelocity.class);
        var target = HorizontalVelocityTracker.class.getDeclaredField("entity"); target.setAccessible(true); target.set(tracker, entity);
        var source = HorizontalVelocityTracker.class.getDeclaredField("instigator"); source.setAccessible(true); source.set(tracker, instigator);
        return tracker;
    }
    private static final class TrackingVelocity extends HorizontalVelocityTracker {
        int updates;
        private TrackingVelocity() { super(null, null, 0, null); }
        @Override public void update() { updates++; }
    }
    private static final class StatusPlayer extends Player {
        final UUID id;
        final EntityEquipment equipment = new EntityEquipment();
        final List<PotionEffect> effects = new ArrayList<>();
        int resets;
        StatusPlayer(int id) { this.id = new UUID(0, id); }
        @Override public UUID getUniqueId() { return id; }
        @Override public EntityEquipment getEquipment() { return equipment; }
        @Override public Collection<PotionEffect> getActivePotionEffects() { return effects; }
        @Override public void addPotionEffect(PotionEffect effect) { effects.add(effect); }
    }
}
