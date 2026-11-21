package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockReference;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackBlockViewsTest {
    private static RollbackGraphCodec codec(World world) {
        var catalog = new RollbackGraphCodec.Catalog(List.of(RollbackBlockReference.class), List.of(),
                List.of(new RollbackGraphCodec.Binding("world", World.class, world)));
        return new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000),
                ignored -> null, new PaperRollbackGraphViews());
    }
    @Test void nativeBlockViewsBecomePortableCoordinatesWithSymmetricSourceLookup() {
        UUID id = UUID.randomUUID();
        var nativeWorld = (org.bukkit.World) Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return id;
                    throw new AssertionError("Transfer accessed live terrain: " + method);
                });
        var nativeBlock = (org.bukkit.block.Block) Proxy.newProxyInstance(org.bukkit.block.Block.class.getClassLoader(),
                new Class<?>[]{org.bukkit.block.Block.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> nativeWorld;
                    case "getX" -> -12;
                    case "getY" -> 64;
                    case "getZ" -> 5;
                    default -> throw new AssertionError("Transfer accessed live block state: " + method);
                });
        var sourceWorld = BukkitMC.world(nativeWorld);
        var targetWorld = new World();
        Block first = BukkitMC.block(nativeBlock), second = BukkitMC.block(nativeBlock);
        byte[] wire = codec(sourceWorld).encode(List.of(first, second, new HashMap<>(Map.of(first, "key"))));
        var target = codec(targetWorld).decode(wire);
        var block = (Block) target.getFirst();
        assertInstanceOf(RollbackBlockReference.class, block);
        assertSame(block, target.get(1)); assertSame(targetWorld, block.getWorld());
        assertEquals(-12, block.getX()); assertEquals(64, block.getY()); assertEquals(5, block.getZ());
        assertNotEquals(first, block); assertNotEquals(block, first);
        var source = codec(sourceWorld).decode(wire);
        var restored = (Block) source.getFirst();
        assertEquals(first, restored); assertEquals(restored, first);
        assertEquals(first.hashCode(), restored.hashCode());
        assertEquals("key", ((Map<?, ?>) source.get(2)).get(second));
        assertEquals("native", new HashMap<>(Map.of(second, "native")).get(restored));
        assertThrows(IllegalArgumentException.class, () -> codec(sourceWorld).encode(List.of(nativeBlock)));
    }
}
