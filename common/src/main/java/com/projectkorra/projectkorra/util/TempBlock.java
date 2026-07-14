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
import com.projectkorra.projectkorra.platform.mc.block.Container;
import com.projectkorra.projectkorra.platform.mc.block.data.Bisected;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.PredictionTiming;
import com.projectkorra.projectkorra.prediction.TempBlockOwnershipPolicy;
import com.projectkorra.projectkorra.prediction.TempBlockSync;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class TempBlock {

    private static final Object MUTATION_LOCK = new Object();
    private static final Map<Block, LinkedList<TempBlock>> instances_ = new HashMap<>();
    private static final PriorityQueue<TempBlock> REVERT_QUEUE = new PriorityQueue<>(128,
            (t1, t2) -> Long.compare(t1.revertTime, t2.revertTime));
    private static final AtomicLong NEXT_LAYER_ID = new AtomicLong();
    private static final AtomicLong NEXT_REVISION = new AtomicLong();
    private static final Map<VisibilityKey, VisibilitySnapshot> VISIBILITY = new ConcurrentHashMap<>();
    /**
     * Marked for removal. Doesn't do anything right now
     */
    @Deprecated
    public static Map<Block, TempBlock> instances = new ConcurrentHashMap<>();

    private final Block block;
    private final long layerId = NEXT_LAYER_ID.incrementAndGet();
    private long revision;
    private UUID ownerId;
    private BlockData newData;
    private BlockState state;
    private Set<TempBlock> attachedTempBlocks; //Temp Block states that should be reverted as well when the temp block expires (e.g. double blocks)
    private long revertTime;
    private boolean inRevertQueue;
    private volatile boolean reverted;
    private Runnable revertTask = null;
    private Optional<CoreAbility> ability = Optional.empty(); // If we want this TempBlock to have an assigned ability created from it
    private boolean isBendableSource = false;
    private boolean suffocate = true;

    public TempBlock(final Block block, final Material newtype) {
        this(block, newtype.createBlockData(), 0);
    }

    @Deprecated
    /**
     * Deprecated. Using the newType here is pointless.
     */
    public TempBlock(final Block block, final Material newtype, final BlockData newData) {
        this(block, newData, 0);
    }

    public TempBlock(final Block block, final BlockData newData) {
        this(block, newData, 0);
    }

    public TempBlock(final Block block, final BlockData newData, final long revertTime, final CoreAbility ability) {
        this(block, newData, revertTime, ability, true);
    }

    public TempBlock(final Block block, final BlockData newData, final CoreAbility ability) {
        this(block, newData, 0, ability);
    }

    public TempBlock(final Block block, BlockData newData, final long revertTime) {
        this(block, newData, revertTime, null, true);
    }

    private TempBlock(final Block block, BlockData newData, final long revertTime,
                      final CoreAbility owner, final boolean ignored) {
        this.block = block;
        this.ability = Optional.ofNullable(owner != null ? owner : AbilityExecutionContext.current());
        this.ownerId = this.ability.map(CoreAbility::getPlayer).filter(Objects::nonNull)
                .map(com.projectkorra.projectkorra.platform.mc.entity.Entity::getUniqueId).orElse(null);
        this.attachedTempBlocks = new HashSet<>(0);
        this.suffocate = ability.isPresent() ? !(ability.get() instanceof WaterAbility) : false;

        //Fire griefing will make the state update on its own, so we don't need to update it ourselves
        if (!FireAbility.canFireGrief() && (newData.getMaterial() == Material.FIRE || newData.getMaterial() == Material.SOUL_FIRE)) {
            newData = FireAbility.createFireState(block, newData.getMaterial() == Material.SOUL_FIRE); //Fix the blockstate looking incorrect
        }
        this.newData = newData;
        if (block.getType() == Material.SNOW) {
            if (newData.getMaterial() == Material.AIR) {
                updateSnowableBlock(block.getRelative(BlockFace.DOWN), false);
            }
        }

        synchronized (MUTATION_LOCK) {
            discardStaleStack(block);
            if (instances_.containsKey(block)) {
                final TempBlock temp = instances_.get(block).getFirst();
                this.state = temp.state; //Set the original blockstate of the tempblock
            } else {
                this.state = block.getState();
                if (this.state instanceof Container || this.state.getType() == Material.JUKEBOX) {
                    this.reverted = true;
                    return;
                }
            }
            put(block, this);
            if (revertTime > 0 && !(this.state instanceof Container)) {
                this.revertTime = PredictionTiming.alignDuration(this.ability.orElse(null), revertTime)
                        + System.currentTimeMillis();
                this.inRevertQueue = true;
                REVERT_QUEUE.add(this);
            }
            // Packet filters must be armed before setBlockData can synchronously
            // emit the vanilla update. Lifecycle metadata is published only
            // after the physical mutation succeeds.
            TempBlockSync.beforeWorldChange(TempBlockSync.Operation.CREATE, this, newData);
            applyWorldData(this, newData);
            // Registration, world mutation, expiry and publication form one
            // transaction. No observer can see a half-created layer.
            publish(TempBlockSync.Operation.CREATE, this.newData, true);
        }
    }

    /**
     * Get a TempBlock at a location
     *
     * @param block The block location
     * @return The topmost TempBlock
     */
    public static TempBlock get(final Block block) {
        synchronized (MUTATION_LOCK) {
            if (isTempBlockLocked(block)) {
                return instances_.get(block).getLast();
            }
            return null;
        }
    }

    /**
     * Get all TempBlocks at the given location
     *
     * @param block The block location
     * @return The list of TempBlocks
     */
    public static LinkedList<TempBlock> getAll(Block block) {
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> layers = instances_.get(block);
            return layers == null ? null : new LinkedList<>(layers);
        }
    }

    /** Stable copy used to rebuild a prediction client's TempBlock ledger. */
    public static List<TempBlock> getActiveLayers() {
        synchronized (MUTATION_LOCK) {
            final List<TempBlock> result = new ArrayList<>();
            for (LinkedList<TempBlock> layers : instances_.values()) result.addAll(layers);
            return List.copyOf(result);
        }
    }

    /**
     * Place a TempBlock in the system
     *
     * @param block     The block location
     * @param tempBlock The TempBlock
     */
    private static void put(Block block, TempBlock tempBlock) {
        if (!instances_.containsKey(block)) {
            instances_.put(block, new LinkedList<>());
        }
        instances_.get(block).add(tempBlock);
        refreshVisibility(block);
    }

    public static boolean isTempBlock(final Block block) {
        synchronized (MUTATION_LOCK) {
            return isTempBlockLocked(block);
        }
    }

    private static boolean isTempBlockLocked(final Block block) {
        return block != null && instances_.containsKey(block) && !instances_.get(block).isEmpty();
    }

    /**
     * Is the specified block touching a TempBlock? Used to prevent physics updates
     * for things like Water
     *
     * @param block The block location
     * @return True if there is a TempBlock beside it
     */
    public static boolean isTouchingTempBlock(final Block block) {
        synchronized (MUTATION_LOCK) {
            final BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
            for (final BlockFace face : faces) {
                if (isTempBlockLocked(block.getRelative(face))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Remove and revert all TempBlocks on the server. Done at server shutdown or PK reload.
     */
    public static void removeAll() {
        final Set<Block> blocks;
        synchronized (MUTATION_LOCK) {
            blocks = new HashSet<>(instances_.keySet());
        }
        for (final Block block : blocks) {
            revertBlock(block, Material.AIR);
        }
        synchronized (MUTATION_LOCK) {
            REVERT_QUEUE.clear();
        }
    }

    public static void removeAllInWorld(World world) {
        final Set<Block> blocks;
        synchronized (MUTATION_LOCK) {
            blocks = new HashSet<>(instances_.keySet());
        }
        for (final Block block : blocks) {
            if (block.getWorld() == world) {
                revertBlock(block, Material.AIR);
            }
        }
    }

    /**
     * Remove all TempBlocks at this location. Used for when a player places a block inside a TempBlock
     *
     * @param block The block location
     */
    public static void removeBlock(final Block block) {
        final List<TempBlock> invalidated;
        synchronized (MUTATION_LOCK) {
            invalidated = invalidateStack(block, block == null ? null : block.getBlockData(), true);
        }
        finishInvalidated(invalidated);
    }

    /**
     * Drops local bookkeeping after external authority has already won. Unlike
     * {@link #removeBlock(Block)}, this never publishes, writes the world, or
     * runs revert/attachment callbacks that could create a second stale change.
     */
    public static void discardBlock(final Block block) {
        synchronized (MUTATION_LOCK) {
            invalidateStack(block, block == null ? null : block.getBlockData(), false);
        }
    }

    /**
     * Remove this instance from the system
     *
     * @param tempBlock The TempBlock to remove
     */
    private static void remove(TempBlock tempBlock) {
        if (instances_.containsKey(tempBlock.block)) {
            instances_.get(tempBlock.block).remove(tempBlock);
            if (instances_.get(tempBlock.block).size() == 0) {
                instances_.remove(tempBlock.block);
            }
            refreshVisibility(tempBlock.block);
        }
    }

    private static boolean sameData(final BlockData first, final BlockData second) {
        // BlockData#getAsString only contains the material in the loader-neutral
        // model. Treating every WATER state as equal concealed fluid-level and
        // waterlogged changes from the stack audit, which then let stale water
        // layers survive their real server block. The wire encoding includes
        // every property represented by the common model.
        return first != null && second != null
                && TempBlockSync.encode(first).equals(TempBlockSync.encode(second));
    }

    /** Drop an unannounced stack when some external code has replaced its top. */
    private static void discardStaleStack(final Block block) {
        final LinkedList<TempBlock> layers = instances_.get(block);
        if (layers == null || layers.isEmpty()) return;
        final TempBlock top = layers.getLast();
        if (sameData(block.getBlockData(), top.newData)) return;
        final List<TempBlock> invalidated = invalidateStack(block, block.getBlockData(), true);
        finishInvalidated(invalidated);
    }

    private static List<TempBlock> invalidateStack(final Block block, final BlockData effectiveData,
                                                    final boolean publishChanges) {
        if (block == null) return List.of();
        final LinkedList<TempBlock> layers = instances_.remove(block);
        if (layers == null || layers.isEmpty()) return List.of();
        refreshVisibility(block);
        final List<TempBlock> invalidated = new ArrayList<>(layers.size());
        for (TempBlock layer : layers) {
            REVERT_QUEUE.remove(layer);
            layer.inRevertQueue = false;
            if (layer.reverted) continue;
            layer.reverted = true;
            if (publishChanges && effectiveData != null) {
                layer.publish(TempBlockSync.Operation.REVERT, effectiveData);
            }
            invalidated.add(layer);
        }
        return invalidated;
    }

    private static void finishInvalidated(final List<TempBlock> invalidated) {
        for (TempBlock layer : invalidated) finishLayer(layer);
    }

    private static void finishLayer(final TempBlock layer) {
        if (layer.revertTask != null) {
            try {
                layer.revertTask.run();
            } catch (RuntimeException failure) {
                ProjectKorra.log.warning("TempBlock revert callback failed at " + layer.getLocation()
                        + ": " + failure.getMessage());
            }
        }
        for (TempBlock attached : new HashSet<>(layer.attachedTempBlocks)) {
            try {
                attached.revertBlock();
            } catch (RuntimeException failure) {
                ProjectKorra.log.warning("Attached TempBlock revert failed at " + attached.getLocation()
                        + ": " + failure.getMessage());
            }
        }
    }

    private static void applyWorldData(final TempBlock layer, final BlockData data) {
        layer.block.setBlockData(data, applyPhysics(data.getMaterial()));
    }

    private static void refreshVisibility(final Block block) {
        if (block == null || block.getWorld() == null) return;
        final VisibilityKey key = VisibilityKey.of(block);
        final LinkedList<TempBlock> layers = instances_.get(block);
        if (layers == null || layers.isEmpty()) {
            VISIBILITY.remove(key);
            return;
        }
        final List<LayerView> views = new ArrayList<>(layers.size());
        final List<UUID> owners = new ArrayList<>(layers.size());
        for (TempBlock layer : layers) {
            views.add(new LayerView(layer.layerId, layer.ownerId, layer.newData.clone()));
            owners.add(layer.ownerId);
        }
        VISIBILITY.put(key, new VisibilitySnapshot(layers.getFirst().state.getBlockData().clone(),
                List.copyOf(views), Collections.unmodifiableList(owners)));
    }

    public static boolean isTopLayerOwnedBy(final World world, final int x, final int y, final int z,
                                             final UUID playerId) {
        if (world == null || playerId == null) return false;
        final VisibilitySnapshot snapshot = VISIBILITY.get(new VisibilityKey(world, x, y, z));
        return snapshot != null && TempBlockOwnershipPolicy.topLayerOwnedBy(snapshot.owners, playerId);
    }

    public static BlockData getVisibleData(final Block block, final UUID playerId) {
        if (block == null || block.getWorld() == null) return null;
        return getVisibleData(block.getWorld(), block.getX(), block.getY(), block.getZ(), playerId);
    }

    public static BlockData getVisibleData(final World world, final int x, final int y, final int z,
                                           final UUID playerId) {
        final VisibilitySnapshot snapshot = VISIBILITY.get(new VisibilityKey(world, x, y, z));
        if (snapshot == null) return null;
        final int visibleIndex = TempBlockOwnershipPolicy.visibleLayerIndex(snapshot.owners, playerId);
        return visibleIndex < 0 ? snapshot.original.clone() : snapshot.layers.get(visibleIndex).data.clone();
    }

    public static Map<UUID, BlockData> getOwnerViews(final Block block, final UUID additionalOwner) {
        if (block == null || block.getWorld() == null) return Map.of();
        final VisibilitySnapshot snapshot = VISIBILITY.get(VisibilityKey.of(block));
        final Set<UUID> owners = new HashSet<>();
        if (snapshot != null) {
            for (UUID owner : snapshot.owners) if (owner != null) owners.add(owner);
        }
        if (additionalOwner != null) owners.add(additionalOwner);
        final Map<UUID, BlockData> views = new HashMap<>();
        for (UUID owner : owners) {
            final BlockData visible = getVisibleData(block, owner);
            if (visible != null) views.put(owner, visible.clone());
        }
        return Map.copyOf(views);
    }

    public static List<VisibleBlock> getOwnedBlocksInChunk(final World world, final int chunkX, final int chunkZ,
                                                            final UUID playerId) {
        if (world == null || playerId == null) return List.of();
        final List<VisibleBlock> result = new ArrayList<>();
        VISIBILITY.forEach((key, snapshot) -> {
            if (key.world != world && !key.world.equals(world) || key.x >> 4 != chunkX || key.z >> 4 != chunkZ) return;
            boolean owned = false;
            for (LayerView layer : snapshot.layers) {
                if (playerId.equals(layer.ownerId)) { owned = true; break; }
            }
            if (owned) result.add(new VisibleBlock(key.x, key.y, key.z,
                    getVisibleData(world, key.x, key.y, key.z, playerId)));
        });
        return result;
    }

    /**
     * Revert all TempBlocks at this location
     *
     * @param block       The block location
     * @param defaulttype The default material to revert to if it can't
     */
    public static void revertBlock(final Block block, final Material defaulttype) {
        final List<TempBlock> layers;
        synchronized (MUTATION_LOCK) {
            final LinkedList<TempBlock> current = instances_.get(block);
            layers = current == null ? List.of() : new ArrayList<>(current);
        }
        if (!layers.isEmpty()) {
            //We clone the list first, then remove before reverting. The tempblock list is cloned so we get no concurrent modification exceptions
            layers.forEach(TempBlock::revertBlock);
        }
        // No registered layer means the current block is real authority. The
        // old fallback forced AIR here, allowing stale ability maps to erase a
        // player placement long after removeBlock() invalidated the TempBlock.
    }

    /**
     * Whether the physics should be updated or not. Fire should be updated so it can burn and spread IF
     * FireGrief is on
     *
     * @param material The material to check
     * @return True if physics should be applied
     */
    public static boolean applyPhysics(Material material) {
        return GeneralMethods.isLightEmitting(material) || (material == Material.FIRE && FireAbility.canFireGrief());
    }

    public Block getBlock() {
        return this.block;
    }

    public BlockData getBlockData() {
        return this.newData;
    }

    public Location getLocation() {
        return this.block.getLocation();
    }

    public BlockState getState() {
        return this.state;
    }

    public void setState(final BlockState newstate) {
        synchronized (MUTATION_LOCK) {
            if (this.reverted || newstate == null) return;
            this.state = newstate;
            refreshVisibility(this.block);
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

    public Optional<UUID> getOwnerId() {
        return Optional.ofNullable(this.ownerId);
    }

    public TempBlock setAbility(CoreAbility ability) {
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return this;
            this.ability = Optional.ofNullable(ability);
            this.ownerId = ability == null || ability.getPlayer() == null ? null : ability.getPlayer().getUniqueId();
            refreshVisibility(this.block);
            publish(TempBlockSync.Operation.CREATE, this.newData);
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

    /**
     * Use {@link #setRevertTask(Runnable)} instead
     */
    @Deprecated
    public void setRevertTask(final RevertTask task) {
        this.setRevertTask((Runnable) task);
    }

    public long getRevertTime() {
        return this.revertTime;
    }

    /**
     * Make this TempBlock revert automatically after the specified amount of time
     *
     * @param revertTime The time it takes to revert. In milliseconds.
     */
    public void setRevertTime(final long revertTime) {
        synchronized (MUTATION_LOCK) {
            if (this.reverted || revertTime <= 0 || state instanceof Container) return;
            this.revertTime = PredictionTiming.alignDuration(this.ability.orElse(null), revertTime)
                    + System.currentTimeMillis();
            if (this.inRevertQueue) REVERT_QUEUE.remove(this);
            else this.inRevertQueue = true;
            REVERT_QUEUE.add(this);
            publish(TempBlockSync.Operation.UPDATE_EXPIRY, this.newData);
        }
    }

    /**
     * Revert this TempBlock
     */
    public void revertBlock() {
        this.trueRevertBlock(true);
    }

    /**
     * This is used to revert the block without removing the instances from memory. Used when multiple tempblocks are to be reverted at once
     */
    private void trueRevertBlock() {
        this.trueRevertBlock(true);
    }

    /**
     * This is used to revert the block without removing the instances from memory. Used when multiple tempblocks are to be reverted at once
     *
     * @param removeFromQueue If the TempBlock should be removed from the queue. Should be false when it has already been removed from the revert queue
     */
    private void trueRevertBlock(boolean removeFromQueue) {
        List<TempBlock> invalidated = List.of();
        boolean packetExpected = false;
        synchronized (MUTATION_LOCK) {
            if (this.reverted) return;
            final LinkedList<TempBlock> layers = instances_.get(this.block);
            if (layers == null || !layers.contains(this)) {
                this.reverted = true;
                if (removeFromQueue) REVERT_QUEUE.remove(this);
                this.inRevertQueue = false;
                return;
            }
            final boolean wasTop = layers.getLast() == this;
            final BlockData actualBefore = this.block.getBlockData();
            remove(this);
            this.reverted = true;
            if (removeFromQueue) REVERT_QUEUE.remove(this);
            this.inRevertQueue = false;

            BlockData effectiveData = instances_.containsKey(this.block)
                    ? instances_.get(this.block).getLast().newData
                    : this.state.getBlockData();
            if (wasTop && !sameData(actualBefore, this.newData)
                    && actualBefore.getMaterial() != Material.FIRE
                    && actualBefore.getMaterial() != Material.SOUL_FIRE) {
                // Real authority changed without going through removeBlock().
                // Never let a stale TempBlock overwrite it, and invalidate all
                // lower layers so the registry cannot conceal future packets.
                invalidated = invalidateStack(this.block, actualBefore, true);
                effectiveData = actualBefore;
            } else if (wasTop) {
                TempBlockSync.beforeWorldChange(TempBlockSync.Operation.REVERT, this, effectiveData);
                packetExpected = true;
                if (instances_.containsKey(this.block)) applyWorldData(this, effectiveData);
                else revertState();
            }
            publish(TempBlockSync.Operation.REVERT, effectiveData, packetExpected);
        }

        finishLayer(this);
        finishInvalidated(invalidated);
    }

    /**
     * Revert the TempBlock to the proper BlockState it should be
     */
    private void revertState() {
        Block block = this.state.getBlock();
        //If the block has been changed by the time we revert (e.g. block place). Also, we ignore fire since it isn't worth the time
        if (block.getType() != this.newData.getMaterial() && block.getType() != Material.FIRE && block.getType() != Material.SOUL_FIRE) {
            //Get the drops of the original block and drop them in the world
            //GeneralMethods.dropItems(block, GeneralMethods.getDrops(block, this.state.getType(), this.state.getBlockData()));
        } else {
            //Previous Material was SNOW
            if (this.state.getType() == Material.SNOW) {
                updateSnowableBlock(block.getRelative(BlockFace.DOWN), true);
            }

            //Revert the original blockstate
            this.state.update(true, applyPhysics(state.getType())
                    && !(state.getBlockData() instanceof Bisected));
        }
    }

    /**
     * Make the provided tempblock revert at the same time as the current tempblock
     *
     * @param tempBlock The tempblock to attach to the current tempblock
     */
    public void addAttachedBlock(TempBlock tempBlock) {
        if (tempBlock == null || tempBlock == this) return;
        synchronized (MUTATION_LOCK) {
            this.attachedTempBlocks.add(tempBlock);
            tempBlock.attachedTempBlocks.add(this);
        }
    }

    /**
     * @return The list of attached tempblocks
     */
    public Set<TempBlock> getAttachedTempBlocks() {
        synchronized (MUTATION_LOCK) {
            return Set.copyOf(attachedTempBlocks);
        }
    }

    /**
     * <b>Not yet implemented. For future use.</b>
     *
     * @return Can this TempBlock be used as a source block
     */
    @Experimental
    public boolean isBendableSource() {
        return isBendableSource;
    }

    /**
     * <b>Not yet implemented. For future use.</b>
     * Set if the TempBlock can be used as a source block
     *
     * @param bool If it can be used as a source block
     */
    @Experimental
    public TempBlock setBendableSource(boolean bool) {
        this.isBendableSource = bool;
        return this;
    }

    /**
     * @return True if the block will suffocate entities inside it
     */
    public boolean canSuffocate() {
        return suffocate;
    }

    /**
     * Set if the TempBlock will suffocate entities inside of it
     *
     * @param suffocate True if they will suffocate, false if they won't
     */
    public TempBlock setCanSuffocate(boolean suffocate) {
        this.suffocate = suffocate;
        return this;
    }

    public void setType(final Material material) {
        this.setType(material.createBlockData());
    }

    @Deprecated
    public void setType(final Material material, final BlockData data) {
        this.setType(data);
    }

    public void setType(final BlockData data) {
        List<TempBlock> invalidated = List.of();
        synchronized (MUTATION_LOCK) {
            if (isReverted()) return;
            final LinkedList<TempBlock> layers = instances_.get(this.block);
            if (layers == null || !layers.contains(this)) {
                this.reverted = true;
                return;
            }
            final boolean isTop = layers.getLast() == this;
            if (isTop && !sameData(this.block.getBlockData(), this.newData)) {
                invalidated = invalidateStack(this.block, this.block.getBlockData(), true);
            } else {
                this.newData = data;
                refreshVisibility(this.block);
                if (isTop) {
                    TempBlockSync.beforeWorldChange(TempBlockSync.Operation.CREATE, this, data);
                    applyWorldData(this, data);
                }
                publish(TempBlockSync.Operation.CREATE, this.newData, isTop);
            }
        }
        finishInvalidated(invalidated);
    }

    private void publish(final TempBlockSync.Operation operation, final BlockData effectiveData) {
        publish(operation, effectiveData, false);
    }

    private void publish(final TempBlockSync.Operation operation, final BlockData effectiveData,
                         final boolean packetExpected) {
        this.revision = NEXT_REVISION.incrementAndGet();
        TempBlockSync.publish(operation, this, effectiveData, packetExpected);
    }

    /**
     * @return If the TempBlock has reverted
     */
    public boolean isReverted() {
        return reverted;
    }

    /**
     * Update grass blocks
     *
     * @param b     The block
     * @param snowy If its snowy
     */
    public void updateSnowableBlock(Block b, boolean snowy) {
        if (b.getBlockData() instanceof Snowable) {
            final Snowable snowable = (Snowable) b.getBlockData();
            snowable.setSnowy(snowy);
            b.setBlockData(snowable);
        }
    }

    @Override
    public String toString() {
        return "TempBlock{" +
                "block=[" + block.getX() + "," + block.getY() + "," + block.getZ() + "]" +
                ", newData=" + newData.getAsString() +
                ", attachedTempBlocks=" + attachedTempBlocks.size() +
                ", revertTime=" + (revertTime == 0 ? "N/A" : (revertTime - System.currentTimeMillis()) + "ms") +
                ", reverted=" + reverted +
                ", revertTask=" + (revertTask != null) +
                ", ability=" + (ability.isPresent() ? ability.get().getClass() : "null") +
                ", isBendableSource=" + isBendableSource +
                ", suffocate=" + suffocate +
                '}';
    }

    @Deprecated
    /**
     * Will be removed in future. Exactly the same as a Runnable so no point having a unique class for it
     */
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

    public static class TempBlockRevertTask implements Runnable {
        @Override
        public void run() {
            final long currentTime = System.currentTimeMillis();
            final List<TempBlock> invalidated = new ArrayList<>();
            synchronized (MUTATION_LOCK) {
                for (Block block : new ArrayList<>(instances_.keySet())) {
                    final LinkedList<TempBlock> layers = instances_.get(block);
                    if (layers == null || layers.isEmpty()) continue;
                    if (!sameData(block.getBlockData(), layers.getLast().newData)) {
                        invalidated.addAll(invalidateStack(block, block.getBlockData(), true));
                    }
                }
            }
            finishInvalidated(invalidated);
            while (true) {
                final TempBlock tempBlock;
                synchronized (MUTATION_LOCK) {
                    tempBlock = REVERT_QUEUE.peek();
                    if (tempBlock == null || currentTime < tempBlock.getRevertTime()) return;
                    REVERT_QUEUE.poll();
                    tempBlock.inRevertQueue = false;
                }
                tempBlock.trueRevertBlock(false);
            }
        }
    }
}
