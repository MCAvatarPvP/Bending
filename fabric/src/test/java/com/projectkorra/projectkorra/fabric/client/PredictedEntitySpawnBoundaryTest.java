package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents a new prediction spawn path from reintroducing positive ID collisions. */
class PredictedEntitySpawnBoundaryTest {
    @Test
    void everyPredictedEntityIsAssignedAClientOnlyIdBeforeWorldInsertion()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java"));

        assertEquals(1, occurrences(source, ".addEntity(entity)"),
                "predicted spawns must use the guarded insertion helper");
        assertTrue(source.contains(
                "entity.setId(PREDICTED_ENTITY_IDS.reserve(id -> world.getEntityById(id) != null"));
        assertTrue(source.contains("|| ExactPredictionRuntime.hasEntityAlias(id))"),
                "hidden server lifecycle aliases must also reserve their numeric IDs");
        assertTrue(source.indexOf("entity.setId(PREDICTED_ENTITY_IDS.reserve")
                        < source.indexOf("world.addEntity(entity)"),
                "the collision-free ID must be assigned before ClientWorld.addEntity can remove an occupant");
    }

    private static int occurrences(final String source, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
