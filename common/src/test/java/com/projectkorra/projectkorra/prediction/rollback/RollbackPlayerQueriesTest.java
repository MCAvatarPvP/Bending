package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerQueriesTest {
    @Test void sightUsesExactCurrentEyeEndpointsAndBoundsDistanceBeforeReadingTerrain() {
        var world = RollbackWorldTest.world(Map.of("rayTraceBlocks", args -> {
            assertEquals(3, args.length, "Sight supplies the viewing entity for native collision context");
            var ray = (RollbackBlockRay) args[1];
            assertEquals(2.125, ray.endX()); assertEquals(2.75, ray.endY()); assertEquals(.375, ray.endZ());
            return null;
        }));
        var viewer = player(world, 1, -2, .75, .5, 0); var target = player(world, 2, 2.125, 2.75, .375, 0);
        assertTrue(viewer.hasLineOfSight(target));
        move(target, 130, .75, .5, 0); assertFalse(viewer.hasLineOfSight(target));
        var sameId = PrivateCombatRollbackTest.player(world, 2);
        assertThrows(IllegalArgumentException.class, () -> viewer.hasLineOfSight(sameId));
        move(target, -2, .75, .5, 0); assertTrue(viewer.hasLineOfSight(target));
    }
    @Test void exactTargetKeepsNativeBlockCoordinateAndUsesCurrentEyePose() {
        var world = RollbackWorldTest.world(Map.of("rayTraceBlocks", args -> {
            var ray = (RollbackBlockRay) args[1];
            assertEquals(.75, ray.y(), 1e-9); assertEquals(-1, ray.x()); assertEquals(1, ray.endX(), 1e-9);
            assertEquals(RollbackBlockRay.Fluids.NEVER, ray.fluids()); assertTrue(ray.ignorePassable());
            // Contact on the east face of block -1 has x=0: flooring the hit is wrong.
            return new RollbackBlockRay.Hit(new RollbackBlockStore.Position(-1, 0, 0), 0, .75, .5, BlockFace.EAST, false);
        }));
        var player = player(world, 1, -1, .75, .5, -90);
        assertSame(world.getBlockAt(-1, 0, 0), player.getTargetBlockExact(2));
        assertThrows(IllegalArgumentException.class, () -> player.getTargetBlockExact(-1));
    }

    @Test void legacyTargetSamplingPreservesOriginTransparencyAndRewindsChangedTerrain() {
        var world = RollbackWorldTest.world(Map.of()); var player = player(world, 1, .5, .75, .5, 0);
        world.getBlockAt(0, 0, 0).setType(Material.STONE, false);
        world.getBlockAt(0, 0, 2).setType(Material.ICE, false);
        // Existing BukkitMC's single-target sampler checks the origin; its last-two sampler skips it.
        assertSame(world.getBlockAt(0, 0, 0), player.getTargetBlock(null, 3));
        assertEquals(List.of(world.getBlockAt(0, 0, 1), world.getBlockAt(0, 0, 2)), player.getLastTwoTargetBlocks(null, 3));
        assertSame(world.getBlockAt(0, 0, 2), player.getTargetBlock(Set.of(Material.STONE), 3));
        var before = RollbackInventoryTest.capture(world);
        world.getBlockAt(0, 0, 2).setType(Material.AIR, false);
        assertSame(world.getBlockAt(0, 0, 3), player.getTargetBlock(Set.of(Material.STONE), 3));
        before.restore();
        assertSame(world.getBlockAt(0, 0, 2), player.getTargetBlock(Set.of(Material.STONE), 3));
        assertEquals(List.of(world.getBlockAt(0, 0, 0), world.getBlockAt(0, 0, 0)), player.getLastTwoTargetBlocks(null, 0));
        assertThrows(IllegalStateException.class, () -> player.getTargetBlock(Set.of(Material.STONE, Material.ICE), 100));
    }

    @Test void nearbyUsesExpandedNativeBoundsExcludesSelfAndRewindsPositionAndMembership() {
        var world = RollbackWorldTest.world(Map.of()); var first = player(world, 1, 0, 1.62, 0, 0);
        var second = player(world, 2, 1.4, 1.62, 0, 0);
        assertEquals(List.of(second), first.getNearbyEntities(1, 0, 0), "Expand the player's box, not a point at their feet");
        var before = RollbackInventoryTest.capture(world);
        move(second, 5, 1.62, 0, 0);
        assertTrue(first.getNearbyEntities(1, 0, 0).isEmpty());
        before.restore(); assertEquals(List.of(second), first.getNearbyEntities(1, 0, 0));
        second.remove(); assertTrue(first.getNearbyEntities(1, 0, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> first.getNearbyEntities(-1, 0, 0));
        assertThrows(IllegalStateException.class, () -> PrivateCombatRollbackTest.player(new World(), 3).getNearbyEntities(1, 1, 1));
        first.remove(); assertThrows(IllegalStateException.class, () -> first.getTargetBlock(null, 0));
    }

    private static RollbackPlayer player(RollbackWorld world, int id, double x, double eyeY, double z, float yaw) {
        var player = PrivateCombatRollbackTest.player(world, id); move(player, x, eyeY, z, yaw); world.entities().add(player); return player;
    }
    private static void move(RollbackPlayer player, double x, double eyeY, double z, float yaw) {
        var old = player.body().kinematics();
        player.body().kinematics(new RollbackEntityBody.Kinematics(new RollbackEntityBody.Pose(x, eyeY - player.getEyeHeight(), z, yaw, 0),
                old.velocity(), old.bounds(), old.height(), old.onGround(), old.fallDistance(), old.velocityChanged()));
    }
}
