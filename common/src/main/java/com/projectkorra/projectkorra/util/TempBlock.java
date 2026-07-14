package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.Container;
import com.projectkorra.projectkorra.platform.mc.block.data.Bisected;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.PredictionTiming;
import com.projectkorra.projectkorra.prediction.TempBlockOwnershipPolicy;
import com.projectkorra.projectkorra.prediction.TempBlockSync;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public class TempBlock {

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
    private static boolean REVERT_TASK_RUNNING;

    private final Block block;
    private final long layerId = NEXT_LAYER_ID.incrementAndGet();
    private long revision;
    private UUID ownerId;
    private BlockData newData;
    private BlockState state;
    private Set<TempBlock> attachedTempBlocks; //Temp Block states that should be reverted as well when the temp block expires (e.g. double blocks)
    private long revertTime;
    private boolean inRevertQueue;
    private boolean reverted;
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

        if (instances_.containsKey(block)) {
            final TempBlock temp = instances_.get(block).getFirst();
            this.state = temp.state; //Set the original blockstate of the tempblock
            put(block, this);
            block.setBlockData(newData, applyPhysics(newData.getMaterial()));
        } else {
            this.state = block.getState();

            if (this.state instanceof Container || this.state.getType() == Material.JUKEBOX) {
                return;
            }

            put(block, this);

            block.setBlockData(newData, applyPhysics(newData.getMaterial()));
        }

        if (revertTime > 0 && !(this.state instanceof Container)) {
            this.revertTime = PredictionTiming.alignDuration(this.ability.orElse(null), revertTime)
                    + System.currentTimeMillis();
            this.inRevertQueue = true;
            REVERT_QUEUE.add(this);
        }
        // Creation is one ordered operation. Previously timed blocks published a
        // CREATE with expiry 0 followed immediately by UPDATE_EXPIRY, doubling
        // the reconciliation work for every trail/wall block.
        publish(TempBlockSync.Operation.CREATE, this.newData);
    }

    /**
     * Get a TempBlock at a location
     *
     * @param block The block location
     * @return The topmost TempBlock
     */
    public static TempBlock get(final Block block) {
        if (isTempBlock(block)) {
            return instances_.get(block).getLast();
        }
        return null;
    }

    /**
     * Get all TempBlocks at the given location
     *
     * @param block The block location
     * @return The list of TempBlocks
     */
    public static LinkedList<TempBlock> getAll(Block block) {
        return instances_.get(block);
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
        return block != null && instances_.containsKey(block);
    }

    /**
     * Is the specified block touching a TempBlock? Used to prevent physics updates
     * for things like Water
     *
     * @param block The block location
     * @return True if there is a TempBlock beside it
     */
    public static boolean isTouchingTempBlock(final Block block) {
        final BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
        for (final BlockFace face : faces) {
            if (instances_.containsKey(block.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove and revert all TempBlocks on the server. Done at server shutdown or PK reload.
     */
    public static void removeAll() {
        for (final Block block : new HashSet<>(instances_.keySet())) {
            revertBlock(block, Material.AIR);
        }
        for (final TempBlock tempblock : REVERT_QUEUE) {
            tempblock.state.update(true, applyPhysics(tempblock.state.getType()));
            if (tempblock.revertTask != null) {
                tempblock.revertTask.run();
            }
        }
        REVERT_QUEUE.clear();
    }

    public static void removeAllInWorld(World world) {
        for (final Block block : new HashSet<>(instances_.keySet())) {
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
        instances_.get(block).forEach(t -> {
            REVERT_QUEUE.remove(t);
            remove(t);
        });
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
        if (instances_.containsKey(block)) {
            //We clone the list first, then remove before reverting. The tempblock list is cloned so we get no concurrent modification exceptions
            List<TempBlock> tempBlocks = new ArrayList<>(instances_.get(block));
            tempBlocks.forEach((b) -> {
                TempBlock.remove(b);
                b.trueRevertBlock();
            });
        } else {
            if ((defaulttype == Material.LAVA) && GeneralMethods.isAdjacentToThreeOrMoreSources(block, true)) {
                final BlockData data = Material.LAVA.createBlockData();

                if (data instanceof Levelled) {
                    ((Levelled) data).setLevel(0);
                }

                block.setBlockData(data, applyPhysics(data.getMaterial()));
            } else if ((defaulttype == Material.WATER) && GeneralMethods.isAdjacentToThreeOrMoreSources(block)) {
                final BlockData data = Material.WATER.createBlockData();

                if (data instanceof Levelled) {
                    ((Levelled) data).setLevel(0);
                }

                block.setBlockData(data, applyPhysics(data.getMaterial()));
            } else {
                block.setType(defaulttype, applyPhysics(defaulttype));
            }
        }
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
        this.state = newstate;
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
        this.ability = Optional.ofNullable(ability);
        this.ownerId = ability == null || ability.getPlayer() == null ? null : ability.getPlayer().getUniqueId();
        refreshVisibility(this.block);
        publish(TempBlockSync.Operation.CREATE, this.newData);
        return this;
    }

    public Runnable getRevertTask() {
        return this.revertTask;
    }

    public void setRevertTask(final Runnable task) {
        this.revertTask = task;
    }

    /**
     * Use {@link #setRevertTask(Runnable)} instead
     */
    @Deprecated
    public void setRevertTask(final RevertTask task) {
        this.revertTask = task;
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
        if (revertTime <= 0 || state instanceof Container) {
            return;
        }
        this.revertTime = PredictionTiming.alignDuration(this.ability.orElse(null), revertTime)
                + System.currentTimeMillis();
        if (this.inRevertQueue) {
            REVERT_QUEUE.remove(this);
        } else {
            this.inRevertQueue = true;
        }
        REVERT_QUEUE.add(this);
        publish(TempBlockSync.Operation.UPDATE_EXPIRY, this.newData);
    }

    /**
     * Revert this TempBlock
     */
    public void revertBlock() {
        if (!this.reverted) {
            remove(this);
            trueRevertBlock();
        }
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
        this.reverted = true;
        // A location may contain a stack of overlapping TempBlocks. Reverting
        // one layer exposes the next temporary state, not necessarily the
        // original state captured by this instance. Publishing the captured
        // state here made prediction clients turn nested water/earth streams
        // into air (or leave a solid block behind) until a later chunk resend.
        final BlockData effectiveData = instances_.containsKey(this.block)
                ? instances_.get(this.block).getLast().newData
                : this.state.getBlockData();
        publish(TempBlockSync.Operation.REVERT, effectiveData);
        Platform.chunks().getChunkAtAsync(this.block.getLocation()).thenAccept(result -> {
            // Other overlapping TempBlocks may revert while the chunk future is
            // pending. Resolve the live top layer here instead of relying on the
            // state observed before scheduling the callback.
            final LinkedList<TempBlock> current = instances_.get(this.block);
            if (current != null && !current.isEmpty()) {
                this.block.setBlockData(current.getLast().newData);
            } else {
                revertState();
            }
        });

        if (removeFromQueue) { //Remove from the queue if it's in there. We only do this when required because it is an intensive action due to the collection type
            REVERT_QUEUE.remove(this);
        }
        if (this.revertTask != null) {
            this.revertTask.run();
        }

        for (TempBlock attached : attachedTempBlocks) {
            attached.revertBlock();
        }
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
        this.attachedTempBlocks.add(tempBlock);
        tempBlock.attachedTempBlocks.add(this);
    }

    /**
     * @return The list of attached tempblocks
     */
    public Set<TempBlock> getAttachedTempBlocks() {
        return attachedTempBlocks;
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
        if (isReverted())
            return;
        this.newData = data;
        refreshVisibility(this.block);
        this.block.setBlockData(data, applyPhysics(data.getMaterial()));
        publish(TempBlockSync.Operation.CREATE, this.newData);
    }

    private void publish(final TempBlockSync.Operation operation, final BlockData effectiveData) {
        this.revision = NEXT_REVISION.incrementAndGet();
        TempBlockSync.publish(operation, this, effectiveData);
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
            while (!REVERT_QUEUE.isEmpty()) {
                final TempBlock tempBlock = REVERT_QUEUE.peek(); //Check if the top TempBlock is ready for reverting
                if (currentTime >= tempBlock.getRevertTime()) {
                    REVERT_QUEUE.poll();
                    if (!tempBlock.reverted) {
                        remove(tempBlock);
                        tempBlock.trueRevertBlock(false); //It's already been removed from the poll(), so don't try remove it again
                    }
                } else {
                    break;
                }
            }
        }
    }
}
