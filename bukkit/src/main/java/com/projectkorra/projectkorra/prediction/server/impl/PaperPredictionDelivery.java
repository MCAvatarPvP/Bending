package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.snapshot.PaperPredictionSnapshot;
import com.projectkorra.projectkorra.prediction.snapshot.PaperRegionProtectionSnapshot;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.NativeActionTagStream;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionVisibility;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockDeliveryTracker;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.hit.HitRewind;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.AbilityCheckpointSync;
import com.projectkorra.projectkorra.prediction.state.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.state.GlidingStateSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PlayerStatusSync;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

public abstract class PaperPredictionDelivery extends PaperPredictionInput {
    protected PaperPredictionDelivery(final JavaPlugin plugin) {
        super(plugin);
    }

    protected void flushTempBlocks() {
        if (pendingTempBlocks.isEmpty()) return;
        if (pendingTempBlocks.size() == 1) {
            flushTempBlock(pendingTempBlocks.remove(0));
            return;
        }
        List<PendingTempBlock> operations = List.copyOf(pendingTempBlocks);
        pendingTempBlocks.clear();
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.player);
            if (player == null) continue;
            final WorldScope scope = refreshWorldScope(player, session);
            Location location = player.getLocation();
            String viewerWorld = scope.identity();
            List<PaperPredictionProtocol.TempBlockOp> visible = new ArrayList<>();
            for (PendingTempBlock pending : operations) {
                final long layerId = pending.operation().layerId();
                final boolean inView = PredictionVisibility.tracksBlock(viewerWorld, pending.worldIdentity(),
                        location.getBlockX(), location.getBlockZ(), pending.operation().x(), pending.operation().z(),
                        player.getClientViewDistance());
                if (!session.tempLayers.route(layerId,
                        pending.operation().operation() == PaperPredictionProtocol.TempOperation.REVERT
                                || pending.operation().operation() == PaperPredictionProtocol.TempOperation.DISCARD,
                        inView)) continue;
                visible.add(pending.forViewer(session.player));
            }
            if (!visible.isEmpty()) {
                sendTempBlockOperations(player, session, visible, false);
            }
        }
    }

    protected void flushTempBlock(final PendingTempBlock pending) {
        final long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            final Player player = Bukkit.getPlayer(session.player);
            if (player == null) continue;
            final WorldScope scope = refreshWorldScope(player, session);
            final Location location = player.getLocation();
            final long layerId = pending.operation().layerId();
            final boolean inView = PredictionVisibility.tracksBlock(scope.identity(), pending.worldIdentity(),
                    location.getBlockX(), location.getBlockZ(), pending.operation().x(), pending.operation().z(),
                    player.getClientViewDistance());
            if (!session.tempLayers.route(layerId,
                    pending.operation().operation() == PaperPredictionProtocol.TempOperation.REVERT
                            || pending.operation().operation() == PaperPredictionProtocol.TempOperation.DISCARD,
                    inView)) continue;
            sendTempBlockOperation(player, session, scope, pending.forViewer(session.player), now);
        }
    }

    protected void sendTempBlockOperation(final Player player, final Session session,
                                        final WorldScope scope,
                                        final PaperPredictionProtocol.TempBlockOp operation,
                                        final long now) {
        send(player, PaperPredictionProtocol.TEMP_BLOCKS,
                PaperPredictionProtocol.tempBlock(session.session, scope.generation(), scope.identity(),
                        ++session.tempBlockStreamSequence, tick, now, operation));
    }

    protected void sendTempBlockOperations(final Player player, final Session session,
                                          final List<PaperPredictionProtocol.TempBlockOp> operations,
                                          final boolean snapshot) {
        final long now = System.currentTimeMillis();
        final WorldScope scope = refreshWorldScope(player, session);
        final List<List<PaperPredictionProtocol.TempBlockOp>> packets =
                partitionTempBlockOperations(operations);
        final int packetCount = packets.size();
        final long snapshotId = snapshot ? ++session.tempBlockSnapshotSequence : 0L;
        for (int packetIndex = 0; packetIndex < packetCount; packetIndex++) {
            send(player, PaperPredictionProtocol.TEMP_BLOCKS,
                    PaperPredictionProtocol.tempBlocks(session.session, scope.generation(), scope.identity(),
                            snapshot, ++session.tempBlockStreamSequence, snapshotId,
                            snapshot ? packetIndex : 0, snapshot ? packetCount : 1, tick, now,
                            packets.get(packetIndex)));
        }
    }

    /**
     * Uses the real encoded operation sizes instead of a tiny fixed count.
     * Dense, ordinary block states now share a packet, while unusually large
     * state strings still retain enough room for the protocol envelope.
     */
    protected static List<List<PaperPredictionProtocol.TempBlockOp>> partitionTempBlockOperations(
            final List<PaperPredictionProtocol.TempBlockOp> operations) {
        if (operations == null || operations.isEmpty()) return List.of(List.of());
        final int payloadBudget = Messenger.MAX_MESSAGE_SIZE - TEMP_BLOCK_PACKET_HEADROOM_BYTES;
        final List<List<PaperPredictionProtocol.TempBlockOp>> packets = new ArrayList<>();
        final List<PaperPredictionProtocol.TempBlockOp> current = new ArrayList<>();
        int encodedBytes = 0;
        for (PaperPredictionProtocol.TempBlockOp operation : operations) {
            final int operationBytes = PaperPredictionProtocol.tempBlockOperationSize(operation);
            if (!current.isEmpty() && (current.size() >= MAX_TEMP_BLOCK_OPS_PER_PACKET
                    || encodedBytes + operationBytes > payloadBudget)) {
                packets.add(List.copyOf(current));
                current.clear();
                encodedBytes = 0;
            }
            current.add(operation);
            encodedBytes += operationBytes;
        }
        if (!current.isEmpty()) packets.add(List.copyOf(current));
        return List.copyOf(packets);
    }

    protected void syncState() {
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.player);
            if (player == null) continue;
            sendState(player, session, false);
        }
    }

    protected void sendState(Player player, Session session, boolean force) {
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        Map<Integer, String> binds = PaperPredictionSnapshot.binds(bending);
        Map<String, Long> cooldowns = PaperPredictionSnapshot.cooldowns(bending);
        List<String> elements = PaperPredictionSnapshot.elements(bending), subs = PaperPredictionSnapshot.subElements(bending);
        List<String> permissions = predictionPermissions(player, session);
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        boolean chiBlocked = bending != null && bending.isChiBlocked();
        PaperPredictionProtocol.PlayerCosmetics cosmetics = playerCosmetics(bending);
        RegionProtectionAuthority.Snapshot regionProtection =
                regionProtectionSnapshot(player, bending, binds, session);
        List<String> activeFlights = activeFlightAbilities(player.getUniqueId());
        int digest = 31 * binds.hashCode() + cooldowns.hashCode();
        digest = 31 * digest + elements.hashCode();
        digest = 31 * digest + subs.hashCode();
        digest = 31 * digest + permissions.hashCode();
        digest = 31 * digest + Double.hashCode(airBlastDecay);
        digest = 31 * digest + Boolean.hashCode(chiBlocked);
        digest = 31 * digest + cosmetics.hashCode();
        digest = 31 * digest + regionProtection.hashCode();
        digest = 31 * digest + activeFlights.hashCode();
        digest = 31 * digest + Long.hashCode(session.lastSequence);
        if (!force && digest == session.stateDigest) return;
        session.stateDigest = digest;
        send(player, PaperPredictionProtocol.STATE, PaperPredictionProtocol.state(session.session, tick,
                System.currentTimeMillis(), session.lastSequence, binds, cooldowns, elements, subs,
                permissions, airBlastDecay, chiBlocked, cosmetics, regionProtection, activeFlights));
    }

    protected static PaperPredictionProtocol.PlayerCosmetics playerCosmetics(final BendingPlayer bending) {
        if (bending == null) return PaperPredictionProtocol.PlayerCosmetics.empty();
        return new PaperPredictionProtocol.PlayerCosmetics(
                bending.getFireColor() == null ? "" : bending.getFireColor().getName(),
                bending.getAirColor() == null ? "" : bending.getAirColor().getName(),
                bending.getGliderColor() == null ? "" : bending.getGliderColor().getName(),
                bending.getWaterCosmetic() == null ? "" : bending.getWaterCosmetic().getName(),
                bending.getEarthCosmetic() == null ? "" : bending.getEarthCosmetic().getName(),
                bending.isSprinkleEnabled());
    }

    protected RegionProtectionAuthority.Snapshot regionProtectionSnapshot(
            final Player player, final BendingPlayer bending, final Map<Integer, String> binds,
            final Session session) {
        if (player == null || bending == null) return RegionProtectionAuthority.Snapshot.empty();
        final List<String> relevant = PaperRegionProtectionSnapshot.relevantAbilities(
                player, binds == null ? List.of() : binds.values());
        final List<String> abilities = RegionProtectionAuthority.normalizedAbilities(relevant);
        final int chunkX = player.getLocation().getBlockX() >> 4;
        final int chunkZ = player.getLocation().getBlockZ() >> 4;
        final long spatialKey = 31L * (31L * (31L * player.getWorld().getUID().hashCode()
                + chunkX) + chunkZ) + abilities.hashCode();
        if (session.regionProtectionSpatialKey != spatialKey
                || tick >= session.nextRegionProtectionRefreshTick) {
            session.regionProtectionSpatial = PaperRegionProtectionSnapshot.spatial(player, abilities);
            session.regionProtectionSpatialKey = spatialKey;
            session.nextRegionProtectionRefreshTick = tick + 100L;
        }
        final RegionProtectionAuthority.Snapshot point =
                PaperRegionProtectionSnapshot.currentPoint(player, abilities);
        final List<RegionProtectionAuthority.Box> boxes = new ArrayList<>(point.boxes());
        if (session.regionProtectionSpatial.world().equalsIgnoreCase(point.world())
                && session.regionProtectionSpatial.abilities().equals(point.abilities())) {
            boxes.addAll(session.regionProtectionSpatial.boxes());
        }
        return new RegionProtectionAuthority.Snapshot(point.world(), point.abilities(), boxes);
    }
}
