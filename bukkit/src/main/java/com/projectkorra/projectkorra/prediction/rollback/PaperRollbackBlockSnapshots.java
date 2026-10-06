package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockSnapshot;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockReference;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.CraftBlockStates;
import java.io.*;

/** Native snapshot conversion; saved contents are never re-read from the current live block. */
public final class PaperRollbackBlockSnapshots {
    private PaperRollbackBlockSnapshots() { }
    public static RollbackBlockSnapshot capture(org.bukkit.block.BlockState source) {
        if (!source.isPlaced()) throw new IllegalArgumentException("Unplaced block snapshot has no arena identity");
        byte[] tile = null;
        if (source instanceof org.bukkit.block.TileState) {
            if (!(source instanceof CraftBlockEntityState<?> nativeState) || !nativeState.isSnapshot())
                throw new IllegalArgumentException("Block entity must be a detached native snapshot");
            tile = encode(nativeState.getSnapshotNBT());
        }
        var nativeData = source.getBlockData();
        var data = BukkitMC.blockData(nativeData);
        data.setExactState(nativeData.getAsString());
        return new RollbackBlockSnapshot(new RollbackBlockReference(BukkitMC.world(source.getWorld()),
                source.getX(), source.getY(), source.getZ()), data, tile);
    }
    public static boolean restore(org.bukkit.block.Block block, BlockData data, byte[] tile, boolean force, boolean physics) {
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Cannot restore a live block during replay");
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Restore blocks on the server thread");
        if (!force && block.getType() != BukkitMC.material(data.getMaterial())) return false;
        var world = (CraftWorld) block.getWorld();
        var position = new BlockPos(block.getX(), block.getY(), block.getZ());
        var state = new PaperRollbackBlockStates().decode(data);
        var tag = tile == null || tile.length == 0 ? null : decode(tile);
        BlockEntity entity = tag == null ? null : BlockEntity.loadStatic(position, state, tag, world.getHandle().registryAccess());
        if (tag != null && (entity == null || !entity.getType().isValid(state))) throw new IllegalArgumentException("Invalid saved block entity");
        var snapshot = CraftBlockStates.getBlockState(world, position, state, entity);
        // Use the existing common update boundary for temporary terrain and output synchronization.
        return BukkitMC.blockState(snapshot).update(force, physics);
    }
    private static byte[] encode(CompoundTag tag) {
        var bytes = new ByteArrayOutputStream();
        var bounded = new OutputStream() {
            @Override public void write(int value) {
                if (bytes.size() >= RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES)
                    throw new IllegalArgumentException("Block snapshot exceeds budget");
                bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) {
                if (length > RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES - bytes.size())
                    throw new IllegalArgumentException("Block snapshot exceeds budget");
                bytes.write(value, offset, length);
            }
        };
        try { NbtIo.write(tag, new DataOutputStream(bounded)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        return bytes.toByteArray();
    }
    private static CompoundTag decode(byte[] bytes) {
        if (bytes.length > RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES)
            throw new IllegalArgumentException("Block snapshot exceeds budget");
        try {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            var tag = NbtIo.read(input, new NbtAccounter(16_777_216, 64));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing block snapshot data");
            return tag;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid block snapshot data", failure); }
    }
}

