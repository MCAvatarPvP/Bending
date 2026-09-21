package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightningPunchConfigTest {
    @TempDir Path directory;

    @Test void addsDefaultsToAnExistingConfigAndPreservesCustomDamage() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Fire:\n    LightningPunch:\n      Damage: 4.0\n      Cooldown: 6000\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertTrue(saved.getBoolean("Abilities.Fire.LightningPunch.Enabled"));
            assertEquals(4.0, saved.getDouble("Abilities.Fire.LightningPunch.Damage"));
            assertEquals(6000, saved.getLong("Abilities.Fire.LightningPunch.Cooldown"));
            assertEquals(1000, saved.getLong("Abilities.Fire.LightningPunch.MissCooldown"));
            assertEquals(List.of("LightningBurst:LEFT_CLICK", "FirePunch:SLOT_CHANGE"),
                    saved.getStringList("Abilities.Fire.LightningPunch.Combination"));
        }
    }

    @Test void newConfigDefaultsToOneAndAHalfHearts() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.createFile(path);
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(3.0, saved.getDouble("Abilities.Fire.LightningPunch.Damage"));
            assertEquals(4000, saved.getLong("Abilities.Fire.LightningPunch.Cooldown"));
            assertEquals(1000, saved.getLong("Abilities.Fire.LightningPunch.MissCooldown"));
        }
    }

    @Test void oldDefaultComboIsMigratedToSlotSelection() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Fire:\n    LightningPunch:\n      Combination:\n        - LightningBurst:LEFT_CLICK\n        - FirePunch:LEFT_CLICK\n      MissCooldown: 1500\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(List.of("LightningBurst:LEFT_CLICK", "FirePunch:SLOT_CHANGE"),
                    saved.getStringList("Abilities.Fire.LightningPunch.Combination"));
            assertEquals(1500, saved.getLong("Abilities.Fire.LightningPunch.MissCooldown"));
        }
    }

    @Test void migrationPreservesACustomCombo() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Fire:\n    LightningPunch:\n      Combination:\n        - LightningBurst:SHIFT_DOWN\n        - FirePunch:SLOT_CHANGE\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(List.of("LightningBurst:SHIFT_DOWN", "FirePunch:SLOT_CHANGE"),
                    saved.getStringList("Abilities.Fire.LightningPunch.Combination"));
        }
    }
}
