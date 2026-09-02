package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.hit.EntityHitboxProvider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level boundaries for regressions reported during the TempBlock rewrite. */
class GameplayRegressionBoundaryTest {
    @Test
    void iceWallNeverDropsCollapseDuringItsRisePhase() throws IOException {
        String iceWall = common("com/jedk1/jedcore/ability/waterbending/IceWall.java");

        assertTrue(iceWall.contains("collapsePending = true"));
        assertTrue(iceWall.contains("pendingCollapseForceful |= forceful"));
        assertTrue(iceWall.contains("if (collapsePending)"));
        assertTrue(iceWall.contains("AbilityExecutionContext.run(requestedCause"),
                "queued collision/input removals must retain their external origin");
        assertFalse(iceWall.contains("if (rising)\n            return;"),
                "a rise-phase collapse request must never be discarded");
    }

    @Test
    void movedEarthTempAirPreservesLegacyStateUntilTheCallerConsumesIt() throws IOException {
        String earth = common("com/projectkorra/projectkorra/ability/EarthAbility.java");
        String handoff = method(earth, "public static void addTempAirBlock", "public static void displaySandParticle");
        String restore = method(earth, "public static void revertAirBlock(Block block)",
                "public static boolean revertBlock");

        int read = handoff.indexOf("MOVED_EARTH.containsKey(block)");
        int retain = handoff.indexOf("info = MOVED_EARTH.get(block)", read);
        int enqueue = handoff.indexOf("TEMP_AIR_LOCATIONS.put", retain);
        assertTrue(read >= 0 && retain > read && enqueue > retain,
                "EarthBlast must retain a moved source's original snapshot through its temporary-air handoff");
        assertFalse(handoff.contains("retireMovedEarth(block)"),
                "consuming moved earth here loses the pre-RaiseEarth state before EarthBlast can carry it forward");
        assertTrue(restore.contains("TEMP_AIR_LOCATIONS.entrySet()")
                        && restore.contains("revertAirBlock(entry.getKey())"));
        assertFalse(restore.contains("i++") || restore.contains("revertAirBlock(i)"),
                "temp-air restores must use Information's actual map id, not iteration position");
    }

    @Test
    void completedRaiseEarthWallsAndColumnsRemainCollapsibleUntilMovedEarthRetires() throws IOException {
        String raise = common("com/projectkorra/projectkorra/earthbending/RaiseEarth.java");
        String earth = common("com/projectkorra/projectkorra/ability/EarthAbility.java");
        String collapse = common("com/projectkorra/projectkorra/earthbending/Collapse.java");
        String progress = method(raise, "public void progress()", "private void untrackAffectedBlocks");
        String remove = method(raise, "public void remove()", "public boolean isRaisedByWall()");

        assertTrue(progress.indexOf("this.completed = true") < progress.indexOf("this.remove()"));
        assertTrue(remove.contains("if (this.completed)")
                        && remove.contains("this.wallBlocks.clear()")
                        && remove.contains("this.columnBlocks.clear()"),
                "completed walls and columns must detach locally without losing persistent identity");
        assertTrue(remove.contains("this.clearTrackedWallBlocks()")
                        && remove.contains("this.clearTrackedColumnBlocks()"),
                "cancelled and incomplete raises must not leak persistent identity");
        assertTrue(raise.contains("ConcurrentHashMap<BlockKey, Integer> WALL_BLOCKS")
                        && raise.contains("ConcurrentHashMap<BlockKey, Integer> COLUMN_BLOCKS")
                        && raise.contains("WALL_BLOCKS.merge(blockKey(affected), 1, Integer::sum)")
                        && raise.contains("COLUMN_BLOCKS.merge(blockKey(affected), 1, Integer::sum)")
                        && raise.contains("references > 1 ? references - 1 : null"),
                "overlapping raises must share coordinate-based, reference-counted ownership");
        assertTrue(raise.contains("if (!getMovedEarth().containsKey(affected))"),
                "source holes and untouched natural terrain must never become Collapse-eligible");
        String failed = method(raise, "private void discardUnstartedBlocks()", "private void trackWallBlocks()");
        assertTrue(failed.contains("this.discardAffectedBlocks()")
                        && failed.contains("this.clearTrackedWallBlocks()")
                        && failed.contains("this.clearTrackedColumnBlocks()"),
                "a RaiseEarth constructor that cannot start must not leak structure identity");
        assertTrue(raise.contains("blockInRaisedAffectedBlocks(final Block block)")
                        && raise.contains("blockInWallAffectedBlocks(block) || blockInColumnAffectedBlocks(block)"));
        assertTrue(earth.contains("RaiseEarth.revertRaisedAffectedBlock(block)")
                        && earth.contains("RaiseEarth.revertRaisedAffectedBlock(source)"));
        assertTrue(earth.contains("RaiseEarth.clearPersistentBlocks()"));
        assertTrue(collapse.contains("RaiseEarth.blockInRaisedAffectedBlocks(this.block)"));
        assertFalse(collapse.contains("if (!RaiseEarth.blockInWallAffectedBlocks(this.block))"),
                "left-click Collapse must not reject a normal RaiseEarth column");
        assertTrue(collapse.contains("RaiseEarth.revertRaisedAffectedBlock(thisBlock)"));
    }

