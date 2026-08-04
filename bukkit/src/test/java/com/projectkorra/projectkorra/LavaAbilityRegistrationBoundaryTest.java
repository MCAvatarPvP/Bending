package com.projectkorra.projectkorra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the bindable addon LavaSurge from the hidden legacy name collision. */
class LavaAbilityRegistrationBoundaryTest {
    @Test
    void addonOverrideWinsRegardlessOfCoreReloadOrder() throws IOException {
        String registry = source("common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java");
        String legacy = source("common/src/main/java/com/projectkorra/projectkorra/earthbending/lava/LavaSurge.java");
        String addon = source("common/src/main/java/me/simplicitee/project/addons/ability/earth/LavaSurge.java");

        assertTrue(registry.contains("existing instanceof AddonAbility && !(ability instanceof AddonAbility)"),
                "a core rescan must preserve a same-named addon override");
        assertTrue(registry.split("indexAbilityName\\(name, coreAbil\\)", -1).length >= 3,
                "both plugin and loose-addon registration paths must use deterministic name resolution");
        assertTrue(legacy.contains("return true; // disabled"));
        assertTrue(addon.contains("implements AddonAbility"));
        assertTrue(addon.contains("return \"LavaSurge\""));
    }

    private static String source(String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("common/")) {
            path = Path.of("..").resolve(relative);
        }
        assertTrue(Files.exists(path), path.toString());
        return Files.readString(path);
    }
}
