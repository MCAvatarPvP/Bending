package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EarthBlastConfigTest {
    @TempDir Path directory;

    @Test void addsRedirectFixToExistingConfigWithoutChangingCustomDamage() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Earth:\n    EarthBlast:\n      Damage: 4.0\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertTrue(saved.getBoolean("Abilities.Earth.EarthBlast.RedirectFix"));
            assertEquals(4.0, saved.getDouble("Abilities.Earth.EarthBlast.Damage"));
        }
    }

    @Test void preservesExplicitLegacyRedirectSetting() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Earth:\n    EarthBlast:\n      RedirectFix: false\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertFalse(saved.getBoolean("Abilities.Earth.EarthBlast.RedirectFix"));
        }
    }
}
