package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackBlockReferenceTest {
    private static final class Arena extends World {
        final RollbackBlockStore terrain;
        Arena(Material material) {
            Rules rules = (Rules) Proxy.newProxyInstance(Rules.class.getClassLoader(), new Class<?>[]{Rules.class},
                    (proxy, method, args) -> {
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                        if (method.getName().equals("geometry")) return new Geometry(true, false, false, false,
                                new Box(0, 0, 0, 1, 1, 1), List.of(new Box(0, 0, 0, 1, 1, 1)), List.of());
                        throw new AssertionError(method);
                    });
            terrain = new RollbackBlockStore(this, new Bounds(0, 0, 0, 3, 1, 1), Map.of(),
                    new Cell(material.createBlockData(), null, Biome.DESERT, (byte) 7, 0.8, 0.4), rules, 20);
        }
        @Override public Block getBlockAt(int x, int y, int z) { return terrain.block(x, y, z); }
    }
    private static final class AddonBlock extends Block { int extra = 23; }
    private static RollbackGraphCodec codec(World world) {
        var catalog = new RollbackGraphCodec.Catalog(List.of(RollbackBlockReference.class, AddonBlock.class), List.of(),
                List.of(new RollbackGraphCodec.Binding("world", World.class, world)));
        return new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000),
                ignored -> null, new RollbackGraphViews());
    }
    @Test void transferredReferencesFollowTargetTerrainThroughMutationAndRewind() {
        var source = new Arena(Material.STONE); var target = new Arena(Material.DIRT);
        Block block = source.getBlockAt(0, 0, 0);
        var roots = List.of(block, block, new HashMap<>(Map.of(block, "held")), new AddonBlock());
        var copied = codec(target).decode(codec(source).encode(roots));
        var reference = (Block) copied.get(0);
        assertInstanceOf(RollbackBlockReference.class, reference);
        assertSame(reference, copied.get(1)); assertSame(target, reference.getWorld());
        assertEquals(Material.DIRT, reference.getType()); assertEquals((byte) 7, reference.getLightLevel());
        assertEquals(Biome.DESERT, reference.getBiome()); assertEquals(1, reference.getCollisionBoxes().size());
        var fresh = target.getBlockAt(0, 0, 0);
        assertEquals(reference, fresh); assertEquals(fresh, reference);
        assertEquals("held", ((Map<?, ?>) copied.get(2)).get(fresh));
        assertEquals("reverse", new HashMap<>(Map.of(fresh, "reverse")).get(reference));
        assertNotEquals(block, reference); assertNotEquals(reference, block);
        var before = target.terrain.captureRollbackState();
        reference.setType(Material.STONE, false);
        assertEquals(Material.STONE, fresh.getType()); assertEquals(Material.STONE, block.getType());
        reference.getRelative(BlockFace.EAST).setType(Material.AIR, false);
        assertEquals(Material.AIR, target.getBlockAt(1, 0, 0).getType());
        assertEquals(Material.STONE, source.getBlockAt(1, 0, 0).getType());
        target.terrain.restoreRollbackState(before);
        assertEquals(Material.DIRT, reference.getType());
        assertEquals(Material.DIRT, reference.getRelative(1, 0, 0).getType());
        var addon = (AddonBlock) copied.get(3);
        assertEquals(23, addon.extra); assertNotEquals(reference, addon); assertNotEquals(addon, reference);
        assertThrows(IllegalStateException.class, () -> reference.getRelative(-1, 0, 0));
        var roundTrip = (Block) codec(source).decode(codec(target).encode(List.of(reference))).getFirst();
        assertEquals(block, roundTrip); assertEquals(roundTrip, block);
    }
}
