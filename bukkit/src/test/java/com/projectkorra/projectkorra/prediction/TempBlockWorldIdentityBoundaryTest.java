package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards custom Bukkit worlds that share an Environment but not a registry key. */
class TempBlockWorldIdentityBoundaryTest {
    @Test
    void tempBlockOperationsUseTheActualBukkitWorldKey() throws IOException {
        String server = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String worldKey = between(server,
                "private static String worldKey(com.projectkorra.projectkorra.platform.mc.World world)",
                "private static boolean finite");

        assertTrue(worldKey.contains("bukkitWorld.getKey().toString()"),
                "TempBlocks must carry minecraft:neptune_arenas, not the NORMAL environment alias");
        assertFalse(worldKey.contains("getEnvironment()"),
                "multiple NORMAL worlds cannot all be serialized as minecraft:overworld");
    }

    @Test
    void directEarthReceiptsUseTheSameNamespacedWorldKeyAsTempBlocks() throws IOException {
        String server = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String directEarth = between(server,
                "public void beforeChange(final CoreAbility ability, final Block block,",
                "private void scheduleTicker()");

        assertTrue(directEarth.contains("worldKey(block.getWorld())"),
                "moved Earth must carry minecraft:neptune_arenas so Fabric can conceal Paper after a world switch");
        assertFalse(directEarth.contains("block.getWorld().getName()"),
                "a bare Bukkit world name is rejected by Fabric outside the overworld");

        String fallingEarth = between(server,
                "public int beforeSpawn(final CoreAbility ability,",
                "public void onSpawn(final CoreAbility ability,");
        assertTrue(fallingEarth.contains("worldKey(location.getWorld())"),
                "falling Earth prepares must use the same destination-world identity");
        assertFalse(fallingEarth.contains("location.getWorld().getName()"),
                "falling Earth cannot fall back to a bare Bukkit world name after a transfer");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from));
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
