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

public abstract class PaperPredictionTransport extends PaperPredictionUtilities {
    protected PaperPredictionTransport(final JavaPlugin plugin) {
        super(plugin);
    }

    public void stop() {
        if (task != null) task.cancel();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.HELLO, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.CLIENT_DISABLED, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.READY, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.INPUT_VETO, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.ACTION_TAG, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.HIT_CLAIM, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
        sessions.clear();
        abilityActions.clear();
        abilityCreationActions.clear();
        predictedOwnershipTransfers.clear();
        tempLayerActions.clear();
        tempLayerEffects.clear();
        serverOwnedTempLayers.clear();
        pendingTempBlocks.clear();
        pendingAbilityRemovals.clear();
        playerHistory.clear();
        TempBlockSync.clear(this);
        DirectBlockSync.clear(this);
        TempFallingBlockSync.clear(this);
        VelocitySync.clear(this);
        AbilityStateSync.clear(this);
        GlidingStateSync.clear(this);
        AbilityCheckpointSync.clear(this);
        AbilityRemovalSync.clear(this);
        CooldownSync.clear(this);
        PlayerStatusSync.clear(this);
        if (this instanceof PaperPredictionServer server) PaperPredictionServer.deactivate(server);
    }

    @Override
    public void onChiBlockedChanged(final BendingPlayer bending, final boolean chiBlocked) {
        if (bending == null || bending.getPlayer() == null) return;
        final UUID playerId = bending.getPlayer().getUniqueId();
        final Session session = sessions.get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null || !session.ready) return;
        sendState(player, session, true);
    }

    protected void registerChannels() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.HELLO, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.CLIENT_DISABLED, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.READY, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.INPUT_VETO, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.ACTION_TAG, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.HIT_CLAIM, this);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.SNAPSHOT);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.WORLD_STATE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.NATIVE_ACTION);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.STATE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.CONFIG_CHUNK);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.RECONCILE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.TEMP_BLOCKS);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.VELOCITY_OWNER);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.VELOCITY_OWNER_V2);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.ABILITY_STATE_OWNER);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.GLIDING_STATE_OWNER);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.TEMP_FALLING_BLOCK);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.TEMP_FALLING_BLOCK_PREPARE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.DIRECT_BLOCK);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.ABILITY_REMOVED);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.ABILITY_TRANSFER);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.AIR_GLIDER_STATE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.STATE_DIRECTIVE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.COOLDOWN_SYNC);
    }

    @Override
    public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) {
        // A self-predicted cooldown starts on the input frame. Re-sending its
        // later Paper expiry would extend it by network latency and make the
        // client wait after its own exact common lifecycle has completed.
        Player predictedOwner = PaperPredictionServer.predictedEffectOwner();
        final UUID playerId = player == null || player.getPlayer() == null
                ? null : player.getPlayer().getUniqueId();
        final Action lifecycleAction = source == null ? null : actionForEffect(source);
        final boolean predictedInputWrite = predictedOwner != null && playerId != null
                && predictedOwner.getUniqueId().equals(playerId);
        final boolean predictedLifecycleWrite = lifecycleAction != null && playerId != null
                && lifecycleAction.locallyPredicted && lifecycleAction.owner.equals(playerId);
        if (predictedInputWrite || predictedLifecycleWrite) {
            final Session session = sessions.get(playerId);
            if (session != null && ability != null && !ability.isBlank()) {
                session.predictedCooldowns.add(ability.toLowerCase(Locale.ROOT));
            }
            return;
        }
        sendDirective(player, "", ability == null ? "" : ability, expiresAtMillis, false, Double.NaN);
    }

    @Override
    public void onRemoved(BendingPlayer player, String ability) {
        final UUID playerId = player == null || player.getPlayer() == null
                ? null : player.getPlayer().getUniqueId();
        final Session session = playerId == null ? null : sessions.get(playerId);
        if (session != null && ability != null
                && session.predictedCooldowns.remove(ability.toLowerCase(Locale.ROOT))) return;
        Player predictedOwner = PaperPredictionServer.predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUniqueId().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, ability == null ? "" : ability, "", 0L, false, Double.NaN);
    }

    @Override
    public void onSynchronize(final BendingPlayer player) {
        if (player == null || player.getPlayer() == null) return;
        PaperPredictionServer.synchronizeCooldowns(Bukkit.getPlayer(player.getPlayer().getUniqueId()));
    }

    @Override
    public void onAirBlastReset(BendingPlayer player) {
        Player predictedOwner = PaperPredictionServer.predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUniqueId().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, "", "", 0L, true, Double.NaN);
    }

    @Override
    public void onAirBlastRegenerated(BendingPlayer player) {
        sendDirective(player, "", "", 0L, false, player == null ? Double.NaN : player.getAirBlastDecay());
    }

    protected void sendDirective(BendingPlayer bending, String removedCooldown, String addedCooldown,
                               long cooldownUntil, boolean resetAirBlast, double airBlastDecay) {
        if (bending == null || bending.getPlayer() == null) return;
        Session session = sessions.get(bending.getPlayer().getUniqueId());
        Player player = Bukkit.getPlayer(bending.getPlayer().getUniqueId());
        if (session != null && player != null) {
            send(player, PaperPredictionProtocol.STATE_DIRECTIVE,
                    PaperPredictionProtocol.stateDirective(session.session, removedCooldown, addedCooldown,
                            cooldownUntil, System.currentTimeMillis(), resetAirBlast, airBlastDecay));
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        Runnable handling = () -> {
            try {
                switch (channel) {
                    case PaperPredictionProtocol.HELLO -> onHello(player, PaperPredictionProtocol.readHello(message));
                    case PaperPredictionProtocol.CLIENT_DISABLED -> onClientDisabled(player,
                            PaperPredictionProtocol.readClientDisabled(message));
                    case PaperPredictionProtocol.READY -> onReady(player, PaperPredictionProtocol.readReady(message));
                    case PaperPredictionProtocol.INPUT_VETO -> onInputVeto(player,
                            PaperPredictionProtocol.readInputVeto(message));
                    case PaperPredictionProtocol.ACTION_TAG -> onActionTag(player,
                            PaperPredictionProtocol.readActionTag(message));
                    case PaperPredictionProtocol.HIT_CLAIM -> onHitClaim(player,
                            PaperPredictionProtocol.readHitClaim(message));
                    default -> {
                    }
                }
            } catch (IllegalArgumentException malformed) {
                plugin.getLogger().warning("Rejected malformed prediction packet from " + player.getName() + ": " + malformed.getMessage());
            }
        };
        if (Bukkit.isPrimaryThread()) handling.run();
        else Bukkit.getScheduler().runTask(plugin, handling);
    }

    @Override
    public void run() {
        tick++;
        recordPlayerHistory();
        uncorrelatedExternalVelocityOrdinals.clear();
        flushTempBlocks();
        flushAbilityRemovals();
        sessions.entrySet().removeIf(entry -> {
            if (Bukkit.getPlayer(entry.getKey()) != null) return false;
            return true;
        });
        abilityActions.entrySet().removeIf(entry -> entry.getKey().isRemoved() || !sessions.containsKey(entry.getValue().owner));
        abilityCreationActions.entrySet().removeIf(entry -> entry.getKey().isRemoved()
                || !sessions.containsKey(entry.getValue().owner));
        if (tick % 20 == 0) {
            syncState();
            // CREATE/REVERT packets are ordered, but a player can enter view
            // after a layer was created. Refresh the ledger when the player's
            // chunk/view changes, with a slow safety repair for exceptional
            // desyncs. Sending every unchanged layer every second caused
            // packet and client-mesh spikes in TempBlock-heavy scenes.
            for (Session session : sessions.values()) {
                final Player player = Bukkit.getPlayer(session.player);
                if (player != null) {
                    sendWorldState(player, session);
                    if (shouldSendPeriodicTempBlockSnapshot(player, session)) {
                        sendTempBlockSnapshot(player, session);
                    }
                    final AirGlider glider = CoreAbility.getAbility(BukkitMC.player(player), AirGlider.class);
                    if (glider != null && !glider.isRemoved()) sendAirGliderState(player, glider, null);
                }
            }
        }
        if (tick % 100 == 0) {
            rebuildPermissionCandidates();
            requestSnapshotRebuild(true);
        }
    }
}
