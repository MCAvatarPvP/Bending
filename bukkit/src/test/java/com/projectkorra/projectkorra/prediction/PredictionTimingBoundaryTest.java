package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PredictionTimingBoundaryTest {
    @Test
    void predictionMetadataNeverReordersOrSynthesizesAuthoritativeInput() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String listener = read("src/main/java/com/projectkorra/projectkorra/PKListener.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/PKListener.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String bridge = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/FabricGameplayBridge.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/FabricGameplayBridge.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String mixin = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/mixin/ServerPlayNetworkHandlerMixin.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/mixin/ServerPlayNetworkHandlerMixin.java");
        String clientMixin = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");
        String runtime = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertFalse(paper.contains("PendingNativeInput"));
        assertFalse(fabric.contains("PendingNativeInput"));
        assertFalse(client.contains("InputFrame"));
        assertFalse(client.contains("ActionPrepare"));
        assertTrue(client.contains("new PredictionPayloads.ClientReady"));
        assertTrue(client.contains("PredictionPayloads.NativeAction.ID"));
        assertTrue(listener.contains("PaperPredictionServer.handleLeftClick")
                && listener.contains("PaperPredictionServer.handleSneak")
                && listener.contains("PaperPredictionServer.handleSwapHands"));
        assertTrue(bridge.contains("PredictionServer.handleVanillaInput"));
        assertTrue(client.contains("PredictionPayloads.InputKind.SWAP_HANDS")
                        && runtime.contains("case SWAP_HANDS -> CommonInputHandler.handleSwapHands"),
                "off-hand combo triggers must use their vanilla Paper event instead of an unowned side path");
        assertTrue(bridge.contains("case START_DESTROY_BLOCK -> false"));
        assertTrue(listener.matches("(?s).*@EventHandler\\(priority = EventPriority\\.NORMAL, ignoreCancelled = true\\)\\R"
                        + "\\s*public void onPlayerInteraction.*"),
                "the authoritative interaction callback must retain legacy Bukkit priority/cancellation order");
        assertFalse(client.contains("leftClickUntilTick") || client.contains("beginLeftClick"),
                "Paper emits every PlayerAnimationEvent; the client must not invent a swing dedup window");
        assertFalse(bridge.contains("leftClickUntilTick") || bridge.contains("beginLeftClick"),
                "Fabric must not collapse native swings which Bukkit would process independently");
        assertTrue(client.contains("if (packet instanceof HandSwingC2SPacket)")
                        && client.contains("owner.captureLeftClick(client)"));
        assertTrue(mixin.contains("FabricGameplayBridge.onArmSwing")
                        && !mixin.contains("packet.getHand() == Hand.MAIN_HAND"),
                "legacy PlayerAnimationEvent intentionally processes both arm-animation hands");
        assertTrue(client.contains("final boolean suppress = droppedItem || rightClickBlockUntilTick >= clientTick"),
                "the client must retain Bukkit's drop/right-click swing suppression before prediction");
        int clientBlockSuppression = client.indexOf("owner.rightClickBlockUntilTick = owner.clientTick + 2");
        int clientMainHand = client.indexOf("if (block.getHand() == Hand.MAIN_HAND)");
        assertTrue(clientBlockSuppression >= 0 && clientMainHand > clientBlockSuppression,
                "Paper installs block-click swing suppression before checking the interaction hand");
        int serverBlockSuppression = bridge.indexOf(
                "bridge.rightClickBlockUntilTick.put(player.getUuid(), bridge.inputTick + 2)");
        int serverMainHand = bridge.indexOf("if (hand != Hand.MAIN_HAND) return false", serverBlockSuppression);
        assertTrue(serverBlockSuppression >= 0 && serverMainHand > serverBlockSuppression,
                "the Fabric server must preserve Paper's off-hand suppression order");
        assertFalse(bridge.contains("if (droppedItem.remove(nativePlayer.getUuid())) return;"),
                "legacy Bukkit consumes the drop marker only on PlayerAnimationEvent, never on sneak");
        assertEquals(1, occurrences(client,
                        "owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY)"),
                "only INTERACT_AT maps to legacy PlayerInteractAtEntityEvent on the prediction client");
        assertEquals(1, occurrences(mixin,
                        "FabricGameplayBridge.onRightClickEntity(handler.player, hand)"),
                "the Fabric server must not treat the broader INTERACT packet as a Paper bending input");
        assertTrue(runtime.contains("CommonInputHandler.handleSlotChange("),
                "client slot edges must run the same multi-ability/addon decision as Paper");
        assertFalse(clientMixin.contains("suppressPredictedSelectedSlot")
                        || clientMixin.contains("suppressAuthoritativeSelectedSlot"),
                "Paper slot corrections must always reach the vanilla client inventory");
        assertTrue(clientMixin.contains("PredictionClient.acceptAuthoritativeSelectedSlot(packet.slot())"),
                "a Paper correction must also update the slot used by the next predicted input");
        assertTrue(client.contains("packet instanceof PlayerInputC2SPacket input")
                        && client.contains("owner.captureSneakState(client, input.input().sneak())"),
                "Minecraft 1.21.11 shift prediction must follow its ordinary vanilla PlayerInput packet");
        assertTrue(mixin.contains("projectKorra$onPlayerInput(PlayerInputC2SPacket packet")
                        && mixin.contains("packet.input().sneak()")
                        && bridge.contains("public static void onPlayerInput(ServerPlayerEntity player"),
                "Fabric authority must consume the same vanilla shift edge as Paper");
        int sneakCapture = client.indexOf("capture(client, sneaking ? PredictionPayloads.InputKind.SNEAK_START");
        int sneakPose = client.indexOf("queueServerVisibleSneakPose(sneaking)", sneakCapture);
        assertTrue(sneakCapture >= 0 && sneakPose > sneakCapture,
                "Paper fires PlayerToggleSneakEvent before setShiftKeyDown, so input spatial queries must use the old pose");
        int firstSneakPose = client.indexOf("player.getEyeHeight(previousSneaking ? EntityPose.CROUCHING : EntityPose.STANDING)");
        assertTrue(firstSneakPose >= 0 && firstSneakPose < sneakCapture,
                "a first-packet sneak edge must seed Paper's old eye height instead of the already-crouched client pose");
        int runtimeTick = client.indexOf("ExactPredictionRuntime.tick(client)", sneakPose);
        int poseCommit = client.indexOf("commitServerVisibleEntityPose(client)", runtimeTick);
        assertTrue(runtimeTick > sneakPose && poseCommit > runtimeTick,
                "Paper runs Bukkit ability progress before the player tick commits its new crouch/standing eye height");
        String repeatedSneak = client.substring(client.indexOf("} else {", sneakPose),
                client.indexOf("private void queueServerVisibleSneakPose", sneakPose));
        assertFalse(repeatedSneak.contains("commitServerVisibleEntityPose")
                        || repeatedSneak.contains("getEyeHeight("),
                "a duplicate representation of the same shift edge must not commit the eye pose before progress");
        String entityPoseCommit = client.substring(client.indexOf("private void commitServerVisibleEntityPose"),
                client.indexOf("public static ServerPose serverVisiblePose"));
        assertTrue(entityPoseCommit.contains("player.getEyeY() - player.getY()"));
        assertFalse(entityPoseCommit.contains("EntityPose.CROUCHING") || entityPoseCommit.contains("EntityPose.STANDING"),
                "the post-scheduler pose must follow vanilla swimming/gliding/collision constraints, not a two-pose guess");
        assertTrue(runtime.contains("private static final ThreadLocal<Long> INPUT_EVENT_POSE")
                        && runtime.contains("final Long eventAction = INPUT_EVENT_POSE.get()"));
        String executionPose = runtime.substring(runtime.indexOf("private PredictionClient.ServerPose executionPose0()"),
                runtime.indexOf("private static boolean finite", runtime.indexOf("private PredictionClient.ServerPose executionPose0()")));
        assertFalse(executionPose.contains("tick - action.createdTick") || executionPose.contains("currentAction()"),
                "Paper's packet pose ends with the event; progress must return to the separately scheduled server-visible pose");
        assertTrue(paper.contains(": nativeInput.get())"));
        assertTrue(fabric.contains(": nativeInput.getAsBoolean())"));
        assertTrue(paper.contains("CooldownSync.runInputVeto")
                        && fabric.contains("CooldownSync.runInputVeto"),
                "a delayed cooldown rejection must still execute the native handler for combo ordering");
        assertFalse(paper.contains("PredictionTiming.alignStart"));
        assertFalse(fabric.contains("PredictionTiming.alignStart"));
        assertFalse(fabric.contains("gameplay.handlePredictedInput"));
        assertFalse(fabric.contains("gameplay.applyPredictedSlot"));
        assertFalse(fabric.contains("FabricMC.setSneakOverride"));
    }

    @Test
    void predictionNeverBackdatesServerAbilityOrTempBlockLifetime() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");

        assertFalse(paper.contains("PredictionTiming.install"));
        assertFalse(fabric.contains("PredictionTiming.install"));
        assertFalse(paper.contains("latencyCompensationMillis"));
        assertFalse(fabric.contains("latencyCompensationMillis"));
        assertFalse(paper.contains("PredictionCooldownTimeline.alignNewCooldown"));
        assertFalse(fabric.contains("PredictionCooldownTimeline.alignNewCooldown"));
        assertFalse(paper.contains("locallyBlockedByCooldown() &&"));
        assertFalse(fabric.contains("locallyBlockedByCooldown() &&"));
    }

    @Test
    void velocityOwnershipReceiptDoesNotWaitForASecondAcknowledgement() throws IOException {
        String runtime = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String velocitySync = read(
                "../common/src/main/java/com/projectkorra/projectkorra/prediction/VelocitySync.java",
                "common/src/main/java/com/projectkorra/projectkorra/prediction/VelocitySync.java");
        int start = runtime.indexOf(
                "private void noteVelocityOwner0(Entity localPlayer, long serverTick");
        int end = runtime.indexOf("private void removeAuthoritativeAbility0", start);
        assertTrue(start >= 0 && end > start);
        String velocityReceipt = runtime.substring(start, end);
        assertFalse(velocityReceipt.contains("action == null || !action.locallyPredicted"),
                "an authoritative ownership receipt must survive local Action retirement");
        assertFalse(velocityReceipt.contains("nativeConfirmed"),
                "the exact authoritative velocity receipt must not be dropped while NativeAction is in flight");
        assertFalse(runtime.contains("closeNetworkVelocity"),
                "velocity ownership must follow receipt/packet order, never vector similarity");
        assertTrue(velocitySync.indexOf("publish(ability, target, velocity);")
                        < velocitySync.indexOf("commit(write);"),
                "every direct ability velocity must publish ownership before vanilla can echo it back");
        assertTrue(runtime.contains("allowed self-owned velocity without retained mutation")
                        && !runtime.contains("suppressed self-owned velocity without retained mutation"),
                "a self-owned receipt without an exact local action+ordinal mutation is the only AirBlast push and must pass");
    }

    @Test
    void abilityStateEchoesUseCausalOwnershipAndAnnouncedResult() throws IOException {
        String runtime = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String wrapper = read("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        String sync = read("../common/src/main/java/com/projectkorra/projectkorra/prediction/AbilityStateSync.java",
                "common/src/main/java/com/projectkorra/projectkorra/prediction/AbilityStateSync.java");
        String scooter = read("../common/src/main/java/com/projectkorra/projectkorra/airbending/AirScooter.java",
                "common/src/main/java/com/projectkorra/projectkorra/airbending/AirScooter.java");

        assertTrue(wrapper.contains("AbilityStateSync.apply(AbilityExecutionContext.current(), this"),
                "Paper must publish ownership before each vanilla abilities write");
        assertTrue(sync.contains("current != null && target != null && resultingState != null"));
        assertFalse(sync.contains("current != null && ability != null && target != null"),
                "constructor-time flight writes do not yet have an AbilityExecutionContext");
        assertTrue(scooter.indexOf("player.setAllowFlight(true)") < scooter.indexOf("this.start()")
                        && scooter.indexOf("player.setFlying(true)") < scooter.indexOf("this.start()"),
                "AirScooter's two initial packets exercise the constructor-time ownership path");
        for (String endpoint : new String[]{paper, fabric}) {
            String beforeWrite = endpoint.substring(endpoint.indexOf("public void beforeWrite"),
                    endpoint.indexOf("public void onRemoved(CoreAbility", endpoint.indexOf("public void beforeWrite")));
            assertTrue(beforeWrite.indexOf("currentInputAction(ownerId)")
                            < beforeWrite.indexOf("abilityActions.get(ability)"),
                    "native input ownership must precede the narrower ability-progress lookup");
            assertTrue(beforeWrite.contains("ability == null ? action."),
                    "constructor flight receipts must retain the native action's logical ability name");
        }
        assertTrue(paper.contains("PaperPredictionProtocol.ABILITY_STATE_OWNER")
                        && paper.contains("action.abilityStateOrdinals.merge"));
        String suppression = runtime.substring(runtime.indexOf("private boolean suppressAuthoritativeAbilityState0"),
                runtime.indexOf("private void reconcileActiveFlightAbilities0"));
        assertTrue(suppression.contains("abilityStateReceipts.remove(receiptIndex)")
                        && suppression.contains("player.getUuid().equals(receipt.abilityOwner)"));
        assertTrue(suppression.contains("matchesAbilityStatePacket(candidate, packet)"),
                "a causal receipt may suppress only the exact flight projection it announced");
        assertTrue(suppression.contains("hasLocalFlightLease()")
                        && suppression.contains("hasRecentLocalAbilityState()")
                        && suppression.contains("abilities.setFlySpeed(packet.getFlySpeed())")
                        && suppression.contains("abilities.setWalkSpeed(packet.getWalkSpeed())"),
                "a live or just-closed predicted flight lifecycle must preserve only its flight bits, not unrelated vanilla state");
        assertFalse(suppression.contains("hasUnconfirmedLocalAction"),
                "acknowledging the input must not hand a still-active WaterSpout back to delayed Paper flight packets");
        String receipt = runtime.substring(runtime.indexOf("private void noteAbilityStateOwner0"),
                runtime.indexOf("private void notePredictedExperience0"));
        assertTrue(receipt.contains("candidate.serverTick == owner.serverTick()")
                        && receipt.contains("candidate.target.equals(owner.target())")
                        && receipt.contains("abilityStateReceipts.set(replacement, receipt)"),
                "coalesced flying/allowFlying writes must leave only the final ownership receipt for a server tick");
        String snapshot = runtime.substring(runtime.indexOf("private void reconcileActiveFlightAbilities0"),
                runtime.indexOf("private boolean suppressAuthoritativeExperience0"));
        assertTrue(snapshot.contains("PredictionStateOrdering.snapshotCoversLatestInput")
                        && snapshot.contains("authoritativeFlightSequence = acknowledgedSequence"),
                "Paper flight presence diagnostics may be accepted only after the latest native input");
        assertTrue(snapshot.contains("diagnostics, not lifecycle authority")
                        && !runtime.contains("reconcilePersistentFlightPresence()")
                        && !runtime.contains("restorePersistentFlight("),
                "a delayed Paper presence set must never reconstruct or remove the client-owned spout lifecycle");
        assertFalse(runtime.contains("AbilityLifecycleSync") || runtime.contains("deferRemoval("),
                "off-water and ground checks must perform their complete local cleanup immediately");
    }

    @Test
    void cooldownPredictionKeepsLocalExpiryAndVetoesDelayedNativeReplay() throws IOException {
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String runtime = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");

        assertFalse(runtime.contains("private void expireCooldowns()"),
                "Fabric must not remove an expired entry before Paper's matching BendingManager heartbeat");
        String tick = runtime.substring(runtime.indexOf("private void tick0"),
                runtime.indexOf("private void reconcileAuthoritativeCooldowns"));
        assertTrue(tick.indexOf("platform.tick()")
                        < tick.indexOf("cooldownAuthority.retainLocallyActive"),
                "the common manager must progress abilities and retire cooldowns before authority bookkeeping");
        assertTrue(runtime.contains("cooldownAuthority.onLocalAdded(ability, expiresAtMillis)"),
                "new cooldowns must still begin immediately in the client simulation");
        assertTrue(runtime.contains("new Cooldown(clientUntilMillis"),
                "join-time and external server cooldowns must still import into the common runtime");
        String cooldownReconcile = runtime.substring(runtime.indexOf("private void reconcileAuthoritativeCooldowns"),
                runtime.indexOf("private boolean noteNativeAction0"));
        assertTrue(cooldownReconcile.contains("initializing || !ready"));
        assertFalse(cooldownReconcile.contains("cooldownAuthority.reconcile")
                        || cooldownReconcile.contains("bendingPlayer.removeCooldown"),
                "periodic Paper snapshots must neither extend nor prematurely retire a locally timed cooldown");
        assertTrue(client.contains("reconcile.ability(), clientCooldownUntil)"));
        assertTrue(client.contains("rememberAuthoritativeCooldowns(convertCooldowns(snapshot.cooldowns()))"),
                "a reconnect or world change must retain already-active Paper cooldowns");
        String capture = client.substring(client.indexOf("private void capture(MinecraftClient client"),
                client.indexOf("static ServerPose poseForInput"));
        assertTrue(capture.indexOf("ExactPredictionRuntime.input(sequence")
                        < capture.indexOf("new PredictionPayloads.InputVeto"),
                "the local common runtime must decide before the negative gate is sent");
        assertTrue(capture.contains("cooldownActiveAtInput && !locallyPredicted"));
        String process = paper.substring(paper.indexOf("private CommonInputHandler.InputResult processInput"),
                paper.indexOf("private void flushTempBlocks", paper.indexOf("private CommonInputHandler.InputResult processInput")));
        assertTrue(process.indexOf("locallyRejectedOnCooldown") < process.indexOf("PredictionDeterminism.run"),
                "Paper must consume a matching veto before invoking ProjectKorra for that native event");
        assertTrue(process.contains("CooldownSync.runInputVeto")
                        && process.contains("inputVetoCooldowns(abilityName, kind), nativeInput")
                        && process.contains("action.locallyPredicted ? \"accepted_combo\" : \"client_cooldown\""),
                "a veto must preserve native combo bookkeeping while rejecting only the delayed bound cast");
        assertFalse(process.contains("return CommonInputHandler.InputResult.pass()"),
                "skipping the complete callback loses cooldown-valid combo steps such as AirSweep's second AirSwipe click");
        String serverAdded = paper.substring(paper.indexOf("public void onAdded(CoreAbility source"),
                paper.indexOf("public void onRemoved(BendingPlayer"));
        assertTrue(serverAdded.contains("sendDirective") && serverAdded.contains("predictedEffectOwner")
                        && serverAdded.contains("actionForEffect(source)")
                        && serverAdded.contains("lifecycleAction.locallyPredicted")
                        && serverAdded.contains("return;"),
                "input-time and delayed lifecycle cooldown starts must not be extended by their Paper echo");
        String serverRemoved = paper.substring(paper.indexOf("public void onRemoved(BendingPlayer"),
                paper.indexOf("public void onAirBlastReset"));
        assertTrue(serverRemoved.contains("session.predictedCooldowns.remove")
                        && serverRemoved.indexOf("session.predictedCooldowns.remove")
                        < serverRemoved.indexOf("sendDirective"),
                "the delayed expiry of one predicted generation must not clear a newer local cooldown");
    }

    @Test
    void allExistingAbilityTransitionsUseOneGenericNativeAssociation() throws IOException {
        String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");

        assertTrue(runtime.contains("trackingResult.handled() && hasMatchingExistingAbility && !createdMatchingAbility"));
        assertFalse(runtime.contains("earthSmashExistingTransition"));
        assertFalse(paper.contains("earthSmashExistingTransition"));
        assertFalse(fabric.contains("earthSmashExistingTransition"));
        assertFalse(runtime.contains("handoffEarthSmashToAuthority"),
                "reconciliation must never delete every EarthSmash to roll back one input");
        assertFalse(paper.contains("cooldownAfter <= cooldownBefore"),
                "a real Bukkit event must never be rejected by an ability-shape heuristic");
        assertFalse(fabric.contains("cooldownAfter <= cooldownBefore"),
                "a real Fabric event must never be rejected by an ability-shape heuristic");
        assertFalse(runtime.contains("rollback(action, true)"),
                "metadata rejection must not revert an already-running common lifecycle");
        assertTrue(runtime.contains("System.getProperty(\"projectkorra.prediction.debug\", \"false\")"),
                "high-volume prediction tracing must be opt-in so visual progression cannot stutter on logging");
        assertFalse(runtime.contains("correctLocations(activeAbility"),
                "reconciliation must not reflectively shift arbitrary ability state");
        assertTrue(paper.contains("boolean implicitExistingTransition = trackingResult.handled() && hadExistingMatchingAbility"));
        assertTrue(fabric.contains("final boolean implicitExistingTransition = trackingResult.handled() && hadExistingMatchingAbility"));

        String input = read("../common/src/main/java/com/projectkorra/projectkorra/listener/CommonInputHandler.java",
                "common/src/main/java/com/projectkorra/projectkorra/listener/CommonInputHandler.java");
        String smash = read("../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java",
                "common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        assertFalse(input.contains("EarthSmash.captureSourceSelection(player)"));
        assertFalse(smash.contains("sourceSelectionEye"));
        assertTrue(smash.contains("this.origin = this.getEarthSourceBlock(this.selectRange)"));
        assertFalse(smash.contains("getVisibleEarthSourceBlock"));
        int cache = input.indexOf("BlockSource.update(player, ClickType.SHIFT_DOWN)");
        int activate = input.indexOf("AbilityActivationManager.dispatch(context)", cache);
        assertTrue(cache >= 0 && activate > cache,
                "Paper's native SHIFT_DOWN source cache must remain authoritative");
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }

    private static int occurrences(final String source, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
