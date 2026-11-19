package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockEntities;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native block entity contents owned by a single private Paper arena. */
public final class PaperRollbackBlockEntities extends RollbackBlockEntities<BlockEntity, BlockState>
        implements PaperRollbackWorldQueries.BlockEntities<Void> {
    private final RegistryAccess registries;
    private final PaperRollbackBlockStates states = new PaperRollbackBlockStates();

    private final RollbackStateGraph graph = new RollbackStateGraph(PaperRollbackBlockEntities::external,
            field -> !(field.getDeclaringClass().getName().startsWith("it.unimi.dsi.fastutil.")
                    && Map.class.isAssignableFrom(field.getDeclaringClass())
                    && Collection.class.isAssignableFrom(field.getType())
                    && Set.of("entries", "keys", "values").contains(field.getName())), 100_000);

    public PaperRollbackBlockEntities(RegistryAccess registries) {
        this.registries = Objects.requireNonNull(registries);
    }

    @Override public BlockEntity get(RollbackBlockStore terrain, Position position, Object nativeState) {
        var state = (BlockState) nativeState;
        return state.hasBlockEntity() ? owned(terrain, position, state) : null;
    }

    @Override protected BlockState decodeState(BlockData data) { return states.decode(data); }

    @Override protected BlockEntity create(Position position, BlockState state, byte[] bytes) {
        if (!(state.getBlock() instanceof EntityBlock block)) throw new IllegalArgumentException("Block cannot own a tile");
        var pos = new BlockPos(position.x(), position.y(), position.z());
        var tile = Objects.requireNonNull(block.newBlockEntity(pos, state), "Missing native block entity");
        if (bytes.length > 0) load(tile, PaperRollbackBlockSnapshots.decode(bytes));
        return tile;
    }

    @Override protected void setState(BlockEntity tile, BlockState state) { tile.setBlockState(state); }

    @Override protected void restore(BlockEntity tile, BlockState state, byte[] bytes) {
        var tag = PaperRollbackBlockSnapshots.decode(bytes);
        tile.setBlockState(state);
        load(tile, tag);
    }

    @Override protected Runnable checkpoint(BlockEntity tile, BlockState state, byte[] canonical) {
        // Native NBT omits fields such as lid animation, and loading it replaces inventory stack identities.
        return graph.capture(List.of(tile), List.of())::restore;
    }

    private static boolean external(Object value) {
        if (value instanceof net.minecraft.world.level.Level || value instanceof net.minecraft.world.entity.Entity
                || value instanceof org.bukkit.entity.Entity) {
            throw new IllegalStateException("Private tile references externally owned world or entity");
        }
        return value instanceof net.minecraft.world.level.block.entity.BlockEntityType<?>
                || value instanceof net.minecraft.world.level.block.Block
                || value instanceof org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry
                || value == net.minecraft.world.LockCode.NO_LOCK || PaperRollbackMetadata.frozen(value);
    }

    private void load(BlockEntity tile, CompoundTag tag) {
        var pos = tile.getBlockPos();
        if (tag.getInt("x").orElseThrow() != pos.getX() || tag.getInt("y").orElseThrow() != pos.getY()
                || tag.getInt("z").orElseThrow() != pos.getZ()) {
            throw new IllegalArgumentException("Saved block entity belongs to another position");
        }
        var type = tag.getString("id").orElseThrow();
        if (type.equals("DUMMY")) return; // Packed Paper chunks may carry a freshly created default tile.
        if (!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tile.getType()).toString().equals(type)) {
            throw new IllegalArgumentException("Saved block entity type differs from the terrain");
        }
        var problems = new ProblemReporter.Collector();
        tile.loadWithComponents(TagValueInput.create(problems, registries, tag));
        requireValid(problems);
    }

    @Override protected byte[] encode(BlockEntity tile) {
        var problems = new ProblemReporter.Collector();
        var output = TagValueOutput.createWithContext(problems, registries);
        tile.saveWithFullMetadata(output);
        requireValid(problems);
        return PaperRollbackBlockSnapshots.encode(output.buildResult());
    }

    private static void requireValid(ProblemReporter.Collector problems) {
        if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid block entity contents: " + problems.getReport());
    }

    @Override protected byte[] merge(byte[] original, byte[] before, byte[] after) {
        if (original.length == 0) return after;
        var result = PaperRollbackBlockSnapshots.decode(original);
        var base = PaperRollbackBlockSnapshots.decode(before);
        var current = PaperRollbackBlockSnapshots.decode(after);
        for (String key : base.keySet()) if (!current.contains(key)) result.remove(key);
        for (var entry : current.entrySet()) {
            if (!Objects.equals(base.get(entry.getKey()), entry.getValue())) result.put(entry.getKey(), entry.getValue());
        }
        // On first mutation a packed default acquires its real native type and fields.
        if ("DUMMY".equals(result.getStringOr("id", ""))) result.merge(current);
        return PaperRollbackBlockSnapshots.encode(result);
    }
}
