package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.Bisected;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.PredictionTiming;
import com.projectkorra.projectkorra.prediction.block.TempBlockOwnershipPolicy;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A transactional stack of temporary block layers.
 *
 * <p>The registry, physical top state, expiry queue and prediction metadata are
 * advanced as one operation. Vanilla world packets are transport side effects;
 * they are never used to audit or invalidate the registry. External block
 * authority must explicitly call {@link #removeBlock(Block)} (with callbacks)
 * or {@link #discardBlock(Block)} (without callbacks). This is important on a
 * predicting client where
 * a hidden server packet must not be mistaken for proof that its local
 * TempBlock disappeared.</p>
 */
public class TempBlock {
    private static final Object MUTATION_LOCK = new Object();
    private static final Map<Block, LinkedList<TempBlock>> LAYERS = new HashMap<>();
    private static final Map<Long, TempBlock> LAYERS_BY_ID = new HashMap<>();
    private static final PriorityQueue<TempBlock> EXPIRATIONS = new PriorityQueue<>(128,
            (first, second) -> {
                int time = Long.compare(first.revertTime, second.revertTime);
                return time != 0 ? time : Long.compare(first.layerId, second.layerId);
            });
    private static final Map<VisibilityKey, VisibilitySnapshot> VISIBILITY = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_LAYER_ID = new AtomicLong();
    private static final AtomicLong NEXT_REVISION = new AtomicLong();
    /**
     * Per-ability semantic creation counters.  Layer ids are process-local,
     * while an ability's running tick and creation order are reproduced by the
     * common implementation on Paper and Fabric.  The weak keys ensure an
     * ended ability cannot be retained by prediction bookkeeping.
     */
    private static final Map<CoreAbility, EffectCounter> EFFECT_COUNTERS = new WeakHashMap<>();
    private static int shutdownCleanupDepth;

    /** Compatibility view containing the current top layer at each coordinate. */
    @Deprecated
    public static final Map<Block, TempBlock> instances = new ConcurrentHashMap<>();

    /** Portable, canonical layer stacks. Native indices are rebuilt from those same layer objects on each import. */
    public static final class RollbackLayers {
        private Map<Block, LinkedList<TempBlock>> stacks = new HashMap<>();
        private Map<CoreAbility, EffectCounter> effects = new WeakHashMap<>();
        private Map<Block, TempBlock> lava = new LinkedHashMap<>(), land = new LinkedHashMap<>(), air = new LinkedHashMap<>();
        private long nextId, idLimit, nextRevision, revisionLimit;
        private Indices indices;
        private static final long CAPACITY = 1L << 32;
        private static final Set<Lease> LEASES = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        private static final class Indices {
            private final Map<Long, TempBlock> ids = new HashMap<>();
            private final Map<Block, TempBlock> tops = new HashMap<>();
            private final Map<VisibilityKey, VisibilitySnapshot> visibility = new HashMap<>();
            private final PriorityQueue<TempBlock> expirations = new PriorityQueue<>(128, EXPIRATIONS.comparator());
        }

        public Map<Block, TempBlock> lava() { return lava; }
        public Map<Block, TempBlock> land() { return land; }
        public Map<Block, TempBlock> air() { return air; }

        public RollbackLayers portable() {
            var copy = new RollbackLayers();
            copy.stacks = stacks;
            copy.effects = effects;
            copy.lava = lava; copy.land = land; copy.air = air;
            copy.nextId = nextId; copy.idLimit = idLimit;
            copy.nextRevision = nextRevision; copy.revisionLimit = revisionLimit;
            return copy;
        }

        public void install() {
            if (activeLayers() != this || indices != null) throw new IllegalStateException("Invalid private temporary-layer installation");
            indices = new Indices();
            index(stacks);
        }

        private static void index(Map<Block, LinkedList<TempBlock>> rows) {
            for (var entry : rows.entrySet()) {
                for (var layer : entry.getValue()) {
                    if (layersById().putIfAbsent(layer.layerId, layer) != null) throw new IllegalArgumentException("Duplicate temporary layer ID");
                    if (layer.scheduled) expirations().add(layer);
                }
                refreshViewsLocked(entry.getKey());
            }
        }

        static void beforeLiveMutation(Block block) {
            if (com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active() || block == null) return;
            final List<Lease> overlaps;
            synchronized (MUTATION_LOCK) { overlaps = LEASES.stream().filter(owner -> owner.contains(block)).toList(); }
            for (var owner : overlaps) {
                owner.stop.run();
                synchronized (MUTATION_LOCK) {
                    if (LEASES.contains(owner)) throw new IllegalStateException("Overlapping temporary layer is still owned by the duel");
                }
            }
        }

        public static Lease capture(World world, com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds bounds,
                                    Set<UUID> players, Runnable stop) {
            if (com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw new IllegalStateException("Capture live temporary layers outside replay");
            synchronized (MUTATION_LOCK) { return new Lease(world, bounds, players, stop); }
        }

        public static final class Lease {
            private final World world;
            private final com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds bounds;
            private final Set<UUID> players;
            private final Runnable stop;
            private final RollbackLayers seed = new RollbackLayers();
            private boolean detached, complete;
            private Lease(World world, com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds bounds,
                          Set<UUID> players, Runnable stop) {
                this.world = Objects.requireNonNull(world); this.bounds = Objects.requireNonNull(bounds);
                this.players = Set.copyOf(players); this.stop = Objects.requireNonNull(stop);
                for (var other : LEASES) if (other.world.handle() == world.handle() && overlaps(bounds, other.bounds))
                    throw new IllegalStateException("Temporary terrain is already owned by another duel");
                LAYERS.forEach((block, stack) -> {
                    if (contains(block)) {
                        if (stack.stream().anyMatch(layer -> layer.ownerId == null || !players.contains(layer.ownerId)))
                            throw new IllegalStateException("Temporary layer in the arena has no enrolled owner");
                        seed.stacks.put(block, new LinkedList<>(stack));
                    }
                    else if (stack.stream().anyMatch(layer -> layer.ownerId != null && players.contains(layer.ownerId)))
                        throw new IllegalStateException("Participant has a temporary layer outside the arena");
                });
                EFFECT_COUNTERS.forEach((ability, counter) -> {
                    if (ability != null && ability.getPlayer() != null && players.contains(ability.getPlayer().getUniqueId())) seed.effects.put(ability, counter);
                });
                var maps = com.projectkorra.projectkorra.earthbending.lava.LavaFlow.rollbackMaps();
                copyLava(maps.get(0), seed.lava); copyLava(maps.get(1), seed.land); copyLava(maps.get(2), seed.air);
                seed.nextId = NEXT_LAYER_ID.getAndUpdate(n -> Math.addExact(n, CAPACITY)); seed.idLimit = seed.nextId + CAPACITY;
                seed.nextRevision = NEXT_REVISION.getAndUpdate(n -> Math.addExact(n, CAPACITY)); seed.revisionLimit = seed.nextRevision + CAPACITY;
                validateRows(seed);
            }
            private void copyLava(Map<Block, TempBlock> live, Map<Block, TempBlock> destination) {
                live.forEach((block, layer) -> { if (contains(block)) destination.put(block, layer); });
            }
            private boolean contains(Block block) {
                return block.getWorld().handle() == world.handle() && bounds.contains(new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(block.getX(), block.getY(), block.getZ()));
            }
            private static boolean overlaps(com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds a,
                                            com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds b) {
                return a.minX() < b.maxX() && b.minX() < a.maxX() && a.minY() < b.maxY() && b.minY() < a.maxY()
                        && a.minZ() < b.maxZ() && b.minZ() < a.maxZ();
            }
            public RollbackLayers seed() { return seed; }
            private void validateRows(RollbackLayers state) {
                var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TempBlock, Boolean>());
                var ids = new HashSet<Long>();
                state.stacks.forEach((block, layers) -> {
                    if (!contains(block) || layers.isEmpty()) throw new IllegalStateException("Invalid restored layer stack");
                    for (var layer : layers) {
                        if (layer == null || !layer.block.equals(block) || !seen.add(layer) || !ids.add(layer.layerId) || layer.reverted
                                || layer.ownerId != null && !players.contains(layer.ownerId))
                            throw new IllegalStateException("Temporary layer has overlapping ownership");
                    }
                });
                for (var layer : seen) if (!seen.containsAll(layer.attachedTempBlocks))
                    throw new IllegalStateException("Attached temporary layer crosses the arena");
                state.effects.forEach((ability, counter) -> {
                    if (ability == null || ability.getPlayer() == null || !players.contains(ability.getPlayer().getUniqueId()))
                        throw new IllegalStateException("Temporary effect counter belongs to another player");
                });
                for (var map : List.of(state.lava, state.land, state.air)) map.forEach((block, layer) -> {
                    if (!contains(block) || !seen.contains(layer) || !layer.block.equals(block))
                        throw new IllegalStateException("LavaFlow references a foreign temporary layer");
                });
            }
            public void detach() {
                synchronized (MUTATION_LOCK) {
                    if (detached) return;
                    for (var other : LEASES) if (other.world.handle() == world.handle() && overlaps(bounds, other.bounds))
                        throw new IllegalStateException("Overlapping temporary layer lease during enrollment");
                    for (var entry : seed.stacks.entrySet()) if (!entry.getValue().equals(LAYERS.get(entry.getKey())))
                        throw new IllegalStateException("Live layers changed before capture");
                    seed.stacks.forEach((block, stack) -> {
                        LAYERS.remove(block);
                        for (var layer : stack) { LAYERS_BY_ID.remove(layer.layerId); EXPIRATIONS.remove(layer); }
                        refreshViewsLocked(block);
                    });
                    seed.effects.keySet().forEach(EFFECT_COUNTERS::remove);
                    var maps = com.projectkorra.projectkorra.earthbending.lava.LavaFlow.rollbackMaps();
                    seed.lava.keySet().forEach(maps.get(0)::remove); seed.land.keySet().forEach(maps.get(1)::remove); seed.air.keySet().forEach(maps.get(2)::remove);
                    LEASES.add(this); detached = true;
                }
            }
            public void validate(RollbackLayers state) {
                if (complete) return;
                if (!detached || state == null || state.idLimit != seed.idLimit || state.revisionLimit != seed.revisionLimit
                        || state.nextId < seed.nextId || state.nextId > state.idLimit
                        || state.nextRevision < seed.nextRevision || state.nextRevision > state.revisionLimit)
                    throw new IllegalStateException("Invalid confirmed temporary-layer reservation");
                validateRows(state);
                state.stacks.forEach((block, stack) -> {
                    if (LAYERS.containsKey(block) || stack.stream().anyMatch(layer -> LAYERS_BY_ID.containsKey(layer.layerId)))
                        throw new IllegalStateException("Live temporary layers changed during duel");
                });
            }
            public void merge(RollbackLayers state) {
                synchronized (MUTATION_LOCK) {
                    if (complete) return;
                    validate(state);
                    LAYERS.putAll(state.stacks); EFFECT_COUNTERS.putAll(state.effects);
                    var maps = com.projectkorra.projectkorra.earthbending.lava.LavaFlow.rollbackMaps();
                    maps.get(0).putAll(state.lava); maps.get(1).putAll(state.land); maps.get(2).putAll(state.air);
                    index(state.stacks);
                    LEASES.remove(this); complete = true;
                }
            }
            public void abort() { if (detached && !complete) merge(seed); }
        }
    }

    private static RollbackLayers activeLayers() { return com.projectkorra.projectkorra.prediction.rollback.RollbackAbilityGlobals.layers(); }
    private static Map<Block, LinkedList<TempBlock>> layers() { var p = activeLayers(); return p == null ? LAYERS : p.stacks; }
    private static Map<Long, TempBlock> layersById() { var p = activeLayers(); return p == null ? LAYERS_BY_ID : p.indices.ids; }
    private static Map<CoreAbility, EffectCounter> effectCounters() { var p = activeLayers(); return p == null ? EFFECT_COUNTERS : p.effects; }
    private static Map<Block, TempBlock> tops() { var p = activeLayers(); return p == null ? instances : p.indices.tops; }
    private static Map<VisibilityKey, VisibilitySnapshot> visibility() { var p = activeLayers(); return p == null ? VISIBILITY : p.indices.visibility; }
    private static PriorityQueue<TempBlock> expirations() { var p = activeLayers(); return p == null ? EXPIRATIONS : p.indices.expirations; }
    private static long nextLayerId() {
        var p = activeLayers();
        if (p == null) return NEXT_LAYER_ID.incrementAndGet();
        if (p.nextId >= p.idLimit) throw new IllegalStateException("Temporary layer ID reservation exhausted");
        return ++p.nextId;
    }
    private static long nextRevision() {
        var p = activeLayers();
        if (p == null) return NEXT_REVISION.incrementAndGet();
        if (p.nextRevision >= p.revisionLimit) throw new IllegalStateException("Temporary layer revision reservation exhausted");
        return ++p.nextRevision;
    }

    private final Block block;
    private final long layerId = nextLayerId();
    private final String effectAbility;
    private final long effectStep;
    private final int effectOrdinal;
    private final Set<TempBlock> attachedTempBlocks = new HashSet<>();
    private BlockData newData;
    private BlockState state;
    private Optional<CoreAbility> ability;
    private UUID ownerId;
    private long revision;
    private long revertTime;
    private boolean scheduled;
    private volatile boolean reverted;
    private Runnable revertTask;
    private boolean bendableSource;
    private boolean suffocate;

    public TempBlock(final Block block, final Material newType) {
        this(block, requireMaterial(newType).createBlockData(), 0L, null, null);
    }

    /** @deprecated {@code newType} is redundant; the supplied data is used. */
    @Deprecated
    public TempBlock(final Block block, final Material newType, final BlockData newData) {
        this(block, newData, 0L, null, null);
    }

    public TempBlock(final Block block, final BlockData newData) {
        this(block, newData, 0L, null, null);
    }

    public TempBlock(final Block block, final BlockData newData, final long revertTime,
                     final CoreAbility ability) {
        this(block, newData, revertTime, ability, null);
    }

    public TempBlock(final Block block, final BlockData newData, final CoreAbility ability) {
        this(block, newData, 0L, ability, null);
    }

    /**
     * Creates a layer with a deterministic ability-owned effect identity.
     * This is intended for predicted structures whose two simulations can
     * legitimately omit different physical blocks while still drawing the
     * same logical shape slot.
     */
    public TempBlock(final Block block, final BlockData newData, final CoreAbility ability,
                     final long effectStep, final int effectOrdinal) {
        this(block, newData, 0L, ability,
                new EffectIdentity(ability == null ? "" : ability.getName(),
                        effectStep, effectOrdinal));
    }

    public TempBlock(final Block block, final BlockData newData, final long revertTime) {
        this(block, newData, revertTime, null, null);
    }

    private TempBlock(final Block block, BlockData newData, final long duration,
                      final CoreAbility explicitAbility, final EffectIdentity explicitIdentity) {
        this.block = Objects.requireNonNull(block, "block");
        RollbackLayers.beforeLiveMutation(block);
        Objects.requireNonNull(newData, "newData");
        final CoreAbility resolvedAbility = explicitAbility != null
                ? explicitAbility : AbilityExecutionContext.current();
        final EffectIdentity effectIdentity = explicitIdentity == null
                ? nextEffectIdentity(resolvedAbility) : explicitIdentity;
        this.effectAbility = effectIdentity.ability();
        this.effectStep = effectIdentity.step();
        this.effectOrdinal = effectIdentity.ordinal();
        this.ability = Optional.ofNullable(resolvedAbility);
        this.ownerId = ownerId(resolvedAbility);
        this.suffocate = resolvedAbility != null && !(resolvedAbility instanceof WaterAbility);

        if ((newData.getMaterial() == Material.FIRE || newData.getMaterial() == Material.SOUL_FIRE)
                && !FireAbility.canFireGrief()) {
            newData = FireAbility.createFireState(block, newData.getMaterial() == Material.SOUL_FIRE);
        }
        this.newData = newData.clone();

        synchronized (MUTATION_LOCK) {
            LinkedList<TempBlock> stack = layers().get(block);
            this.state = stack == null || stack.isEmpty() ? block.getState() : stack.getFirst().state;
            // Ability removal can run arbitrary addon callbacks. During a full
            // teardown, a replacement layer would miss the removal snapshot and
            // retain a cancelled expiry task after /pk reload.
            if (shutdownCleanupDepth > 0) {
                this.reverted = true;
                return;
            }
            if (this.state.hasBlockEntity() || this.state.getType() == Material.JUKEBOX) {
                this.reverted = true;
                return;
            }

            if (stack == null) {
                stack = new LinkedList<>();
                layers().put(block, stack);
            }
            stack.addLast(this);
            layersById().put(this.layerId, this);
            refreshViewsLocked(block);
            try {
                scheduleLocked(duration);
                advanceRevisionLocked();
                writeTopLocked(TempBlockSync.Operation.CREATE, this.newData,
                        () -> block.setBlockData(this.newData.clone(), applyPhysics(this.newData.getMaterial())));
                publishLocked(TempBlockSync.Operation.CREATE, this.newData, true);
            } catch (RuntimeException | Error failure) {
                unscheduleLocked(this);
                stack.remove(this);
                layersById().remove(this.layerId);
                this.reverted = true;
                if (stack.isEmpty()) layers().remove(block);
                refreshViewsLocked(block);
                advanceRevisionLocked();
                final BlockData effectiveData = stack.isEmpty()
                        ? this.state.getBlockData().clone() : stack.getLast().newData.clone();
                publishLocked(TempBlockSync.Operation.DISCARD, effectiveData, false);
                throw failure;
            }
        }
    }

    private static Material requireMaterial(final Material material) {
        return Objects.requireNonNull(material, "material");
    }

    private static UUID ownerId(final CoreAbility ability) {
        return ability == null || ability.getPlayer() == null ? null : ability.getPlayer().getUniqueId();
    }

    private static EffectIdentity nextEffectIdentity(final CoreAbility ability) {
        if (ability == null) return new EffectIdentity("", -1L, 0);
        synchronized (MUTATION_LOCK) {
            final long step = ability.isStarted() ? ability.getRunningTicks() : 0L;
            final EffectCounter previous = effectCounters().get(ability);
            final int ordinal = previous != null && previous.step == step
                    ? previous.ordinal + 1 : 1;
            effectCounters().put(ability, new EffectCounter(step, ordinal));
            return new EffectIdentity(ability.getName(), step, ordinal);
        }
    }

    public static TempBlock get(final Block block) {
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = layers().get(block);
            return stack == null || stack.isEmpty() ? null : stack.getLast();
        }
    }

    public static LinkedList<TempBlock> getAll(final Block block) {
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = layers().get(block);
            return stack == null ? null : new LinkedList<>(stack);
        }
    }

    public static List<TempBlock> getActiveLayers() {
        synchronized (MUTATION_LOCK) {
            final List<TempBlock> result = new ArrayList<>();
            layers().values().forEach(result::addAll);
            return List.copyOf(result);
        }
    }

    /** Constant-time identity lookup used by the prediction lifecycle. */
    public static TempBlock getActiveLayer(final long layerId) {
        synchronized (MUTATION_LOCK) {
            final TempBlock layer = layersById().get(layerId);
            return layer == null || layer.reverted ? null : layer;
        }
    }

    /**
     * Updates every live layer belonging to an ability after redirection. The
     * block itself is unchanged; only the authenticated prediction owner and
     * lifecycle revision are republished.
     */
    public static void refreshAbilityOwnership(final CoreAbility ability) {
        if (ability == null) return;
        synchronized (MUTATION_LOCK) {
            final UUID nextOwner = ownerId(ability);
            final List<TempBlock> owned = new ArrayList<>();
            final Set<Block> changedCoordinates = new HashSet<>();
            for (TempBlock layer : layersById().values()) {
                if (layer.reverted || layer.ability.orElse(null) != ability
                        || Objects.equals(layer.ownerId, nextOwner)) continue;
                layer.ownerId = nextOwner;
                owned.add(layer);
                changedCoordinates.add(layer.block);
            }
            for (Block block : changedCoordinates) refreshViewsLocked(block);
            for (TempBlock layer : owned) {
                layer.advanceRevisionLocked();
                layer.publishLocked(TempBlockSync.Operation.UPDATE_EXPIRY,
                        layer.newData, false);
            }
        }
    }

    public static boolean isTempBlock(final Block block) {
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = layers().get(block);
            return block != null && stack != null && !stack.isEmpty();
        }
    }

    public static boolean isTouchingTempBlock(final Block block) {
        if (block == null) return false;
        synchronized (MUTATION_LOCK) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                    BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                final LinkedList<TempBlock> stack = layers().get(block.getRelative(face));
                if (stack != null && !stack.isEmpty()) return true;
            }
            return false;
        }
    }

    /**
     * Fences a complete plugin teardown. Existing layers may still restore
     * their captured world state, but callbacks and newly constructed layers
     * cannot repopulate registries whose scheduler is about to be cancelled.
     */
    public static void runShutdownCleanup(final Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        synchronized (MUTATION_LOCK) {
            shutdownCleanupDepth++;
        }
        try {
            cleanup.run();
        } finally {
            synchronized (MUTATION_LOCK) {
                shutdownCleanupDepth--;
            }
        }
    }

    public static void removeAll() {
        final List<TempBlock> snapshot = getActiveLayers();
        try {
            for (TempBlock layer : snapshot) {
                try {
                    layer.revertBlock();
                } catch (RuntimeException failure) {
                    if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw failure;
                    ProjectKorra.log.warning("TempBlock shutdown restore failed at " + layer.getLocation()
                            + ": " + failure.getMessage());
                }
            }
        } finally {
            synchronized (MUTATION_LOCK) {
                expirations().clear();
                if (shutdownCleanupDepth > 0) {
                    for (LinkedList<TempBlock> stack : layers().values()) {
                        for (TempBlock layer : stack) {
                            layer.reverted = true;
                            layer.scheduled = false;
                        }
                    }
                    layers().clear();
                    layersById().clear();
                    tops().clear();
                    visibility().clear();
                    effectCounters().clear();
                } else if (layers().isEmpty()) {
                    layersById().clear();
                }
            }
        }
    }

    /**
     * Clears every registry and expiry entry without writing any world state or
     * invoking callbacks. Client runtime/world shutdown uses this so an old
     * ClientWorld can never be repainted during the next session.
     */
    public static void discardAll() {
        synchronized (MUTATION_LOCK) {
            for (Block block : new ArrayList<>(layers().keySet())) {
                invalidateStackLocked(block, null, false);
            }
            expirations().clear();
            layersById().clear();
        }
    }

    public static void removeAllInWorld(final World world) {
        if (world == null) return;
        for (TempBlock layer : getActiveLayers()) {
            if (world.equals(layer.block.getWorld())) layer.revertBlock();
        }
    }

    /**
     * Invalidates a stack after an external block operation has become real
     * authority. This method never writes a replacement block state.
     */
    public static void removeBlock(final Block block) {
        RollbackLayers.beforeLiveMutation(block);
        final List<TempBlock> removed;
        synchronized (MUTATION_LOCK) {
            removed = invalidateStackLocked(block, block == null ? null : block.getBlockData(), true);
        }
        finishLayers(removed);
    }

    /**
     * Invalidates a stack immediately before an ordinary platform block write.
     * The closing metadata is flushed before the vanilla block packet can be
     * emitted, while the replacement write itself remains ordinary authority.
     * No captured TempBlock state is restored along the way.
     */
    public static void removeBlockBeforeWrite(final Block block, final BlockData replacementData) {
        RollbackLayers.beforeLiveMutation(block);
        if (block == null) return;
        final BlockData effectiveData = replacementData == null
                ? block.getBlockData().clone() : replacementData.clone();
        final List<TempBlock> removed;
        synchronized (MUTATION_LOCK) {
            removed = invalidateStackLocked(block, effectiveData, false);
            for (TempBlock layer : removed) {
                layer.advanceRevisionLocked();
                TempBlockSync.beforeWorldChange(TempBlockSync.Operation.DISCARD, layer, effectiveData);
            }
        }
        finishLayers(removed);
    }

    /** Drops one coordinate without a world write or callbacks, while closing its network lifecycle. */
    public static void discardBlock(final Block block) {
        RollbackLayers.beforeLiveMutation(block);
        synchronized (MUTATION_LOCK) {
            invalidateStackLocked(block, block == null ? null : block.getBlockData(), true);
        }
    }

    private static List<TempBlock> invalidateStackLocked(final Block block, final BlockData effectiveData,
                                                          final boolean publish) {
        if (block == null) return List.of();
        final LinkedList<TempBlock> stack = layers().remove(block);
        if (stack == null || stack.isEmpty()) {
            tops().remove(block);
            if (block.getWorld() != null) visibility().remove(VisibilityKey.of(block));
            return List.of();
        }
        tops().remove(block);
        visibility().remove(VisibilityKey.of(block));
        final List<TempBlock> removed = new ArrayList<>(stack.size());
        for (TempBlock layer : stack) {
            unscheduleLocked(layer);
            layersById().remove(layer.layerId);
            if (layer.reverted) continue;
            layer.reverted = true;
            if (publish && effectiveData != null) {
                layer.advanceRevisionLocked();
                layer.publishLocked(TempBlockSync.Operation.DISCARD, effectiveData, false);
            }
            removed.add(layer);
        }
        return removed;
    }

    private static void finishLayers(final List<TempBlock> layers) {
        for (TempBlock layer : layers) finishLayer(layer);
    }

    private static void finishLayer(final TempBlock layer) {
        synchronized (MUTATION_LOCK) {
            if (shutdownCleanupDepth > 0) return;
        }
        final Runnable completion = () -> {
            if (layer.revertTask != null) {
                try {
                    layer.revertTask.run();
                } catch (RuntimeException failure) {
                    if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw failure;
                    ProjectKorra.log.warning("TempBlock revert callback failed at " + layer.getLocation()
                            + ": " + failure.getMessage());
                }
            }
            for (TempBlock attached : layer.getAttachedTempBlocks()) {
                try {
                    attached.revertBlock();
                } catch (RuntimeException failure) {
                    if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw failure;
                    ProjectKorra.log.warning("Attached TempBlock revert failed at " + attached.getLocation()
                            + ": " + failure.getMessage());
                }
            }
        };
        final CoreAbility owner = layer.ability.orElse(null);
        if (owner == null) completion.run();
        else AbilityExecutionContext.run(owner, completion);
    }

    private void scheduleLocked(final long duration) {
        if (duration <= 0L || this.state.hasBlockEntity()) return;
        final long now = RollbackClock.millis();
        final long aligned = PredictionTiming.alignDuration(this.ability.orElse(null), duration);
        this.revertTime = aligned >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + aligned;
        this.scheduled = true;
        expirations().add(this);
    }

    private static void unscheduleLocked(final TempBlock layer) {
        if (!layer.scheduled) return;
        expirations().remove(layer);
        layer.scheduled = false;
    }

    private void advanceRevisionLocked() {
        this.revision = nextRevision();
    }

    /** Sends pre-mutation metadata before the vanilla packet can be emitted. */
    private void writeTopLocked(final TempBlockSync.Operation operation, final BlockData effectiveData,
                                final Runnable worldWrite) {
        TempBlockSync.beforeWorldChange(operation, this, effectiveData);
        TempBlockSync.runWorldMutation(operation, this, effectiveData, () -> {
            worldWrite.run();
            updateSnowableBlock(this.block.getRelative(BlockFace.DOWN),
                    isSnowCover(effectiveData.getMaterial()));
        });
    }

    private void publishLocked(final TempBlockSync.Operation operation, final BlockData effectiveData,
                               final boolean physicalWrite) {
        TempBlockSync.publish(operation, this, effectiveData, physicalWrite);
    }

    private static void refreshViewsLocked(final Block block) {
        if (block == null || block.getWorld() == null) return;
        final LinkedList<TempBlock> stack = layers().get(block);
        if (stack == null || stack.isEmpty()) {
            tops().remove(block);
            visibility().remove(VisibilityKey.of(block));
            return;
        }
        tops().put(block, stack.getLast());
        final List<LayerView> layers = new ArrayList<>(stack.size());
        final List<UUID> owners = new ArrayList<>(stack.size());
        for (TempBlock layer : stack) {
            layers.add(new LayerView(layer.layerId, layer.ownerId, layer.newData.clone()));
            owners.add(layer.ownerId);
        }
        visibility().put(VisibilityKey.of(block), new VisibilitySnapshot(
                stack.getFirst().state.getBlockData().clone(), List.copyOf(layers),
                Collections.unmodifiableList(owners)));
    }

    public static boolean isTopLayerOwnedBy(final World world, final int x, final int y, final int z,
                                             final UUID playerId) {
        if (world == null || playerId == null) return false;
        final VisibilitySnapshot snapshot = visibility().get(new VisibilityKey(world, x, y, z));
        return snapshot != null && TempBlockOwnershipPolicy.topLayerOwnedBy(snapshot.owners, playerId);
    }

    public static boolean hasOwnedLayer(final Block block, final UUID playerId) {
        if (block == null || block.getWorld() == null || playerId == null) return false;
        final VisibilitySnapshot snapshot = visibility().get(VisibilityKey.of(block));
        if (snapshot == null) return false;
        for (UUID owner : snapshot.owners) if (playerId.equals(owner)) return true;
        return false;
    }

    public static BlockData getVisibleData(final Block block, final UUID playerId) {
        if (block == null || block.getWorld() == null) return null;
        return getVisibleData(block.getWorld(), block.getX(), block.getY(), block.getZ(), playerId);
    }

    public static BlockData getVisibleData(final World world, final int x, final int y, final int z,
                                           final UUID playerId) {
        final VisibilitySnapshot snapshot = visibility().get(new VisibilityKey(world, x, y, z));
        if (snapshot == null) return null;
        final int index = TempBlockOwnershipPolicy.visibleLayerIndex(snapshot.owners, playerId);
        return index < 0 ? snapshot.original.clone() : snapshot.layers.get(index).data.clone();
    }

    public static Map<UUID, BlockData> getOwnerViews(final Block block, final UUID additionalOwner) {
        if (block == null || block.getWorld() == null) return Map.of();
        final VisibilitySnapshot snapshot = visibility().get(VisibilityKey.of(block));
        final Set<UUID> owners = new HashSet<>();
        if (snapshot != null) for (UUID owner : snapshot.owners) if (owner != null) owners.add(owner);
        if (additionalOwner != null) owners.add(additionalOwner);
        final Map<UUID, BlockData> result = new HashMap<>();
        for (UUID owner : owners) {
            final BlockData data = getVisibleData(block, owner);
            if (data != null) result.put(owner, data);
        }
        return Map.copyOf(result);
    }

    public static List<VisibleBlock> getOwnedBlocksInChunk(final World world, final int chunkX, final int chunkZ,
                                                            final UUID playerId) {
        if (world == null || playerId == null) return List.of();
        final List<VisibleBlock> result = new ArrayList<>();
        visibility().forEach((key, snapshot) -> {
            if ((!key.world.equals(world)) || key.x >> 4 != chunkX || key.z >> 4 != chunkZ) return;
            boolean owned = false;
            for (UUID owner : snapshot.owners) {
                if (playerId.equals(owner)) {
                    owned = true;
                    break;
                }
            }
            if (owned) result.add(new VisibleBlock(key.x, key.y, key.z,
                    getVisibleData(world, key.x, key.y, key.z, playerId)));
        });
        return List.copyOf(result);
    }

    /** Reverts every registered layer at this coordinate; an absent stack is a no-op. */
    public static void revertBlock(final Block block, final Material defaultType) {
        RollbackLayers.beforeLiveMutation(block);
        final List<TempBlock> snapshot;
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = layers().get(block);
            snapshot = stack == null ? List.of() : new ArrayList<>(stack);
        }
        for (TempBlock layer : snapshot) layer.revertBlock();
    }

    public static boolean applyPhysics(final Material material) {
        return GeneralMethods.isLightEmitting(material)
                || ((material == Material.FIRE || material == Material.SOUL_FIRE) && FireAbility.canFireGrief());
    }

    public Block getBlock() {
        return this.block;
    }

    public BlockData getBlockData() {
        return this.newData.clone();
    }

    public Location getLocation() {
        return this.block.getLocation();
    }

    public BlockState getState() {
        return this.state;
    }

    public void setState(final BlockState newState) {
        RollbackLayers.beforeLiveMutation(this.block);
        if (newState == null) return;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = layers().get(this.block);
            if (stack == null || !stack.contains(this)) return;
            for (TempBlock layer : stack) layer.state = newState;
            refreshViewsLocked(this.block);
        }
    }

    public Optional<CoreAbility> getAbility() {
        return this.ability;
    }

    public long getLayerId() {
        return this.layerId;
    }

    public long getRevision() {
        return this.revision;
    }

    public String getEffectAbility() {
        return this.effectAbility;
    }

    public long getEffectStep() {
        return this.effectStep;
    }

    public int getEffectOrdinal() {
        return this.effectOrdinal;
    }

    public Optional<UUID> getOwnerId() {
        return Optional.ofNullable(this.ownerId);
    }

    public TempBlock setAbility(final CoreAbility ability) {
        RollbackLayers.beforeLiveMutation(this.block);
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return this;
            this.ability = Optional.ofNullable(ability);
            this.ownerId = ownerId(ability);
            refreshViewsLocked(this.block);
            advanceRevisionLocked();
            publishLocked(TempBlockSync.Operation.CREATE, this.newData, false);
        }
        return this;
    }

    public Runnable getRevertTask() {
        return this.revertTask;
    }

    public void setRevertTask(final Runnable task) {
        RollbackLayers.beforeLiveMutation(this.block);
        synchronized (MUTATION_LOCK) {
            this.revertTask = task;
        }
    }

    /** @deprecated use {@link #setRevertTask(Runnable)}. */
    @Deprecated
    public void setRevertTask(final RevertTask task) {
        setRevertTask((Runnable) task);
    }

    public long getRevertTime() {
        return this.revertTime;
    }

    public void setRevertTime(final long duration) {
        RollbackLayers.beforeLiveMutation(this.block);
        synchronized (MUTATION_LOCK) {
            if (this.reverted || duration <= 0L || this.state.hasBlockEntity()) return;
            unscheduleLocked(this);
            scheduleLocked(duration);
            advanceRevisionLocked();
            publishLocked(TempBlockSync.Operation.UPDATE_EXPIRY, this.newData, false);
        }
    }

    /** Re-publishes semantic ability metadata without changing this layer's expiry. */
    public void refreshPredictionMetadata() {
        RollbackLayers.beforeLiveMutation(this.block);
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            advanceRevisionLocked();
            publishLocked(TempBlockSync.Operation.UPDATE_EXPIRY, this.newData, false);
        }
    }

    public void revertBlock() {
        revertBlock(true);
    }

    /**
     * Retires only this layer without repainting its captured snapshot or
     * invoking ability callbacks. This is a lifecycle operation for callers
     * which intentionally abandon a layer; reconciliation never calls it to
     * undo an already-running client prediction.
     */
    public void discard() {
        RollbackLayers.beforeLiveMutation(this.block);
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = layers().get(this.block);
            if (stack == null || !stack.contains(this)) {
                this.reverted = true;
                layersById().remove(this.layerId);
                unscheduleLocked(this);
                return;
            }
            stack.remove(this);
            layersById().remove(this.layerId);
            this.reverted = true;
            unscheduleLocked(this);
            if (stack.isEmpty()) layers().remove(this.block);
            refreshViewsLocked(this.block);
            final BlockData effectiveData = stack.isEmpty()
                    ? this.state.getBlockData().clone() : stack.getLast().newData.clone();
            advanceRevisionLocked();
            publishLocked(TempBlockSync.Operation.DISCARD, effectiveData, false);
        }
    }

    private void revertBlock(final boolean removeFromQueue) {
        RollbackLayers.beforeLiveMutation(this.block);
        boolean complete = false;
        Throwable writeFailure = null;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = layers().get(this.block);
            if (stack == null || !stack.contains(this)) {
                this.reverted = true;
                layersById().remove(this.layerId);
                if (removeFromQueue) unscheduleLocked(this);
                return;
            }

            final boolean wasTop = stack.getLast() == this;
            stack.remove(this);
            layersById().remove(this.layerId);
            this.reverted = true;
            if (removeFromQueue) unscheduleLocked(this);
            else this.scheduled = false;
            if (stack.isEmpty()) layers().remove(this.block);
            refreshViewsLocked(this.block);

            final BlockData effectiveData = stack.isEmpty()
                    ? this.state.getBlockData().clone() : stack.getLast().newData.clone();
            advanceRevisionLocked();
            if (wasTop) {
                try {
                    writeTopLocked(TempBlockSync.Operation.REVERT, effectiveData,
                            () -> restoreTopLocked(stack, effectiveData));
                } catch (RuntimeException | Error failure) {
                    writeFailure = failure;
                }
            }
            publishLocked(TempBlockSync.Operation.REVERT, effectiveData, wasTop);
            complete = true;
        }
        if (complete) finishLayer(this);
        if (writeFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (writeFailure instanceof Error errorFailure) throw errorFailure;
    }

    private void restoreTopLocked(final LinkedList<TempBlock> remaining, final BlockData effectiveData) {
        if (!remaining.isEmpty()) {
            final boolean physics = applyPhysics(effectiveData.getMaterial());
            try {
                this.block.setBlockData(effectiveData.clone(), physics);
            } catch (RuntimeException firstFailure) {
                if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw firstFailure;
                try {
                    this.block.setBlockData(effectiveData.clone(), physics);
                } catch (RuntimeException finalFailure) {
                    finalFailure.addSuppressed(firstFailure);
                    throw finalFailure;
                }
            }
            return;
        }

        final BlockData originalData = this.state.getBlockData().clone();
        final boolean physics = applyPhysics(this.state.getType()) && !(originalData instanceof Bisected);
        try {
            if (this.state.update(true, physics)) return;
        } catch (RuntimeException firstFailure) {
            if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw firstFailure;
            try {
                this.block.setBlockData(originalData.clone(), physics);
                return;
            } catch (RuntimeException finalFailure) {
                finalFailure.addSuppressed(firstFailure);
                throw finalFailure;
            }
        }
        this.block.setBlockData(originalData, physics);
    }

    public void addAttachedBlock(final TempBlock tempBlock) {
        RollbackLayers.beforeLiveMutation(this.block);
        if (tempBlock != null) RollbackLayers.beforeLiveMutation(tempBlock.block);
        if (tempBlock == null || tempBlock == this) return;
        synchronized (MUTATION_LOCK) {
            this.attachedTempBlocks.add(tempBlock);
            tempBlock.attachedTempBlocks.add(this);
        }
    }

    public Set<TempBlock> getAttachedTempBlocks() {
        synchronized (MUTATION_LOCK) {
            return Set.copyOf(this.attachedTempBlocks);
        }
    }

    @Experimental
    public boolean isBendableSource() {
        return this.bendableSource;
    }

    @Experimental
    public TempBlock setBendableSource(final boolean value) {
        RollbackLayers.beforeLiveMutation(this.block);
        this.bendableSource = value;
        return this;
    }

    public boolean canSuffocate() {
        return this.suffocate;
    }

    public TempBlock setCanSuffocate(final boolean value) {
        RollbackLayers.beforeLiveMutation(this.block);
        this.suffocate = value;
        return this;
    }

    public void setType(final Material material) {
        setType(requireMaterial(material).createBlockData());
    }

    /** @deprecated {@code material} is redundant; the supplied data is used. */
    @Deprecated
    public void setType(final Material material, final BlockData data) {
        setType(data);
    }

    public void setType(final BlockData data) {
        RollbackLayers.beforeLiveMutation(this.block);
        if (data == null) return;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = layers().get(this.block);
            if (stack == null || !stack.contains(this)) {
                this.reverted = true;
                layersById().remove(this.layerId);
                return;
            }
            final BlockData previousData = this.newData;
            this.newData = data.clone();
            final boolean top = stack.getLast() == this;
            refreshViewsLocked(this.block);
            advanceRevisionLocked();
            try {
                if (top) {
                    writeTopLocked(TempBlockSync.Operation.CREATE, this.newData,
                            () -> this.block.setBlockData(this.newData.clone(), applyPhysics(this.newData.getMaterial())));
                }
                publishLocked(TempBlockSync.Operation.CREATE, this.newData, top);
            } catch (RuntimeException | Error failure) {
                this.newData = previousData;
                refreshViewsLocked(this.block);
                advanceRevisionLocked();
                publishLocked(TempBlockSync.Operation.CREATE, this.newData, false);
                throw failure;
            }
        }
    }

    public boolean isReverted() {
        return this.reverted;
    }

    public void updateSnowableBlock(final Block block, final boolean snowy) {
        if (block == null) return;
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = layers().get(block);
            final TempBlock top = stack == null || stack.isEmpty() ? null : stack.getLast();
            final BlockData currentData = top == null ? block.getBlockData() : top.newData.clone();
            if (!(currentData instanceof Snowable snowable)) return;
            if (snowable.isSnowy() == snowy) return;
            snowable.setSnowy(snowy);
            if (top != null && top != this) {
                top.setType(snowable);
                return;
            }
            block.setBlockData(snowable, false);
        }
    }

    private static boolean isSnowCover(final Material material) {
        return material == Material.SNOW || material == Material.SNOW_BLOCK;
    }

    @Override
    public String toString() {
        return "TempBlock{" +
                "block=[" + block.getX() + ',' + block.getY() + ',' + block.getZ() + "]" +
                ", layerId=" + layerId +
                ", revision=" + revision +
                ", newData=" + newData.getAsString() +
                ", attachedTempBlocks=" + attachedTempBlocks.size() +
                ", revertTime=" + (revertTime == 0L ? "N/A" : revertTime - RollbackClock.millis() + "ms") +
                ", reverted=" + reverted +
                ", ability=" + ability.map(value -> value.getClass().getName()).orElse("null") +
                '}';
    }

    /** @deprecated a plain {@link Runnable} is equivalent. */
    @Deprecated
    public interface RevertTask extends com.projectkorra.projectkorra.platform.PKRunnable {
    }

    public record VisibleBlock(int x, int y, int z, BlockData data) {
    }

    private record VisibilityKey(World world, int x, int y, int z) {
        private static VisibilityKey of(final Block block) {
            return new VisibilityKey(block.getWorld(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record LayerView(long id, UUID ownerId, BlockData data) {
    }

    private record VisibilitySnapshot(BlockData original, List<LayerView> layers, List<UUID> owners)
            implements RollbackStateCell<Void> {
        // The private owners list is an immutable owned copy and permits null (unowned layers).
        // Only block-data children are mutable; do not reflect into the JDK's read-only list wrapper.
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
        @Override public List<?> rollbackReferences() { return List.of(original, layers); }
    }

    private static final class EffectCounter {
        private final long step;
        private final int ordinal;
        private EffectCounter(long step, int ordinal) { this.step = step; this.ordinal = ordinal; }
    }

    private record EffectIdentity(String ability, long step, int ordinal) {
    }

    public static class TempBlockRevertTask implements Runnable {
        @Override
        public void run() {
            final long now = RollbackClock.millis();
            while (true) {
                final TempBlock expired;
                synchronized (MUTATION_LOCK) {
                    expired = expirations().peek();
                    if (expired == null || expired.revertTime > now) return;
                    expirations().poll();
                    expired.scheduled = false;
                }
                try {
                    expired.revertBlock(false);
                } catch (RuntimeException failure) {
                    if (RollbackClock.active() || com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active()) throw failure;
                    ProjectKorra.log.warning("Timed TempBlock restore failed at " + expired.getLocation()
                            + ": " + failure.getMessage());
                }
            }
        }
    }
}
