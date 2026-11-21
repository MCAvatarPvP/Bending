package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Restores confirmed ability terrain while the match owner still holds the live roster. */
public final class PaperRollbackTerrainWriteback implements Runnable {
    private record Value(BlockState state, CompoundTag tile) {
        static Value from(ServerLevel world, BlockPos position, RollbackBlockStore.Cell cell, PaperRollbackBlockStates states) {
            var state = states.decode(cell.data());
            if (!state.hasBlockEntity() && cell.hasBlockEntity())
                throw new IllegalArgumentException("Captured terrain block and entity disagree");
            var bytes = cell.blockEntity();
            if (bytes == null) return new Value(state, null);
            if (bytes.length == 0) return withDefaultTile(world, position, state);
            return fromTag(world, position, state, PaperRollbackBlockSnapshots.decode(bytes));
        }
        private static Value withDefaultTile(ServerLevel world, BlockPos position, BlockState state) {
            var empty = ((EntityBlock) state.getBlock()).newBlockEntity(position, state);
            if (empty == null) throw new IllegalArgumentException("Missing default block entity at " + position);
            return new Value(state, empty.saveWithFullMetadata(world.registryAccess()));
        }
        private static Value fromTag(ServerLevel world, BlockPos position, BlockState state, CompoundTag tile) {
            // Paper promotes a packed DUMMY to the block's default entity on first access.
            if (tile != null && "DUMMY".equals(tile.getStringOr("id", ""))) {
                if (tile.getInt("x").orElseThrow() != position.getX() || tile.getInt("y").orElseThrow() != position.getY()
                        || tile.getInt("z").orElseThrow() != position.getZ())
                    throw new IllegalArgumentException("Packed default block entity belongs to another position");
                return withDefaultTile(world, position, state);
            }
            return new Value(state, tile);
        }
        static Value read(ServerLevel world, BlockPos position) {
            var chunk = world.getChunkSource().getChunkAtIfLoadedImmediately(position.getX() >> 4, position.getZ() >> 4);
            if (chunk == null) throw new IllegalStateException("Rollback terrain chunk unloaded at " + position);
            var state = chunk.getBlockState(position);
            return fromTag(world, position, state, state.hasBlockEntity() ? PaperRollbackTerrainCapture.blockEntity(world, chunk, position) : null);
        }
    }

    private static final class Write {
        final BlockPos position;
        final Value original, target;
        final CompoundTag emptyTile;
        boolean started;

        Write(ServerLevel world, BlockPos position, Value original, Value desired) {
            this.position = position;
            this.original = original;
            // A native state change can create a default tile, even when the confirmed cell has none.
            var empty = desired.state().hasBlockEntity() && (desired.tile() != null || original.state() != desired.state())
                    ? ((EntityBlock) desired.state().getBlock()).newBlockEntity(position, desired.state()) : null;
            emptyTile = empty == null ? null : empty.saveWithFullMetadata(world.registryAccess());
            if (desired.tile() == null) { target = desired; return; }
            var replacement = BlockEntity.loadStatic(position, desired.state(), desired.tile(), world.registryAccess());
            if (replacement == null || !replacement.getType().isValid(desired.state())
                    || empty != null && replacement.getType() != empty.getType())
                throw new IllegalArgumentException("Invalid outgoing block entity at " + position);
            target = new Value(desired.state(), replacement.saveWithFullMetadata(world.registryAccess()));
        }

        Value validate(ServerLevel world) {
            var live = Value.read(world, position);
            // Changing the state can create a default entity or retain the previous one before the final NBT write.
            boolean partial = original.state() != target.state() && live.state() == target.state()
                    && (Objects.equals(live.tile(), emptyTile) || Objects.equals(live.tile(), original.tile()));
            boolean unexpectedTile = !started && original.state().hasBlockEntity() && original.tile() == null && live.tile() != null;
            if (unexpectedTile || !live.equals(original) && !live.equals(target) && !(started && partial))
                throw new IllegalStateException("Live terrain changed during rollback at " + position);
            return live;
        }

        void commit(ServerLevel world) {
            var live = validate(world);
            if (!started && live.equals(target)) return;
            started = true;
            if (live.state() != target.state()) {
                // Neighbour physics, inventory drops and on-place callbacks already ran in simulation.
                world.setBlock(position, target.state(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                live = validate(world);
                if (live.state() != target.state()) throw new IllegalStateException("Paper declined terrain write at " + position);
            }
            if (!live.equals(target)) {
                // An interrupted attempt may have attached its entity to the live world. Recreate it from the
                // immutable staged contents so a later live mutation cannot change what this retry writes.
                if (target.tile() == null) {
                    world.removeBlockEntity(position);
                } else {
                    var replacement = BlockEntity.loadStatic(position, target.state(), target.tile().copy(), world.registryAccess());
                    if (replacement == null) throw new IllegalStateException("Could not restore staged block entity at " + position);
                    world.setBlockEntity(replacement);
                }
                if (!target.equals(Value.read(world, position))) throw new IllegalStateException("Paper declined block entity at " + position);
            }
            if (target.state().hasBlockEntity()) world.blockEntityChanged(position);
            world.sendBlockUpdated(position, original.state(), target.state(), Block.UPDATE_CLIENTS);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ServerLevel world;
    private final List<Write> writes;
    private int completed;
    private boolean running;

    private PaperRollbackTerrainWriteback(ServerLevel world, List<Write> writes) {
        this.world = world;
        this.writes = List.copyOf(writes);
    }

    /** Capture the authority's confirmed overlay before touching the live arena. Retain this plan across teardown retries. */
    public static PaperRollbackTerrainWriteback prepare(ServerLevel world, RollbackWorldSeed seed,
            RollbackBlockStore terrain, RollbackCombatRuntime<?, ?> authority) {
        boundary();
        if (!world.getWorld().getUID().equals(seed.world())) throw new IllegalArgumentException("Rollback arena world changed");
        var changes = authority.readConfirmedState(() -> terrain.changesSince(seed.terrain()));
        return prepare(world, seed.terrain(), changes);
    }

    static PaperRollbackTerrainWriteback prepare(ServerLevel world, RollbackTerrainSeed seed, List<RollbackBlockStore.Change> changes) {
        boundary();
        var writes = new ArrayList<Write>(changes.size());
        var states = new PaperRollbackBlockStates();
        for (var change : changes) {
            var position = change.position();
            var nativePosition = new BlockPos(position.x(), position.y(), position.z());
            var original = Value.from(world, nativePosition, seed.cell(position), states);
            var desired = Value.from(world, nativePosition, change.value(), states);
            if (original.equals(desired)) continue;
            writes.add(new Write(world, nativePosition, original, desired));
        }
        var result = new PaperRollbackTerrainWriteback(world, writes);
        result.validate();
        return result;
    }

    public void validate() {
        boundary();
        if (Thread.currentThread() != owner) throw new IllegalStateException("Terrain writeback crossed threads");
        if (completed < writes.size() && world.getServer().getLevel(world.dimension()) != world)
            throw new IllegalStateException("Rollback arena world unloaded");
        for (int i = completed; i < writes.size(); i++) writes.get(i).validate(world);
    }

    @Override public void run() {
        validate();
        if (running) throw new IllegalStateException("Recursive terrain writeback");
        running = true;
        try {
            while (completed < writes.size()) {
                writes.get(completed).commit(world);
                completed++;
            }
        } finally { running = false; }
    }

    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Restore terrain on the live server tick thread");
    }
}
