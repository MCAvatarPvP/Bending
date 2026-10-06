package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class RollbackWorldTest {
    @Test void publishingAWholeRosterRejectsLateIdentityAndCapacityFailuresWithoutPartialMembership() {
        var world = world(Map.of());
        var first = PrivateCombatRollbackTest.player(world, 1); var duplicate = PrivateCombatRollbackTest.player(world, 1);
        assertThrows(IllegalArgumentException.class, () -> world.entities().addAll(List.of(first, duplicate)));
        assertTrue(world.getEntities().isEmpty());
        var candidates = new ArrayList<Entity>();
        for (int id = 1; id <= 101; id++) candidates.add(PrivateCombatRollbackTest.player(world, id));
        assertThrows(IllegalStateException.class, () -> world.entities().addAll(candidates));
        assertTrue(world.getEntities().isEmpty());
        var second = PrivateCombatRollbackTest.player(world, 2);
        world.entities().addAll(List.of(second, first));
        assertEquals(List.of(first, second), world.getPlayers());
    }
    private static final RollbackBlockStore.Bounds BOUNDS = new RollbackBlockStore.Bounds(-8, -4, -8, 8, 12, 8);
    private static final RollbackBlockStore.Box BOX = new RollbackBlockStore.Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3);

    @Test void everyWorldApiMethodUsesLogicalStateOrAnExplicitService() throws Exception {
        for (var method : World.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) continue;
            assertEquals(RollbackWorld.class, RollbackWorld.class.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(), method.toString());
        }
        var world = world(Map.of());
        assertSame(world, world.handle());
        assertEquals("duel", world.getName());
        assertSame(World.Environment.NORMAL, world.getEnvironment());
        assertEquals(-64, world.getMinHeight());
        assertEquals(320, world.getMaxHeight());
        assertEquals("HARD", world.getDifficulty().name());
        assertEquals(0.7, world.getTemperature(0, 0, 0));
        assertEquals(0.3, world.getHumidity(0, 0, 0));
        // No Platform is installed: an inherited global player query would fail.
        assertTrue(world.getPlayers().isEmpty());
    }

    @Test void oneWorldRootRestoresTerrainMovementMembershipAndEnvironmentTogether() {
        var world = world(Map.of());
        var entity = entity(world, 1, new RollbackEntityBody.Pose(0, 0, 0, 0, 0));
        world.entities().add(entity);
        var block = world.getBlockAt(0, 0, 0);
        block.setType(Material.ICE, false);
        var saved = capture(world);
        block.setType(Material.WATER, false);
        entity.teleport(new Location(world, 7, 0, 0));
        assertTrue(world.getNearbyEntities(queryBox(), null).isEmpty());
        entity.remove();
        world.entities().pruneRemoved();
        var replacement = entity(world, 1, new RollbackEntityBody.Pose(0, 0, 0, 0, 0));
        world.entities().add(replacement);
        world.conditions(new RollbackWorld.Conditions(300, 48_300, "PEACEFUL", true, Set.of()));
        assertSame(Difficulty.PEACEFUL, world.getDifficulty());
        saved.restore();
        assertSame(block, world.getBlockAt(0, 0, 0));
        assertEquals(Material.ICE, block.getType());
        assertEquals(List.of(entity), world.getNearbyEntities(queryBox(), null));
        assertEquals(List.of(entity), world.getEntities());
        assertFalse(replacement.isValid());
        assertEquals(0, entity.getLocation().getX());
        assertEquals(100, world.getTime());
        assertEquals(24_100, world.getFullTime());
        assertFalse(world.hasStorm());
        assertTrue(world.isChunkLoaded(0, 0));
        assertEquals("HARD", world.getDifficulty().name());
    }

    @Test void dynamicSpawnDispatchRegistersBeforeConsumerAndRewindInvalidatesDiscardedViews() {
        var calls = new HashMap<String, Function<Object[], Object>>();
        calls.put("spawn", args -> {
            assertSame(Entity.class, args[2]);
            return entity((RollbackWorld) args[0], 2, (RollbackEntityBody.Pose) args[1]);
        });
        var world = world(calls);
        var saved = capture(world);
        Entity created = world.spawn(new Location(world, 1, 2, 3), Entity.class, value -> {
            assertSame(value, world.entities().get(value.getUniqueId()));
            value.setVelocity(new Vector(4, 0, 0));
        });
        assertEquals(4, created.getVelocity().getX());
        saved.restore();
        assertTrue(world.getEntities().isEmpty());
        assertFalse(created.isValid());
        calls.put("spawn", args -> entity((RollbackWorld) args[0], 3, (RollbackEntityBody.Pose) args[1]));
        assertThrows(ClassCastException.class, () -> world.spawn(new Location(world, 0, 0, 0), Player.class));
        assertTrue(world.getEntities().isEmpty(), "A bad typed factory must not register its result");
        calls.put("spawn", args -> new Entity());
        assertThrows(IllegalArgumentException.class, () -> world.spawn(new Location(world, 0, 0, 0), Entity.class));
    }

    @Test void raysUseRestoredTerrainValidateMembershipAndDetachReturnedPositions() {
        var calls = new HashMap<String, Function<Object[], Object>>();
        calls.put("rayTraceBlocks", args -> {
            var world = (RollbackWorld) args[0];
            var ray = (RollbackBlockRay) args[1];
            assertEquals(RollbackBlockRay.Fluids.SOURCE_ONLY, ray.fluids());
            assertEquals(4, ray.endX());
            if (world.getBlockAt(2, 0, 0).getType() == Material.AIR) return null;
            return new RollbackBlockRay.Hit(new RollbackBlockStore.Position(2, 0, 0), 2, 0.5, 0.5, BlockFace.WEST, false);
        });
        var world = world(calls);
        var location = new Location(world, 0, 0.5, 0.5);
        world.getBlockAt(2, 0, 0).setType(Material.ICE, false);
        var entity = entity(world, 1, new RollbackEntityBody.Pose(1, 0, 0, 0, 0));
        world.entities().add(entity);
        var saved = capture(world);
        world.getBlockAt(2, 0, 0).setType(Material.AIR, false);
        assertNull(world.rayTraceBlocks(location, new Vector(2, 0, 0), 4, FluidCollisionMode.valueOf("SOURCE_ONLY"), true));
        saved.restore();
        assertEquals(2, world.rayTraceBlocks(location, new Vector(2, 0, 0), 4, FluidCollisionMode.valueOf("SOURCE_ONLY"), true).getHitPosition().getX());
        var point = new Vector(1, 0.5, 0.5);
        calls.put("rayTrace", args -> new RayTraceResult(point, entity));
        var hit = world.rayTrace(location, new Vector(1, 0, 0), 4, FluidCollisionMode.NEVER, true, 0, null);
        point.setY(99);
        assertEquals(1, hit.getHitPosition().getX());
        assertSame(entity, hit.getHitEntity());
        assertThrows(IllegalStateException.class, () -> world.rayTrace(location, new Vector(1, 0, 0), 4, FluidCollisionMode.NEVER, true, 0, null));
        calls.put("rayTrace", args -> new RayTraceResult(new Vector(1, 0.5, 0.5), new Entity()));
        assertThrows(IllegalArgumentException.class, () -> world.rayTrace(location, new Vector(1, 0, 0), 4, FluidCollisionMode.NEVER, true, 0, null));
        calls.put("rayTrace", args -> {
            @SuppressWarnings("unchecked") var filter = (Predicate<Entity>) args[3];
            filter.test(new Entity());
            fail("An external view must be rejected before the caller filter sees it");
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> world.rayTrace(location, new Vector(1, 0, 0), 4, FluidCollisionMode.NEVER, true, 0,
                value -> { fail("Foreign entity escaped service boundary"); return true; }));
    }

    @Test void outputCallbacksReceiveCapturedPositionAndServicesCanBufferRewindableEffects() {
        var calls = new HashMap<String, Function<Object[], Object>>();
        var effects = new ArrayList<String>();
        calls.put("sound", args -> { effects.add("sound:" + ((RollbackEntityBody.Pose) args[1]).x() + ":" + args[3]); return null; });
        calls.put("particle", args -> {
            // The service contract encodes the payload now, without retaining this mutable input.
            effects.add("particle:" + ((int[]) args[8])[0]); return null;
        });
        var world = world(calls);
        var saved = new RollbackStateGraph(value -> false, field -> true, 10_000).capture(List.of(world, effects), List.of());
        var location = new Location(world, 2, 0, 0);
        world.playSound(location, "test:sound", 1, 1);
        location.setX(99);
        int[] payload = {7};
        world.spawnParticle(Particle.BLOCK, new Location(world, 0, 0, 0), 1, 0, 0, 0, 0, payload);
        payload[0] = 99;
        assertEquals(List.of("sound:2.0:test:sound", "particle:7"), effects);
        saved.restore();
        assertTrue(effects.isEmpty());
    }

    @Test void uncapturedCoordinatesForeignWorldsAndThreadsAbortBeforeServicesRun() {
        var world = world(Map.of());
        var foreign = new Location(new World(), 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> world.getBlockAt(foreign));
        assertThrows(IllegalArgumentException.class, () -> world.getNearbyLivingEntities(foreign, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> world.getNearbyLivingEntities(new Location(world, 0, 0, 0), -1, 1));
        assertThrows(IllegalStateException.class, () -> world.getBlockAt(100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> world.restoreRollbackState(world(Map.of()).captureRollbackState()));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(world::getEntities).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test void defaultExplosionKeepsPaperBlockBreakingAndOnlyUsesLogicalState() {
        var calls = new HashMap<String, Function<Object[], Object>>();
        calls.put("explosion", args -> {
            assertEquals(false, args[3]);
            assertEquals(true, args[4]); // Paper World.createExplosion(Location, float) default.
            ((RollbackWorld) args[0]).getBlockAt(0, 0, 0).setType(Material.AIR, false);
            return true;
        });
        var world = world(calls);
        world.getBlockAt(0, 0, 0).setType(Material.STONE, false);
        var saved = capture(world);
        assertTrue(world.createExplosion(new Location(world, 0, 0, 0), 2));
        assertEquals(Material.AIR, world.getBlockAt(0, 0, 0).getType());
        saved.restore();
        assertEquals(Material.STONE, world.getBlockAt(0, 0, 0).getType());
    }

    private static RollbackStateGraph.Snapshot capture(Object value) { return RollbackInventoryTest.capture(value); }
    private static BoundingBox queryBox() { return new BoundingBox(new Vector(-1, -1, -1), new Vector(1, 2, 1)); }
    private static RollbackEntity entity(World world, int id, RollbackEntityBody.Pose position) {
        var rules = new RollbackEntityBody.Rules() {
            @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody entity, RollbackEntityBody.Pose destination) { return destination; }
            @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return true; }
        };
        return new RollbackEntity(new RollbackEntityBody(new RollbackEntityBody.Identity(new UUID(0, id), id, "fixture", EntityType.ZOMBIE), world,
                new RollbackEntityBody.Kinematics(position, new RollbackEntityBody.Motion(0, 0, 0), BOX, 1.8, true, 0, false), rules));
    }
    static RollbackWorld world(Map<String, Function<Object[], Object>> handlers) {
        // Explicit query/output fixtures. No native physics or live event delivery is claimed by this suite.
        var rules = (RollbackWorld.Rules) Proxy.newProxyInstance(RollbackWorldTest.class.getClassLoader(), new Class<?>[]{RollbackWorld.Rules.class},
                (proxy, method, args) -> {
                    var handler = handlers.get(method.getName());
                    if (handler == null) throw new AssertionError("Unexpected world service: " + method.getName());
                    return handler.apply(args);
                });
        var blocks = new RollbackBlockStore.Rules() {
            @Override public RollbackBlockStore.Geometry geometry(RollbackBlockStore terrain, RollbackBlockStore.Position position, BlockData data) {
                var cube = new RollbackBlockStore.Box(0, 0, 0, 1, 1, 1);
                boolean solid = data.getMaterial() != Material.AIR;
                return new RollbackBlockStore.Geometry(solid, false, !solid, false, cube, solid ? List.of(cube) : List.of(), List.of());
            }
            @Override public byte legacyData(RollbackBlockStore terrain, RollbackBlockStore.Position position, BlockData data) { return 0; }
            @Override public void physics(RollbackBlockStore terrain, RollbackBlockStore.Position position) { throw new AssertionError("Unexpected fixture physics"); }
            @Override public Collection<ItemStack> drops(RollbackBlockStore terrain, RollbackBlockStore.Position position, ItemStack tool) { throw new AssertionError("Unexpected fixture drops"); }
            @Override public boolean breakNaturally(RollbackBlockStore terrain, RollbackBlockStore.Position position, ItemStack tool) { throw new AssertionError("Unexpected fixture break"); }
        };
        return new RollbackWorld(new RollbackWorld.Identity("duel", RollbackWorld.Dimension.NORMAL, -64, 320),
                new RollbackWorld.Conditions(100, 24_100, "HARD", false, Set.of(new RollbackWorld.Chunk(0, 0))), BOUNDS,
                Map.of(), new RollbackBlockStore.Cell(Material.AIR.createBlockData(), null, Biome.DESERT, (byte) 7, 0.7, 0.3),
                blocks, 100, 100, RollbackInventoryTest.ITEMS, rules);
    }
}
