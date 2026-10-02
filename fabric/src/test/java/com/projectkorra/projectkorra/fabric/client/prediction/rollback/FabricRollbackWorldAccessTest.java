package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntity;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityRegistry;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackWorldAccessTest {
    @BeforeAll static void bootstrap() {
        FabricRollbackTestRegistry.bootstrap();
    }

    private static final RollbackEntityBody.Rules MOTION_RULES = new RollbackEntityBody.Rules() {
        @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
        @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
    };

    @Test void logicalMovementAndNativePhysicsShareOneStoreAndRewindWithTerrain() {
        var scene = new NativeSimulation(); var view = scene.view;
        var registry = new RollbackEntityRegistry(scene.queries.logical, 4); registry.add(view);
        scene.player.use(player -> { player.fallDistance = 1.23456789012345; return null; });
        var initial = view.body().kinematics();
        var saved = new RollbackStateGraph(value -> false, field -> true, 20_000).capture(List.of(registry), List.of());
        view.setVelocity(new Vector(0.2, 0.3, 0.4));
        scene.player.use(player -> {
            assertEquals(new Vec3d(0.2, 0.3, 0.4), player.getVelocity());
            assertEquals(initial.fallDistance(), player.fallDistance); assertTrue(player.knockedBack);
            assertFalse(player.velocityDirty, "The owner velocity update flag is distinct from native impulse tracking");
            return null;
        });
        var destination = new Location(scene.queries.logical, -0.5, 1.25, 0.5);
        destination.setYaw(90); destination.setPitch(-20);
        assertTrue(view.teleport(destination));
        scene.player.use(player -> {
            assertEquals(-0.5, player.getX()); assertEquals(1.25, player.getY());
            assertEquals(90, player.getYaw()); assertEquals(-20, player.getPitch());
            player.travel(new Vec3d(0, 0, 1));
            assertEquals(player.getX(), view.getLocation().getX()); assertEquals(player.getY(), view.getLocation().getY());
            assertEquals(player.getBoundingBox().maxY, view.getBoundingBox().getMaxY());
            view.setFallDistance(3); assertEquals(3, player.fallDistance);
            assertThrows(IllegalStateException.class, scene.player::captureRollbackState);
            return null;
        });
        assertEquals(List.of(view), List.copyOf(registry.nearby(view.getBoundingBox(), null)));
        scene.queries.block(Material.ICE, "minecraft:ice");
        saved.restore();
        assertEquals(initial, view.body().kinematics()); assertEquals(initial, scene.player.readKinematics());
        assertEquals(Material.STONE, scene.queries.logical.getBlockAt(0, 0, 0).getType());
        assertEquals(List.of(view), List.copyOf(registry.nearby(view.getBoundingBox(), null)));
    }

    @Test void nativeMovementRejectsForeignIdentityDimensionsAndCrossThreadWrites() {
        var scene = new NativeSimulation(); var initial = scene.player.readKinematics();
        var foreign = new RollbackEntityBody.Identity(new UUID(0, 999), 999, "foreign", com.projectkorra.projectkorra.platform.mc.entity.EntityType.PLAYER);
        assertThrows(IllegalArgumentException.class, () -> RollbackEntityBody.nativeBacked(foreign, scene.queries.logical, scene.player, MOTION_RULES));
        var invalid = new RollbackEntityBody.Kinematics(initial.pose(), new RollbackEntityBody.Motion(8, 8, 8),
                initial.bounds(), 100, initial.onGround(), initial.fallDistance(), true);
        assertThrows(IllegalArgumentException.class, () -> scene.view.body().kinematics(invalid));
        assertEquals(initial, scene.player.readKinematics());
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> scene.player.writeKinematics(initial)).join());
        scene.player.use(player -> { player.setId(player.getId() + 1); return null; });
        assertThrows(IllegalStateException.class, scene.view::getLocation);
    }

    @Test void nativeWorldReadsAndSupportingBlocksFollowRestoredTerrain() {
        var queries = new Queries();
        var access = new FabricRollbackWorldAccess(queries);
        World world = access.world();
        assertInstanceOf(net.minecraft.server.world.ServerWorld.class, world);
        queries.block(Material.STONE, "minecraft:stone");
        var saved = capture(access);
        var player = new Player(world);
        player.setPosition(0.5, 1, 0.5);
        assertEquals(Blocks.STONE.getDefaultState(), world.getBlockState(BlockPos.ORIGIN));
        assertTrue(world.getFluidState(BlockPos.ORIGIN).isEmpty());
        assertEquals(BlockPos.ORIGIN, world.findSupportingBlockPos(player, new Box(0.2, 0.99, 0.2, 0.8, 1, 0.8)).orElseThrow());
        assertFalse(world.isSpaceEmpty(player, new Box(0.2, 0, 0.2, 0.8, 1, 0.8)));
        queries.block(Material.AIR, "minecraft:air");
        assertTrue(world.findSupportingBlockPos(player, new Box(0.2, 0.99, 0.2, 0.8, 1, 0.8)).isEmpty());
        assertTrue(world.isSpaceEmpty(player, new Box(0.2, 0, 0.2, 0.8, 1, 0.8)));
        saved.restore();
        assertEquals(Blocks.STONE.getDefaultState(), world.getBlockState(BlockPos.ORIGIN));
        assertFalse(world.isSpaceEmpty(player, new Box(0.2, 0, 0.2, 0.8, 1, 0.8)));
        assertThrows(IllegalStateException.class, () -> world.getBlockState(new BlockPos(20, 0, 0)));
        assertThrows(IllegalStateException.class, () -> world.setBlockState(BlockPos.ORIGIN, Blocks.AIR.getDefaultState()));
        assertThrows(IllegalStateException.class, () -> world.getBlockCollisions(player, new Box(0, 0, 0, 100_000, 100_000, 100_000)));
    }

    @Test void actualNativeTravelUsesTheCapturedSurfaceAndGravityWithoutALiveWorld() {
        var queries = new Queries();
        var access = new FabricRollbackWorldAccess(queries);
        queries.block(Material.STONE, "minecraft:stone");
        var saved = capture(access);
        var stone = travel(access.world());
        queries.block(Material.ICE, "minecraft:ice");
        var ice = travel(access.world());
        assertEquals(1, stone.getY(), 1e-9);
        assertEquals(-0.0784, stone.getVelocity().y, 1e-7);
        assertTrue(stone.getZ() > 0.5);
        assertNotEquals(stone.getVelocity().z, ice.getVelocity().z);
        saved.restore();
        var replayed = travel(access.world());
        assertEquals(stone.getEntityPos(), replayed.getEntityPos());
        assertEquals(stone.getVelocity(), replayed.getVelocity());
        // Fresh native players here isolate this world's query behavior. This is not
        // yet a native player-state checkpoint or a complete entity/world tick.
    }

    @Test void nativePlayerMovementStateCanBeRestoredBetweenTravelSteps() {
        var queries = new Queries();
        var access = new FabricRollbackWorldAccess(queries);
        queries.block(Material.STONE, "minecraft:stone");
        var player = travel(access.world());
        var item = new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE, 4);
        item.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("saved"));
        player.getInventory().setStack(0, item);
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SPEED, 80, 1));
        double savedSpeed = player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.MOVEMENT_SPEED);
        var nativeState = new FabricRollbackNativePlayerState(player, access, 20_000);
        var saved = new RollbackStateGraph(value -> false, field -> true, 1_000).capture(List.of(nativeState), List.of());
        double expectedRandom = player.getRandom().nextGaussian();
        player.travel(new Vec3d(0, 0, 1));
        var expectedPosition = player.getEntityPos();
        var expectedVelocity = player.getVelocity();
        item.setCount(2);
        item.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("changed"));
        player.setHealth(7);
        player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED);
        player.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MOVEMENT_SPEED).setBaseValue(0.7);
        saved.restore();
        assertEquals(expectedRandom, player.getRandom().nextGaussian());
        assertSame(item, player.getInventory().getStack(0));
        assertEquals(4, item.getCount());
        assertEquals("saved", item.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME).getString());
        assertEquals(20, player.getHealth());
        assertEquals(80, player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED).getDuration());
        assertEquals(1, player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED).getAmplifier());
        assertEquals(savedSpeed, player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.MOVEMENT_SPEED));
        player.travel(new Vec3d(0, 0, 1));
        assertEquals(expectedPosition, player.getEntityPos());
        assertEquals(expectedVelocity, player.getVelocity());
    }

    @Test void lateInputReplaysNativeTravelFromItsPreviousTransientState() {
        var first = new NativeSimulation();
        var second = new NativeSimulation();
        var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
        UUID participant = new UUID(0, 7);
        var onTime = new RollbackEngine<>(first, Map.of(participant, false), limits, 0);
        var delayed = new RollbackEngine<>(second, Map.of(participant, false), limits, 0);
        onTime.submit(participant, 1, true);
        onTime.advance();
        var expected = onTime.advance().head().effects();
        delayed.advance();
        var predicted = delayed.advance().head().effects();
        assertNotEquals(expected, predicted);
        delayed.submit(participant, 1, true);
        assertEquals(expected, delayed.reconcile().head().effects());
        assertEquals(onTime.advance().head().effects(), delayed.advance().head().effects());
    }

    @Test void nativePlayerCheckpointGuardsRejectForeignReplicasThreadsAndNestedOperations() {
        var first = new NativeSimulation();
        var second = new NativeSimulation();
        var checkpoint = first.player.captureRollbackState();
        assertThrows(IllegalArgumentException.class, () -> second.player.restoreRollbackState(checkpoint));
        assertThrows(IllegalStateException.class, () -> first.player.use(player -> first.player.captureRollbackState()));
        assertThrows(IllegalStateException.class, () -> first.player.use(player -> first.player.use(nested -> null)));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(first.player::captureRollbackState).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertThrows(IllegalArgumentException.class, () -> new FabricRollbackNativePlayerState(new Player(first.world.world()), second.world, 20_000));
        assertNotNull(first.player.captureRollbackState());
    }

    @Test void nativeCrossPlayerReferencesRestoreThroughTheOwningWorldRoster() {
        var queries = new Queries();
        var world = new FabricRollbackWorldAccess(queries);
        var first = new Player(world.world()); var second = new Player(world.world(), new UUID(0, 8));
        var firstState = new FabricRollbackNativePlayerState(first, world, 20_000);
        var secondState = new FabricRollbackNativePlayerState(second, world, 20_000);
        first.setAttacker(second); second.setAttacker(first);
        var graph = new RollbackStateGraph(value -> false, field -> true, 100_000);
        var saved = graph.capture(List.of(firstState), List.of());
        first.setAttacker(null); second.setAttacker(null);
        first.setHealth(7); second.setHealth(8);
        saved.restore();
        assertSame(second, first.getAttacker()); assertSame(first, second.getAttacker());
        assertEquals(20, first.getHealth()); assertEquals(20, second.getHealth());
        secondState.captureRollbackState();
        assertThrows(IllegalStateException.class, () -> new FabricRollbackNativePlayerState(new Player(world.world()), world, 20_000));
        var foreign = new Player(new FabricRollbackWorldAccess(new Queries()).world());
        first.setAttacker(foreign);
        assertThrows(IllegalStateException.class, firstState::captureRollbackState);
    }

    @Test void privateUuidLookupsRejectDuplicateIdentitiesAndExcludeUnownedOrRemovedPlayers() {
        var access = new FabricRollbackWorldAccess(new Queries());
        var world = access.world(); var first = new Player(world);
        var state = new FabricRollbackNativePlayerState(first, access, 20_000);
        var duplicateUuid = new Player(world);
        var duplicateId = new Player(world, new UUID(0, 8)); duplicateId.setId(first.getId());
        assertThrows(IllegalArgumentException.class, () -> new FabricRollbackNativePlayerState(duplicateUuid, access, 20_000));
        assertThrows(IllegalArgumentException.class, () -> new FabricRollbackNativePlayerState(duplicateId, access, 20_000));
        assertSame(first, world.getPlayerAnyDimension(first.getUuid()));
        assertSame(first, world.getEntityAnyDimension(first.getUuid()));
        assertSame(first, world.getPlayerByUuid(first.getUuid()));
        assertNull(world.getPlayerAnyDimension(duplicateId.getUuid()));
        assertNull(world.getEntityAnyDimension(duplicateId.getUuid()));
        assertNull(world.getPlayerByUuid(duplicateId.getUuid()));
        var reference = net.minecraft.entity.LazyEntityReference.<net.minecraft.entity.LivingEntity>ofUUID(first.getUuid());
        assertSame(first, reference.getEntityByClass(world, net.minecraft.entity.LivingEntity.class));
        var saved = state.captureRollbackState();
        first.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        assertNull(world.getPlayerAnyDimension(first.getUuid()));
        assertNull(world.getEntityAnyDimension(first.getUuid()));
        assertNull(world.getPlayerByUuid(first.getUuid()));
        assertNull(reference.getEntityByClass(world, net.minecraft.entity.LivingEntity.class));
        state.restoreRollbackState(saved);
        assertSame(first, reference.getEntityByClass(world, net.minecraft.entity.LivingEntity.class));
    }

    @Test void completeNativePlayerTickCanAdvanceAndRewindTransientState() {
        var simulation = new NativeSimulation();
        var saved = simulation.snapshot();
        simulation.player.use(player -> { player.forwardSpeed = 1; return null; });
        simulation.player.tick();
        Motion expected = simulation.player.use(player -> {
            assertEquals(1, player.age);
            assertTrue(player.getZ() > 0.5);
            assertEquals(-0.0784, player.getVelocity().y, 1e-6);
            var velocity = player.getVelocity();
            return new Motion(player.getX(), player.getY(), player.getZ(), velocity.x, velocity.y, velocity.z, player.isOnGround(), player.fallDistance);
        });
        simulation.snapshot(); // Checkpoint transient state created by the full base-player tick.
        var outputs = List.copyOf(simulation.queries.waypoints);
        assertFalse(outputs.isEmpty());
        simulation.restore(saved);
        simulation.player.use(player -> { assertEquals(0, player.age); player.forwardSpeed = 1; return null; });
        simulation.player.tick();
        assertEquals(expected, simulation.player.use(player -> {
            var velocity = player.getVelocity();
            return new Motion(player.getX(), player.getY(), player.getZ(), velocity.x, velocity.y, velocity.z, player.isOnGround(), player.fallDistance);
        }));
        assertEquals(outputs, simulation.queries.waypoints);
    }

    @Test void lateInputReplaysBasePlayerTicksIncludingMovementAndTimers() {
        var first = new NativeSimulation(true); var second = new NativeSimulation(true);
        var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
        UUID participant = new UUID(0, 7);
        var onTime = new RollbackEngine<>(first, Map.of(participant, false), limits, 0);
        var late = new RollbackEngine<>(second, Map.of(participant, false), limits, 0);
        onTime.submit(participant, 1, true);
        onTime.advance(); late.advance();
        var expected = onTime.advance().head().effects();
        assertNotEquals(expected, late.advance().head().effects());
        late.submit(participant, 1, true);
        assertEquals(expected, late.reconcile().head().effects());
        assertEquals(onTime.advance().head().effects(), late.advance().head().effects());
        assertEquals(first.queries.waypoints, second.queries.waypoints);
        first.player.use(player -> { assertEquals(3, player.age); return null; });
        second.player.use(player -> { assertEquals(3, player.age); return null; });
    }

    @Test void nativeEnvironmentAndLoadedRegionQueriesReadRestoredConditions() {
        var queries = new Queries();
        queries.biome = net.minecraft.registry.BuiltinRegistries.createWrapperLookup()
                .getOrThrow(net.minecraft.registry.RegistryKeys.BIOME).getOrThrow(net.minecraft.world.biome.BiomeKeys.PLAINS);
        queries.block(Material.STONE, "minecraft:stone");
        var access = new FabricRollbackWorldAccess(queries);
        var world = access.world();
        var saved = capture(access);
        assertFalse(world.hasRain(new BlockPos(0, 1, 0)));
        queries.raining = true;
        assertTrue(world.hasRain(new BlockPos(0, 1, 0)));
        queries.skyVisible = false;
        assertFalse(world.hasRain(new BlockPos(0, 1, 0)));
        queries.fastLava = true;
        assertTrue(world.getEnvironmentAttributes().getAttributeValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA_GAMEPLAY));
        assertTrue(((net.minecraft.world.WorldView) world).getEnvironmentAttributes()
                .getAttributeValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA_GAMEPLAY));
        assertTrue(world.isRegionLoaded(-4, -4, 4, 4));
        queries.loaded = false;
        assertFalse(world.isRegionLoaded(-4, -4, 4, 4));
        saved.restore();
        assertFalse(world.hasRain(new BlockPos(0, 1, 0)));
        assertFalse(world.getEnvironmentAttributes().getAttributeValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA_GAMEPLAY));
        assertTrue(world.isRegionLoaded(-4, -4, 4, 4));
        assertThrows(IllegalStateException.class, () -> world.isRegionLoaded(-1_000_000, -1_000_000, 1_000_000, 1_000_000));
        assertThrows(IllegalStateException.class, () -> world.getServer().getPlayerManager());
        assertThrows(IllegalStateException.class, () -> world.getScoreboard().addTeam("live-write"));
    }

    @Test void nativeTickRejectsUnownedTargetsBeforeMutatingTheirTimers() {
        var simulation = new NativeSimulation();
        var unowned = new Player(simulation.world.world());
        assertThrows(IllegalArgumentException.class, () -> ((net.minecraft.server.world.ServerWorld) simulation.world.world()).tickEntity(unowned));
        assertEquals(0, unowned.age);
        var foreign = new Player(new FabricRollbackWorldAccess(new Queries()).world());
        assertThrows(IllegalArgumentException.class, () -> ((net.minecraft.server.world.ServerWorld) simulation.world.world()).tickEntity(foreign));
        assertEquals(0, foreign.age);
    }

    private record Motion(double x, double y, double z, double vx, double vy, double vz, boolean grounded, double fallDistance) { }
    private static final class NativeSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, Motion> {
        final Queries queries = new Queries();
        final FabricRollbackWorldAccess world = new FabricRollbackWorldAccess(queries);
        final FabricRollbackNativePlayerState player;
        final RollbackEntity view;
        final RollbackStateGraph graph = new RollbackStateGraph(value -> false, field -> true, 1_000);
        final boolean fullTick;
        NativeSimulation() { this(false); }
        NativeSimulation(boolean tick) {
            this.fullTick = tick;
            queries.block(Material.STONE, "minecraft:stone");
            var replica = new Player(world.world());
            replica.setPosition(0.5, 1, 0.5);
            replica.setYaw(0); replica.setPitch(0);
            replica.setOnGround(true);
            replica.getRandom().setSeed(55);
            player = new FabricRollbackNativePlayerState(replica, world, 20_000);
            view = new RollbackEntity(RollbackEntityBody.nativeBacked(player.identity(), queries.logical, player, MOTION_RULES));
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(view), List.of()); }
        @Override public void restore(RollbackStateGraph.Snapshot snapshot) { snapshot.restore(); }
        @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Motion> effects) {
            if (fullTick) {
                player.use(replica -> {
                    replica.sidewaysSpeed = inputs.values().iterator().next() ? 1 : 0;
                    replica.forwardSpeed = 1;
                    return null;
                });
                player.tick();
            }
            player.use(replica -> {
                if (!fullTick) replica.travel(new Vec3d(inputs.values().iterator().next() ? 1 : 0, 0, 1));
                var state = view.body().kinematics(); var p = state.pose(); var v = state.velocity();
                effects.emit(new Motion(p.x(), p.y(), p.z(), v.x(), v.y(), v.z(), state.onGround(), state.fallDistance()));
                return null;
            });
        }
    }

    private static Player travel(World world) {
        var player = new Player(world);
        player.setPosition(0.5, 1, 0.5);
        player.setYaw(0);
        player.setPitch(0);
        player.setOnGround(true);
        player.travel(new Vec3d(0, 0, 1));
        return player;
    }
    private static RollbackStateGraph.Snapshot capture(FabricRollbackWorldAccess access) {
        return new RollbackStateGraph(value -> false, field -> true, 1_000).capture(List.of(access), List.of());
    }
    private static final class Player extends PlayerEntity {
        Player(World world) { this(world, new UUID(0, 7)); }
        Player(World world, UUID id) { super(world, new GameProfile(id, "movement")); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
    static final class Queries implements FabricRollbackWorldAccess.Queries<Queries.Conditions> {
        record Waypoint(FabricRollbackWorldAccess.WaypointAction action, UUID player, double x, double y, double z) { }
        record Conditions(boolean raining, boolean skyVisible, boolean loaded, boolean fastLava, boolean cancelFlight, boolean cancelGlide,
                          net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biome,
                          long time, net.minecraft.world.Difficulty difficulty,
                          Map<net.minecraft.world.rule.GameRule<?>, Object> rules) { }
        record Event(String event, UUID entity, double x, double y, double z) { }
        final FabricRollbackGeometryTest.LogicalWorld logical;
        final WorldBorder border = new WorldBorder();
        final net.minecraft.scoreboard.Scoreboard scoreboard = new net.minecraft.scoreboard.Scoreboard();
        final java.util.ArrayList<Waypoint> waypoints = new java.util.ArrayList<>();
        final java.util.ArrayList<FabricRollbackWorldAccess.Output> outputs = new java.util.ArrayList<>();
        final java.util.ArrayList<Event> events = new java.util.ArrayList<>();
        final com.projectkorra.projectkorra.prediction.rollback.RollbackRandom soundRandom = new com.projectkorra.projectkorra.prediction.rollback.RollbackRandom(31);
        final net.minecraft.util.math.random.Random worldRandom = net.minecraft.util.math.random.Random.create(17);
        final Map<net.minecraft.world.rule.GameRule<?>, Object> rules = new java.util.HashMap<>();
        long time;
        net.minecraft.world.Difficulty difficulty = net.minecraft.world.Difficulty.NORMAL;
        boolean raining, fastLava, cancelFlight, cancelGlide;
        boolean loaded = true, skyVisible = true;
        net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biome;
        Queries() {
            this(new RollbackBlockStore.Bounds(-4, -4, -4, 5, 5, 5));
        }
        Queries(RollbackBlockStore.Bounds bounds) {
            logical = new FabricRollbackGeometryTest.LogicalWorld(bounds);
            border.setSize(100);
            var defaults = new net.minecraft.world.rule.GameRules(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES);
            defaults.streamRules().forEach(rule -> rules.put(rule, defaults.getValue(rule)));
        }
        void block(Material material, String exact) {
            var data = new BlockData(material); data.setExactState(exact);
            logical.getBlockAt(0, 0, 0).setBlockData(data, false);
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            return FabricRollbackGeometry.decode(logical.terrain.cell(new RollbackBlockStore.Position(pos.getX(), pos.getY(), pos.getZ())).data());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { if (getBlockState(pos).hasBlockEntity()) throw new AssertionError("No block entity fixture"); return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getBottomY() { return -64; }
        @Override public BlockView getChunkAsView(int x, int z) { return this; }
        @Override public boolean isChunkLoaded(int x, int z) { return loaded && x >= -1 && x <= 0 && z >= -1 && z <= 0; }
        @Override public net.minecraft.world.attribute.EnvironmentAttributeAccess environmentAttributes() {
            return new net.minecraft.world.attribute.EnvironmentAttributeAccess() {
                @Override @SuppressWarnings("unchecked") public <V> V getAttributeValue(net.minecraft.world.attribute.EnvironmentAttribute<V> attribute) {
                    return attribute == net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA_GAMEPLAY ? (V) Boolean.valueOf(fastLava) : attribute.getDefaultValue();
                }
                @Override public <V> V getAttributeValue(net.minecraft.world.attribute.EnvironmentAttribute<V> attribute, Vec3d pos,
                                                        net.minecraft.world.attribute.WeightedAttributeList pool) {
                    getBlockState(BlockPos.ofFloored(pos));
                    return getAttributeValue(attribute);
                }
            };
        }
        @Override public boolean isRaining() { return raining; }
        @Override public boolean isSkyVisible(BlockPos pos) { getBlockState(pos); return skyVisible; }
        @Override public BlockPos topPosition(net.minecraft.world.Heightmap.Type type, BlockPos pos) {
            for (int y = 4; y >= -4; y--) {
                if (type.getBlockPredicate().test(getBlockState(new BlockPos(pos.getX(), y, pos.getZ())))) {
                    return new BlockPos(pos.getX(), y + 1, pos.getZ());
                }
            }
            return new BlockPos(pos.getX(), -4, pos.getZ());
        }
        @Override public net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biome(BlockPos pos) {
            getBlockState(pos);
            return java.util.Objects.requireNonNull(biome, "fixture biome");
        }
        @Override public int seaLevel() { return 63; }
        @Override public List<? extends Entity> otherEntities(Entity except, Box bounds) { return List.of(); }
        @Override public net.minecraft.scoreboard.Scoreboard scoreboard() { return scoreboard; }
        @Override public net.minecraft.registry.DynamicRegistryManager.Immutable registries() { return FabricRollbackTestRegistry.simulationRegistries(); }
        @Override public long time() { return time; }
        @Override public net.minecraft.world.Difficulty difficulty() { return difficulty; }
        @Override @SuppressWarnings("unchecked") public <T> T gameRule(net.minecraft.world.rule.GameRule<T> rule) { return (T) java.util.Objects.requireNonNull(rules.get(rule)); }
        @Override public long nextSoundSeed() { return soundRandom.nextLong(); }
        @Override public net.minecraft.util.math.random.Random random() { return worldRandom; }
        @Override public void output(FabricRollbackWorldAccess.Output output) { outputs.add(output); }
        @Override public void gameEvent(net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.event.GameEvent> event,
                                        Vec3d position, net.minecraft.world.event.GameEvent.Emitter emitter) {
            // This fixture has no sculk or other causal game-event listeners.
            events.add(new Event(event.getKey().orElseThrow().getValue().toString(),
                    emitter.sourceEntity() == null ? null : emitter.sourceEntity().getUuid(), position.x, position.y, position.z));
        }
        @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity) {
            waypoints.add(new Waypoint(action, entity.getUuid(), entity.getX(), entity.getY(), entity.getZ()));
        }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public boolean flightAllowed(PlayerEntity player, boolean flying, boolean cancelled) { return flightAllowed(player.getUuid(), flying, cancelled); }
        boolean flightAllowed(UUID player, boolean flying, boolean cancelled) {
            events.add(new Event("flight:" + flying + ":" + cancelled, player, 0, 0, 0));
            return !cancelled && !cancelFlight;
        }
        @Override public boolean glideAllowed(PlayerEntity player, boolean gliding, boolean cancelled) { return glideAllowed(player.getUuid(), gliding, cancelled); }
        boolean glideAllowed(UUID player, boolean gliding, boolean cancelled) {
            events.add(new Event("glide:" + gliding + ":" + cancelled, player, 0, 0, 0));
            return !cancelled && !cancelGlide;
        }
        @Override public List<VoxelShape> getEntityCollisions(Entity except, Box box) { return List.of(); }
        @Override public Conditions captureRollbackState() { return new Conditions(raining, skyVisible, loaded, fastLava, cancelFlight, cancelGlide, biome, time, difficulty, Map.copyOf(rules)); }
        @Override public void restoreRollbackState(Conditions state) {
            raining = state.raining; skyVisible = state.skyVisible; loaded = state.loaded; fastLava = state.fastLava; biome = state.biome;
            cancelFlight = state.cancelFlight;
            cancelGlide = state.cancelGlide;
            time = state.time; difficulty = state.difficulty; rules.clear(); rules.putAll(state.rules);
        }
        @Override public List<?> rollbackReferences() { return List.of(logical.terrain, scoreboard, waypoints, outputs, events, soundRandom, worldRandom); }
    }
}
