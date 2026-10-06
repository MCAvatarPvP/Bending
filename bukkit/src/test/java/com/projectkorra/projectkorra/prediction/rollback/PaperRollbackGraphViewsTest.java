package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackGraphViewsTest {
    private static final RollbackGraphCodec.Limits LIMITS = new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000);
    private static final class AddonLocation extends Location { int extra = 71; }

    @Test void nativeLocationValuesPreserveAliasesAndHashIterationWithoutRetainingTheLiveBackingObject() {
        UUID worldId = UUID.randomUUID();
        var nativeWorld = (org.bukkit.World) Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return worldId;
                    throw new AssertionError(method);
                });
        var liveWorld = BukkitMC.world(nativeWorld); var privateWorld = new World();
        var sender = codec(liveWorld, new PaperRollbackGraphViews());
        var receiver = codec(privateWorld, () -> ignored -> null);
        var nativeLocation = new org.bukkit.Location(nativeWorld, -1.25, 64.5, 8.75, 91.5f, -34f);
        var first = BukkitMC.location(nativeLocation); var second = BukkitMC.location(nativeLocation);
        var keyed = new HashMap<Location, Integer>();
        for (int i = 0; i < 24; i++) keyed.put(BukkitMC.location(new org.bukkit.Location(nativeWorld, i * 1.5, 64, -i)), i);
        var addon = new AddonLocation();
        var roots = List.of(first, second, keyed, addon, BukkitMC.location(new org.bukkit.Location(null, 0, 0, 0)));
        byte[] wire = sender.encode(roots);
        var restored = (Location) sender.decode(wire).getFirst();
        assertEquals(first, restored); assertEquals(restored, first);
        assertEquals(first.hashCode(), restored.hashCode());
        assertEquals("found", new HashMap<>(Map.of(restored, "found")).get(second));
        var copy = receiver.decode(wire);
        var location = (Location) copy.get(0);
        assertSame(location, copy.get(1)); assertEquals(Location.class, location.getClass());
        assertSame(privateWorld, location.getWorld()); assertEquals(first.hashCode(), location.hashCode());
        assertNotEquals(first, location); assertNotEquals(location, first);
        assertEquals(-1.25, location.getX()); assertEquals(91.5f, location.getYaw()); assertEquals(-34f, location.getPitch());
        assertEquals(new ArrayList<>(keyed.values()), new ArrayList<>(((Map<?, ?>) copy.get(2)).values()));
        assertEquals(71, ((AddonLocation) copy.get(3)).extra, "Addon state must not be flattened as a native wrapper");
        assertNull(((Location) copy.get(4)).getWorld());
        nativeLocation.setX(99);
        assertEquals(-1.25, location.getX());
        var next = (Location) receiver.decode(sender.encode(roots)).getFirst();
        assertEquals(99, next.getX(), "A new capture must not reuse stale projected values");
        next.setX(123); assertEquals(99, nativeLocation.getX());
        assertThrows(IllegalArgumentException.class, () -> sender.encode(List.of(nativeLocation)));
        var explicit = receiver.decode(sender.encode(List.of(first), value -> value == first
                ? RollbackStateTransfer.Replacement.fromProjection("explicit") : null));
        assertEquals(List.of("explicit"), explicit);
    }
    private static RollbackGraphCodec codec(World world,
            java.util.function.Supplier<java.util.function.Function<Object, RollbackStateTransfer.Replacement>> views) {
        var catalog = new RollbackGraphCodec.Catalog(List.of(Location.class, AddonLocation.class), List.of(),
                List.of(new RollbackGraphCodec.Binding("world", World.class, world)));
        return new RollbackGraphCodec(catalog, LIMITS, ignored -> null, views);
    }
}
