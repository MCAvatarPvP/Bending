package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Reads the same generated pieces that vanilla location advancement predicates query. */
final class PaperRollbackStructures {
    private final RollbackStructurePieces pieces;
    private final Map<String, Holder.Reference<Structure>> registry = new HashMap<>();
    private final Map<RollbackStructurePieces.Piece, StructureStart> matches = new HashMap<>();

    PaperRollbackStructures(RollbackStructurePieces pieces, RegistryAccess access) {
        this.pieces = pieces;
        if (pieces.pieces().isEmpty()) return;
        Registry<Structure> structures = access.lookupOrThrow(Registries.STRUCTURE);
        for (var piece : pieces.pieces()) {
            registry.computeIfAbsent(piece.structure(), id -> structures.getOrThrow(
                    ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id))));
        }
    }

    @SuppressWarnings("unchecked")
    StructureManager manager() {
        var shell = RollbackNativeQueryShell.create(StructureManager.class);
        shell.query(value -> value.getStructureWithPieceAt(BlockPos.ZERO, (HolderSet<Structure>) null), null,
                args -> find((BlockPos) args[0], ((HolderSet<Structure>) args[1])::contains));
        shell.query(value -> value.getStructureWithPieceAt(BlockPos.ZERO, (Predicate<Holder<Structure>>) null), null,
                args -> find((BlockPos) args[0], (Predicate<Holder<Structure>>) args[1]));
        shell.query(value -> value.getStructureWithPieceAt(BlockPos.ZERO, (TagKey<Structure>) null), null,
                args -> find((BlockPos) args[0], holder -> holder.is((TagKey<Structure>) args[1])));
        shell.query(value -> value.getStructureWithPieceAt(BlockPos.ZERO, (Structure) null), null,
                args -> find((BlockPos) args[0], holder -> holder.value() == args[1]));
        return shell.instance();
    }

    private StructureStart find(BlockPos pos, Predicate<Holder<Structure>> filter) {
        for (var piece : pieces.at(pos.getX(), pos.getY(), pos.getZ())) {
            var holder = registry.get(piece.structure());
            if (filter.test(holder)) return matches.computeIfAbsent(piece, row -> {
                var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(row.minX(), row.minY(), row.minZ(),
                        row.maxX(), row.maxY(), row.maxZ());
                var nativePiece = RollbackNativeQueryShell.create(net.minecraft.world.level.levelgen.structure.StructurePiece.class)
                        .constant(net.minecraft.world.level.levelgen.structure.StructurePiece::getBoundingBox, box).instance();
                var parts = new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(java.util.List.of(nativePiece));
                return new StructureStart(holder.value(), new net.minecraft.world.level.ChunkPos(pos), 0, parts);
            });
        }
        return StructureStart.INVALID_START;
    }
}
