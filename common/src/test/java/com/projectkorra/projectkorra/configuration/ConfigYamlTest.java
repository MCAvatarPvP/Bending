package com.projectkorra.projectkorra.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.error.YAMLException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigYamlTest {
    private static final String DESCRIPTION = "Control a large stream of air that grabs onto enemies allowing you to direct them temporarily.";
    private static final String INSTRUCTIONS = "AirShield (Hold Shift) > AirSuction (Left Click) > AirBlast (Left Click)";
    private static final String AIR_STREAM = "Abilities.Air.Combo.AirStream.";

    @TempDir
    Path directory;

    @Test
    void wrappedAirStreamHelpRetainsTheContinuationLines() throws Exception {
        final Config config = load("""
                Abilities:
                  Air:
                    Combo:
                      AirStream:
                        Description: Control a large stream of air that grabs onto enemies allowing
                          you to direct them temporarily.
                        Instructions: AirShield (Hold Shift) > AirSuction (Left Click) > AirBlast
                          (Left Click)
                """);

        assertEquals(DESCRIPTION, config.getString(AIR_STREAM + "Description"));
        assertEquals(INSTRUCTIONS, config.getString(AIR_STREAM + "Instructions"));
        config.save();
        config.reload();
        assertEquals(DESCRIPTION, config.getString(AIR_STREAM + "Description"));
        assertEquals(INSTRUCTIONS, config.getString(AIR_STREAM + "Instructions"));
    }

    @Test
    void quotedFoldedAndLiteralTextSurvivesLoadingAndSaving() throws Exception {
        final Config config = load("""
                quoted: 'Aim at the target''s feet and hold sneak
                  to launch them.'
                folded: >-
                  First sentence continues
                  on the next physical line.
                literal: |-
                  First visible line.
                  Second visible line: # this is text.
                escaped: "First\\nSecond line"
                colors: ['#ice', 'white, WHITE_STAINED_GLASS']
                speed: '2.5 # 3.0, 3.5'
                """);

        for (int pass = 0; pass < 2; pass++) {
            assertEquals("Aim at the target's feet and hold sneak to launch them.", config.getString("quoted"));
            assertEquals("First sentence continues on the next physical line.", config.getString("folded"));
            assertEquals("First visible line.\nSecond visible line: # this is text.", config.getString("literal"));
            assertEquals("First\nSecond line", config.getString("escaped"));
            assertEquals(List.of("#ice", "white, WHITE_STAINED_GLASS"), config.getStringList("colors"));
            assertEquals(2.5, config.getDouble("speed"));
            config.save();
            config.reload();
        }
    }

    @Test
    void previouslyTruncatedStockHelpIsRepairedWithoutReplacingCustomHelp() throws Exception {
        final Config config = load("""
                Abilities:
                  Air:
                    Combo:
                      AirStream:
                        Description: 'Control a large stream of air that grabs onto enemies allowing'
                        Instructions: 'AirShield (Hold Shift) > AirSuction (Left Click) > AirBlast'
                      Custom:
                        Description: 'This server uses a custom explanation.'
                """);
        config.addDefault(AIR_STREAM + "Description", DESCRIPTION);
        config.addDefault(AIR_STREAM + "Instructions", INSTRUCTIONS);
        config.addDefault("Abilities.Air.Combo.Custom.Description", DESCRIPTION);

        assertEquals(2, config.repairTruncatedAbilityText());
        assertEquals(0, config.repairTruncatedAbilityText(), "migration must be idempotent");
        config.save();
        config.reload();
        assertEquals(DESCRIPTION, config.getString(AIR_STREAM + "Description"));
        assertEquals(INSTRUCTIONS, config.getString(AIR_STREAM + "Instructions"));
        assertEquals("This server uses a custom explanation.",
                config.getString("Abilities.Air.Combo.Custom.Description"));
    }

    @Test
    void parsingFailureKeepsThePreviouslyLoadedConfiguration() throws Exception {
        final Config config = load("value: complete\n");
        Files.writeString(directory.resolve("language.yml"), "value: 'unterminated\n");
        final YAMLException failure = assertThrows(YAMLException.class, config::reload);
        assertTrue(failure.getMessage().contains(directory.resolve("language.yml").toString()));
        assertEquals("complete", config.getString("value"));
    }

    @Test
    void legacyBarePresetKeyIsBackedUpAndRetainedAlongsideNamedPresets() throws Exception {
        final String original = ":\r\n  - 'FireBlast'\r\n  - 'AirBlast'\r\nCustom:\r\n- WaterManipulation\r\n";
        final Path file = directory.resolve("presets.yml");
        Files.writeString(file, original);
        final Config config = new Config(file.toFile(), false);

        assertEquals(List.of("FireBlast", "AirBlast"), config.getStringList(""));
        assertEquals(List.of("WaterManipulation"), config.getStringList("Custom"));
        assertEquals(original, Files.readString(file), "loading must leave the original file intact");
        final List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertEquals(original, Files.readString(backups.getFirst()));

        config.save();
        config.reload();
        assertEquals(List.of("FireBlast", "AirBlast"), config.getStringList(""));
        assertEquals(List.of("WaterManipulation"), config.getStringList("Custom"));
        assertEquals(1, backups().size(), "valid saved YAML needs no further repair");

        Files.writeString(file, ": # another legacy empty key\n- EarthBlast\n");
        config.reload();
        assertEquals(List.of("EarthBlast"), config.getStringList(""));
        assertEquals(2, backups().size());
        assertEquals(original, Files.readString(backups.getFirst()), "never overwrite an earlier backup");
    }

    @Test
    void bareKeyRecoveryDoesNotHideOtherSyntaxErrorsOrOverwriteValues() throws Exception {
        final Config config = load("value: complete\n");
        final String malformed = ":\n- FireBlast\nvalue: 'unterminated\n";
        Files.writeString(directory.resolve("language.yml"), malformed);
        assertThrows(YAMLException.class, config::reload);
        assertEquals("complete", config.getString("value"));
        assertEquals(malformed, Files.readString(directory.resolve("language.yml")));
        assertTrue(backups().isEmpty(), "no repair may be committed until the complete YAML parses");
        Files.writeString(directory.resolve("language.yml"), "value: fixed\n");
        config.reload();
        assertEquals("fixed", config.getString("value"));
    }

    @Test
    void validColonTextAndUnindentedPresetListsNeedNoRepair() throws Exception {
        final String original = "text: |\n  :\n  Keep this text.\nExample:\n- FireBlast\n- AirBlast\n";
        final Config config = load(original);
        assertEquals(":\nKeep this text.\n", config.getString("text"));
        assertEquals(List.of("FireBlast", "AirBlast"), config.getStringList("Example"));
        assertTrue(backups().isEmpty());
        assertEquals(original, Files.readString(directory.resolve("language.yml")));
        config.save();
        config.reload();
        assertEquals(List.of("FireBlast", "AirBlast"), config.getStringList("Example"));
        assertEquals(":\nKeep this text.\n", config.getString("text"));
    }

    private List<Path> backups() throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".invalid.bak")).toList();
        }
    }

    @Test
    void savingExistingFilesDoesNotMaterializeUnrelatedDefaults() throws Exception {
        final Config config = load("existing: 7\n");
        config.addDefault("other.value", 42);
        config.save();
        config.reload();
        assertEquals(42, config.getInt("other.value"));
        assertFalse(config.containsExplicit("other.value"));
        assertTrue(config.hasLoadedValues());
    }

    private Config load(final String yaml) throws Exception {
        final Path file = directory.resolve("language.yml");
        Files.writeString(file, yaml);
        return new Config(file.toFile(), false);
    }
}
