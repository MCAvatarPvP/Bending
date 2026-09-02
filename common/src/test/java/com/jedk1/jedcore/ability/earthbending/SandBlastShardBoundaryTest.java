package com.jedk1.jedcore.ability.earthbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandBlastShardBoundaryTest {

    @Test
    void sandBlastUsesManagedDisplayShardsWithSweptPhysics() throws IOException {
        final String source = source("src/main/java/com/jedk1/jedcore/ability/earthbending/SandBlast.java");

        assertTrue(source.contains("List<SandShard> shards")
                        && source.contains("spawn(location, BlockDisplay.class)")
                        && source.contains("display.setBlock(this.sourceData.clone())"),
                "the selected sand block data must drive independently managed display shards");
        assertFalse(source.contains("new TempFallingBlock"),
                "the old full-block falling entity must not return");
        assertTrue(source.contains("PredictionDeterminism.random")
                        && source.contains(":display-shard-physics"),
                "shard variation must remain deterministic between prediction and authority");
        assertTrue(source.contains("PHYSICS_SUBSTEP")
                        && source.contains("Math.ceil(movement.length() / PHYSICS_SUBSTEP)")
                        && source.contains("this.isBlocked("),
                "fast shards must use substeps and volume-aware world collision checks");
        assertTrue(source.contains("shard.velocity.multiply(this.shardDrag)")
                        && source.contains("shard.velocity.getY() - this.shardGravity")
                        && source.contains("* this.shardBounce")
                        && source.contains("shard.bounces > this.maximumBounces"),
                "shards must model drag, gravity, bounce response, and bounded settling");
    }

    @Test
    void shardsTumbleAndAreAlwaysCleanedUp() throws IOException {
        final String source = source("src/main/java/com/jedk1/jedcore/ability/earthbending/SandBlast.java");

        assertTrue(source.contains("new Quaternionf().rotateX(shard.rotationX)")
                        && source.contains("shard.angularX *= 0.992F")
                        && source.contains("shard.scaleX, shard.scaleY, shard.scaleZ"),
                "each shard must have non-uniform geometry and damped angular momentum");
        assertTrue(source.contains("for (final SandShard shard : this.shards)")
                        && source.contains("shard.display.remove()")
                        && source.contains("this.shards.clear()"),
                "ability teardown must not leak display entities");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("common").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
