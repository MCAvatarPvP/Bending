package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards smooth movement for locally predicted Display entities. */
class DisplayInterpolationBoundaryTest {
    @Test
    void paperDisplayLifecycleIsHiddenWithoutAliasingThePredictedDisplay() throws IOException {
        Path runtimeSource = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(runtimeSource)) runtimeSource = Path.of("fabric").resolve(runtimeSource);
        final String runtime = Files.readString(runtimeSource);
        final int removalStart = runtime.indexOf("private boolean removeAliasedEntity0");
        final int removalEnd = runtime.indexOf("private long currentAction()", removalStart);
        assertTrue(removalStart >= 0 && removalEnd > removalStart);
        final String removal = runtime.substring(removalStart, removalEnd);
        final int spawnStart = runtime.indexOf("private boolean reconcileSpawn0");
        final int spawnEnd = runtime.indexOf("private boolean removeAliasedEntity0", spawnStart);
        assertTrue(spawnStart >= 0 && spawnEnd > spawnStart);
        final String spawn = runtime.substring(spawnStart, spawnEnd);

        assertTrue(runtime.contains("private final Map<Integer, Entity> hiddenPredictedDisplayEntities"),
                "Paper display IDs need a tombstone ledger that never exposes the predicted entity");
        assertTrue(spawn.contains("best instanceof DisplayEntity")
                        && spawn.contains("hiddenPredictedDisplayEntities.put(packet.getEntityId(), best)")
                        && spawn.indexOf("hiddenPredictedDisplayEntities.put(packet.getEntityId(), best)")
                        < spawn.indexOf("authoritativeEntityAliases.put(packet.getEntityId(), best)"),
                "the Paper spawn must be hidden before generic aliasing can expose the local display");
        assertTrue(removal.contains("removeHiddenEntity0(serverEntityId)"),
                "Paper removal must close only its hidden ID tombstone, never the predicted display");

        Path packetSource = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");
        if (!Files.exists(packetSource)) packetSource = Path.of("fabric").resolve(packetSource);
        final String packets = Files.readString(packetSource);
        assertTrue(packets.contains("if (ExactPredictionRuntime.reconcileSpawn(packet)) ci.cancel()"),
                "the Paper BlockDisplay spawn must remain suppressed after it is paired to the predicted display");
        assertTrue(packets.contains("ExactPredictionRuntime.removeHiddenEntity(id)")
                        && packets.contains("packet.getEntityIds().removeInt(index)"),
                "a destroy packet must close hidden display IDs without resolving them to the local entity");

        Path aliasSource = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientWorldPredictionAliasMixin.java");
        if (!Files.exists(aliasSource)) aliasSource = Path.of("fabric").resolve(aliasSource);
        final String aliases = Files.readString(aliasSource);
        assertTrue(aliases.contains("if (ExactPredictionRuntime.removeAliasedEntity(id)) ci.cancel()"),
                "the Paper removal must not fall through and target the predicted entity by its aliased id");
    }

    @Test
    void predictedClientBlockDisplayTeleportFeedsTheNativePositionInterpolator() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        final String fabric = Files.readString(source);
        final int start = fabric.indexOf("private static final class ClientBlockDisplay");
        final int end = fabric.indexOf("private static final class ClientShulkerBullet", start);
        assertTrue(start >= 0 && end > start);
        final String display = fabric.substring(start, end);

        assertTrue(display.contains("value.getTeleportDuration() > 0")
                        && display.contains("value.getInterpolator().setLerpDuration")
                        && display.contains("value.getInterpolator().refreshPositionAndAngles"),
                "predicted BlockDisplays must interpolate their render position instead of snapping every ability tick");
    }

    @Test
    void clientDisplayTeleportFeedsTheNativePositionInterpolator() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        final String fabric = Files.readString(source);
        final int start = fabric.indexOf("private static class DisplayView");
        final int end = fabric.indexOf("private static final class BlockDisplayView", start);
        assertTrue(start >= 0 && end > start);
        final String display = fabric.substring(start, end);

        assertTrue(display.contains("value.getEntityWorld().isClient()")
                        && display.contains("value.getInterpolator().setLerpDuration")
                        && display.contains("value.getInterpolator().refreshPositionAndAngles"),
                "direct requestTeleport snaps a locally predicted display; its render position must use DisplayEntity's interpolator");
    }
}
