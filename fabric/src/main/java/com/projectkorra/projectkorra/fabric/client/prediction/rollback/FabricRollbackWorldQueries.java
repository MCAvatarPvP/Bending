package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockRay;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.Difficulty;
import net.minecraft.world.EntityView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.attribute.EnvironmentAttributeAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.rule.GameRule;

import com.projectkorra.projectkorra.prediction.rollback.RollbackHandSwap;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import java.util.*;
import java.util.function.Predicate;

/** Native spatial queries over the same mutable terrain used by bending and the owned native roster. */
public final class FabricRollbackWorldQueries implements FabricRollbackWorldAccess.Queries<Void>, EntityView {
    /** All mutable service state must be owned and checkpointed; no live client/server world may be retained. */
    public interface Services<S> extends RollbackStateCell<S> {
        DynamicRegistryManager.Immutable registries();
        EnvironmentAttributeAccess environmentAttributes();
        WorldBorder border();
        Scoreboard scoreboard();
        /** Game time, distinct from the logical world's day time. */
        long time();
        int seaLevel();
        <T> T gameRule(GameRule<T> rule);
        /** Sky lighting must account for the current terrain; a motion-blocking heightmap is not equivalent. */
        boolean skyVisible(RollbackBlockStore terrain, BlockPos position);
        net.minecraft.util.math.random.Random random();
        long nextSoundSeed();
        void output(FabricRollbackWorldAccess.Output output);
        void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter);
        void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity);
        default RollbackHandSwap swapHands(UUID player, RollbackItemData main, RollbackItemData off) {
            throw new UnsupportedOperationException("Private swap-event policy is not bound");
        }
        default boolean updateEquipmentOnActions() {
            throw new UnsupportedOperationException("Private equipment-update policy is not bound");
        }
        default boolean flightAllowed(UUID player, boolean flying, boolean cancelled) {
            throw new UnsupportedOperationException("Private flight-event policy is not bound");
        }
        default boolean glideAllowed(UUID player, boolean gliding, boolean cancelled) {
            throw new UnsupportedOperationException("Private glide-event policy is not bound");
        }
    }
    public interface BlockEntities<S> extends FabricRollbackGeometry.BlockEntities, RollbackStateCell<S> { }

    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final Services<?> services;
    private final BlockEntities<?> blockEntities;
    private final DynamicRegistryManager.Immutable registries;
    // Cells are immutable and replaced on terrain edits. This bounded cache has no simulation state.
    private final Map<RollbackBlockStore.Cell, BlockState> states = new IdentityHashMap<>();
    private List<PlayerEntity> players;
    private Set<Entity> membership;
    private net.minecraft.world.World nativeWorld;
    private FabricRollbackWorldAccess owner;

    public FabricRollbackWorldQueries(RollbackWorld logical, Services<?> services, BlockEntities<?> blockEntities) {
        requireSetup();
        this.logical = Objects.requireNonNull(logical); this.services = Objects.requireNonNull(services);
        this.blockEntities = Objects.requireNonNull(blockEntities);
        this.registries = Objects.requireNonNull(services.registries(), "frozen session registries");
    }

    /** Bind once after constructing all bodies and before any simulation checkpoint/tick. */
    public void bindRoster(FabricRollbackRoster roster) {
        checkThread(); requireSetup(); Objects.requireNonNull(roster);
        if (players != null) throw new IllegalStateException("Native query roster is already bound");
        if (!roster.world().usesQueries(this)) throw new IllegalArgumentException("Native roster belongs to another query world");
        var players = new ArrayList<PlayerEntity>(); var membership = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        for (var entry : new TreeMap<>(roster.players()).entrySet()) {
            var player = entry.getValue().ownedPlayer();
            if (!player.getUuid().equals(entry.getKey()) || player.getEntityWorld() != roster.world().world() || !membership.add(player)) {
                throw new IllegalArgumentException("Native query roster identity changed");
            }
            players.add(player);
        }
        this.players = List.copyOf(players); this.membership = Collections.unmodifiableSet(membership); this.nativeWorld = roster.world().world(); this.owner = roster.world();
    }
    @Override public BlockState getBlockState(BlockPos pos) {
        checkThread(); var cell = logical.terrain().cell(key(pos));
        var state = states.get(cell);
        if (state == null) {
            state = FabricRollbackGeometry.decode(cell.data());
            if (states.size() >= 4096) states.clear();
            states.put(cell, state);
        }
        return state;
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        var state = getBlockState(pos);
        if (!state.hasBlockEntity()) return null;
        var tile = Objects.requireNonNull(blockEntities.get(logical.terrain(), key(pos), state), "Missing private block entity");
        if (!tile.getPos().equals(pos) || tile.getCachedState() != state || tile.getWorld() != null && tile.getWorld() != nativeWorld) {
            throw new IllegalArgumentException("Block entity does not match the private terrain/world");
        }
        return tile;
    }
    @Override public int getHeight() { checkThread(); return Math.subtractExact(logical.getMaxHeight(), logical.getMinHeight()); }
    @Override public int getBottomY() { checkThread(); return logical.getMinHeight(); }
    @Override public BlockView getChunkAsView(int x, int z) { return isChunkLoaded(x, z) ? this : null; }
    @Override public boolean isChunkLoaded(int x, int z) {
        checkThread(); var b = logical.terrain().bounds();
        if (x < (b.minX() >> 4) || x > ((b.maxX() - 1) >> 4) || z < (b.minZ() >> 4) || z > ((b.maxZ() - 1) >> 4)) {
            throw new IllegalStateException("Chunk availability outside captured region");
        }
        return logical.isChunkLoaded(x, z);
    }
    @Override public BlockPos topPosition(Heightmap.Type type, BlockPos pos) {
        checkThread(); Objects.requireNonNull(type); int visited = 0;
        for (int y = logical.getMaxHeight() - 1; ; y--) {
            if (++visited > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Native height query budget exceeded");
            if (type.getBlockPredicate().test(getBlockState(new BlockPos(pos.getX(), y, pos.getZ())))) return new BlockPos(pos.getX(), y + 1, pos.getZ());
            if (y == logical.getMinHeight()) return new BlockPos(pos.getX(), y, pos.getZ());
        }
    }
    @Override public RegistryEntry<Biome> biome(BlockPos pos) {
        checkThread(); var cell = logical.terrain().cell(key(pos));
        return registries.getOrThrow(RegistryKeys.BIOME).getEntry(Identifier.of(cell.biomeKey()))
                .orElseThrow(() -> new IllegalStateException("Captured biome is absent from session registries: " + cell.biomeKey()));
    }
    @Override public boolean isRaining() { checkThread(); return logical.hasStorm(); }
    @Override public Difficulty difficulty() { checkThread(); return Difficulty.valueOf(logical.conditions().difficulty()); }
    @Override public boolean isSkyVisible(BlockPos pos) { getBlockState(pos); return services.skyVisible(logical.terrain(), pos); }

    @Override public List<? extends Entity> otherEntities(Entity except, Box bounds) { return getOtherEntities(except, bounds, entity -> true); }
    @Override public List<Entity> getOtherEntities(Entity except, Box bounds, Predicate<? super Entity> predicate) {
        requireRoster(); requireMember(except); requireBounds(bounds); Objects.requireNonNull(predicate);
        var result = new ArrayList<Entity>();
        for (var player : players) if (player != except && !player.isRemoved() && player.getBoundingBox().intersects(bounds) && predicate.test(player)) result.add(player);
        return List.copyOf(result);
    }
    @Override public <T extends Entity> List<T> getEntitiesByType(TypeFilter<Entity, T> filter, Box bounds, Predicate<? super T> predicate) {
        Objects.requireNonNull(filter); Objects.requireNonNull(predicate);
        var result = new ArrayList<T>();
        for (var entity : getOtherEntities(null, bounds, value -> true)) {
            T candidate = filter.downcast(entity);
            if (candidate != null && predicate.test(candidate)) result.add(candidate);
        }
        return List.copyOf(result);
    }
    @Override public List<? extends PlayerEntity> getPlayers() { requireRoster(); return players.stream().filter(player -> !player.isRemoved()).toList(); }
    @Override public List<VoxelShape> getEntityCollisions(Entity except, Box bounds) {
        requireRoster(); requireMember(except); requireBounds(bounds);
        return FabricRollbackEntityCollisions.collisions(this, except, bounds);
    }
    @Override public Iterable<VoxelShape> getBlockCollisions(Entity except, Box bounds) {
        requireRoster(); requireMember(except); requireBounds(bounds);
        return FabricRollbackWorldAccess.Queries.super.getBlockCollisions(except, bounds);
    }
    @Override public boolean doesNotIntersectEntities(Entity except, VoxelShape shape) {
        requireRoster(); requireMember(except); if (!shape.isEmpty()) requireBounds(shape.getBoundingBox());
        return FabricRollbackEntityCollisions.unobstructed(this, except, shape);
    }
    @Override public DynamicRegistryManager.Immutable registries() { checkThread(); return registries; }
    @Override public EnvironmentAttributeAccess environmentAttributes() { checkThread(); return services.environmentAttributes(); }
    @Override public WorldBorder getWorldBorder() { checkThread(); return services.border(); }
    @Override public Scoreboard scoreboard() { checkThread(); return services.scoreboard(); }
    @Override public long time() { checkThread(); return services.time(); }
    @Override public int seaLevel() { checkThread(); return services.seaLevel(); }
    @Override public <T> T gameRule(GameRule<T> rule) { checkThread(); return services.gameRule(rule); }
    @Override public net.minecraft.util.math.random.Random random() { checkThread(); return services.random(); }
    @Override public long nextSoundSeed() { checkThread(); return services.nextSoundSeed(); }
    @Override public void output(FabricRollbackWorldAccess.Output output) { checkThread(); services.output(output); }
    @Override public void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter) {
        requireRoster(); requireMember(emitter.sourceEntity());
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) throw new IllegalArgumentException("Game event position");
        logical.terrain().cell(key(BlockPos.ofFloored(position)));
        services.gameEvent(event, position, emitter);
    }
    @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity) { requireRoster(); requireMember(entity); services.waypoint(action, entity); }
    @Override public RollbackHandSwap swapHands(PlayerEntity player, RollbackItemData main, RollbackItemData off) {
        requireRoster(); requireMember(player); return services.swapHands(player.getUuid(), main, off);
    }
    @Override public boolean updateEquipmentOnActions() { requireRoster(); return services.updateEquipmentOnActions(); }
    @Override public boolean flightAllowed(PlayerEntity player, boolean flying, boolean cancelled) {
        requireRoster(); requireMember(player); return services.flightAllowed(player.getUuid(), flying, cancelled);
    }
    @Override public boolean glideAllowed(PlayerEntity player, boolean gliding, boolean cancelled) {
        requireRoster(); requireMember(player); return services.glideAllowed(player.getUuid(), gliding, cancelled);
    }
    @Override public Void captureRollbackState() { requireRoster(); return null; }
    @Override public void restoreRollbackState(Void ignored) { requireRoster(); }
    @Override public List<?> rollbackReferences() { requireRoster(); return List.of(logical, services, blockEntities, owner); }
    private void requireMember(Entity entity) { if (entity != null && !membership.contains(entity)) throw new IllegalArgumentException("Native query contains an unowned entity"); }
    private void requireRoster() { checkThread(); if (players == null) throw new IllegalStateException("Bind the native query roster before simulation"); }
    private void requireBounds(Box bounds) {
        var box = new RollbackBlockStore.Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        RollbackMovementSolver.requireBlockQuery(box); var captured = logical.terrain().bounds();
        if (box.minX() < captured.minX() || box.maxX() > captured.maxX() || box.minY() < captured.minY() || box.maxY() > captured.maxY()
                || box.minZ() < captured.minZ() || box.maxZ() > captured.maxZ()) throw new IllegalStateException("Entity query outside captured terrain");
    }
    private static RollbackBlockStore.Position key(BlockPos pos) { return new RollbackBlockStore.Position(pos.getX(), pos.getY(), pos.getZ()); }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Private world queries crossed threads"); }
    private static void requireSetup() { if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Configure native queries before replay"); }
}
