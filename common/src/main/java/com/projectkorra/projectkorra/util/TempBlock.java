package com.projectkorra.projectkorra.util;

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

    /** Compatibility view containing the current top layer at each coordinate. */
    @Deprecated
    public static final Map<Block, TempBlock> instances = new ConcurrentHashMap<>();

    private final Block block;
    private final long layerId = NEXT_LAYER_ID.incrementAndGet();
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
            LinkedList<TempBlock> stack = LAYERS.get(block);
            this.state = stack == null || stack.isEmpty() ? block.getState() : stack.getFirst().state;
            if (this.state.hasBlockEntity() || this.state.getType() == Material.JUKEBOX) {
                this.reverted = true;
                return;
            }

            if (stack == null) {
                stack = new LinkedList<>();
                LAYERS.put(block, stack);
            }
            stack.addLast(this);
            LAYERS_BY_ID.put(this.layerId, this);
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
                LAYERS_BY_ID.remove(this.layerId);
                this.reverted = true;
                if (stack.isEmpty()) LAYERS.remove(block);
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
            final EffectCounter previous = EFFECT_COUNTERS.get(ability);
            final int ordinal = previous != null && previous.step == step
                    ? previous.ordinal + 1 : 1;
            EFFECT_COUNTERS.put(ability, new EffectCounter(step, ordinal));
            return new EffectIdentity(ability.getName(), step, ordinal);
        }
    }

    public static TempBlock get(final Block block) {
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = LAYERS.get(block);
            return stack == null || stack.isEmpty() ? null : stack.getLast();
        }
    }

    public static LinkedList<TempBlock> getAll(final Block block) {
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = LAYERS.get(block);
            return stack == null ? null : new LinkedList<>(stack);
        }
    }

    public static List<TempBlock> getActiveLayers() {
        synchronized (MUTATION_LOCK) {
            final List<TempBlock> result = new ArrayList<>();
            LAYERS.values().forEach(result::addAll);
            return List.copyOf(result);
        }
    }

    /** Constant-time identity lookup used by the prediction lifecycle. */
    public static TempBlock getActiveLayer(final long layerId) {
        synchronized (MUTATION_LOCK) {
            final TempBlock layer = LAYERS_BY_ID.get(layerId);
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
            for (TempBlock layer : LAYERS_BY_ID.values()) {
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
            final LinkedList<TempBlock> stack = LAYERS.get(block);
            return block != null && stack != null && !stack.isEmpty();
        }
    }

    public static boolean isTouchingTempBlock(final Block block) {
        if (block == null) return false;
        synchronized (MUTATION_LOCK) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                    BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                final LinkedList<TempBlock> stack = LAYERS.get(block.getRelative(face));
                if (stack != null && !stack.isEmpty()) return true;
            }
            return false;
        }
    }

    public static void removeAll() {
        final List<TempBlock> snapshot = getActiveLayers();
        try {
            for (TempBlock layer : snapshot) {
                try {
                    layer.revertBlock();
                } catch (RuntimeException failure) {
                    ProjectKorra.log.warning("TempBlock shutdown restore failed at " + layer.getLocation()
                            + ": " + failure.getMessage());
                }
            }
        } finally {
            synchronized (MUTATION_LOCK) {
                EXPIRATIONS.clear();
                if (LAYERS.isEmpty()) LAYERS_BY_ID.clear();
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
            for (Block block : new ArrayList<>(LAYERS.keySet())) {
                invalidateStackLocked(block, null, false);
            }
            EXPIRATIONS.clear();
            LAYERS_BY_ID.clear();
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
        synchronized (MUTATION_LOCK) {
            invalidateStackLocked(block, block == null ? null : block.getBlockData(), true);
        }
    }

    private static List<TempBlock> invalidateStackLocked(final Block block, final BlockData effectiveData,
                                                          final boolean publish) {
        if (block == null) return List.of();
        final LinkedList<TempBlock> stack = LAYERS.remove(block);
        if (stack == null || stack.isEmpty()) {
            instances.remove(block);
            if (block.getWorld() != null) VISIBILITY.remove(VisibilityKey.of(block));
            return List.of();
        }
        instances.remove(block);
        VISIBILITY.remove(VisibilityKey.of(block));
        final List<TempBlock> removed = new ArrayList<>(stack.size());
        for (TempBlock layer : stack) {
            unscheduleLocked(layer);
            LAYERS_BY_ID.remove(layer.layerId);
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
        final Runnable completion = () -> {
            if (layer.revertTask != null) {
                try {
                    layer.revertTask.run();
                } catch (RuntimeException failure) {
                    ProjectKorra.log.warning("TempBlock revert callback failed at " + layer.getLocation()
                            + ": " + failure.getMessage());
                }
            }
            for (TempBlock attached : layer.getAttachedTempBlocks()) {
                try {
                    attached.revertBlock();
                } catch (RuntimeException failure) {
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
        final long now = System.currentTimeMillis();
        final long aligned = PredictionTiming.alignDuration(this.ability.orElse(null), duration);
        this.revertTime = aligned >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + aligned;
        this.scheduled = true;
        EXPIRATIONS.add(this);
    }

    private static void unscheduleLocked(final TempBlock layer) {
        if (!layer.scheduled) return;
        EXPIRATIONS.remove(layer);
        layer.scheduled = false;
    }

    private void advanceRevisionLocked() {
        this.revision = NEXT_REVISION.incrementAndGet();
    }

    /** Sends pre-mutation metadata before the vanilla packet can be emitted. */
    private void writeTopLocked(final TempBlockSync.Operation operation, final BlockData effectiveData,
                                final Runnable worldWrite) {
        TempBlockSync.beforeWorldChange(operation, this, effectiveData);
        TempBlockSync.runWorldMutation(operation, this, effectiveData, worldWrite);
    }

    private void publishLocked(final TempBlockSync.Operation operation, final BlockData effectiveData,
                               final boolean physicalWrite) {
        TempBlockSync.publish(operation, this, effectiveData, physicalWrite);
    }

    private static void refreshViewsLocked(final Block block) {
        if (block == null || block.getWorld() == null) return;
        final LinkedList<TempBlock> stack = LAYERS.get(block);
        if (stack == null || stack.isEmpty()) {
            instances.remove(block);
            VISIBILITY.remove(VisibilityKey.of(block));
            return;
        }
        instances.put(block, stack.getLast());
        final List<LayerView> layers = new ArrayList<>(stack.size());
        final List<UUID> owners = new ArrayList<>(stack.size());
        for (TempBlock layer : stack) {
            layers.add(new LayerView(layer.layerId, layer.ownerId, layer.newData.clone()));
            owners.add(layer.ownerId);
        }
        VISIBILITY.put(VisibilityKey.of(block), new VisibilitySnapshot(
                stack.getFirst().state.getBlockData().clone(), List.copyOf(layers),
                Collections.unmodifiableList(owners)));
    }

    public static boolean isTopLayerOwnedBy(final World world, final int x, final int y, final int z,
                                             final UUID playerId) {
        if (world == null || playerId == null) return false;
        final VisibilitySnapshot snapshot = VISIBILITY.get(new VisibilityKey(world, x, y, z));
        return snapshot != null && TempBlockOwnershipPolicy.topLayerOwnedBy(snapshot.owners, playerId);
    }

    public static boolean hasOwnedLayer(final Block block, final UUID playerId) {
        if (block == null || block.getWorld() == null || playerId == null) return false;
        final VisibilitySnapshot snapshot = VISIBILITY.get(VisibilityKey.of(block));
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
        final VisibilitySnapshot snapshot = VISIBILITY.get(new VisibilityKey(world, x, y, z));
        if (snapshot == null) return null;
        final int index = TempBlockOwnershipPolicy.visibleLayerIndex(snapshot.owners, playerId);
        return index < 0 ? snapshot.original.clone() : snapshot.layers.get(index).data.clone();
    }

    public static Map<UUID, BlockData> getOwnerViews(final Block block, final UUID additionalOwner) {
        if (block == null || block.getWorld() == null) return Map.of();
        final VisibilitySnapshot snapshot = VISIBILITY.get(VisibilityKey.of(block));
        final Set<UUID> owners = new HashSet<>();
        if (snapshot != null) for (UUID owner : snapshot.owners) if (owner != null) owners.add(owner);
        if (additionalOwner != null) owners.add(additionalOwner);
        final Map<UUID, BlockData> result = new HashMap<>();
        for (UUID owner : owners) {
            final BlockData data = getVisibleData(block, owner);
            if (data != null) result.put(owner, data.clone());
        }
        return Map.copyOf(result);
    }

    public static List<VisibleBlock> getOwnedBlocksInChunk(final World world, final int chunkX, final int chunkZ,
                                                            final UUID playerId) {
        if (world == null || playerId == null) return List.of();
        final List<VisibleBlock> result = new ArrayList<>();
        VISIBILITY.forEach((key, snapshot) -> {
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
        final List<TempBlock> snapshot;
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> stack = LAYERS.get(block);
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
        if (newState == null) return;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = LAYERS.get(this.block);
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
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = LAYERS.get(this.block);
            if (stack == null || !stack.contains(this)) {
                this.reverted = true;
                LAYERS_BY_ID.remove(this.layerId);
                unscheduleLocked(this);
                return;
            }
            stack.remove(this);
            LAYERS_BY_ID.remove(this.layerId);
            this.reverted = true;
            unscheduleLocked(this);
            if (stack.isEmpty()) LAYERS.remove(this.block);
            refreshViewsLocked(this.block);
            final BlockData effectiveData = stack.isEmpty()
                    ? this.state.getBlockData().clone() : stack.getLast().newData.clone();
            advanceRevisionLocked();
            publishLocked(TempBlockSync.Operation.DISCARD, effectiveData, false);
        }
    }

    private void revertBlock(final boolean removeFromQueue) {
        boolean complete = false;
        Throwable writeFailure = null;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = LAYERS.get(this.block);
            if (stack == null || !stack.contains(this)) {
                this.reverted = true;
                LAYERS_BY_ID.remove(this.layerId);
                if (removeFromQueue) unscheduleLocked(this);
                return;
            }

            final boolean wasTop = stack.getLast() == this;
            stack.remove(this);
            LAYERS_BY_ID.remove(this.layerId);
            this.reverted = true;
            if (removeFromQueue) unscheduleLocked(this);
            else this.scheduled = false;
            if (stack.isEmpty()) LAYERS.remove(this.block);
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
        this.bendableSource = value;
        return this;
    }

    public boolean canSuffocate() {
        return this.suffocate;
    }

    public TempBlock setCanSuffocate(final boolean value) {
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
        if (data == null) return;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> stack = LAYERS.get(this.block);
            if (stack == null || !stack.contains(this)) {
                this.reverted = true;
                LAYERS_BY_ID.remove(this.layerId);
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
        if (block != null && block.getBlockData() instanceof Snowable snowable) {
            snowable.setSnowy(snowy);
            block.setBlockData(snowable);
        }
    }

    @Override
    public String toString() {
        return "TempBlock{" +
                "block=[" + block.getX() + ',' + block.getY() + ',' + block.getZ() + "]" +
                ", layerId=" + layerId +
                ", revision=" + revision +
                ", newData=" + newData.getAsString() +
                ", attachedTempBlocks=" + attachedTempBlocks.size() +
                ", revertTime=" + (revertTime == 0L ? "N/A" : revertTime - System.currentTimeMillis() + "ms") +
                ", reverted=" + reverted +
                ", ability=" + ability.map(value -> value.getClass().getName()).orElse("null") +
                '}';
    }

    /** @deprecated a plain {@link Runnable} is equivalent. */
    @Deprecated
    public interface RevertTask extends Runnable {
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

    private record VisibilitySnapshot(BlockData original, List<LayerView> layers, List<UUID> owners) {
    }

    private record EffectCounter(long step, int ordinal) {
    }

    private record EffectIdentity(String ability, long step, int ordinal) {
    }

    public static class TempBlockRevertTask implements Runnable {
        @Override
        public void run() {
            final long now = System.currentTimeMillis();
            while (true) {
                final TempBlock expired;
                synchronized (MUTATION_LOCK) {
                    expired = EXPIRATIONS.peek();
                    if (expired == null || expired.revertTime > now) return;
                    EXPIRATIONS.poll();
                    expired.scheduled = false;
                }
                try {
                    expired.revertBlock(false);
                } catch (RuntimeException failure) {
                    ProjectKorra.log.warning("Timed TempBlock restore failed at " + expired.getLocation()
                            + ": " + failure.getMessage());
                }
            }
        }
    }
}
