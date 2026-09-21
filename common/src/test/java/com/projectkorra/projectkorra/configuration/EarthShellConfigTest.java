package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EarthShellConfigTest {
    private static final String SHELL = "Abilities.Earth.EarthShell.";
    @TempDir Path directory;

    @Test void existingConfigGetsEarthShellWithoutCopyingUnrelatedDefaults() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Air:\n    AirBlast:\n      Range: 37\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(37, saved.getInt("Abilities.Air.AirBlast.Range"));
            assertFalse(saved.contains("Abilities.Air.AirSwipe.Range"));
            assertFalse(saved.contains("AvatarState"));
            assertEquals(8000, saved.getLong(SHELL + "Duration"));
            assertEquals(12, saved.getDouble(SHELL + "BurstRange"));
            assertEquals(3000, saved.getLong(SHELL + "AirStunDuration"));
            assertEquals(0.85, saved.getDouble(SHELL + "ShardHitRadius"));
            assertEquals(2.0, saved.getDouble(SHELL + "MaxHeightAboveGround"));
            assertEquals(List.of("Shockwave:SHIFT_DOWN", "EarthBlast:LEFT_CLICK"), saved.getStringList(SHELL + "Combination"));
        }
    }

    @Test void partialSectionRetainsCustomValuesAndOnlyAddsMissingOptions() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, """
                Abilities:
                  Earth:
                    EarthShell:
                      Enabled: false
                      Duration: 4000
                      BurstRange: 18
                      AirStunDuration: 0
                      MaxHeightAboveGround: 0
                      Combination:
                      - RaiseEarth:LEFT_CLICK
                      - Shockwave:SHIFT_DOWN
                """);
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            String firstSave = Files.readString(path);
            ConfigManager.defaultConfig.reload();
            ConfigManager.configCheck(ConfigType.DEFAULT);
            assertEquals(firstSave, Files.readString(path), "reloading must not duplicate the section");
            Config saved = new Config(path.toFile(), false);
            assertFalse(saved.getBoolean(SHELL + "Enabled"));
            assertEquals(4000, saved.getLong(SHELL + "Duration"));
            assertEquals(18, saved.getDouble(SHELL + "BurstRange"));
            assertEquals(0, saved.getLong(SHELL + "AirStunDuration"));
            assertEquals(0, saved.getDouble(SHELL + "MaxHeightAboveGround"));
            assertEquals(7000, saved.getLong(SHELL + "Cooldown"));
            assertEquals(List.of("RaiseEarth:LEFT_CLICK", "Shockwave:SHIFT_DOWN"), saved.getStringList(SHELL + "Combination"));
        }
    }

    @Test void freshConfigStillGeneratesAllDefaultSections() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.createFile(path);
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertTrue(saved.containsExplicit(SHELL + "Combination"));
            assertTrue(saved.containsExplicit("Abilities.Air.AirBlast.Range"));
            assertTrue(saved.containsExplicit("Abilities.Water.WaterManipulation.Range"));
        }
    }

    @Test void helpSectionAlsoPersistsWithoutReplacingCustomizedText() throws Exception {
        Path path = directory.resolve("language.yml");
        String section = "Abilities.Earth.Combo.EarthShell.";
        Files.writeString(path, "Abilities:\n  Earth:\n    Combo:\n      EarthShell:\n        Instructions: Custom input\n");
        Config language = new Config(path.toFile(), false);
        language.addDefault(section + "Instructions", "Default input");
        language.addDefault(section + "Description", "Shell description");
        language.addDefault("Chat.Enable", true);
        language.persistDefaultsInSection("Abilities.Earth.Combo.EarthShell");
        language.save();
        Config saved = new Config(path.toFile(), false);
        assertEquals("Custom input", saved.getString(section + "Instructions"));
        assertEquals("Shell description", saved.getString(section + "Description"));
        assertFalse(saved.contains("Chat.Enable"));
    }
}
