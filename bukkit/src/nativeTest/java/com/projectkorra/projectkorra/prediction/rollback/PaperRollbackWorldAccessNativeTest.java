package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Native read/travel boundary only; this does not start a server or run a complete entity tick. */
class PaperRollbackWorldAccessNativeTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void finalLoadedBlockAndMoonrisePaletteReadsFollowRestoredLogicalTerrain() {
        var queries = new Queries();
        var access = new PaperRollbackWorldAccess(queries, 4);
        var level = (ServerLevel) access.world();
        assertFalse(level.isClientSide());
        assertEquals(-64, level.getMinY());
        assertEquals(319, level.getMaxY());
        assertEquals(24, level.getSectionsCount());
        assertEquals(Blocks.STONE.defaultBlockState(), level.getBlockStateIfLoaded(BlockPos.ZERO));
        var chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(0, 0);
        var section = chunk.getSections()[level.getSectionIndex(0)];
        assertFalse(section.hasOnlyAir());
        assertTrue(section.moonrise$hasSpecialCollidingBlocks());
        var saved = queries.logical.terrain.captureRollbackState();
        queries.block(Material.ICE, "minecraft:ice");
        assertEquals(Blocks.ICE.defaultBlockState(), section.states.get(0));
        assertEquals(Blocks.ICE.defaultBlockState(), level.getBlockStateIfLoaded(BlockPos.ZERO));
        queries.logical.terrain.restoreRollbackState(saved);
        assertEquals(Blocks.STONE.defaultBlockState(), section.states.get(0));
        assertEquals(Blocks.STONE.defaultBlockState(), level.getBlockStateIfLoaded(BlockPos.ZERO));
        assertSame(chunk, level.getChunkSource().getChunkAtIfLoadedImmediately(0, 0));
        assertEquals(Blocks.VOID_AIR.defaultBlockState(), level.getBlockStateIfLoaded(new BlockPos(0, -65, 0)));
        assertThrows(IllegalStateException.class, () -> level.getBlockState(new BlockPos(100, 0, 0)));
        assertThrows(IllegalStateException.class, () -> level.setBlock(BlockPos.ZERO, Blocks.AIR.defaultBlockState(), 3));
    }

    @Test void capturedEnvironmentAndLoadedRegionAreRestoredAndBounded() {
        var queries = new Queries();
        var access = new PaperRollbackWorldAccess(queries, 4);
        var level = access.world();
        var saved = new RollbackStateGraph(value -> false, field -> true, 1_000).capture(List.of(access), List.of());
        assertTrue(level.hasChunksAt(-1, -1, 1, 1));
        assertFalse(level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA));
        queries.loaded = false; queries.fastLava = true;
        assertFalse(level.hasChunksAt(-1, -1, 1, 1));
        assertTrue(level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA));
        assertTrue(level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA, BlockPos.ZERO));
        assertTrue(level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA, Vec3.ZERO));
        saved.restore();
        assertTrue(level.hasChunksAt(-1, -1, 1, 1));
        assertFalse(level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA));
        assertThrows(IllegalStateException.class, () -> level.hasChunksAt(-100, -100, 100, 100));
    }

    @Test void actualNativeTravelUsesLogicalGroundFrictionAndRestoredTerrain() throws Exception {
        onTickThread(() -> {
            var queries = new Queries();
            var access = new PaperRollbackWorldAccess(queries, 4);
            var saved = queries.logical.terrain.captureRollbackState();
            Motion stone = travel(access);
            assertTrue(stone.position.z > 0.5);
            assertEquals(-0.0784, stone.velocity.y, 1e-6);
            queries.block(Material.ICE, "minecraft:ice");
            Motion ice = travel(access);
            assertNotEquals(stone.velocity.z, ice.velocity.z);
            queries.logical.terrain.restoreRollbackState(saved);
            assertEquals(stone, travel(access));
            return null;
        });
    }

    @Test void nativeEntityCollisionQueriesFilterOnceAndRejectForeignWorldsAndThreads() throws Exception {
        onTickThread(() -> {
            var queries = new Queries();
            var access = new PaperRollbackWorldAccess(queries, 4);
            var level = (ServerLevel) access.world();
            var player = new NativePlayer(level);
            queries.candidates = List.of(player);
            var bounds = new AABB(-1, -1, -1, 1, 2, 1);
            int[] calls = {0};
            assertEquals(List.of(player), level.getEntities((Entity) null, bounds, entity -> { calls[0]++; return true; }));
            assertEquals(1, calls[0]);
            assertFalse(queries.lastHardOnly);
            var output = level.moonrise$getHardCollidingEntities(null, bounds, entity -> { calls[0]++; return false; });
            assertTrue(output.isEmpty());
            assertTrue(queries.lastHardOnly);
            assertEquals(2, calls[0]);
            var foreign = new PaperRollbackWorldAccess(new Queries(), 4);
            queries.candidates = List.of(new NativePlayer((Level) foreign.world()));
            assertThrows(IllegalArgumentException.class, () -> level.getEntities((Entity) null, bounds, entity -> fail()));
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> level.getBlockState(BlockPos.ZERO)).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertThrows(IllegalStateException.class, () -> level.noCollision(player, new AABB(0, 0, 0, 1e6, 1e6, 1e6)));
            return null;
        });
    }

    @Test void horizontalContactUsesPrivateBukkitIdentityAndRestoredTerrain() throws Exception {
        onTickThread(() -> {
            var queries = new Queries();
            var access = new PaperRollbackWorldAccess(queries, 4);
            var saved = queries.logical.terrain.captureRollbackState();
            Motion open = travel(access, new Vec3(0, 0, 0.8));
            assertTrue(open.position.z > 1);
            queries.block(0, 1, 1, Material.STONE, "minecraft:stone");
            queries.block(0, 2, 1, Material.STONE, "minecraft:stone");
            Motion wall = travel(access, new Vec3(0, 0, 0.8));
            assertEquals(0.7, wall.position.z, 1e-6);
            assertEquals(0, wall.velocity.z);
            queries.logical.terrain.restoreRollbackState(saved);
            assertEquals(open, travel(access, new Vec3(0, 0, 0.8)));
            return null;
        });
    }

    private record Motion(Vec3 position, Vec3 velocity, boolean onGround, double fallDistance) { }
    private static Motion travel(PaperRollbackWorldAccess access) {
        return travel(access, Vec3.ZERO);
    }
    private static Motion travel(PaperRollbackWorldAccess access, Vec3 velocity) {
        var player = new NativePlayer((Level) access.world());
        var state = new PaperRollbackNativePlayerState(player, access, 55, 50_000);
        assertEquals(player.getUUID(), player.getBukkitEntity().getUniqueId());
        assertEquals("movement", player.getBukkitEntity().getName());
        assertFalse(player.getBukkitEntity() instanceof org.bukkit.entity.Vehicle);
        assertThrows(IllegalStateException.class, () -> player.getBukkitEntity().getServer());
        player.setPos(0.5, 1, 0.5);
        player.setYRot(0); player.setXRot(0); player.setOnGround(true);
        player.setDeltaMovement(velocity);
        state.use(value -> { ((Player) value).travel(new Vec3(0, 0, 1)); return null; });
        return new Motion(player.position(), player.getDeltaMovement(), player.onGround(), player.fallDistance);
    }

    static final class NativePlayer extends Player {
        NativePlayer(Level level) { super(level, new GameProfile(new UUID(0, 7), "movement")); }
        @Override public GameType gameMode() { return GameType.SURVIVAL; }
    }

    static <T> T onTickThread(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        var thread = new TickThread(task, "rollback-native-test");
        thread.setDaemon(true);
        thread.start();
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw new AssertionError(failure.getCause());
        }
    }

    static final class Queries implements PaperRollbackWorldAccess.Queries<Queries.Conditions>, CollisionGetter {
        record Conditions(boolean raining, boolean loaded, boolean fastLava) { }
        final PaperRollbackGeometryNativeTest.LogicalWorld logical;
        final WorldBorder border = new WorldBorder();
        List<?> candidates = List.of();
        boolean lastHardOnly;
        boolean raining;
        boolean loaded = true, fastLava;
        Queries() { this(new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(-4, -4, -4, 5, 5, 5)); }
        Queries(com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds bounds) {
            logical = new PaperRollbackGeometryNativeTest.LogicalWorld(Map.of(), bounds);
            border.setSize(100); block(Material.STONE, "minecraft:stone");
        }
        void block(Material material, String exact) {
            block(0, 0, 0, material, exact);
        }
        void block(int x, int y, int z, Material material, String exact) {
            var data = new BlockData(material); data.setExactState(exact);
            logical.getBlockAt(x, y, z).setBlockData(data, false);
        }
        @Override public int minimumY() { return -64; }
        @Override public int height() { return 384; }
        @Override public Object blockState(int x, int y, int z) { return getBlockState(new BlockPos(x, y, z)); }
        @Override public Object blockEntity(int x, int y, int z) { return getBlockEntity(new BlockPos(x, y, z)); }
        @Override public Object worldBorder() { return border; }
        @Override public List<?> entities(Object except, Box bounds, boolean hardCollidingOnly) { lastHardOnly = hardCollidingOnly; return candidates; }
        @Override public Object collisionQuery(String method, Object[] arguments) {
            var entity = (Entity) arguments[0]; var bounds = (AABB) arguments[1];
            return switch (method) {
                case "findSupportingBlock" -> findSupportingBlock(entity, bounds);
                case "noCollision" -> noCollision(entity, bounds);
                case "collidesWithSuffocatingBlock" -> collidesWithSuffocatingBlock(entity, bounds);
                case "getBlockCollisions" -> getBlockCollisions(entity, bounds);
                default -> throw new AssertionError(method);
            };
        }
        @Override public BlockState getBlockState(BlockPos position) {
            try { return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK,
                    logical.getBlockAt(position.getX(), position.getY(), position.getZ()).getBlockData().getAsString(), false).blockState(); }
            catch (CommandSyntaxException failure) { throw new IllegalArgumentException(failure); }
        }
        @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        @Override public BlockState getBlockStateIfLoaded(BlockPos position) { return getBlockState(position); }
        @Override public FluidState getFluidIfLoaded(BlockPos position) { return getFluidState(position); }
        @Override public BlockEntity getBlockEntity(BlockPos position) {
            assertFalse(getBlockState(position).hasBlockEntity(), "fixture contains no block entities"); return null;
        }
        @Override public int getMinY() { return minimumY(); }
        @Override public int getHeight() { return height(); }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public BlockGetter getChunkForCollisions(int x, int z) { return this; }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return List.of(); }
        @Override public boolean isUnobstructed(Entity entity, VoxelShape shape) { return CollisionGetter.super.isUnobstructed(entity, shape); }
        @Override public boolean raining() { return raining; }
        @Override public boolean skyVisible(BlockPos position) { return topPosition(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, position).getY() <= position.getY(); }
        @Override public BlockPos topPosition(net.minecraft.world.level.levelgen.Heightmap.Types type, BlockPos position) {
            for (int y = minimumY() + height() - 1; y >= minimumY(); y--) {
                if (type.isOpaque().test(getBlockState(new BlockPos(position.getX(), y, position.getZ())))) return new BlockPos(position.getX(), y + 1, position.getZ());
            }
            return new BlockPos(position.getX(), minimumY(), position.getZ());
        }
        @Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(BlockPos position) { throw new UnsupportedOperationException("This dry fixture has no biome seed"); }
        @Override public int seaLevel() { return 63; }
        @Override public boolean chunkLoaded(int x, int z) {
            var bounds = logical.terrain.bounds();
            if (x < (bounds.minX() >> 4) || x > ((bounds.maxX() - 1) >> 4)
                    || z < (bounds.minZ() >> 4) || z > ((bounds.maxZ() - 1) >> 4)) throw new IllegalStateException("Chunk outside captured terrain");
            return loaded;
        }
        @Override public boolean chunksLoaded(int minimumX, int minimumZ, int maximumX, int maximumZ) {
            var bounds = logical.terrain.bounds();
            if (minimumX > maximumX || minimumZ > maximumZ || minimumX < bounds.minX() || minimumZ < bounds.minZ()
                    || maximumX >= bounds.maxX() || maximumZ >= bounds.maxZ()) throw new IllegalStateException("Region outside captured terrain");
            return loaded;
        }
        @Override public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
            return new net.minecraft.world.attribute.EnvironmentAttributeReader() {
                @SuppressWarnings("unchecked")
                @Override public <T> T getDimensionValue(net.minecraft.world.attribute.EnvironmentAttribute<T> attribute) {
                    return attribute == net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA ? (T) Boolean.valueOf(fastLava) : attribute.defaultValue();
                }
                @Override public <T> T getValue(net.minecraft.world.attribute.EnvironmentAttribute<T> attribute, net.minecraft.world.phys.Vec3 position,
                        net.minecraft.world.attribute.SpatialAttributeInterpolator interpolator) { return getDimensionValue(attribute); }
            };
        }
        @Override public Conditions captureRollbackState() { return new Conditions(raining, loaded, fastLava); }
        @Override public void restoreRollbackState(Conditions state) { raining = state.raining; loaded = state.loaded; fastLava = state.fastLava; }
        @Override public List<?> rollbackReferences() { return List.of(logical.terrain); }
    }
}