    @Test
    void wallOfFireSweepsEveryEntityAcrossTicks() throws IOException {
        String wall = common("com/projectkorra/projectkorra/firebending/WallOfFire.java");
        String damage = method(wall, "private void damage()", "private void display()");

        assertTrue(wall.contains("private Map<UUID, BoundingBox> previousEntityBounds"));
        assertFalse(wall.contains("predictedContacts"),
                "WallOfFire must not retain a client-selected hit target");
        assertTrue(damage.contains("this.previousEntityBounds.getOrDefault"));
        assertTrue(damage.contains("this.previousEntityBounds.put(entityId, curBB)"));
        assertTrue(damage.contains("this.previousEntityBounds.keySet().retainAll(observed)"));
        assertFalse(damage.contains("lastBoundingBB") || damage.contains("break;"),
                "one server-observed or already-affected entity must not starve every later target");
        assertTrue(wall.contains("implements EntityHitboxProvider")
                        && wall.contains("block.getLocation().add(0.5, 0.5, 0.5)")
                        && wall.contains("return ENTITY_HIT_SAMPLE_RADIUS"),
                "reaction-window validation must cover each whole wall cell, not only its block corner");
    }

    @Test
    void dischargeNeverReusesADeadBranchId() throws IOException {
        String discharge = common("com/jedk1/jedcore/ability/firebending/Discharge.java");
        String advance = method(discharge, "private void advanceLocation()", "private double createBranch()");

        assertTrue(discharge.contains("private int nextBranchId = 1"));
        assertTrue(advance.contains("branches.put(nextBranchId++, fork.clone())"));
        assertFalse(advance.contains("branches.put(branches.size() + 1"),
                "removing an old branch must not overwrite a live predicted branch");
    }

    //@Test
    //void overlappingAbilitiesProgressInAPlatformIndependentOrder() throws IOException {
    //    String core = common("com/projectkorra/projectkorra/ability/CoreAbility.java");
    //    String progress = method(core, "public static void progressAll()", "public static void removeAll()");
    //    String ordered = method(core, "private static <T extends CoreAbility> List<T> orderedInstances",
    //            "/**\n     * Returns an List of fake instances");
//
    //    assertTrue(progress.contains("orderedInstances(INSTANCES)"));
    //    assertTrue(ordered.contains("ability.getPlayer().getUniqueId().toString()"));
    //    assertTrue(ordered.contains("ability.getClass().getName()"));
    //    assertTrue(ordered.contains("thenComparingInt(CoreAbility::getId)"),
    //            "Paper and Fabric must mutate overlapping TempBlock stacks in the same order");
    //}

