package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeScreenProjectileParityTest {
    @Test
    void bothSmokeScreenLaunchApisCreateRealBukkitSnowballs() throws IOException {
        String adapter = read("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        String core = read("../common/src/main/java/com/projectkorra/projectkorra/chiblocking/Smokescreen.java",
                "common/src/main/java/com/projectkorra/projectkorra/chiblocking/Smokescreen.java");
        String hyperion = read("../common/src/main/java/me/moros/hyperion/abilities/chiblocking/Smokescreen.java",
                "common/src/main/java/me/moros/hyperion/abilities/chiblocking/Smokescreen.java");

        assertTrue(core.contains("getWorld().spawn(spawn, Snowball.class"));
        assertTrue(hyperion.contains("player.launchProjectile(Snowball.class)"));
        assertTrue(adapter.contains("value instanceof org.bukkit.entity.Snowball snowball"));
        assertTrue(adapter.contains("org.bukkit.entity.Snowball snowball = value.spawn(")
                && adapter.contains("locationHandle(location), org.bukkit.entity.Snowball.class);"));
        assertTrue(adapter.contains("value.launchProjectile(org.bukkit.entity.Snowball.class)"));
        assertTrue(adapter.contains("new SnowballView(value)"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
