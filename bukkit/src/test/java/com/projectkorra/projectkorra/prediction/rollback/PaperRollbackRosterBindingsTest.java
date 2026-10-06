package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackRosterBindingsTest {
    @Test void actualBukkitPlayerWrappersResolveToTheSameImportedPlayer() {
        var nativeWorld = stub(org.bukkit.World.class, Map.of("getUID", UUID.randomUUID()));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var nativeA = stub(org.bukkit.entity.Player.class, Map.of("getUniqueId", a, "getWorld", nativeWorld, "isOnline", true));
        var nativeB = stub(org.bukkit.entity.Player.class, Map.of("getUniqueId", b, "getWorld", nativeWorld, "isOnline", true));
        var first = BukkitMC.player(nativeA); var repeated = BukkitMC.player(nativeA);
        assertNotSame(first, repeated);
        var source = new RollbackRosterBindings(BukkitMC.world(nativeWorld), List.of(first, BukkitMC.player(nativeB)));
        var privateWorld = new World();
        var privateA = player(a, privateWorld); var privateB = player(b, privateWorld);
        var target = new RollbackRosterBindings(privateWorld, List.of(privateA, privateB));
        var limits = new RollbackGraphCodec.Limits(100, 1000, 10000, 1000);
        var sender = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(), List.of(), source.bindings()), limits, source);
        var receiver = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(), List.of(), target.bindings()), limits, target);
        var copy = receiver.decode(sender.encode(List.of(first, repeated, BukkitMC.offline(nativeA), BukkitMC.world(nativeWorld))));
        assertSame(privateA, copy.get(0)); assertSame(copy.get(0), copy.get(1)); assertSame(copy.get(0), copy.get(2));
        assertSame(privateWorld, copy.get(3));
    }
    @Test void replacementWorldWithSameUuidCannotReuseTheEnrolledWorldView() {
        UUID worldId = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var original = stub(org.bukkit.World.class, Map.of("getUID", worldId));
        var replacement = stub(org.bukkit.World.class, Map.of("getUID", worldId));
        var valuesA = new HashMap<String, Object>(Map.of("getUniqueId", a, "getWorld", original, "isOnline", true));
        var nativeA = stub(org.bukkit.entity.Player.class, valuesA);
        var nativeB = stub(org.bukkit.entity.Player.class, Map.of("getUniqueId", b, "getWorld", original, "isOnline", true));
        var world = BukkitMC.world(original);
        var roster = new RollbackRosterBindings(world, List.of(BukkitMC.player(nativeA), BukkitMC.player(nativeB)));
        valuesA.put("getWorld", replacement);
        var current = BukkitMC.world(replacement);
        assertNotSame(world, current); assertSame(replacement, current.handle());
        assertSame(original, world.handle(), "Previously captured view must not be rebound");
        assertThrows(IllegalArgumentException.class, () -> roster.apply(BukkitMC.player(nativeA)));
    }

    private static Player player(UUID id, World world) {
        return new Player() {
            @Override public UUID getUniqueId() { return id; }
            @Override public World getWorld() { return world; }
        };
    }
    private static <T> T stub(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (values.containsKey(method.getName())) return values.get(method.getName());
            throw new AssertionError(method);
        }));
    }
}
