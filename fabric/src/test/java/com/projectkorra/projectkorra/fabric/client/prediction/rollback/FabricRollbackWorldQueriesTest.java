package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import net.minecraft.SharedConstants;
import net.minecraft.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackWorldQueriesTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
    }

    @Test void nativeWorldQueriesRewindChangedTerrainAndEntityPositionsTogether() {
        var world = world();
        var target = entity(world, 1, 2);
        world.entities().add(target);
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]"), false);
        var origin = new Location(world, -2, 0.75, 0.5);
        var before = capture(world);
        var hit = world.rayTrace(origin, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, null);
        assertSame(target, hit.getHitEntity());
        assertEquals(1.7, hit.getHitPosition().getX(), 1e-9);
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=top]"), false);
        target.teleport(new Location(world, 3, 0, 0.5));
        var obstructed = world.rayTrace(origin, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, null);
        assertNull(obstructed.getHitEntity());
        assertEquals(0, obstructed.getHitPosition().getX(), 1e-9);
        before.restore();
        var restored = world.rayTrace(origin, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, null);
        assertSame(target, restored.getHitEntity());
        assertEquals(1.7, restored.getHitPosition().getX(), 1e-9);
        assertNull(world.rayTrace(origin, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, ignored -> false));
    }

    @Test void insideOriginAndZeroDistanceEntityQueriesUsePaperExitFaceSemantics() {
        var world = world();
        var target = entity(world, 1, 1);
        world.entities().add(target);
        for (double range : new double[]{0, 0.01, 2}) {
            var hit = world.rayTrace(new Location(world, 1, 0.75, 0.5), new Vector(1, 0, 0), range, FluidCollisionMode.NEVER, true, 0, null);
            assertSame(target, hit.getHitEntity());
            assertEquals(1.3, hit.getHitPosition().getX(), 1e-9);
        }
    }

    @Test void nativeHeightmapCountsWaterSkipsTorchAndReadsChangesWithoutAStaleCache() {
        var world = world();
        world.getBlockAt(0, 2, 0).setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        world.getBlockAt(0, 10, 0).setBlockData(data(Material.TORCH, "minecraft:torch"), false);
        var before = capture(world);
        var location = new Location(world, 0.2, 0, 0.8);
        assertEquals(3, world.getHighestBlockAt(location).getY());
        world.getBlockAt(0, 319, 0).setBlockData(data(Material.STONE, "minecraft:stone"), false);
        assertEquals(320, world.getHighestBlockAt(location).getY());
        assertEquals(Material.valueOf("VOID_AIR"), world.getHighestBlockAt(location).getType());
        before.restore();
        assertEquals(3, world.getHighestBlockAt(location).getY());
        world.getBlockAt(0, 2, 0).setBlockData(data(Material.AIR, "minecraft:air"), false);
        assertEquals(-64, world.getHighestBlockAt(location).getY());
    }

    @Test void changedRadiusFilteringAndStableTiesSelectLogicalViews() {
        var world = world();
        var second = entity(world, 2, 1);
        var first = entity(world, 1, 1);
        world.entities().add(second); world.entities().add(first);
        var origin = new Location(world, -2, 0.75, 0.5);
        assertSame(first, world.rayTrace(origin, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, null).getHitEntity());
        assertSame(second, world.rayTrace(origin, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, entity -> entity.getUniqueId().equals(second.getUniqueId())).getHitEntity());
        var offset = new Location(world, -2, 0.75, 1.1);
        assertNull(world.rayTrace(offset, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0, null));
        assertSame(first, world.rayTrace(offset, new Vector(1, 0, 0), 5, FluidCollisionMode.NEVER, true, 0.4, null).getHitEntity());
    }

    private static RollbackStateGraph.Snapshot capture(RollbackWorld world) {
        return new RollbackStateGraph(ignored -> false, ignored -> true, 10_000).capture(List.of(world), List.of());
    }
    private static RollbackEntity entity(RollbackWorld world, int id, double x) {
        var rules = new RollbackEntityBody.Rules() {
            @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody entity, RollbackEntityBody.Pose destination) { return destination; }
            @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return true; }
        };
        return new RollbackEntity(new RollbackEntityBody(new RollbackEntityBody.Identity(new UUID(0, id), id, "target", EntityType.ZOMBIE), world,
                new RollbackEntityBody.Kinematics(new RollbackEntityBody.Pose(x, 0, 0.5, 0, 0), new RollbackEntityBody.Motion(0, 0, 0),
                        new Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3), 1.8, true, 0, false), rules));
    }
    private static BlockData data(Material material, String state) {
        BlockData data = material.createBlockData(); data.setExactState(state); return data;
    }
    static RollbackWorld world() {
        var geometry = new FabricRollbackGeometryTest.LogicalWorld().shapes;
        var rules = (Rules) Proxy.newProxyInstance(FabricRollbackWorldQueriesTest.class.getClassLoader(), new Class<?>[]{Rules.class}, (proxy, method, args) -> {
            if (method.getName().equals("geometry")) return geometry.geometry((RollbackBlockStore) args[0], (Position) args[1], (BlockData) args[2]);
            throw new AssertionError("Native query test did not request block mutation service: " + method);
        });
        var items = (RollbackItems) Proxy.newProxyInstance(FabricRollbackWorldQueriesTest.class.getClassLoader(), new Class<?>[]{RollbackItems.class},
                (proxy, method, args) -> { throw new AssertionError("Query accessed item service"); });
        var actions = (RollbackWorld.Actions) Proxy.newProxyInstance(FabricRollbackWorldQueriesTest.class.getClassLoader(), new Class<?>[]{RollbackWorld.Actions.class},
                (proxy, method, args) -> { throw new AssertionError("Query accessed live action service"); });
        var empty = new Cell(data(Material.AIR, "minecraft:air"), null, Biome.DESERT, (byte) 0, 0.8, 0.4);
        var outside = new Cell(data(Material.valueOf("VOID_AIR"), "minecraft:void_air"), null, Biome.DESERT, (byte) 0, 0.8, 0.4);
        return new RollbackWorld(new RollbackWorld.Identity("native-query", RollbackWorld.Dimension.NORMAL, -64, 320),
                new RollbackWorld.Conditions(0, 0, "HARD", false, Set.of(new RollbackWorld.Chunk(0, 0))),
                new Bounds(-4, -64, -4, 5, 321, 5), Map.of(new Position(0, 320, 0), outside), empty,
                rules, 100, 100, items, new RollbackWorldQueries(geometry), actions);
    }
}
