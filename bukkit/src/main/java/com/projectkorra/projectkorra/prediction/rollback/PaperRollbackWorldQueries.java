package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockRay;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.*;
import java.util.function.Predicate;

/** Paper/Moonrise spatial queries over captured logical terrain and a once-bound private native roster. */
public final class PaperRollbackWorldQueries implements PaperRollbackWorldAccess.Queries<Void>, CollisionGetter, EntityGetter {
    public interface Services<S> extends RollbackStateCell<S> {
        RegistryAccess registries();
        WorldBorder border();
        EnvironmentAttributeReader environmentAttributes();
        /** Must read private sky lighting, not approximate it with a motion-blocking heightmap. */
        boolean skyVisible(RollbackBlockStore terrain, BlockPos position);
        int seaLevel();
    }
    public interface BlockEntities<S> extends PaperRollbackGeometry.BlockEntities, RollbackStateCell<S> { }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final Services<?> services;
    private final BlockEntities<?> blockEntities;
    private final RegistryAccess registries;
    private final PaperRollbackBlockStates decoder = new PaperRollbackBlockStates();
    // Immutable cells make this cache independent of simulation state and safe across terrain restores.
    private final Map<RollbackBlockStore.Cell, BlockState> states = new IdentityHashMap<>();
    private List<Player> players;
    private Set<Entity> membership;
    private ServerLevel nativeWorld;
    private PaperRollbackWorldAccess owner;