    @Test
    void spoutsReleaseOnlyTheirOwnSharedFlightLease() throws IOException {
        String handler = common("com/projectkorra/projectkorra/util/FlightHandler.java");
        String water = common("com/projectkorra/projectkorra/waterbending/WaterSpout.java");
        String air = common("com/projectkorra/projectkorra/airbending/AirSpout.java");
        String wave = common("com/projectkorra/projectkorra/waterbending/WaterSpoutWave.java");
        String core = common("com/projectkorra/projectkorra/ability/CoreAbility.java");
        String airRemove = method(air, "public void remove()", "private void animateAirSpout");

        assertTrue(handler.contains("public boolean hasOtherInstance")
                        && handler.contains("!current.equals(identifier)"),
                "temporary flight suspension must be aware of every other lease owner");
        assertTrue(water.contains("!this.flightHandler.hasOtherInstance(this.player, this.getName())"),
                "WaterSpout may not turn off AirSpout or another active flight ability");
        assertTrue(air.contains("this.flightHandler.hasOtherInstance(this.player, this.getName())"),
                "AirSpout height suspension may not revoke another flight ability");
        assertTrue(airRemove.contains("this.flightHandler.removeInstance(this.player, this.getName())"));
        assertFalse(airRemove.contains("this.removeFlight()"),
                "FlightHandler alone must restore the captured baseline when the final lease ends");
        assertFalse(core.contains("AbilityLifecycleSync.deferRemoval"),
                "an off-water/ground removal must not leave the ability registry and flight lease half alive");
        assertTrue(method(water, "public void remove()", "public void revertBaseBlock")
                        .indexOf("if (this.isRemoved()) return;")
                        < method(water, "public void remove()", "public void revertBaseBlock").indexOf("super.remove()"));
        String waterRemove = method(water, "public void remove()", "public void revertBaseBlock");
        assertTrue(waterRemove.contains("finally")
                        && waterRemove.contains("this.flightHandler.removeInstance(this.player, this.getName())"),
                "a failed WaterSpout TempBlock restore must never strand its flight lease");
        assertTrue(waterRemove.contains("new ArrayList<>(this.blocks)")
                        && waterRemove.contains("catch (RuntimeException failure)"),
                "one broken WaterSpout layer must not prevent every remaining layer from retiring");
        assertTrue(airRemove.indexOf("if (this.isRemoved()) return;") < airRemove.indexOf("super.remove()"));
        String waveRemove = method(wave, "public void remove()", "public void createBlockDelay");
        assertTrue(waveRemove.indexOf("if (this.isRemoved()) return;") < waveRemove.indexOf("super.remove()"),
                "WaterSpoutWave may add its hidden cooldown only once per lifecycle");
    }

    @Test
    void worldRestartCannotRetainAPhantomWaterSpoutToggle() throws IOException {
        String core = common("com/projectkorra/projectkorra/ability/CoreAbility.java");
        String water = common("com/projectkorra/projectkorra/ability/WaterAbility.java");
        String sources = common("com/projectkorra/projectkorra/util/BlockSource.java");

        assertTrue(core.contains("public static void discardAllInstances()")
                        && core.contains("INSTANCES.clear()")
                        && core.contains("INSTANCES_BY_PLAYER.clear()")
                        && core.contains("INSTANCES_BY_CLASS.clear()"),
                "a failed addon remove may not leave an old WaterSpout visible to the next runtime");
        assertTrue(water.contains("public static void discardAllWaterbendingState()")
                        && water.contains("WaterSpout.discardAllTracking()")
                        && water.contains("PhaseChange.discardAllTracking()")
                        && water.contains("WaterArmsSpear.discardAllTracking()"),
                "every long-lived water TempBlock index must be scoped to one client world");
        String earth = common("com/projectkorra/projectkorra/ability/EarthAbility.java");
        assertTrue(earth.contains("LavaFlow.discardAllTracking()")
                        && earth.contains("LavaSurgeWall.discardAllTracking()")
                        && earth.contains("Ripple.progressAllCleanup()")
                        && earth.contains("RaiseEarth.clearAllTracking()"),
                "auxiliary earth ownership may not survive a client-world replacement");
        assertTrue(sources.contains("public static void clearAll()")
                        && sources.contains("playerSources.clear()"),
                "source selections from the outgoing world may not be consumed after a world switch");
    }

    @Test
    void transientWorldRestartFailureCannotDisableAllPredictionPermanently() throws IOException {
        String client = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(client.contains("RUNTIME_RETRY_TICKS")
                        && client.contains("cached-state-recovery")
                        && client.contains("lastRuntimeStartAttemptTick"),
                "a one-tick world transition failure must retry from the already authenticated snapshot");
        assertTrue(client.contains("ProjectKorra prediction: ")
                        || client.contains("diagnosticStatus()"),
                "the client must expose whether handshake, runtime, abilities, and binds are active");
        assertTrue(runtime.contains("lastStartFailure = failure.getClass().getSimpleName()"),
                "startup failures must remain inspectable instead of silently falling back to server-only play");
    }

