package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionActivationBoundaryTest {
    @Test
    void successfulCombosReportTheirDifferentlyNamedRuntimeAbility() throws IOException {
        String combos = read("../common/src/main/java/com/projectkorra/projectkorra/ability/util/ComboManager.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/util/ComboManager.java");

        assertTrue(combos.contains("created instanceof CoreAbility ability && ability.isStarted() && !ability.isRemoved()"));
        assertTrue(combos.contains("AbilityActivationManager.markHandled()"));
    }

    @Test
    void combustionAlwaysSelectsJedCoreWarmupImplementation() throws IOException {
        String embedded = read("../common/src/main/java/com/projectkorra/projectkorra/ability/util/EmbeddedAddonBootstrap.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/util/EmbeddedAddonBootstrap.java");
        String addons = read("../common/src/main/java/com/projectkorra/projectkorra/ability/activation/AddonAbilityActivationBootstrap.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/activation/AddonAbilityActivationBootstrap.java");
        String core = read("../common/src/main/java/com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java");

        int hyperion = embedded.indexOf("tryEnable(\"Hyperion abilities\"");
        int jedCore = embedded.indexOf("tryEnable(\"JedCore abilities\"");
        assertTrue(hyperion >= 0 && jedCore > hyperion, "JedCore must win the duplicate public ability name");
        assertFalse(addons.contains("new me.moros.hyperion.abilities.firebending.Combustion"));
        assertFalse(core.contains("register(\"Combustion\""));
        assertTrue(addons.contains("register(\"Combustion\", ClickType.SHIFT_DOWN, context -> created(new Combustion"));
        assertTrue(addons.contains("Combustion.combust(context.getPlayer())"));
    }

    @Test
    void dischargeUsesTheSameActionSeedAndInputOriginOnBothSides() throws IOException {
        String discharge = read("../common/src/main/java/com/jedk1/jedcore/ability/firebending/Discharge.java",
                "common/src/main/java/com/jedk1/jedcore/ability/firebending/Discharge.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");

        assertTrue(discharge.contains("PredictionDeterminism.random("));
        assertTrue(discharge.contains("location = player.getEyeLocation().clone()"));
        assertTrue(fabric.contains("PredictionDeterminism.run(input.sequence()"));
    }

    @Test
    void serverNativeAttackersUseTheReactionWindowWithoutAPredictionAction() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");

        assertFalse(paper.contains("if (action == null) return false"));
        assertFalse(fabric.contains("if (action == null) return false"));
        assertTrue(paper.contains("pendingNativeReactions")
                && paper.contains("target.getBoundingBox().getCenter()"));
        assertTrue(fabric.contains("pendingNativeReactions")
                && fabric.contains("target.getBoundingBox().getCenter()"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
