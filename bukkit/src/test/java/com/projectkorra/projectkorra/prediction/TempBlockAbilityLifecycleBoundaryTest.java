package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression boundaries for the two abilities that exposed the lifecycle defects. */
class TempBlockAbilityLifecycleBoundaryTest {
    @Test
    void earthSmashUsesItsRegisteredLayersInsteadOfLeakedWorldState() throws IOException {
        String source = common("com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        String revert = method(source, "public void revert()", "private BlockData visibleData");
        String remaining = method(source, "public void checkRemainingBlocks()", "public void remove()");

        assertTrue(revert.contains("List.copyOf(this.affectedBlocks)"));
        assertTrue(revert.indexOf("this.affectedBlocks.clear()") < revert.indexOf("tblock.revertBlock()"),
                "callbacks must not mutate the list being iterated");
        assertTrue(remaining.contains("final List<TempBlock> layers = TempBlock.getAll(block)"));
        assertTrue(remaining.contains("candidate.getAbility().orElse(null) == this"));
        assertTrue(remaining.contains("smashLayer.getBlockData().getMaterial()"));
    }

    @Test
    void earthSmashRetainsLegacyPaperSourceSelectionOrder() throws IOException {
        String source = common("com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        String input = common("com/projectkorra/projectkorra/listener/CommonInputHandler.java");
        String release = method(source, "if (this.state == State.START && this.progressCounter > 1)",
                "} else if (this.state == State.LIFTING)");
        String sneak = method(input, "public static InputResult handleSneak",
                "public static SlotResult handleSlotChange");

        int cachedSource = sneak.indexOf("BlockSource.update(player, ClickType.SHIFT_DOWN)");
        int activation = sneak.indexOf("AbilityActivationManager.dispatch(context)", cachedSource);
        assertTrue(cachedSource >= 0 && activation > cachedSource,
                "Paper must cache the SHIFT_DOWN earth source before constructing EarthSmash");
        assertTrue(release.contains("this.origin = this.getEarthSourceBlock(this.selectRange)"),
                "EarthSmash release must consume Paper's cached BlockSource target");
        assertTrue(release.contains("TempBlock.isTempBlock(this.origin) && !isBendableEarthTempBlock(this.origin)"),
                "temporary source eligibility must remain identical to the legacy Paper ability");
        assertFalse(source.contains("getVisibleEarthSourceBlock"),
                "a release-time Fabric ray cast changes placement and player input timing");
    }

    @Test
    void phaseChangeEndsOnlyItsExactMeltLayerAndNeverAdoptsTemporaryWater() throws IOException {
        String source = common("com/projectkorra/projectkorra/waterbending/ice/PhaseChange.java");
        String torrent = common("com/projectkorra/projectkorra/waterbending/Torrent.java");
        String freeze = method(source, "public void freeze(final Block b)", "private void trackFrozen");
        String meltedCleanup = method(source, "public void revertMeltedBlocks()", "public void remove()");

        assertTrue(source.contains("private static final long MELT_REVERT_MILLIS"));
        assertTrue(source.contains("private final Map<Block, TempBlock> meltLayers"));
        assertTrue(freeze.contains("tb.isReverted()"));
        assertTrue(freeze.contains("tb.getAbility().orElse(null) != this"));
        assertTrue(freeze.contains("final TempBlock meltLayer = this.meltLayers.get(b)"));
        assertTrue(freeze.contains("tb != meltLayer"),
                "PhaseChange must not convert arbitrary WaterArms/Surge/WaterSpout TempBlock water into ice");
        assertTrue(freeze.contains("tb == null && TempBlockSync.hasAuthoritativeLayer(b)"),
                "remote/server-only temporary water must obey the same no-adoption rule");
        assertTrue(freeze.contains("tb.revertBlock();"),
                "refreeze must end the exact owned WATER melt and reveal its captured ice");
        assertFalse(freeze.contains("tb.setType("),
                "a reverted melt handle must never be mutated into a second lifecycle");
        assertTrue(source.contains("final List<TempBlock> layers = TempBlock.getAll(b)"),
                "thaw must locate a PhaseChange layer even when another layer overlaps it");
        assertTrue(source.contains("block.setRevertTask(() -> untrackFrozen(block))"),
                "external layer retirement must clean the static PhaseChange indexes");
        assertTrue(source.contains("if (block.isReverted()) untrackFrozen(block)"),
                "callback-free client DISCARD must still be purged on the next ability tick");
        assertTrue(source.contains("this.melted_blocks.addIfAbsent(block)"));
        assertTrue(source.contains("this.meltLayers.remove(block, layer)"),
                "expiry must remove the exact melt handle instead of leaving a stale coordinate marker");
        assertTrue(source.contains("final BlockData visibleData = b.getBlockData()"),
                "melt decisions must use the merged client/server visual top, including remote overlays");
        assertTrue(source.contains("isRegisteredMeltable(l.getBlock())"));
        String melt = method(source, "public void melt(final Block b)", "private static BlockData meltedData");
        assertTrue(melt.contains("if (thaw(b))"),
                "melt must find the exact PhaseChange layer through overlaps");
        assertTrue(melt.contains("if (!Torrent.canThaw(b))") && melt.contains("Torrent.thaw(b)"),
                "PhaseChange must retain legacy delegation to Torrent's own thaw lifecycle");
        assertTrue(torrent.contains("TempBlockSync.hasAuthoritativeEffect(block, \"Torrent\")"),
                "Fabric must recognize Torrent ice even when only server metadata owns its handle");
        assertTrue(melt.contains("WaterArmsSpear.canThaw(b)") && melt.contains("WaterSpoutWave.canThaw(b)"),
                "known frozen-water abilities must be thawed through their exact tracked layer");
        assertTrue(melt.contains("TempBlockSync.hasAuthoritativeLayer(b)"),
                "all remote TempBlocks must wait for Paper when Fabric has no inspectable handle");
        assertTrue(melt.contains("ElementalAbility.isWater(tb.getState().getBlockData().getMaterial())"),
                "generic temporary ice may only be thawed when its captured source was water");
        assertTrue(melt.contains("tb.revertBlock()"),
                "legacy thaw ends the exact temporary ice or snow layer instead of stacking replacements forever");
        assertTrue(source.contains("visibleData.getMaterial() == Material.SNOW_BLOCK")
                        && source.contains("return Material.AIR.createBlockData()"),
                "full snow blocks and layered snow must both melt");
        assertFalse(meltedCleanup.contains("revertBlock(") || meltedCleanup.contains("setType("),
                "ending the input must not immediately undo the melt");
        assertTrue(source.contains("Material.WATER.createBlockData(), MELT_REVERT_MILLIS, this"));
        assertTrue(source.contains("Material.AIR.createBlockData(), MELT_REVERT_MILLIS, this"));
    }

    @Test
    void phaseChangeMeltAdvancesTheClientLifecycleToo() throws IOException {
        Path path = Path.of("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(path)) path = Path.of("fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        assertTrue(Files.exists(path));
        String runtime = Files.readString(path);
        String decision = method(runtime, "public static boolean shouldPredictInput",
                "public static boolean canActivate");

        assertTrue(decision.contains("return supports(abilityName)"));
        assertFalse(decision.contains("PhaseChange") && decision.contains("LEFT_CLICK"),
                "server-only melt would strand the client's frozen TempBlock and collision");
    }

    @Test
    void phaseChangeLayersUseTheCurrentlyExecutingInput() throws IOException {
        String bootstrap = common("com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java");
        assertTrue(bootstrap.contains("AbilityActivationManager.markHandled(phaseChange)"),
                "an existing PhaseChange instance must be identified exactly");

        String paper = source("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        assertCurrentInputPrecedesCachedAbility(paper);
        assertCurrentInputOwnsSynchronousEffects(paper);
    }

    @Test
    void stagedAbilityKeepsItsNewestActionForItsWholeLifetime() throws IOException {
        String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String association = method(runtime, "private void associateAbility(",
                "private boolean affectedExistingAbility");

        assertTrue(association.contains("previous.abilities.remove(ability)"),
                "a throw/release/redirect must transfer lifecycle ownership off the older action");
        assertTrue(association.contains("action.abilities.add(ability)"),
                "the newest action must remain retained while a staged ability can still create effects");
        assertTrue(runtime.contains("this.tick - action.createdTick > 160L && action.abilities.isEmpty()"),
                "only actions with no live ability owner may age out");
    }

    @Test
    void delayedChildAbilitiesInheritTheExactParentInputOnPaperAndClient() throws IOException {
        String core = common("com/projectkorra/projectkorra/ability/CoreAbility.java");
        String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String paper = source("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");

        assertTrue(core.contains("predictionActionSequence = PredictionDeterminism.currentAction()"),
                "a child constructed by RaiseEarthWall or Shockwave must capture its parent's native input");
        String clientAction = method(runtime, "private long currentAction()",
                "private com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose executionPose0()");
        assertTrue(clientAction.contains("ability.getPredictionActionSequence()")
                        && clientAction.contains("actions.get(inherited)")
                        && clientAction.contains("action.abilities.add(ability)"),
                "Fabric must retain child ownership before its delayed direct/temp/falling effects");
        String paperAction = method(paper, "private Action actionForEffect", "private Action currentInputAction");
        int inherited = paperAction.indexOf("ability.getPredictionActionSequence()");
        int nameFallback = paperAction.indexOf("List<Action> recent");
        assertTrue(inherited >= 0 && nameFallback > inherited,
                "Paper must resolve the exact inherited input before any ability-name fallback");
    }

    @Test
    void delayedEffectsKeepTheExactNativeActionOnPaper() throws IOException {
        String paper = source("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        assertTrue(paper.contains("Long sequence = contextualActionSequence(server, uuid)"));
        assertTrue(paper.contains("runWithOwnerAndSequence(uuid, sequence, task)"));
        String paperContext = method(paper, "private static void runWithOwnerAndSequence",
                "private static void runWithOwner(UUID owner, boolean locallyPredicted");
        assertTrue(paperContext.contains("INPUT_SEQUENCE.set(sequence)")
                        && paperContext.contains("INPUT_SEQUENCE.set(previousSequence)"),
                "Paper callbacks must retain and restore the input ordinal, not merely the player UUID");
    }

    @Test
    void overlappingWaterAbilitiesRetireOnlyTheirOwnLayers() throws IOException {
        String spear = common("com/projectkorra/projectkorra/waterbending/multiabilities/WaterArmsSpear.java");
        String waterArms = common("com/projectkorra/projectkorra/waterbending/multiabilities/WaterArms.java");
        String surge = common("com/projectkorra/projectkorra/waterbending/SurgeWave.java");

        assertTrue(spear.contains("private static final Map<Block, TempBlock> TRACKED_BLOCKS"));
        assertTrue(spear.contains("TRACKED_BLOCKS.remove(block, layer)"));
        assertTrue(waterArms.contains("WaterArmsSpear.expireBlocks(ignoreTime)"));
        assertFalse(waterArms.contains("TempBlock.revertBlock(block, Material.AIR)"));
        assertTrue(waterArms.contains("AbilityExecutionContext.run(waterArms"),
                "manager-driven arm redraws must retain their owning prediction action");
        assertTrue(surge.contains("private Map<Block, TempBlock> waveLayers"));
        assertTrue(surge.contains("private Map<Block, TempBlock> frozenLayers"));
        assertFalse(surge.contains("TempBlock.revertBlock(block, Material.AIR)"));
    }

    @Test
    void remoteRedirectsAndIceWallsUseAuthoritativeTempBlockIdentity() throws IOException {
        String waterManipulation = common(
                "com/projectkorra/projectkorra/waterbending/WaterManipulation.java");
        String iceSpike = common(
                "com/projectkorra/projectkorra/waterbending/ice/IceSpikeBlast.java");
        String iceWall = common("com/jedk1/jedcore/ability/waterbending/IceWall.java");
        String core = common("com/projectkorra/projectkorra/ability/CoreAbility.java");
        String sync = common("com/projectkorra/projectkorra/prediction/block/TempBlockSync.java");
        String paper = source("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String tempAuthority = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");

        assertTrue(sync.contains("getAuthoritativeEffectAlongRay")
                        && sync.contains("authoritativeOwnerId"),
                "remote semantic layers must be targetable without fabricating common ability instances");
        assertTrue(waterManipulation.contains("getAuthoritativeEffectAlongRay(player,")
                        && waterManipulation.contains("\"WaterManipulation\""),
                "both click and shift redirection must suppress a client-only fallback for a remote stream");
        assertTrue(iceSpike.contains("final boolean redirected = redirect(player)")
                        && iceSpike.contains("!activate && !redirected")
                        && iceSpike.contains("getAuthoritativeEffectAlongRay(player,"),
                "redirecting a remote spike must not also start IceSpikePillar or bottle ice locally");
        assertTrue(iceWall.contains("TempBlockSync.hasAuthoritativeEffect(b, this.getName())")
                        && iceWall.contains("getAuthoritativeEffectAlongRay("),
                "another player's rendered IceWall must be selected as a wall, never as ordinary source ice");
        assertTrue(core.contains("this.ownershipTransferred = true")
                        && core.contains("public boolean hasTransferredOwnership()"),
                "ownership transfer must remain explicit even when the original caster has no prediction session");
        assertTrue(paper.contains("final boolean unpredictedOwnershipTransfer")
                        && paper.contains("unpredictedOwnershipTransfer\n                || ownershipBridgeTempLayers.contains(change.layerId())")
                        && paper.contains("? null : predictedTempBlockOwner"));
        assertTrue(tempAuthority.contains("public UUID authoritativeOwnerId"));
    }

    @Test
    void earthSmashOwnershipHandoffRetainsPredictionWithExactServerState() throws IOException {
        String core = common("com/projectkorra/projectkorra/ability/CoreAbility.java");
        String tempBlock = common("com/projectkorra/projectkorra/util/TempBlock.java");
        String smash = common("com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        String paper = source("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(core.contains("AbilityRemovalSync.ownerTransferred(this, previousOwner, nextOwner)")
                        && core.contains("TempBlock.refreshAbilityOwnership(this)"),
                "the endpoint must choose transfer authority before existing layers publish their new owner");
        assertTrue(tempBlock.contains("public static void refreshAbilityOwnership")
                        && tempBlock.contains("layer.ownerId = nextOwner")
                        && tempBlock.contains("UPDATE_EXPIRY"));
        assertTrue(tempBlock.contains("final long effectStep, final int effectOrdinal")
                        && tempBlock.contains("explicitIdentity == null"),
                "a predicted structure must be able to provide a stable logical layer identity");
        assertTrue(smash.contains("public boolean supportsPredictedOwnershipTransfer()")
                        && smash.contains("capturePredictionTransfer()")
                        && smash.contains("applyPredictionTransfer(final PredictionTransfer transfer)")
                        && smash.contains("restorePredictionTransfer"));
        assertTrue(smash.contains("final long effectFrame = ++this.predictionFrame")
                        && smash.contains("effectFrame, predictionShapeSlot(blockRep)")
                        && smash.contains("transfer.predictionFrame()"),
                "EarthSmash must retain an exact logical frame and shape slot across handoff");
        String remaining = method(smash, "public void checkRemainingBlocks()", "public void remove()");
        assertTrue(smash.contains("private boolean isAuthoritativeShapeBlock(final Block block)")
                        && remaining.contains("this.isAuthoritativeShapeBlock(block)"),
                "a concealed authoritative handoff must not make EarthSmash prune its restored shape");
        assertTrue(paper.contains("predictedOwnershipTransfers.add(ability)")
                        && paper.contains("tempLayerActions.remove(layer.getLayerId())")
                        && paper.contains("tempLayerEffects.remove(layer.getLayerId())")
                        && paper.contains("serverOwnedTempLayers.add(layer.getLayerId())")
                        && paper.contains("ownershipBridgeTempLayers.add(layer.getLayerId())"),
                "pre-transfer layers must remain visible and outside the redirect action until their ordinary close");
        assertTrue(paper.contains("final boolean stableEarthSmashSlot")
                        && paper.contains("change.effectStep(),")
                        && paper.contains("change.effectOrdinal()")
                        && paper.contains("new TempEffectIdentity(semanticAbility, 0L, ++action.tempBlockOrdinal)"),
                "Paper must preserve EarthSmash's exact slot while other abilities retain generic action ordinals");
        assertTrue(paper.contains("worldKey(smash.getLocation().getWorld())"),
                "Paper world names must be normalized to the registry key used by the Fabric client");
        String tempAuthority = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        assertTrue(runtime.contains("transferAuthoritativeAbility")
                        && runtime.contains("authoritativelyEstablishedAbilities.add")
                        && !runtime.contains("resetAbilityLayerIdentity(selected)")
                        && tempAuthority.contains("viewerId.equals(operation.ownerId())"),
                "the new client must restore the exact smash and each ownership revision must update visibility");
        assertTrue(tempAuthority.contains("!change.ability().tracksPredictedTempBlocks()")
                        && !tempAuthority.contains("public void resetAbilityLayerIdentity")
                        && tempAuthority.contains("stableEarthSmashSlot")
                        && !tempAuthority.contains("closestUnpairedEarthSmash"),
                "a foreign preview must stay outside the ledger while confirmed frames pair only by exact slot identity");
        assertTrue(smash.contains("new EarthSmash(player, transfer, true)")
                        && !smash.contains("nearestOwnedPrediction"),
                "the input action owns one explicit provisional continuation and never selects one by coordinate proximity");
        String activation = common("com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java");
        assertTrue(activation.contains("activateEarthSmash(context, ClickType.SHIFT_DOWN)")
                        && activation.contains("activateEarthSmash(context, ClickType.SHIFT_UP)")
                        && activation.contains("activation.didHandleActivation()")
                        && smash.contains("AbilityActivationManager.markHandled(affected)"),
                "only the exact EarthSmash instance whose state changed may move to the new action");
        assertTrue(paper.contains("hadExistingMatchingAbility && abilityName.equalsIgnoreCase(\"EarthSmash\")")
                        && paper.contains("onCheckpoint(candidate)"),
                "Paper must checkpoint every input-dispatched EarthSmash transition, including release");
        String checkpoint = method(smash, "public boolean matchesPredictionCheckpoint",
                "private static BlockData predictionBlockData");
        assertTrue(checkpoint.contains("this.origin.getX()") && !checkpoint.contains("this.location.getX()"),
                "a delayed lift checkpoint compares the stable source and must not rewind an already-rising smash");
        assertTrue(checkpoint.contains("checkpointState == State.GRABBED && this.state == State.LIFTED"),
                "a delayed grab checkpoint must confirm, rather than erase, an already-released smash");
    }

    @Test
    void tempBlockHotPathsUseIdentityAndCoordinateIndexes() throws IOException {
        String tempBlock = common("com/projectkorra/projectkorra/util/TempBlock.java");
        String authority = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        String lookup = method(authority, "private static TempBlock findActiveLayer",
                "private static boolean matchesWorld");
        String expiry = method(authority, "private void expireUnconfirmedLayers",
                "private boolean preserveLocalAuthority");
        String authoritativeTop = method(authority, "private ServerLayer topAuthoritative",
                "private boolean hidesServerLayer");

        assertTrue(tempBlock.contains("private static final Map<Long, TempBlock> LAYERS_BY_ID")
                        && tempBlock.contains("public static TempBlock getActiveLayer"));
        assertTrue(lookup.contains("TempBlock.getActiveLayer(layerId)"));
        assertFalse(lookup.contains("TempBlock.getActiveLayers()"),
                "each of hundreds of PhaseChange layers must not rescan the complete registry every tick");
        assertFalse(expiry.contains("List.copyOf(localLayers.entrySet())"),
                "the steady 600-layer tick must not allocate a complete entry snapshot");
        assertTrue(authority.contains("authoritativeByCoordinate")
                        && authority.contains("localLayersByCoordinate"));
        assertTrue(authoritativeTop.contains("authoritativeByCoordinate.get(key)"));
    }

    @Test
    void regenTempBlocksCannotRestoreASecondStaleSnapshot() throws IOException {
        String regen = common("com/jedk1/jedcore/util/RegenTempBlock.java");
        String gimbal = common("com/jedk1/jedcore/ability/waterbending/combo/WaterGimbal.java");
        String manage = method(regen, "public static void manage()", "public static void revert(Block block)");

        assertTrue(gimbal.contains("new RegenTempBlock(block, Material.WATER"));
        assertTrue(regen.contains("private static void createTempBlock"));
        assertFalse(regen.contains("TempBlock.get(block).revertBlock()"),
                "WaterGimbal must never retire whichever unrelated layer happens to be on top");
        assertTrue(manage.contains("TempBlock tb = temps.get(b)"));
        assertTrue(manage.contains("BlockState bs = states.remove(b)"));
        assertFalse(regen.contains("states.put(block, block.getState());\n                createTempBlock"),
                "TempBlock mode must not also retain a stale BlockState that overwrites later overlaps");
    }

    @Test
    void tempBlockClosuresAreDeliveredBeforeAbilityRemoval() throws IOException {
        String paper = source("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        assertRemovalIsQueued(paper, "public void run()");
    }

    private static void assertCurrentInputPrecedesCachedAbility(final String source) {
        String effect = method(source, "private Action actionForEffect",
                "private Action currentInputAction");
        int current = effect.indexOf("currentInputAction(ownerId)");
        int cachedEffect = effect.indexOf("abilityActions.get(ability)");
        assertTrue(current >= 0 && cachedEffect > current,
                "all synchronous effects must prefer the currently executing input");
        assertTrue(source.contains("final Long sequence = INPUT_SEQUENCE.get()")
                        && source.contains("session.actions.get(sequence)"));

        int start = source.indexOf("private PendingTempBlock queueTempBlock");
        if (start < 0) start = source.indexOf("private void queueTempBlock");
        int end = source.indexOf("private Map<UUID, ", start);
        assertTrue(start >= 0 && end > start);
        String queue = source.substring(start, end);
        int input = queue.indexOf("currentInputAction(effectOwner)");
        int cached = queue.indexOf("actionForEffect(effectiveAbility)");
        assertTrue(input >= 0 && cached > input,
                "the synchronous input must override an existing ability's stale action mapping");
    }

    private static void assertCurrentInputOwnsSynchronousEffects(final String source) {
        String prepare = method(source, "public int beforeSpawn(final CoreAbility ability",
                "public void onSpawn(final CoreAbility ability");
        int prepareInput = prepare.indexOf("currentInputAction(ownerId)");
        int prepareCached = prepare.indexOf("actionForEffect(ability)");
        assertTrue(prepareInput >= 0 && prepareCached > prepareInput,
                "a falling block must reserve its causal ordinal from the currently executing input before spawning");

        String spawn = method(source, "public void onSpawn(final CoreAbility ability",
                "public void onVelocity(");
        int spawnInput = spawn.indexOf("currentInputAction(ownerId)");
        int spawnCached = spawn.indexOf("actionForEffect(ability)");
        assertTrue(spawnInput >= 0 && spawnCached > spawnInput,
                "a falling block receipt from an existing ability's new input must use that input action");

        String velocity = method(source, "public void onVelocity(",
                "public void onRemoved(CoreAbility ability, boolean externallyCaused)");
        int velocityInput = velocity.indexOf("currentInputAction(ownerId)");
        int velocityCached = velocity.indexOf("abilityActions.get(coreAbility)");
        assertTrue(velocityInput >= 0 && velocityCached > velocityInput,
                "a synchronous impulse must use the current input before an existing ability's old action");
    }

    private static void assertRemovalIsQueued(final String source, final String tickMarker) {
        String removed = method(source, "public void onRemoved(CoreAbility ability, boolean externallyCaused)",
                "private void flushAbilityRemovals()");
        assertTrue(removed.contains("pendingAbilityRemovals.add"));
        assertFalse(removed.contains("AbilityRemoved("),
                "CoreAbility.remove publishes before subclass TempBlock cleanup and therefore cannot send immediately");
        assertFalse(removed.contains("abilityActions.remove(ability)"),
                "the closing layers still need the removed instance's exact action association");
        assertTrue(source.contains("abilityActions.remove(removal.instance)")
                        && source.contains("abilityCreationActions.remove(removal.instance)"));
        int tick = source.indexOf(tickMarker);
        int temp = source.indexOf("flushTempBlocks();", tick);
        int abilities = source.indexOf("flushAbilityRemovals();", tick);
        assertTrue(tick >= 0 && temp > tick && abilities > temp,
                "all TempBlock operations must flush before the queued ability removal");
    }

    private static String common(String relative) throws IOException {
        Path path = Path.of("../common/src/main/java").resolve(relative);
        if (!Files.exists(path)) path = Path.of("common/src/main/java").resolve(relative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }

    private static String source(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
