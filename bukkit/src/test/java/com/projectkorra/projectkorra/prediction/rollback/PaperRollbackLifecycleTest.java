package com.projectkorra.projectkorra.prediction.rollback;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackLifecycleTest {
    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName", "toString" -> id.toString();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private static PlayerCommandPreprocessEvent command(Player player) {
        return new PlayerCommandPreprocessEvent(player, "/test", Set.of());
    }
    @Test void commandRestoresWholeRosterAndOutsidersDoNotStopIt() {
        var lifecycle = new PaperRollbackLifecycle(() -> true);
        var a = player(UUID.randomUUID()); var b = player(UUID.randomUUID()); var outsider = player(UUID.randomUUID());
        var lease = new AtomicReference<PaperRollbackLifecycle.Lease>(); var stops = new AtomicInteger();
        lease.set(lifecycle.reserve(Set.of(a.getUniqueId(), b.getUniqueId()), () -> { stops.incrementAndGet(); lease.get().release(); }));
        assertThrows(IllegalStateException.class, () -> lifecycle.reserve(Set.of(b.getUniqueId()), () -> { }));
        lifecycle.command(command(outsider)); assertEquals(0, stops.get());
        var event = command(a); lifecycle.command(event);
        assertFalse(event.isCancelled()); assertEquals(1, stops.get());
        lifecycle.command(command(b)); assertEquals(1, stops.get());
        lifecycle.close(); lifecycle.close(); assertEquals(1, stops.get());
    }
    @Test void failedAndIncompleteTeardownCancelMutationAndRetainBothParticipants() {
        var lifecycle = new PaperRollbackLifecycle(() -> true);
        var a = player(UUID.randomUUID()); var b = player(UUID.randomUUID());
        var failure = new AtomicReference<Runnable>(() -> { throw new IllegalArgumentException("restore failed"); });
        var lease = lifecycle.reserve(Set.of(a.getUniqueId(), b.getUniqueId()), () -> failure.get().run());
        var teleport = new PlayerTeleportEvent(a, new Location(null, 0, 1, 0), new Location(null, 8, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.teleport(teleport)); assertTrue(teleport.isCancelled());
        failure.set(() -> { });
        var event = command(b); assertThrows(IllegalStateException.class, () -> lifecycle.command(event)); assertTrue(event.isCancelled());
        failure.set(lease::release);
        var retry = command(a); lifecycle.command(retry); assertFalse(retry.isCancelled());
        lifecycle.close();
    }
    @Test void mountStopsBothAffectedDuelsAndPrivateSimulationDoesNotStopLiveOwnership() {
        var lifecycle = new PaperRollbackLifecycle(() -> true);
        var a = player(UUID.randomUUID()); var b = player(UUID.randomUUID());
        var first = new AtomicReference<PaperRollbackLifecycle.Lease>(); var second = new AtomicReference<PaperRollbackLifecycle.Lease>();
        var stopped = new ArrayList<String>();
        first.set(lifecycle.reserve(Set.of(a.getUniqueId()), () -> { stopped.add("first"); first.get().release(); }));
        second.set(lifecycle.reserve(Set.of(b.getUniqueId()), () -> { stopped.add("second"); second.get().release(); }));
        try (var clock = RollbackClock.at(1, 1, 1, 1)) { lifecycle.command(command(a)); }
        assertTrue(stopped.isEmpty());
        var mount = new EntityMountEvent(a, b); lifecycle.mount(mount);
        assertFalse(mount.isCancelled()); assertEquals(List.of("first", "second"), stopped);
        lifecycle.close();
    }
    @Test void nativeEffectsAreSuppressedOnlyAfterHandoffAndNeverInsideReplay() {
        var lifecycle = new PaperRollbackLifecycle(() -> true);
        var a = player(UUID.randomUUID()); var b = player(UUID.randomUUID()); var outsider = player(UUID.randomUUID());
        var lease = lifecycle.reserve(Set.of(a.getUniqueId(), b.getUniqueId()), () -> fail("Effects must not stop the duel"));
        var before = new org.bukkit.event.entity.EntityRegainHealthEvent(a, 3, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.CUSTOM);
        lifecycle.regainHealth(before); assertFalse(before.isCancelled());
        lease.suspendNativeEffects();
        var damageSource = (org.bukkit.damage.DamageSource) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{org.bukkit.damage.DamageSource.class}, (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        for (var participant : List.of(a, b)) {
            var damage = new org.bukkit.event.entity.EntityDamageEvent(participant,
                    org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL, damageSource, 4);
            lifecycle.damage(damage); assertTrue(damage.isCancelled()); assertEquals(4, damage.getDamage());
            var potion = new org.bukkit.event.entity.EntityPotionEffectEvent(participant, null, null,
                    org.bukkit.event.entity.EntityPotionEffectEvent.Cause.PLUGIN,
                    org.bukkit.event.entity.EntityPotionEffectEvent.Action.CLEARED, false);
            lifecycle.potionEffect(potion); assertTrue(potion.isCancelled());
            var healing = new org.bukkit.event.entity.EntityRegainHealthEvent(participant, 3, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.CUSTOM);
            lifecycle.regainHealth(healing); assertTrue(healing.isCancelled());
            var fire = new org.bukkit.event.entity.EntityCombustEvent(participant, 4F);
            lifecycle.combust(fire); assertTrue(fire.isCancelled());
            var food = new org.bukkit.event.entity.FoodLevelChangeEvent(participant, 10, null);
            lifecycle.food(food); assertTrue(food.isCancelled());
            var air = new org.bukkit.event.entity.EntityAirChangeEvent(participant, 20);
            lifecycle.air(air); assertTrue(air.isCancelled());
        }
        var outsiderHealing = new org.bukkit.event.entity.EntityRegainHealthEvent(outsider, 3, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.CUSTOM);
        lifecycle.regainHealth(outsiderHealing); assertFalse(outsiderHealing.isCancelled());
        var replayHealing = new org.bukkit.event.entity.EntityRegainHealthEvent(a, 3, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.CUSTOM);
        try (var clock = RollbackClock.at(1, 1, 1, 1)) { lifecycle.regainHealth(replayHealing); }
        assertFalse(replayHealing.isCancelled());
        lease.release();
        var after = new org.bukkit.event.entity.EntityRegainHealthEvent(b, 3, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.CUSTOM);
        lifecycle.regainHealth(after); assertFalse(after.isCancelled());
        assertThrows(IllegalStateException.class, lease::suspendNativeEffects);
        lifecycle.close();
    }

    @Test void shutdownAttemptsEveryDuelAndRetainsFailedOwnerForRetry() {
        var lifecycle = new PaperRollbackLifecycle(() -> true);
        var first = new AtomicReference<PaperRollbackLifecycle.Lease>(); var second = new AtomicReference<PaperRollbackLifecycle.Lease>();
        var fail = new AtomicBoolean(true); var stops = new AtomicInteger();
        first.set(lifecycle.reserve(Set.of(UUID.randomUUID()), () -> { if (fail.get()) throw new IllegalStateException("retry"); first.get().release(); }));
        second.set(lifecycle.reserve(Set.of(UUID.randomUUID()), () -> { stops.incrementAndGet(); second.get().release(); }));
        assertThrows(IllegalStateException.class, lifecycle::close); assertEquals(1, stops.get());
        assertThrows(IllegalStateException.class, () -> lifecycle.reserve(Set.of(UUID.randomUUID()), () -> { }));
        fail.set(false); lifecycle.close(); lifecycle.close(); assertEquals(1, stops.get());
    }
}
