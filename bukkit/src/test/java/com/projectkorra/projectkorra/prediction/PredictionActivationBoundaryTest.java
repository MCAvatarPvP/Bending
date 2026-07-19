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
    void paperAcceptedSuppressedInputsAndComboOutcomesConvergeClientSide() throws IOException {
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String combos = read("../common/src/main/java/com/projectkorra/projectkorra/ability/util/ComboManager.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/util/ComboManager.java");

        assertTrue(client.contains("ExactPredictionRuntime.recordNativeOnlyInput(sequence, kind, selectedSlot, pose, ability)"),
                "a locally suppressed swing must remain available for semantic Paper association");
        assertTrue(runtime.contains("!action.executed && (inputHandled || comboRecorded || !authoritativeCreated.isEmpty())")
                        && runtime.contains("action = replayNativeOnlyAction(action)"),
                "Paper accepting a missed AirBlast must execute that same input in the client common runtime");
        assertTrue(runtime.contains("reconcileCreatedAbilities(action, authoritativeCreated)")
                        && runtime.contains("recoverMissingCombo(action, authoritativeName)"),
                "differing combo creation outcomes must converge to the authoritative instance set");
        assertTrue(paper.contains("trackingResult.handled(), comboRecorded, List.copyOf(createdAbilities)")
                        && fabric.contains("trackingResult.handled(), comboRecorded, List.copyOf(createdAbilities)"),
                "both authoritative loaders must report the generic post-input outcome");
        assertFalse(combos.contains("RECENTLY_USED.clear()"),
                "independently phased cleanup tasks must never erase a fresh combo chain wholesale");
        assertTrue(combos.contains("pruneExpired(history, now)"));
    }

    @Test
    void successfulCombosReportTheirDifferentlyNamedRuntimeAbility() throws IOException {
        String combos = read("../common/src/main/java/com/projectkorra/projectkorra/ability/util/ComboManager.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/util/ComboManager.java");

        assertTrue(combos.contains("created instanceof CoreAbility ability && ability.isStarted() && !ability.isRemoved()"));
        assertTrue(combos.contains("AbilityActivationManager.markHandled()"));
    }

    @Test
    void combustionAlwaysSelectsHyperionImplementationAndHandlers() throws IOException {
        String embedded = read("../common/src/main/java/com/projectkorra/projectkorra/ability/util/EmbeddedAddonBootstrap.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/util/EmbeddedAddonBootstrap.java");
        String addons = read("../common/src/main/java/com/projectkorra/projectkorra/ability/activation/AddonAbilityActivationBootstrap.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/activation/AddonAbilityActivationBootstrap.java");
        String core = read("../common/src/main/java/com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java");

        int jedCore = embedded.indexOf("tryEnable(\"JedCore abilities\"");
        int hyperion = embedded.indexOf("tryEnable(\"Hyperion abilities\"");
        assertTrue(jedCore >= 0 && hyperion > jedCore, "Hyperion must win the duplicate public ability name");
        assertTrue(addons.contains("CoreAbility.registerPluginAbilities(Hyperion.getPlugin()"),
                "activation reloads must restore the same duplicate-name winner");
        assertTrue(addons.contains("new me.moros.hyperion.abilities.firebending.Combustion"));
        assertTrue(addons.contains("Combustion.attemptExplode(context.getPlayer())"));
        assertFalse(core.contains("register(\"Combustion\""));
        assertFalse(addons.contains("Combustion.combust(context.getPlayer())"));
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
        assertTrue(discharge.contains("if (collided && authoritative)"),
                "a stale predicted entity position must not terminate Discharge");
        assertFalse(discharge.contains("hit = CollisionDetector.checkEntityCollisions"),
                "later samples must not overwrite an earlier collision result");
        assertTrue(fabric.contains("PredictionDeterminism.run(sequence"));
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
        final String contacts = read("../common/src/main/java/com/projectkorra/projectkorra/prediction/PredictedContactSync.java",
                "common/src/main/java/com/projectkorra/projectkorra/prediction/PredictedContactSync.java");
        final String fabricEntities = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        final String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String paperPlatform = read("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        final String fabricPlatform = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");
        final String predictionClient = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String fabricServer = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        final String payloads = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java");
        final String paperServer = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        final String paperProtocol = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionProtocol.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionProtocol.java");

        assertTrue(damage.indexOf("PredictedContactSync.mark(ability, entity)")
                        < damage.indexOf("damageEntityNow(entity, source, damage"),
                "remote contact ownership must be established before damage processing");
        assertTrue(velocity.indexOf("PredictedContactSync.mark(ability, entity)")
                        < velocity.indexOf("new AbilityVelocityAffectEntityEvent"),
                "remote contact ownership must be established before velocity processing");
        assertFalse(ability.contains("PredictedContactSync.suppressRemoval"),
                "remote contacts must not make locally terminal abilities immortal");
        assertFalse(execution.contains("PredictedContactSync.Abort") || contacts.contains("throw Abort"),
                "suppressing remote state must not abandon the rest of an ability's visual/world pass");
        assertTrue(contacts.contains("interface Listener") && contacts.contains("onPredictedContact"),
                "suppressed client mutations must emit contact evidence without ending the ability pass");
        assertTrue(predictionClient.contains("queueExactHitClaim")
                        && predictionClient.contains("PendingHitClaim"),
                "the exact client must queue contact evidence behind its vanilla input packet");
        assertTrue(payloads.contains("record HitClaim") && payloads.contains("id(\"hit_claim\")"),
                "the Fabric wire protocol must carry the bounded hit claim");
        assertTrue(fabricServer.contains("onHitClaim") && fabricServer.contains("augmentNearbyPlayers"),
                "Fabric must validate rewind history before augmenting a real ability query");
        assertTrue(paperProtocol.contains("projectkorra:hit_claim")
                        && paperServer.contains("onHitClaim(") && paperServer.contains("augmentNearbyPlayers"),
                "Paper must decode and independently validate the same hit evidence");
        assertFalse(paperServer.contains("consumedTick") || fabricServer.contains("consumedTick"),
                "a successful rewind claim must be removed on its first real query, including within the same tick");
        assertTrue(contacts.contains("CooldownSync.isAuthoritative()")
                        && velocity.contains("VelocitySync.publish(velocityAbility, velocityTarget, committedVelocity)")
                        && velocity.contains("velocityTarget.setVelocity(committedVelocity.clone())"),
                "a validated rewind hit must use the ability's ordinary authoritative knockback write");
        assertTrue(fabricEntities.contains("private boolean suppressRemoteMutation()")
                && fabricEntities.contains("!ExactPredictionRuntime.isPredictedOwned(value)"),
                "direct addon mutations must be blocked without affecting locally spawned ability entities");
        assertTrue(runtime.contains("forceRemoveAbility(selected)"),
                "a genuinely unpredicted server removal must still close its matching local instance");
        assertFalse(runtime.contains("forcingPersistentRemoval")
                        || ability.contains("AbilityLifecycleSync.deferRemoval"),
                "accepted client lifecycles must not be kept half alive by delayed Paper presence");
        assertFalse(paperPlatform.contains("HitResolutionSync")
                || fabricPlatform.contains("HitResolutionSync"),
                "status effects must commit immediately without reaction hit registration");
        assertTrue(paperPlatform.contains("VelocitySync.applyDirect(AbilityExecutionContext.current(), this")
                && fabricPlatform.contains("VelocitySync.applyDirect(AbilityExecutionContext.current(), this"),
                "legacy/addon direct knockback must retain ownership receipts on both servers");
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
                "direct entity damage bypasses prediction ownership: " + bypasses);
    }

    @Test
    void hitRegistrationRewindsWithoutDeferringDamageOrVelocity() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String damage = read("../common/src/main/java/com/projectkorra/projectkorra/util/DamageHandler.java",
                "common/src/main/java/com/projectkorra/projectkorra/util/DamageHandler.java");
        String velocity = read("../common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java",
                "common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java");
        String config = read("../common/src/main/java/com/projectkorra/projectkorra/configuration/ConfigManager.java",
                "common/src/main/java/com/projectkorra/projectkorra/configuration/ConfigManager.java");

        assertTrue(paper.contains("HitRewind.combinedRewindTicks")
                && paper.contains("player.getPing(), defenderPing"));
        assertTrue(fabric.contains("HitRewind.combinedRewindTicks")
                && fabric.contains("player.networkHandler.getLatency(), defenderPing"));
        assertTrue(paper.contains("frame.box.clone().expand(CLAIM_CONTACT_TOLERANCE)")
                && fabric.contains("frame.box.expand(CLAIM_CONTACT_TOLERANCE)"));
        assertFalse(paper.contains("HitResolutionSync") || paper.contains("pendingNativeReactions"));
        assertFalse(fabric.contains("HitResolutionSync") || fabric.contains("pendingNativeReactions"));
        assertFalse(damage.contains("HitResolutionSync") || velocity.contains("HitResolutionSync"));
        assertFalse(config.contains("Properties.Prediction.Reaction"));
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
        assertTrue(client.contains("Objects.equals(abilityCreationActions.get(ability), localCreationSequence)"),
                "Paper removals must resolve through the correlated local creation identity");
        assertFalse(client.contains("abilityCreationActions.get(ability), removed.actionSequence()"),
                "raw Paper and Fabric ordinals are not directly comparable");
        assertTrue(client.contains("!removed.externallyCaused()"),
                "collision/other-ability removals must override retained local lifecycle prediction");
        assertTrue(client.contains("allowed self-owned velocity without retained mutation"),
                "a receipt cannot suppress AirBlast unless the exact local action+ordinal impulse exists");
    }

    @Test
    void sameNamedAbilityImplementationsCannotRemoveEachOther() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String payloads = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(paper.contains("AbilityRemovalSync.typeId(ability)"));
        assertTrue(fabric.contains("AbilityRemovalSync.typeId(ability)"));
        assertTrue(payloads.contains("String abilityType, long actionSequence"));
        assertTrue(client.contains("AbilityRemovalSync.isType(ability, removed.abilityType())"),
                "WaterSpoutWave and WaterSpout share a display name and input but require distinct lifecycles");
    }

    @Test
    void ropeDartAppliesItsAuthoritativePullImmediately() throws IOException {
        String ropeDart = read("../common/src/main/java/me/literka/abilities/RopeDart.java",
                "common/src/main/java/me/literka/abilities/RopeDart.java");
        String generalMethods = read("../common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java",
                "common/src/main/java/com/projectkorra/projectkorra/GeneralMethods.java");

        assertTrue(ropeDart.contains("GeneralMethods.setVelocity(this"));
        assertFalse(ropeDart.contains("setVelocityAfterConfirmedHit"));
        assertFalse(ropeDart.contains("HitResolutionSync") || generalMethods.contains("HitResolutionSync"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
