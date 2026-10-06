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
        var catalog = new RollbackGraphCodec.Catalog(List.of(RollbackBlockReference.class, RollbackBlockSnapshot.class, AddonBlock.class, com.projectkorra.projectkorra.platform.mc.block.data.BlockData.class, com.projectkorra.projectkorra.platform.mc.block.data.Levelled.class), List.of(Material.class),
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
    @Test void savedContentsTransferAndRestoreWithoutRewindingCurrentEnvironment() {
        var source = new Arena(Material.CHEST); var target = new Arena(Material.DIRT);
        var position = new Position(0, 0, 0);
        byte[] tile = {1, 2, 3};
        source.terrain.replace(position, new Cell(Material.CHEST.createBlockData(), tile, Biome.DESERT, (byte) 3, 0.8, 0.4), false);
        var snapshot = source.getBlockAt(0, 0, 0).getState();
        source.getBlockAt(0, 0, 0).setType(Material.AIR, false);
        var copies = codec(target).decode(codec(source).encode(List.of(snapshot, snapshot)));
        var copy = (BlockState) copies.getFirst();
        assertSame(copy, copies.get(1)); assertTrue(copy.hasBlockEntity());
        assertEquals(Material.CHEST, copy.getType());
        assertFalse(copy.update(false, false)); assertEquals(Material.DIRT, target.getBlockAt(0, 0, 0).getType());
        var before = target.terrain.captureRollbackState();
        assertTrue(copy.update(true, false));
        assertArrayEquals(tile, target.terrain.cell(position).blockEntity());
        assertEquals((byte) 7, target.getBlockAt(0, 0, 0).getLightLevel());
        assertEquals(Material.AIR, source.getBlockAt(0, 0, 0).getType());
        target.terrain.restoreRollbackState(before);
        assertEquals(Material.DIRT, target.getBlockAt(0, 0, 0).getType());
        assertTrue(copy.update(true, false));
        assertArrayEquals(tile, target.terrain.cell(position).blockEntity());
        var fluid = new com.projectkorra.projectkorra.platform.mc.block.data.Levelled(Material.WATER);
        fluid.setLevel(5); fluid.setExactState("minecraft:water[level=5]");
        var detached = new RollbackBlockSnapshot(target.getBlockAt(0, 0, 0), fluid, null);
        fluid.setLevel(7);
        var data = detached.getBlockData();
        assertEquals(5, ((com.projectkorra.projectkorra.platform.mc.block.data.Levelled) data).getLevel());
        assertEquals("minecraft:water[level=5]", data.getExactState());
        ((com.projectkorra.projectkorra.platform.mc.block.data.Levelled) data).setLevel(9);
        assertEquals(5, ((com.projectkorra.projectkorra.platform.mc.block.data.Levelled) detached.getBlockData()).getLevel());
    }}
