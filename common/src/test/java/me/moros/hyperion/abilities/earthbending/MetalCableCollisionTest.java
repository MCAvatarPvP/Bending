package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetalCableCollisionTest {
    private final AbilityWorld world = new AbilityWorld();

    @AfterEach void cleanup() throws Exception { world.close(); }

    @Test void fastDiagonalSweepFindsTheFirstThinShapeInEitherDirection() {
        var block = world.getBlockAt(5, 65, 5);
        block.setType(Material.STONE);
        block.setCollisionBoxes(new BoundingBox(new Vector(5.45, 65, 5.45), new Vector(5.55, 66, 5.55)));
        var forward = MetalCableCollision.trace(new Location(world, 0.5, 65.5, 0.5), new Vector(15, 0, 15), 0, 0, null);
        var backward = MetalCableCollision.trace(new Location(world, 15.5, 65.5, 15.5), new Vector(-15, 0, -15), 0, 0, null);
        assertSame(block, forward.block());
        assertSame(block, backward.block());
        assertEquals(5.45, forward.position().getX(), 1.0E-8);
        assertEquals(5.55, backward.position().getZ(), 1.0E-8);
    }

    @Test void hookPassesOverSlabInsteadOfAttachingToEmptyHalf() {
        var block = world.getBlockAt(0, 65, 5);
        block.setType(Material.STONE);
        block.setCollisionBoxes(new BoundingBox(new Vector(0, 65, 5), new Vector(1, 65.5, 6)));
        assertNull(MetalCableCollision.trace(new Location(world, 0.5, 65.75, 0), new Vector(0, 0, 10), 0, 0, null));
        var hit = MetalCableCollision.trace(new Location(world, 0.5, 65.25, 0), new Vector(0, 0, 10), 0, 0, null);
        assertSame(block, hit.block());
    }

    @Test void sweptBlockVolumeStopsBeforeItsCenterEntersTerrain() {
        var block = world.getBlockAt(0, 65, 5);
        block.setType(Material.STONE);
        var hit = MetalCableCollision.trace(new Location(world, 0.5, 65.5, 0), new Vector(0, 0, 10), 0.47, 0.47, null);
        assertEquals(4.53, hit.position().getZ(), 1.0E-8);
    }

    @Test void nearestEntityWinsUntilABlockOccludesIt() {
        var far = world.player(0.5, 64, 10);
        var near = world.player(0.5, 64, 5);
        var start = new Location(world, 0.5, 65, 0);
        var delta = new Vector(0, 0, 15);
        var hit = MetalCableCollision.trace(start, delta, 0, 0.22, entity -> true);
        assertSame(near, hit.entity());
        world.getBlockAt(0, 65, 3).setType(Material.STONE);
        hit = MetalCableCollision.trace(start, delta, 0, 0.22, entity -> true);
        assertNull(hit.entity());
        assertEquals(3, hit.position().getZ(), 1.0E-8);
    }

    @Test void collisionChecksFenceHeightAboveItsBlockCell() {
        var block = world.getBlockAt(0, 64, 5);
        block.setType(Material.STONE);
        block.setCollisionBoxes(new BoundingBox(new Vector(0.375, 64, 5.375), new Vector(0.625, 65.5, 5.625)));
        var hit = MetalCableCollision.trace(new Location(world, 0.5, 65.25, 0), new Vector(0, 0, 10), 0, 0, null);
        assertSame(block, hit.block());
    }
}
