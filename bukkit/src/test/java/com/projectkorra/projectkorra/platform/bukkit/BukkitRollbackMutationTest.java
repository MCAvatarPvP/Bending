package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BukkitRollbackMutationTest {
    @Test void ownedLiveBodiesRejectDirectWritesWhileOutsidersReplayAndReleasedBodiesProceed() {
        var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("scheduler")) throw new AssertionError(method);
                    var type = method.getReturnType();
                    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (scheduler, operation, values) -> {
                        if (operation.getName().equals("isPrimaryThread")) return true;
                        throw new AssertionError(operation);
                    });
                });
        var id = UUID.randomUUID(); var writes = new AtomicInteger(); var otherWrites = new AtomicInteger();
        var player = BukkitMC.player(nativePlayer(id, writes));
        var outsider = BukkitMC.player(nativePlayer(UUID.randomUUID(), otherWrites));
        try (var scope = Platform.using(platform)) {
            var lease = RollbackLiveOwnership.prepare(Set.of(id)); lease.acquire();
            try {
                player.setHealth(4); player.damage(3); player.damage(3, outsider);
                player.setVelocity(new Vector(1, 2, 3)); player.setFireTicks(40);
                player.setNoDamageTicks(8); player.setRemainingAir(20);
                assertFalse(player.addPotionEffect(null, true));
                assertEquals(0, writes.get());
                outsider.setHealth(4); outsider.setVelocity(new Vector(1, 0, 0)); outsider.setFireTicks(10);
                assertEquals(3, otherWrites.get());
                var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 100),
                        List.of(), List.of(), platform, () -> { });
                domain.call(() -> {
                    player.setHealth(5); player.setVelocity(new Vector(0, 1, 0)); player.setFireTicks(12);
                    return null;
                });
                assertEquals(3, writes.get());
                player.setHealth(6); assertEquals(3, writes.get());
                lease.restoreAndRelease(() -> { });
                player.setHealth(7); player.setVelocity(new Vector(0, 0, 1)); player.setFireTicks(8);
                assertEquals(6, writes.get());
            } finally { lease.restoreAndRelease(() -> { }); }
        }
    }
    private static org.bukkit.entity.Player nativePlayer(UUID id, AtomicInteger writes) {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return id;
                    if (Set.of("setHealth", "damage", "setVelocity", "setFireTicks", "setNoDamageTicks", "setRemainingAir").contains(method.getName())) {
                        writes.incrementAndGet(); return null;
                    }
                    throw new AssertionError(method);
                });
    }
}
