package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards Paper/Fabric particle-count parity for locally predicted effects. */
class ParticleCountParityBoundaryTest {
    @Test
    void predictedPlayerParticlesKeepCountAndForceSemantics() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        final String fabric = Files.readString(source);

        final int worldStart = fabric.indexOf("public static final class ClientWorldView");
        final int worldEnd = fabric.indexOf("public static final class ClientBlockView", worldStart);
        assertTrue(worldStart >= 0 && worldEnd > worldStart);
        final String world = fabric.substring(worldStart, worldEnd);
        assertTrue(world.contains("int samples = count == 0 ? 1 : Math.max(1, count)")
                        && world.contains("value.addParticleClient(effect, force, false"),
                "predicted particles must emit the requested sample count and retain Paper's force flag");

        final int playerStart = fabric.indexOf("public static final class ClientPlayerView", worldEnd);
        final int playerEnd = fabric.indexOf("private static final class ClientInventory", playerStart);
        assertTrue(playerStart >= 0);
        assertTrue(playerEnd > playerStart);
        final String player = fabric.substring(playerStart, playerEnd);
        assertTrue(player.contains(".spawnParticle(particle, location, count, ox, oy, oz, extra, data, force)"),
                "the client player wrapper must not discard ParticleUtil's force argument");
    }
}
