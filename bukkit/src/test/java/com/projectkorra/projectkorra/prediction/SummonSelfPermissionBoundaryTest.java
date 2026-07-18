package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the default AirCombo permission inheritance used by non-OP players. */
class SummonSelfPermissionBoundaryTest {
    @Test
    void normalAirbendersInheritSummonSelfThroughAirCombo() throws IOException {
        Path plugin = Path.of("src/main/resources/plugin.yml");
        if (!Files.exists(plugin)) plugin = Path.of("bukkit").resolve(plugin);
        final String yaml = Files.readString(plugin);
        // Include the preceding newline so nested child entries such as the
        // bending.air -> AirCombo grant cannot be mistaken for the definition.
        final int airCombo = yaml.indexOf("\n  bending.ability.AirCombo:\n");
        final int flight = yaml.indexOf("\n  bending.ability.Flight:\n", airCombo);
        assertTrue(airCombo >= 0 && flight > airCombo);
        final String children = yaml.substring(airCombo, flight);

        assertTrue(children.contains("bending.ability.SummonSelf: true"),
                "an undeclared ability permission defaults to OP and blocks normal airbenders");
    }
}
