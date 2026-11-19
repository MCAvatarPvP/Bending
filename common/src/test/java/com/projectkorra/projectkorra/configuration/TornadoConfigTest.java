package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TornadoConfigTest {
    private static final String TORNADO = "Abilities.Air.Tornado.";
    @TempDir Path directory;

    @Test void existingConfigReceivesChargeControlsAndBoundedVisualDefaults() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "Abilities:\n  Air:\n    AirBlast:\n      Range: 37\n");
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(37, saved.getInt("Abilities.Air.AirBlast.Range"));
            assertFalse(saved.contains("Abilities.Air.AirSwipe.Range"));
            assertEquals(500, saved.getLong(TORNADO + "MinimumChargeTime"));
            assertEquals(500, saved.getLong(TORNADO + "MinimumPullDuration"));
            assertEquals(2000, saved.getLong(TORNADO + "MaxPullDuration"));
            assertEquals(6000, saved.getLong(TORNADO + "ChargeTime"));
            assertEquals(4, saved.getDouble(TORNADO + "MinimumHeight"));
            assertEquals(18, saved.getDouble(TORNADO + "Height"));
            assertEquals(1.5, saved.getDouble(TORNADO + "MinimumRadius"));
            assertEquals(7, saved.getDouble(TORNADO + "Radius"));
            assertEquals(2.5, saved.getDouble(TORNADO + "MinimumPullZoneRadius"));
            assertEquals(9, saved.getDouble(TORNADO + "PullZoneRadius"));
            assertEquals(3000, saved.getLong(TORNADO + "MinimumCooldown"));
            assertEquals(18000, saved.getLong(TORNADO + "Cooldown"));
            assertEquals(0.35, saved.getDouble(TORNADO + "Speed"));
            assertEquals(32, saved.getDouble(TORNADO + "Range"));
            assertEquals(12000, saved.getLong(TORNADO + "RemoveDelay"));
            assertEquals(0.315, saved.getDouble(TORNADO + "PullVelocity"));
            assertEquals(6, saved.getDouble(TORNADO + "Control.CenterDistance"));
            assertEquals(0.16, saved.getDouble(TORNADO + "Control.Speed"));
            assertEquals(0.02, saved.getDouble(TORNADO + "Control.Acceleration"));
            assertEquals(0.88, saved.getDouble(TORNADO + "Control.Drag"));
            assertEquals(0.1, saved.getDouble(TORNADO + "Control.AimSmoothing"));
            assertEquals(4, saved.getInt(TORNADO + "Visuals.Ribbons"));
            assertEquals(32, saved.getInt(TORNADO + "Visuals.RibbonPoints"));
            assertEquals(10, saved.getInt(TORNADO + "Visuals.CloudLobes"));
            assertEquals(7, saved.getInt(TORNADO + "Visuals.GroundTendrils"));
            assertEquals(20, saved.getInt(TORNADO + "Visuals.GlassPieces"));
            assertEquals(0.18, saved.getDouble(TORNADO + "Visuals.MinimumGlassScale"));
            assertEquals(0.65, saved.getDouble(TORNADO + "Visuals.MaximumGlassScale"));
            assertEquals(0.16, saved.getDouble(TORNADO + "Visuals.GlassOrbitSpeed"));
            assertTrue(saved.getBoolean(TORNADO + "Sound.Enabled"));
            assertEquals(8, saved.getInt(TORNADO + "Sound.IntervalTicks"));
            assertEquals(1, saved.getDouble(TORNADO + "Sound.Volume"));
            assertEquals(0.65, saved.getDouble(TORNADO + "Sound.Pitch"));
            assertEquals(0.22, saved.getDouble(TORNADO + "Ride.Speed"));
        }
    }

    @Test void stockFixedSizeSettingsUpgradeAndPersistExactlyOnce() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, """
                Abilities:
                  Air:
                    Tornado:
                      ChargeTime: 750
                      Height: 8
                      Radius: 3.5
                      PullZoneRadius: 5.25
                      Cooldown: 10000
                      Speed: 0.35
                      Range: 16
                      RemoveDelay: 1500
                      MaxPullDuration: 0
                      PullVelocity: 0.315
                      Ride:
                        Speed: 0.8
                """);
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(6000, saved.getLong(TORNADO + "ChargeTime"));
            assertEquals(18, saved.getDouble(TORNADO + "Height"));
            assertEquals(7, saved.getDouble(TORNADO + "Radius"));
            assertEquals(9, saved.getDouble(TORNADO + "PullZoneRadius"));
            assertEquals(18000, saved.getLong(TORNADO + "Cooldown"));
            assertEquals(0.35, saved.getDouble(TORNADO + "Speed"));
            assertEquals(32, saved.getDouble(TORNADO + "Range"));
            assertEquals(12000, saved.getLong(TORNADO + "RemoveDelay"));
            assertEquals(2000, saved.getLong(TORNADO + "MaxPullDuration"));
            assertEquals(0.315, saved.getDouble(TORNADO + "PullVelocity"));
            assertEquals(0.22, saved.getDouble(TORNADO + "Ride.Speed"));
            String firstSave = Files.readString(path);
            ConfigManager.defaultConfig.reload();
            ConfigManager.configCheck(ConfigType.DEFAULT);
            assertEquals(firstSave, Files.readString(path));
        }
    }

    @Test void customTuningAndNewOptionsSurviveUpgrade() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, """
                Abilities:
                  Air:
                    Tornado:
                      Enabled: false
                      ChargeTime: 9000
                      MinimumChargeTime: 1000
                      Height: 21
                      MinimumHeight: 3
                      Radius: 10
                      PullZoneRadius: 12
                      Cooldown: 24000
                      MinimumCooldown: 0
                      Speed: 0.4
                      Range: 40
                      RemoveDelay: 18000
                      PullVelocity: 0.4
                      MinimumPullDuration: 800
                      MaxPullDuration: 3500
                      Ride:
                        Speed: 0.3
                      Sound:
                        Enabled: false
                        IntervalTicks: 12
                      Control:
                        Speed: 0.15
                      Visuals:
                        GlassPieces: 12
                        MinimumGlassScale: 0.25
                        MaximumGlassScale: 0.5
                        GlassOrbitSpeed: 0.2
                """);
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertFalse(saved.getBoolean(TORNADO + "Enabled"));
            assertEquals(9000, saved.getLong(TORNADO + "ChargeTime"));
            assertEquals(1000, saved.getLong(TORNADO + "MinimumChargeTime"));
            assertEquals(21, saved.getDouble(TORNADO + "Height"));
            assertEquals(3, saved.getDouble(TORNADO + "MinimumHeight"));
            assertEquals(10, saved.getDouble(TORNADO + "Radius"));
            assertEquals(12, saved.getDouble(TORNADO + "PullZoneRadius"));
            assertEquals(24000, saved.getLong(TORNADO + "Cooldown"));
            assertEquals(0, saved.getLong(TORNADO + "MinimumCooldown"));
            assertEquals(0.4, saved.getDouble(TORNADO + "Speed"));
            assertEquals(40, saved.getDouble(TORNADO + "Range"));
            assertEquals(18000, saved.getLong(TORNADO + "RemoveDelay"));
            assertEquals(0.4, saved.getDouble(TORNADO + "PullVelocity"));
            assertEquals(800, saved.getLong(TORNADO + "MinimumPullDuration"));
            assertEquals(3500, saved.getLong(TORNADO + "MaxPullDuration"));
            assertEquals(0.3, saved.getDouble(TORNADO + "Ride.Speed"));
            assertFalse(saved.getBoolean(TORNADO + "Sound.Enabled"));
            assertEquals(12, saved.getInt(TORNADO + "Sound.IntervalTicks"));
            assertEquals(0.15, saved.getDouble(TORNADO + "Control.Speed"));
            assertEquals(12, saved.getInt(TORNADO + "Visuals.GlassPieces"));
            assertEquals(0.25, saved.getDouble(TORNADO + "Visuals.MinimumGlassScale"));
            assertEquals(0.5, saved.getDouble(TORNADO + "Visuals.MaximumGlassScale"));
            assertEquals(0.2, saved.getDouble(TORNADO + "Visuals.GlassOrbitSpeed"));
        }
    }

    @Test void emptyConfigStillBootstrapsAllDefaults() throws Exception {
        Path path = directory.resolve("config.yml");
        Files.createFile(path);
        try (AbilityWorld ignored = new AbilityWorld()) {
            ConfigManager.defaultConfig = new Config(path.toFile(), false);
            ConfigManager.configCheck(ConfigType.DEFAULT);
            Config saved = new Config(path.toFile(), false);
            assertEquals(6000, saved.getLong(TORNADO + "ChargeTime"));
            assertTrue(saved.containsExplicit(TORNADO + "MinimumCooldown"));
            assertTrue(saved.containsExplicit("Abilities.Air.AirBlast.Range"));
            assertTrue(saved.containsExplicit("Abilities.Water.WaterManipulation.Range"));
        }
    }

    @Test void originalHelpUpdatesToChargingSteeringAndThrowing() throws Exception {
        Path path = directory.resolve("language.yml");
        Files.writeString(path, """
                Abilities:
                  Air:
                    Tornado:
                      Description: Create a particle cyclone that travels along the ground and pulls in nearby entities. Aim at your own Tornado and right-click to ride it.
                      Instructions: Hold sneak to charge and deploy Tornado. Once it forms, aim at the funnel and right-click in the air or on a block to ride; sneak to dismount.
                """);
        Config language = new Config(path.toFile(), false);
        ConfigManager.configureTornadoLanguage(language);
        language.save();
        Config saved = new Config(path.toFile(), false);
        assertTrue(saved.getString(TORNADO + "Description").contains("longer cooldown"));
        assertTrue(saved.getString(TORNADO + "Description").contains("light blue glass"));
        String instructions = saved.getString(TORNADO + "Instructions");
        assertTrue(instructions.contains("minimum charge"));
        assertTrue(instructions.contains("hold sneak to steer"));
        assertTrue(instructions.contains("Left click to throw"));
        assertTrue(instructions.contains("right-click to ride"));
        assertTrue(instructions.contains("look to steer"));
        assertTrue(instructions.contains("sneak to dismount"));
    }

    @Test void customHelpSurvivesAndMissingHelpPersists() throws Exception {
        Path path = directory.resolve("language.yml");
        Files.writeString(path, "Abilities:\n  Air:\n    Tornado:\n      Description: My custom tornado\n");
        Config language = new Config(path.toFile(), false);
        ConfigManager.configureTornadoLanguage(language);
        language.save();
        Config saved = new Config(path.toFile(), false);
        assertEquals("My custom tornado", saved.getString(TORNADO + "Description"));
        assertTrue(saved.containsExplicit(TORNADO + "Instructions"));
        assertTrue(saved.getString(TORNADO + "Instructions").contains("Left click to throw"));
    }
}
