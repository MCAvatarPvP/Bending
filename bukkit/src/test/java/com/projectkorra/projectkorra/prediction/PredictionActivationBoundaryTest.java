package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        assertTrue(discharge.contains("branches.put(nextBranchId++, location.clone())"),
                "capturing the input origin must also seed the branch advanced by progress()");
        assertTrue(discharge.contains("implements AddonAbility, EntityHitboxProvider"));
        assertTrue(discharge.contains("entityHitSamples.add(l.clone())"),
                "reaction geometry must use every transient sphere tested against entities");
        assertTrue(discharge.contains("return entityCollisionRadius"),
                "entity hit resolution must not substitute the ability-collision radius");
        assertTrue(discharge.contains("if (collided && authoritative)"),
                "a stale predicted entity position must not terminate Discharge");
        assertFalse(discharge.contains("hit = CollisionDetector.checkEntityCollisions"),
                "later samples must not overwrite an earlier collision result");
        assertTrue(fabric.contains("PredictionDeterminism.run(input.sequence()"));
    }

    @Test
    void everySeparatelyConfiguredJedCoreEntityHitboxFeedsReactionGeometry() throws IOException {
        Path root = Path.of("../common/src/main/java/com/jedk1/jedcore/ability");
        if (!Files.exists(root)) root = Path.of("common/src/main/java/com/jedk1/jedcore/ability");
        assertTrue(Files.exists(root));

        final List<String> missingProviders = new ArrayList<>();
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    final String source = Files.readString(path);
                    if ((source.contains("EntityCollisionRadius") || source.contains("entityCollisionRadius"))
                            && !source.contains("EntityHitboxProvider")) {
                        missingProviders.add(path.toString());
                    }
                } catch (final IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
        assertTrue(missingProviders.isEmpty(),
                "separate entity colliders must feed authoritative reaction geometry: " + missingProviders);

        final String hitGeometry = read("../common/src/main/java/com/projectkorra/projectkorra/prediction/HitGeometry.java",
                "common/src/main/java/com/projectkorra/projectkorra/prediction/HitGeometry.java");
        final String waterGimbal = read("../common/src/main/java/com/jedk1/jedcore/ability/waterbending/combo/WaterGimbal.java",
                "common/src/main/java/com/jedk1/jedcore/ability/waterbending/combo/WaterGimbal.java");
        final String combustion = read("../common/src/main/java/com/jedk1/jedcore/ability/firebending/Combustion.java",
                "common/src/main/java/com/jedk1/jedcore/ability/firebending/Combustion.java");
        assertTrue(hitGeometry.contains("entityHitbox.getEntityHitLocations()")
                && hitGeometry.contains("entityHitbox.getEntityHitRadius()"));
        assertTrue(waterGimbal.contains("blast.getAbility() == this"),
                "WaterGimbal must expose the child streams that actually attribute its damage");
        assertTrue(combustion.contains("entityHitRadius = Math.max(0D, size)"),
                "Combustion must retain its transient explosion radius for reaction resolution");
    }

    @Test
    void comboStreamDamageUsesEveryActiveStreamLocation() throws IOException {
        final String fireKick = read("../common/src/main/java/com/projectkorra/projectkorra/firebending/combo/FireKick.java",
                "common/src/main/java/com/projectkorra/projectkorra/firebending/combo/FireKick.java");
        final String fireSpin = read("../common/src/main/java/com/projectkorra/projectkorra/firebending/combo/FireSpin.java",
                "common/src/main/java/com/projectkorra/projectkorra/firebending/combo/FireSpin.java");
        final String jetBlaze = read("../common/src/main/java/com/projectkorra/projectkorra/firebending/combo/JetBlaze.java",
                "common/src/main/java/com/projectkorra/projectkorra/firebending/combo/JetBlaze.java");

        assertTrue(fireKick.contains("public List<Location> getLocations()")
                && fireKick.contains("locations.add(stream.getLocation())"));
        assertTrue(fireSpin.contains("public List<Location> getLocations()")
                && fireSpin.contains("locations.add(stream.getLocation())"));
        assertTrue(jetBlaze.contains("implements ComboAbility, EntityHitboxProvider")
                && jetBlaze.contains("locations.add(task.getLocation().clone())"));
        assertTrue(jetBlaze.contains("return 2D"),
                "JetBlaze reaction geometry must use its stream's configured entity radius");
    }

    @Test
    void predictedRemoteContactsCannotTerminateAuthoritativeProjectiles() throws IOException {
        final String[] guardedProjectiles = {
                "airbending/AirBlade.java",
                "airbending/AirPunch.java",
                "airbending/SonicBlast.java",
                "waterbending/WaterBlast.java"
        };
        for (final String relative : guardedProjectiles) {
            final String source = read("../common/src/main/java/com/jedk1/jedcore/ability/" + relative,
                    "common/src/main/java/com/jedk1/jedcore/ability/" + relative);
            assertTrue(source.contains("if (!authoritative) return true;"), relative);
            assertTrue(source.contains("hit && authoritative"), relative);
        }

        final String fireBall = read("../common/src/main/java/com/jedk1/jedcore/ability/firebending/FireBall.java",
                "common/src/main/java/com/jedk1/jedcore/ability/firebending/FireBall.java");
        final String fireShots = read("../common/src/main/java/com/jedk1/jedcore/ability/firebending/FireShots.java",
                "common/src/main/java/com/jedk1/jedcore/ability/firebending/FireShots.java");
        final String combustion = read("../common/src/main/java/com/jedk1/jedcore/ability/firebending/Combustion.java",
                "common/src/main/java/com/jedk1/jedcore/ability/firebending/Combustion.java");
        final String earthShard = read("../common/src/main/java/com/jedk1/jedcore/ability/earthbending/EarthShard.java",
                "common/src/main/java/com/jedk1/jedcore/ability/earthbending/EarthShard.java");
        assertTrue(fireBall.contains("else if (CooldownSync.isAuthoritative())")
                && fireBall.contains("if (!CooldownSync.isAuthoritative()) return true;"));
        assertTrue(fireShots.contains("hit && authoritative")
                && fireShots.contains("if (!authoritative) return true;"));
        assertTrue(combustion.contains("hit && authoritative")
                && combustion.contains("if (!authoritative) return true;"));
        assertTrue(earthShard.contains("if (!CooldownSync.isAuthoritative()) return true;"),
                "a stale client entity must not destroy a predicted falling shard");
    }

    @Test
    void everyMoveUsesTheSharedRemoteContactLifecycleBoundary() throws IOException {
        final String damage = read("../common/src/main/java/com/projectkorra/projectkorra/util/DamageHandler.java",
                "common/src/main/java/com/projectkorra/projectkorra/util/DamageHandler.java");
        final String velocity = read("../common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java",
                "common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java");
        final String ability = read("../common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java");
        final String execution = read("../common/src/main/java/com/projectkorra/projectkorra/prediction/AbilityExecutionContext.java",
                "common/src/main/java/com/projectkorra/projectkorra/prediction/AbilityExecutionContext.java");
        final String fabricEntities = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        final String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String paperPlatform = read("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        final String fabricPlatform = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");

        assertTrue(damage.indexOf("PredictedContactSync.mark(ability, entity)")
                        < damage.indexOf("HitResolutionSync.defer(HitResolutionSync.Effect.DAMAGE"),
                "remote contact ownership must be established before damage processing");
        assertTrue(velocity.indexOf("PredictedContactSync.mark(ability, entity)")
                        < velocity.indexOf("new AbilityVelocityAffectEntityEvent"),
                "remote contact ownership must be established before velocity processing");
        assertTrue(ability.contains("if (PredictedContactSync.suppressRemoval(this))"));
        assertTrue(execution.contains("catch (final PredictedContactSync.Abort ignored)"),
                "ability-specific code after a stale remote contact must not run");
        assertTrue(fabricEntities.contains("private boolean suppressRemoteMutation()")
                && fabricEntities.contains("!ExactPredictionRuntime.isPredictedOwned(value)"),
                "direct addon mutations must be blocked without affecting locally spawned ability entities");
        assertTrue(runtime.contains("PredictedContactSync.forceRemoval(authoritativeSelection"),
                "server reconciliation must always be able to end a guarded ability");
        assertTrue(paperPlatform.contains("HitResolutionSync.Effect.STATUS")
                && fabricPlatform.contains("HitResolutionSync.Effect.STATUS"),
                "direct fire/potion state from every bundled move must share the hit decision");
        assertTrue(paperPlatform.contains("VelocitySync.applyDirect(AbilityExecutionContext.current(), this")
                && fabricPlatform.contains("VelocitySync.applyDirect(AbilityExecutionContext.current(), this"),
                "legacy/addon direct knockback must share the reaction decision on both servers");
    }

    @Test
    void noBundledMoveBypassesAuthoritativeDamageResolution() throws IOException {
        Path root = Path.of("../common/src/main/java");
        if (!Files.exists(root)) root = Path.of("common/src/main/java");
        assertTrue(Files.exists(root));
        final Set<String> resourceDamageOnly = Set.of(
                "PlantArmor.java", "LeafStorm.java", "WallOfFire.java");
        final List<String> bypasses = new ArrayList<>();
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                final String name = path.getFileName().toString();
                if (name.equals("DamageHandler.java") || resourceDamageOnly.contains(name)) return;
                try {
                    int lineNumber = 0;
                    for (final String line : Files.readAllLines(path)) {
                        lineNumber++;
                        if (line.contains(".damage(") && !line.contains("Utils.damage(")) {
                            bypasses.add(path + ":" + lineNumber + " " + line.trim());
                        }
                    }
                } catch (final IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
        assertTrue(bypasses.isEmpty(),
                "direct entity damage bypasses reaction and prediction ownership: " + bypasses);
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

    @Test
    void concurrentAirBlastsKeepStableInstanceRemovalOwnership() throws IOException {
        String commonInput = read("../common/src/main/java/com/projectkorra/projectkorra/listener/CommonInputHandler.java",
                "common/src/main/java/com/projectkorra/projectkorra/listener/CommonInputHandler.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(commonInput.contains("AbilityActivationManager.markHandled(blast)"));
        assertTrue(paper.contains("Action action = abilityCreationActions.get(ability)"));
        assertTrue(fabric.contains("Action action = abilityCreationActions.get(ability)"));
        assertTrue(client.contains("abilityCreationActions.get(ability), removed.actionSequence()"));
        assertTrue(client.contains("accepted self-owned velocity without retained mutation"));
    }

    @Test
    void ropeDartConfirmsContactOnceBeforeApplyingItsSustainedPull() throws IOException {
        String ropeDart = read("../common/src/main/java/me/literka/abilities/RopeDart.java",
                "common/src/main/java/me/literka/abilities/RopeDart.java");
        String generalMethods = read("../common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java",
                "common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java");

        assertTrue(ropeDart.contains("HitResolutionSync.defer(HitResolutionSync.Effect.VELOCITY, this, target, confirm)"),
                "the hooked defender, rather than the velocity recipient, owns the reaction decision");
        assertTrue(ropeDart.contains("if (pullResolutionRequested) return;"),
                "continuous pull ticks must not create independent reaction windows");
        assertTrue(ropeDart.contains("GeneralMethods.setVelocityAfterConfirmedHit"));
        assertTrue(generalMethods.contains("if (hitConfirmed || !HitResolutionSync.defer"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
