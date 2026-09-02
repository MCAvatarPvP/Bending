package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the client correction for vanilla's velocity-discarding tinted-leaf factory. */
class TintedLeavesVelocityBoundaryTest {

    @Test
    void tintedLeavesRetainPacketVelocity() throws IOException {
        final String mixin = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/TintedLeavesVelocityMixin.java");
        final String config = source("src/main/resources/projectkorra.mixins.json");
        final String adapter = source(
                "src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");

        assertTrue(config.contains("client.TintedLeavesVelocityMixin"),
                "the tinted-leaf velocity correction must be registered client-side");
        assertTrue(mixin.contains("@Mixin(LeavesParticle.TintedLeavesFactory.class)")
                        && mixin.contains("particle.setVelocity(velocityX, velocityY, velocityZ)"),
                "the native tinted-leaf particle must inherit the packet's exact velocity");
        assertTrue(adapter.contains("ParticleTypes.TINTED_LEAVES")
                        && adapter.contains("TintedParticleEffect.create("),
                "the shared particle adapter must create Minecraft's native colored leaf effect");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