    public PaperRollbackWorldQueries(RollbackWorld logical, Services<?> services, BlockEntities<?> blockEntities) {
        requireSetup(); this.logical = Objects.requireNonNull(logical); this.services = Objects.requireNonNull(services);
        this.blockEntities = Objects.requireNonNull(blockEntities); this.registries = Objects.requireNonNull(services.registries());
    }
    public void bindRoster(PaperRollbackWorldAccess world, Map<UUID, PaperRollbackNativePlayerState> roster) {
        checkThread(); requireSetup(); Objects.requireNonNull(world); Objects.requireNonNull(roster);
        if (players != null) throw new IllegalStateException("Native query roster is already bound");
        if (!world.usesQueries(this) || roster.isEmpty() || world.playerCount() != roster.size()) throw new IllegalArgumentException("Native query requires the complete owning roster");
        var players = new ArrayList<Player>(); var membership = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        var ids = new HashSet<Integer>();
        for (var entry : new TreeMap<>(roster).entrySet()) {
            var player = entry.getValue().ownedPlayer();
            if (!player.getUUID().equals(entry.getKey()) || player.level() != world.world() || !world.ownsPlayer(player)
                    || !membership.add(player) || !ids.add(player.getId())) throw new IllegalArgumentException("Native query roster identity changed");
            players.add(player);
        }
        world.sealPlayers();
        this.players = List.copyOf(players); this.membership = Collections.unmodifiableSet(membership); nativeWorld = world.world(); owner = world;
    }
    @Override public int minimumY() { checkThread(); return logical.getMinHeight(); }
    @Override public int height() { checkThread(); return Math.subtractExact(logical.getMaxHeight(), logical.getMinHeight()); }
    @Override public int getMinY() { return minimumY(); }
    @Override public int getHeight() { return height(); }
    @Override public BlockState blockState(int x, int y, int z) { return getBlockState(new BlockPos(x, y, z)); }
    @Override public BlockEntity blockEntity(int x, int y, int z) { return getBlockEntity(new BlockPos(x, y, z)); }
    @Override public BlockState getBlockState(BlockPos pos) {
        checkThread(); var cell = logical.terrain().cell(key(pos)); var state = states.get(cell);
        if (state == null) {
            state = decoder.decode(cell.data()); if (states.size() >= 4096) states.clear(); states.put(cell, state);
        }
        return state;
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockState getBlockStateIfLoaded(BlockPos pos) { return chunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) ? getBlockState(pos) : null; }
    @Override public FluidState getFluidIfLoaded(BlockPos pos) { var state = getBlockStateIfLoaded(pos); return state == null ? null : state.getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        var state = getBlockState(pos); if (!state.hasBlockEntity()) return null;
        var tile = (BlockEntity) Objects.requireNonNull(blockEntities.get(logical.terrain(), key(pos), state), "Missing private block entity");
        if (!tile.getBlockPos().equals(pos) || tile.getBlockState() != state || tile.getLevel() != null && tile.getLevel() != nativeWorld) {
            throw new IllegalArgumentException("Block entity does not match private terrain/world");
        }
        return tile;
    }
    @Override public WorldBorder worldBorder() { checkThread(); return services.border(); }
    @Override public WorldBorder getWorldBorder() { return worldBorder(); }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { return chunkLoaded(x, z) ? this : null; }
    @Override public boolean chunkLoaded(int x, int z) {
        checkThread(); var b = logical.terrain().bounds();
        if (x < (b.minX() >> 4) || x > ((b.maxX() - 1) >> 4) || z < (b.minZ() >> 4) || z > ((b.maxZ() - 1) >> 4)) {
            throw new IllegalStateException("Chunk availability outside captured region");
        }
        return logical.isChunkLoaded(x, z);
    }
    @Override public boolean chunksLoaded(int minX, int minZ, int maxX, int maxZ) {
        checkThread(); var b = logical.terrain().bounds();
        if (minX > maxX || minZ > maxZ || minX < b.minX() || maxX >= b.maxX() || minZ < b.minZ() || maxZ >= b.maxZ()) {
            throw new IllegalStateException("Region outside captured terrain");
        }
        long count = ((long) (maxX >> 4) - (minX >> 4) + 1) * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
        if (count > 65_536) throw new IllegalStateException("Loaded-region query budget exceeded");
        for (int x = minX >> 4; x <= maxX >> 4; x++) for (int z = minZ >> 4; z <= maxZ >> 4; z++) if (!chunkLoaded(x, z)) return false;
        return true;
    }
    @Override public boolean raining() { checkThread(); return logical.hasStorm(); }
    @Override public boolean skyVisible(BlockPos pos) { getBlockState(pos); return services.skyVisible(logical.terrain(), pos); }
    @Override public int seaLevel() { checkThread(); return services.seaLevel(); }
    @Override public EnvironmentAttributeReader environmentAttributes() { checkThread(); return services.environmentAttributes(); }
    @Override public Holder<Biome> biome(BlockPos pos) {
        checkThread(); var cell = logical.terrain().cell(key(pos));
        return registries.lookupOrThrow(Registries.BIOME).get(Identifier.parse(cell.biomeKey()))
                .orElseThrow(() -> new IllegalStateException("Captured biome is absent from session registries: " + cell.biomeKey()));
    }
    @Override public BlockPos topPosition(Heightmap.Types type, BlockPos pos) {
        checkThread(); Objects.requireNonNull(type); int visited = 0;
        for (int y = logical.getMaxHeight() - 1; ; y--) {
            if (++visited > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Native height query budget exceeded");
            if (type.isOpaque().test(getBlockState(new BlockPos(pos.getX(), y, pos.getZ())))) return new BlockPos(pos.getX(), y + 1, pos.getZ());
            if (y == logical.getMinHeight()) return new BlockPos(pos.getX(), y, pos.getZ());
        }
    }
    @Override public List<Entity> entities(Object except, RollbackBlockStore.Box bounds, boolean hardOnly) {
        var box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        return hardOnly ? moonrise$getHardCollidingEntities((Entity) except, box, null) : getEntities((Entity) except, box, null);
    }
    @Override public List<Entity> getEntities(Entity except, AABB bounds, Predicate<? super Entity> predicate) { return entities(except, bounds, predicate, false); }
    @Override public List<Entity> moonrise$getHardCollidingEntities(Entity except, AABB bounds, Predicate<? super Entity> predicate) { return entities(except, bounds, predicate, true); }
    private List<Entity> entities(Entity except, AABB bounds, Predicate<? super Entity> predicate, boolean hardOnly) {
        requireRoster(); requireMember(except); requireBounds(bounds); var result = new ArrayList<Entity>();
        for (var player : players) if (player != except && !player.isRemoved() && player.getBoundingBox().intersects(bounds)
                && (!hardOnly || player.moonrise$isHardColliding()) && (predicate == null || predicate.test(player))) result.add(player);
        return List.copyOf(result);
    }
    @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> filter, AABB bounds, Predicate<? super T> predicate) {
        Objects.requireNonNull(filter); var result = new ArrayList<T>();
        for (var entity : getEntities((Entity) null, bounds, null)) { T value = filter.tryCast(entity); if (value != null && (predicate == null || predicate.test(value))) result.add(value); }
        return List.copyOf(result);
    }
    @Override public List<? extends Player> players() { requireRoster(); return players.stream().filter(player -> !player.isRemoved()).toList(); }
    @Override public List<VoxelShape> getEntityCollisions(Entity except, AABB bounds) {
        requireRoster(); requireMember(except); requireBounds(bounds); return EntityGetter.super.getEntityCollisions(except, bounds);
    }
    @Override public boolean isUnobstructed(Entity except, VoxelShape shape) {
        requireRoster(); requireMember(except); if (!shape.isEmpty()) requireBounds(shape.bounds());
        return EntityGetter.super.isUnobstructed(except, shape);
    }
    @Override public Iterable<VoxelShape> getBlockCollisions(Entity except, AABB bounds) {
        requireRoster(); requireMember(except); requireBounds(bounds); return CollisionGetter.super.getBlockCollisions(except, bounds);
    }
    @Override public Object collisionQuery(String method, Object[] arguments) {
        var entity = (Entity) arguments[0]; var bounds = (AABB) arguments[1]; requireRoster(); requireMember(entity); requireBounds(bounds);
        return switch (method) {
            case "findSupportingBlock" -> findSupportingBlock(entity, bounds);
            case "noCollision" -> noCollision(entity, bounds);
            case "collidesWithSuffocatingBlock" -> collidesWithSuffocatingBlock(entity, bounds);
            case "getBlockCollisions" -> getBlockCollisions(entity, bounds);
            default -> throw new IllegalArgumentException("Unbound native collision query: " + method);
        };
    }
    @Override public Void captureRollbackState() { requireRoster(); return null; }
    @Override public void restoreRollbackState(Void ignored) { requireRoster(); }
    @Override public List<?> rollbackReferences() { requireRoster(); return List.of(logical, services, blockEntities, owner); }
    private void requireRoster() { checkThread(); if (players == null) throw new IllegalStateException("Bind the native query roster before simulation"); }
    private void requireMember(Entity entity) { if (entity != null && !membership.contains(entity)) throw new IllegalArgumentException("Native query contains an unowned entity"); }
    private void requireBounds(AABB bounds) {
        var box = new RollbackBlockStore.Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        RollbackMovementSolver.requireBlockQuery(box); var b = logical.terrain().bounds();
        if (box.minX() < b.minX() || box.maxX() > b.maxX() || box.minY() < b.minY() || box.maxY() > b.maxY() || box.minZ() < b.minZ() || box.maxZ() > b.maxZ()) throw new IllegalStateException("Entity query outside captured terrain");
    }
    private static RollbackBlockStore.Position key(BlockPos pos) { return new RollbackBlockStore.Position(pos.getX(), pos.getY(), pos.getZ()); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private world queries crossed threads"); }
    private static void requireSetup() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Configure native queries on the tick thread before replay");
    }
}
