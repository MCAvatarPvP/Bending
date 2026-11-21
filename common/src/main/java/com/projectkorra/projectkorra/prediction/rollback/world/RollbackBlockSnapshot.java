package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import java.util.Objects;

/** Detached saved block contents; update resolves against the destination world's current block. */
public final class RollbackBlockSnapshot extends BlockState {
    public static final int MAXIMUM_BLOCK_ENTITY_BYTES = 1_048_576;
    public interface Target {
        boolean restoreBlockSnapshot(BlockData data, byte[] blockEntity, boolean force, boolean physics);
    }
    private final Block block;
    private final BlockData data;
    private final byte[] blockEntity;
    public RollbackBlockSnapshot(Block block, BlockData data, byte[] blockEntity) {
        this.block = Objects.requireNonNull(block);
        this.data = RollbackBlockStore.copyData(data);
        if (blockEntity != null && blockEntity.length > MAXIMUM_BLOCK_ENTITY_BYTES)
            throw new IllegalArgumentException("Block snapshot exceeds budget");
        this.blockEntity = blockEntity == null ? null : blockEntity.clone();
    }
    @Override public Material getType() { return data.getMaterial(); }
    @Override public BlockData getBlockData() { return RollbackBlockStore.copyData(data); }
    @Override public Block getBlock() { return block; }
    @Override public boolean hasBlockEntity() { return blockEntity != null; }
    @Override public boolean update(boolean force, boolean physics) {
        if (!(block instanceof Target target)) throw new IllegalStateException("World cannot restore a block snapshot");
        return target.restoreBlockSnapshot(getBlockData(), blockEntity == null ? null : blockEntity.clone(), force, physics);
    }
}

