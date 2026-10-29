package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client with "Client-side visuals" off advertises CAPABILITY_SERVER_VISUALS.
 * Paper must then send it every authoritative visual like an ordinary viewer,
 * while gameplay ownership (cooldowns, receipts, context) stays unchanged.
 */
class PredictionServerVisualsBoundaryTest {
    @Test
    void visualExclusionHelpersHonorTheServerVisualsCapability() throws IOException {
        final String server = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");

        assertTrue(server.contains("CAPABILITY_SERVER_VISUALS = 16"),
                "Paper must read the same server-visuals bit the Fabric hello sets");
        assertTrue(server.contains("public static Player predictedVisualEffectOwner()")
                        && server.contains("return drawsOwnVisuals(predictedEffectOwner());")
                        && server.contains("public static Player predictedVisualSoundOwner()")
                        && server.contains("return drawsOwnVisuals(predictedSoundEffectOwner());"),
                "particle and sound exclusion must go through the visuals-aware helpers");
        assertTrue(server.contains("(session.capabilities & CAPABILITY_SERVER_VISUALS) != 0 ? null : owner"),
                "an owner with client-side visuals off must not be excluded from broadcasts");
        assertTrue(server.contains("new Session(player.getUniqueId(), UUID.randomUUID(), hello.capabilities(),")
                        && server.contains("sessions.put(player.getUniqueId(), session)"),
                "a re-sent hello must replace the session so a live toggle takes effect");
    }

    @Test
    void platformBroadcastsUseOnlyTheVisualsAwareExclusion() throws IOException {
        final String platform = source(
                "src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        final String world = source(
                "src/main/java/com/projectkorra/projectkorra/platform/bukkit/model/BukkitWorld.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/model/BukkitWorld.java");

        assertTrue(platform.contains("PaperPredictionServer.predictedVisualEffectOwner()")
                        && platform.contains("PaperPredictionServer.predictedVisualSoundOwner()")
                        && world.contains("PaperPredictionServer.predictedVisualEffectOwner()"),
                "world and per-player particle/sound paths must use the visuals-aware owner");
        assertFalse(platform.contains("PaperPredictionServer.predictedEffectOwner()")
                        || platform.contains("PaperPredictionServer.predictedSoundEffectOwner()")
                        || world.contains("PaperPredictionServer.predictedEffectOwner()")
                        || world.contains("PaperPredictionServer.predictedSoundEffectOwner()"),
                "a raw owner lookup would hide visuals from a client that no longer draws its own");
    }

    @Test
    void fallingBlockPairingIsSkippedForServerVisualsSessions() throws IOException {
        final String server = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String spawn = method(server, "public int beforeSpawn(", "public void onSpawn(");
        final String receipt = method(server, "public void onSpawn(", "boolean drawsServerVisuals(");

        assertTrue(server.contains("(session.capabilities & CAPABILITY_SERVER_VISUALS) != 0;"),
                "drawsServerVisuals must test the session's capability bit");
        assertTrue(spawn.contains("++action.tempFallingBlockOrdinal")
                        && spawn.indexOf("if (drawsServerVisuals(ownerSession)) return ordinal;")
                        > spawn.indexOf("++action.tempFallingBlockOrdinal")
                        && spawn.indexOf("if (drawsServerVisuals(ownerSession)) return ordinal;")
                        < spawn.indexOf("PaperPredictionProtocol.TEMP_FALLING_BLOCK_PREPARE"),
                "the ordinal still advances, but no prepare may let the client hide Paper's falling block");
        assertTrue(receipt.contains("|| drawsServerVisuals(ownerSession)) return;")
                        && receipt.indexOf("drawsServerVisuals(ownerSession)")
                        < receipt.indexOf("PaperPredictionProtocol.TEMP_FALLING_BLOCK,"),
                "no receipt may alias Paper's falling block onto the hidden local copy");
    }

    @Test
    void gameplayOwnershipIgnoresTheVisualsSetting() throws IOException {
        final String server = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String cooldowns = method(server, "public void onAdded(CoreAbility source",
                "public void onSynchronize(");
        final String contextual = method(server, "public static Runnable contextual(Runnable task)",
                "public static <T> Callable<T> contextual(");

        assertTrue(cooldowns.contains("PaperPredictionServer.predictedEffectOwner()")
                        && !cooldowns.contains("predictedVisual"),
                "predicted cooldown suppression is gameplay and must not depend on the visuals setting");
        assertTrue(contextual.contains("predictedEffectOwner()") && !contextual.contains("predictedVisual"),
                "scheduled ability context must keep the raw prediction owner");
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        assertTrue(from >= 0, "missing start: " + start);
        final int to = source.indexOf(end, from);
        assertTrue(to > from, "missing end: " + end);
        return source.substring(from, to);
    }

    private static String source(final String first, final String second) throws IOException {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        assertTrue(Files.exists(path), "missing source: " + path);
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path);
    }
}