    @Test
    void fabricRunsTheCompletePaperManagerPipelineInTaskOrder() throws IOException {
        String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String scheduler = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricClientPredictionPlatform.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricClientPredictionPlatform.java");
        String startup = method(runtime, "private boolean start0(", "private void updatePlayerState0(");
        String tick = method(runtime, "private void tick0(", "private void reconcileAuthoritativeCooldowns(");

        int bending = startup.indexOf("runTimer(this.bendingManager, 0L, 1L)");
        int water = startup.indexOf("runTimer(new WaterbendingManager", bending);
        int earth = startup.indexOf("runTimer(new EarthbendingManager", water);
        int fire = startup.indexOf("runTimer(new FirebendingManager", earth);
        int chi = startup.indexOf("runTimer(new ChiblockingManager", fire);
        int addons = startup.indexOf("EmbeddedAddonBootstrap.enable()", chi);
        assertTrue(bending >= 0 && water > bending && earth > water && fire > earth
                        && chi > fire && addons > chi,
                "Fabric must use GeneralMethods.reloadPlugin's Paper manager/addon order");
        assertTrue(startup.contains("ProjectKorra.collisionInitializer.initializeDefaultCollisions()"),
                "client ability collisions are part of the common lifecycle");
        assertTrue(tick.contains("this.platform.tick()"));
        assertFalse(tick.contains("bendingManager.run()"),
                "BendingManager must not run a second time outside the ordered scheduler");
        assertTrue(scheduler.contains("thenComparingInt(task -> task.id)"),
                "equal-tick tasks must retain Bukkit's task-id ordering");
        assertTrue(scheduler.contains("tick + Math.max(1, delay)"),
                "a zero-delay task scheduled during a heartbeat must wait for Bukkit's next heartbeat");
        assertFalse(scheduler.contains("tick + Math.max(0, delay)"),
                "Fabric must not re-enter newly scheduled child work in the current ability tick");

        String fabricServerScheduler = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricProjectKorraPlatform.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricProjectKorraPlatform.java");
        assertTrue(fabricServerScheduler.contains("thenComparingInt(scheduled -> scheduled.id)"));
        assertTrue(fabricServerScheduler.contains("tick + Math.max(1, delay)"),
                "the dedicated Fabric scheduler must retain Bukkit heartbeat and insertion ordering too");
    }

    @Test
    void reloadRestartsBundledTempBlockManagersBehindATeardownFence() throws IOException {
        final String general = common("com/projectkorra/projectkorra/GeneralMethods.java");
        final String tempBlock = common("com/projectkorra/projectkorra/util/TempBlock.java");
        final String handlerList = common("com/projectkorra/projectkorra/platform/mc/event/HandlerList.java");
        final String bukkitEvents = source(
                "src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitProjectKorraPlatform.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitProjectKorraPlatform.java");
        final String fabricEvents = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricProjectKorraPlatform.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricProjectKorraPlatform.java");
        final String reload = method(general, "public static void reloadPlugin(",
                "public static void reloadAddonPlugins(");
        final String stop = method(general, "public static void stopBending()",
                "public static void stopPlugin()");

        assertTrue(stop.contains("TempBlock.runShutdownCleanup"));
        assertTrue(stop.contains("EmbeddedAddonBootstrap.disable()"),
                "cancelled bundled-addon tickers must be marked stopped before reload startup");
        assertTrue(reload.indexOf("Platform.scheduler().cancelAll()")
                        < reload.indexOf("EmbeddedAddonBootstrap.enable()"),
                "bundled temp-block managers must restart after the old task set is cancelled");
        assertTrue(tempBlock.contains("if (shutdownCleanupDepth > 0)"));
        assertTrue(tempBlock.contains("if (shutdownCleanupDepth > 0) return;"),
                "teardown must reject new layers and suppress callbacks that recreate them");
        assertTrue(handlerList.contains("Platform.events().unregisterAll(o)"));
        assertTrue(bukkitEvents.contains("registration.owner() == target")
                        && fabricEvents.contains("handler.owner()==target"),
                "listener teardown must honor plugin ownership on both server platforms");
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
