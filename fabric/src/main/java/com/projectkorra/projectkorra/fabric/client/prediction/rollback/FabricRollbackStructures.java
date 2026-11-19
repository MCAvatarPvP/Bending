package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStructurePieces;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import java.util.*;
import java.util.function.Predicate;

/** Native structure-piece containment for advancement predicates inside the captured arena. */
final class FabricRollbackStructures {
    private final RollbackStructurePieces pieces;
    private final RollbackBlockStore.Bounds bounds;
    private final Registry<Structure> registry;
    private final Map<Structure, StructureStart> starts = new IdentityHashMap<>();

    FabricRollbackStructures(RollbackStructurePieces pieces, RollbackBlockStore.Bounds bounds, DynamicRegistryManager registries) {
        this.pieces = pieces;
        this.bounds = bounds;
        this.registry = registries.getOptional(RegistryKeys.STRUCTURE).orElse(null);
    }

    @SuppressWarnings("unchecked")
    StructureAccessor accessor() {
        var shell = RollbackNativeQueryShell.create(StructureAccessor.class);
        shell.query(access -> access.getStructureContaining(BlockPos.ORIGIN, (RegistryEntryList<Structure>) null), null,
                args -> matchingList((BlockPos) args[0], (RegistryEntryList<Structure>) args[1]));
        shell.query(access -> access.getStructureContaining(BlockPos.ORIGIN, (TagKey<Structure>) null), null,
                args -> matching((BlockPos) args[0], entry -> entry.isIn((TagKey<Structure>) args[1])));
        shell.query(access -> access.getStructureContaining(BlockPos.ORIGIN, (Predicate<RegistryEntry<Structure>>) null), null,
                args -> matching((BlockPos) args[0], (Predicate<RegistryEntry<Structure>>) args[1]));
        shell.query(access -> access.getStructureContaining(BlockPos.ORIGIN, (Structure) null), null,
                args -> matching((BlockPos) args[0], entry -> entry.value() == args[1]));
        return shell.instance();
    }

    private List<RollbackStructurePieces.Piece> at(BlockPos pos) {
        if (!bounds.contains(new RollbackBlockStore.Position(pos.getX(), pos.getY(), pos.getZ())))
            throw new IllegalArgumentException("Structure query outside captured arena " + pos);
        return pieces.at(pos.getX(), pos.getY(), pos.getZ());
    }

    private StructureStart matchingList(BlockPos pos, RegistryEntryList<Structure> allowed) {
        // Criterion holders carry their own keys even on a connection without the worldgen registry.
        for (var piece : at(pos)) {
            for (var entry : allowed) {
                if (entry.getKey().isPresent() && entry.getKey().get().getValue().toString().equals(piece.structure()))
                    return start(entry.value());
            }
        }
        return StructureStart.DEFAULT;
    }

    private StructureStart matching(BlockPos pos, Predicate<RegistryEntry<Structure>> matches) {
        for (var piece : at(pos)) {
            if (registry == null) throw new IllegalStateException("Native structure registry unavailable for " + piece.structure());
            var entry = registry.getEntry(Identifier.of(piece.structure())).orElseThrow(
                    () -> new IllegalStateException("Missing native structure definition " + piece.structure()));
            if (matches.test(entry)) return start(entry.value());
        }
        return StructureStart.DEFAULT;
    }

    private StructureStart start(Structure structure) {
        return starts.computeIfAbsent(structure, value -> RollbackNativeQueryShell.create(StructureStart.class)
                .constant(StructureStart::hasChildren, true).constant(StructureStart::getStructure, value).instance());
    }
}
