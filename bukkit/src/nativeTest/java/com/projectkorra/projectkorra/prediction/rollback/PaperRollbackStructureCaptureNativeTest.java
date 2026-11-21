package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackStructureCaptureNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void capturesOnlyReferencedPiecesInTheirActualQueryChunkAndNeverLoadsMissingStarts() {
        var registry = ((net.minecraft.core.RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess()).lookupOrThrow(Registries.STRUCTURE);
        var structure = registry.get(net.minecraft.resources.Identifier.parse("minecraft:stronghold")).orElseThrow().value();
        var origin = new ChunkPos(2, 0);
        var box = new BoundingBox(-3, -10, -8, 29, 15, 20);
        var piece = RollbackNativeQueryShell.create(StructurePiece.class).constant(StructurePiece::getBoundingBox, box).instance();
        var start = new StructureStart(structure, origin, 0, new PiecesContainer(List.of(piece, piece)));
        var ref = Map.<Structure, LongSet>of(structure, new LongOpenHashSet(new long[]{origin.toLong()}));
        var referencing = chunk(ref, null);
        var empty = chunk(Map.of(), null);
        var source = new HashMap<Long, ChunkAccess>();
        source.put(ChunkPos.asLong(0, 0), referencing);
        source.put(ChunkPos.asLong(1, 0), empty);
        source.put(origin.toLong(), chunk(Map.of(), start));
        var bounds = new Bounds(3, -5, 2, 28, 12, 13);
        var expected = new RollbackStructurePieces(List.of(
                new RollbackStructurePieces.Piece("minecraft:stronghold", 3, -5, 2, 15, 11, 12)));
        assertEquals(expected, PaperRollbackStructureCapture.capture(bounds, 3, ignored -> "minecraft:stronghold", source::get));
        source.remove(origin.toLong());
        assertThrows(IllegalStateException.class, () -> PaperRollbackStructureCapture.capture(bounds, 3, ignored -> "minecraft:stronghold", source::get));
        source.put(origin.toLong(), empty);
        assertThrows(IllegalStateException.class, () -> PaperRollbackStructureCapture.capture(bounds, 3, ignored -> "minecraft:stronghold", source::get));
        source.put(origin.toLong(), chunk(Map.of(), StructureStart.INVALID_START));
        assertEquals(RollbackStructurePieces.EMPTY, PaperRollbackStructureCapture.capture(bounds, 3, ignored -> "minecraft:stronghold", source::get));
    }
    private static ChunkAccess chunk(Map<Structure, LongSet> references, StructureStart start) {
        return RollbackNativeQueryShell.create(ChunkAccess.class)
                .constant(ChunkAccess::getAllReferences, references)
                .query(chunk -> chunk.getStartForStructure(null), null, args -> start).instance();
    }
}
