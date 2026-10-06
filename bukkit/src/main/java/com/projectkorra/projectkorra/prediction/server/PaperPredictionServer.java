package com.projectkorra.projectkorra.prediction.server;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.server.impl.PaperPredictionSnapshots;
import com.projectkorra.projectkorra.prediction.snapshot.PaperPredictionSnapshot;
import com.projectkorra.projectkorra.prediction.snapshot.PaperRegionProtectionSnapshot;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.NativeActionTagStream;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionVisibility;
import com.projectkorra.projectkorra.prediction.authority.AuthoritativeEffects;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockDeliveryTracker;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.hit.HitRewind;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackIngress;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSession;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartNegotiation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartServerEndpoint;
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
import com.projectkorra.projectkorra.prediction.rollback.PaperRollbackMatchBootstrap;
import com.projectkorra.projectkorra.prediction.rollback.PaperRollbackBootstraps;
import com.projectkorra.projectkorra.prediction.rollback.PaperRollbackLifecycle;
import com.projectkorra.projectkorra.prediction.rollback.RollbackBootstrapData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackBootstrapServerEndpoint;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
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

/**
 * Authoritative Paper endpoint for Fabric client prediction.
 */
public final class PaperPredictionServer extends PaperPredictionSnapshots {
    public static final int MAX_REWIND_TICKS = 12;
    private static volatile PaperPredictionServer active;
    private PaperRollbackMatchBootstrap rollbackMatchBootstrap;

    private PaperPredictionServer(JavaPlugin plugin) {
        super(plugin);
    }

    /** Internal lifecycle access for the extracted server implementation. */
    public static PaperPredictionServer activeInstance() {
        return active;
    }

    public PaperRollbackMatchBootstrap rollbackMatchBootstrap() {
        return active == this ? rollbackMatchBootstrap : null;
    }

    public PaperRollbackLifecycle.Lease reserveRollbackLifecycle(Set<UUID> players, Runnable stop) {
        if (active != this) throw new IllegalStateException("Prediction server is not active");
        return rollbackLifecycle.reserve(players, stop);
    }

    /** Prepare against the installed lifecycle listener; caller retains this handle before acquisition. */
    public com.projectkorra.projectkorra.prediction.rollback.PaperRollbackLiveOwnership prepareRollbackOwnership(
            java.util.Collection<net.minecraft.server.level.ServerPlayer> players,
            com.projectkorra.projectkorra.prediction.rollback.RollbackLiveScheduler scheduler,
            java.util.function.Predicate<com.projectkorra.projectkorra.prediction.rollback.RollbackLiveScheduler.Work> selectTasks,
            int taskCapacity, Runnable stop) {
        if (active != this) throw new IllegalStateException("Prediction server is not active");
        return com.projectkorra.projectkorra.prediction.rollback.PaperRollbackLiveOwnership.prepare(
                players, scheduler, selectTasks, taskCapacity, rollbackLifecycle, stop);
    }
    public boolean supportsRollbackBootstrap(Set<UUID> players) { return active == this && rollbackBootstraps.supports(players); }

    /** Native preparation owns this handle before poll sends any snapshot or mutates client ownership. */
    public PaperRollbackBootstraps.Transfer beginRollbackBootstrap(RollbackBootstrapData data, RollbackTerrainCodec.Limits terrainLimits,
            RollbackBootstrapServerEndpoint.Limits limits, java.util.function.BooleanSupplier ready) {
        if (active != this) throw new IllegalStateException("Prediction server is not active");
        return rollbackBootstraps.begin(data, terrainLimits, limits, ready);
    }

    /** Install only the complete capture/transfer/native-ownership implementation. */
    public void installRollbackMatchBootstrap(PaperRollbackMatchBootstrap bootstrap) {
        if (active != this || !Bukkit.isPrimaryThread() || rollbackMatchBootstrap != null) {
            throw new IllegalStateException("Rollback match bootstrap is unavailable or already installed");
        }
        rollbackMatchBootstrap = Objects.requireNonNull(bootstrap);
    }

    /** The Neptune round owner supplies fully prepared peers and a runtime. */
    public RollbackStartServerEndpoint.Registration beginRollbackStart(UUID session, UUID challenge, String contentHash,
            Collection<RollbackStartNegotiation.PreparedPeer> peers, RollbackStartNegotiation.Limits limits,
            java.util.function.BooleanSupplier ready, RollbackStartServerEndpoint.Runtime runtime) {
        if (active != this || peers.stream().anyMatch(peer -> {
            Session legacy = sessions.get(peer.player());
            return legacy == null || !legacy.ready;
        })) throw new IllegalStateException("Rollback preparation requires ready prediction sessions");
        return rollbackStarts.begin(session, challenge, contentHash, peers, tick, limits, ready, runtime);
    }

    /**
     * Loader handoff after whole-duel negotiation, private bootstrap and suspension of
     * live simulation. This API alone does not freeze native movement or start a duel.
     */
    public RollbackIngress.Registration enrollRollback(RollbackSession<?, ?> session,
                                                       java.util.function.Consumer<RollbackIngress.Registration> stopped) {
        if (active != this || session.peers().stream().anyMatch(peer -> {
            Session legacy = sessions.get(peer.player());
            return legacy == null || !legacy.ready;
        })) throw new IllegalStateException("Rollback requires current ready prediction sessions");
        var registration = rollbackInputs.enroll(session, stopped);
        Set<UUID> roster = session.peers().stream().map(RollbackSession.Peer::player).collect(java.util.stream.Collectors.toSet());
        roster.forEach(player -> { sessions.remove(player); playerHistory.remove(player); });
        synchronized (abilityActions) { abilityActions.entrySet().removeIf(entry -> roster.contains(entry.getValue().owner)); }
        synchronized (abilityCreationActions) { abilityCreationActions.entrySet().removeIf(entry -> roster.contains(entry.getValue().owner)); }
        // Retire evidence against the enrolled defenders, including claims made by an outsider.
        for (Session legacy : sessions.values()) for (Action action : legacy.actions.values()) action.claims.keySet().removeAll(roster);
        return registration;
    }

    /** Runtime owner calls after advancing/reconciling; this does not start a live duel. */
    public void publishRollback(RollbackSession<?, ?> session) {
        if (active != this) throw new IllegalStateException("Prediction server is not active");
        rollbackInputs.publish(session, plugin);
    }

    /** Clears the singleton only when the same implementation is stopping. */
    public static void deactivate(final PaperPredictionServer server) {
        if (active == server) active = null;
    }

    public static PaperPredictionServer start(JavaPlugin plugin) {
        PaperPredictionServer server = new PaperPredictionServer(plugin);
        server.registerChannels();
        active = server;
        server.rollbackLifecycle.install(plugin);
        TempBlockSync.install(server);
        DirectBlockSync.install(server);
        TempFallingBlockSync.install(server);
        VelocitySync.install(server);
        AbilityStateSync.install(server);
        GlidingStateSync.install(server);
        AbilityCheckpointSync.install(server);
        AbilityRemovalSync.install(server);
        CooldownSync.install(server);
        PlayerStatusSync.install(server);
        server.rebuildPermissionCandidates();
        server.scheduleTicker();
        server.requestSnapshotRebuild(false);
        plugin.getLogger().info("Fabric client prediction endpoint enabled on Paper (protocol " + PaperPredictionProtocol.VERSION + ")");
        return server;
    }

    private static CommonInputHandler.InputResult handleVanilla(
            Player player, PaperPredictionProtocol.InputKind kind,
            Supplier<CommonInputHandler.InputResult> nativeInput) {
        PaperPredictionServer server = active;
        if (server == null || player == null || nativeInput == null) {
            return nativeInput == null ? CommonInputHandler.InputResult.pass() : nativeInput.get();
        }
        return server.handleVanilla0(player, kind, nativeInput);
    }

    public static CommonInputHandler.InputResult handleLeftClick(
            Player player, Supplier<CommonInputHandler.InputResult> nativeInput) {
        return handleVanilla(player, PaperPredictionProtocol.InputKind.LEFT_CLICK, nativeInput);
    }

    public static CommonInputHandler.InputResult handleRightClick(
            Player player, boolean block, Supplier<CommonInputHandler.InputResult> nativeInput) {
        return handleVanilla(player, block ? PaperPredictionProtocol.InputKind.RIGHT_CLICK_BLOCK
                : PaperPredictionProtocol.InputKind.RIGHT_CLICK, nativeInput);
    }

    public static CommonInputHandler.InputResult handleRightClickEntity(
            Player player, Supplier<CommonInputHandler.InputResult> nativeInput) {
        return handleVanilla(player, PaperPredictionProtocol.InputKind.RIGHT_CLICK_ENTITY, nativeInput);
    }

    public static CommonInputHandler.InputResult handleSneak(
            Player player, boolean sneakingNow, Supplier<CommonInputHandler.InputResult> nativeInput) {
        return handleVanilla(player, sneakingNow ? PaperPredictionProtocol.InputKind.SNEAK_START
                : PaperPredictionProtocol.InputKind.SNEAK_STOP, nativeInput);
    }

    public static CommonInputHandler.InputResult handleSwapHands(
            Player player, Supplier<CommonInputHandler.InputResult> nativeInput) {
        return handleVanilla(player, PaperPredictionProtocol.InputKind.SWAP_HANDS, nativeInput);
    }

    public static Player predictedEffectOwner() {
        if (AuthoritativeEffects.isBroadcasting()) return null;
        PaperPredictionServer server = active;
        if (server == null) return null;
        UUID owner = EFFECT_OWNER.get();
        if (owner != null && !Boolean.TRUE.equals(EFFECT_PREDICTED.get())) return null;
        if (owner == null) {
            CoreAbility ability = AbilityExecutionContext.current();
            Action action = ability == null ? null : server.actionForEffect(ability);
            if (action != null && !action.locallyPredicted) return null;
            owner = action == null ? null : action.owner;
        }
        Session session = owner == null ? null : server.sessions.get(owner);
        return session != null && (session.capabilities & CAPABILITY_EXACT) != 0 ? Bukkit.getPlayer(owner) : null;
    }

    public static Player predictedSoundEffectOwner() {
        if (ConfirmedHitEffects.isBroadcastingAuthoritativeSound()) return null;
        // Predicted clients already ran ordinary ability sounds locally. A
        // server-confirmed hit sound uses ConfirmedHitEffects to opt back into
        // the broadcast without granting the client contact authority.
        return predictedEffectOwner();
    }

    /** Adds only server-validated historical player boxes to a real ability query. */
    public static void augmentNearbyPlayers(
            final World world, final org.bukkit.util.BoundingBox query,
            final CoreAbility ability,
            final Predicate<com.projectkorra.projectkorra.platform.mc.entity.Entity> filter,
            final Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.Entity> result) {
        if (RollbackDomain.active()) throw new IllegalStateException("Historical hit claims cannot augment a rollback query");
        final PaperPredictionServer server = active;
        if (server == null || world == null || query == null || result == null) return;
        final Action action = ability == null ? null : server.actionForEffect(ability);
        if (action == null) return;
        if (server.rollbackInputs.blocksLegacy(action.owner)) return;
        final Iterator<Claim> claims = action.claims.values().iterator();
        while (claims.hasNext()) {
            final Claim claim = claims.next();
            if (server.rollbackInputs.blocksLegacy(claim.target)) { claims.remove(); continue; }
            if (claim.expiresTick < server.tick) {
                claims.remove();
                continue;
            }
            final Player target = Bukkit.getPlayer(claim.target);
            if (target == null || target.isDead() || target.getWorld() != world) continue;
            if (HitRegistrationPolicy.forTarget(ability, isExactClient(target.getUniqueId()))
                    == HitRegistrationPolicy.SERVER_CURRENT) {
                claims.remove();
                continue;
            }
            if (!query.clone().expand(CLAIM_QUERY_TOLERANCE).overlaps(claim.rewoundBox)) continue;
            final com.projectkorra.projectkorra.platform.mc.entity.Entity wrapped = BukkitMC.entity(target);
            if (wrapped == null || filter != null && !filter.test(wrapped)) continue;
            result.put(target.getUniqueId(), wrapped);
            // A claim may extend one real query, once. Consuming it here keeps
            // abilities with several entity scans in one progress pass from
            // applying the same damage/velocity impulse repeatedly.
            claims.remove();
        }
    }

    public static Runnable contextual(Runnable task) {
        PaperPredictionServer server = active;
        Player owner = predictedEffectOwner();
        if (owner == null) return task;
        UUID uuid = owner.getUniqueId();
        Long sequence = contextualActionSequence(server, uuid);
        return () -> runWithOwnerAndSequence(uuid, sequence, task);
    }

    public static <T> Callable<T> contextual(Callable<T> task) {
        PaperPredictionServer server = active;
        Player owner = predictedEffectOwner();
        if (owner == null) return task;
        UUID uuid = owner.getUniqueId();
        Long sequence = contextualActionSequence(server, uuid);
        return () -> {
            UUID previous = EFFECT_OWNER.get();
            Boolean previousPredicted = EFFECT_PREDICTED.get();
            Long previousSequence = INPUT_SEQUENCE.get();
            EFFECT_OWNER.set(uuid);
            EFFECT_PREDICTED.set(Boolean.TRUE);
            if (sequence == null) INPUT_SEQUENCE.remove();
            else INPUT_SEQUENCE.set(sequence);
            try {
                return task.call();
            } finally {
                if (previous == null) EFFECT_OWNER.remove();
                else EFFECT_OWNER.set(previous);
                if (previousPredicted == null) EFFECT_PREDICTED.remove();
                else EFFECT_PREDICTED.set(previousPredicted);
                if (previousSequence == null) INPUT_SEQUENCE.remove();
                else INPUT_SEQUENCE.set(previousSequence);
            }
        };
    }

    private static Long contextualActionSequence(final PaperPredictionServer server, final UUID owner) {
        if (server == null || owner == null) return null;
        final Long current = INPUT_SEQUENCE.get();
        final Session session = server.sessions.get(owner);
        if (current != null && session != null && session.actions.containsKey(current)) return current;
        final CoreAbility ability = AbilityExecutionContext.current();
        final Action action = ability == null ? null : server.actionForEffect(ability);
        return action != null && owner.equals(action.owner) ? action.sequence : null;
    }

    /**
     * GeneralMethods reloads cancel this plugin's tasks; restore the endpoint ticker.
     */
    public static void schedulerReset() {
        PaperPredictionServer server = active;
        if (server != null) server.scheduleTicker();
    }

    public static boolean isExactClient(final UUID playerId) {
        final PaperPredictionServer server = active;
        if (server == null || playerId == null) return false;
        final Session session = server.sessions.get(playerId);
        return session != null && (session.capabilities & CAPABILITY_EXACT) != 0;
    }

    /**
     * Publishes the destination-world TempBlock ledger in the same server
     * transaction as Bukkit's world-change event. It is also requested again
     * by the client after its local runtime restart, covering either packet
     * ordering and high-latency transitions without waiting for the periodic
     * self-heal snapshot.
     */
    public static void synchronizeWorld(final Player player) {
        final PaperPredictionServer server = active;
        if (server == null || player == null) return;
        final Session session = server.sessions.get(player.getUniqueId());
        if (session != null && session.ready) {
            server.sendWorldState(player, session);
            server.sendTempBlockSnapshot(player, session);
        }
    }

    /**
     * Replaces an exact-prediction client's cooldowns with the cooldown map
     * currently held by Paper. This is intended for external/admin mutations
     * where an intentional removal must override a locally predicted cooldown
     * generation.
     */
    public static void synchronizeCooldowns(final Player player) {
        final PaperPredictionServer server = active;
        if (server == null || player == null) return;
        final Session session = server.sessions.get(player.getUniqueId());
        if (session == null || !session.ready) return;
        final BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        session.predictedCooldowns.clear();
        server.send(player, PaperPredictionProtocol.COOLDOWN_SYNC,
                PaperPredictionProtocol.cooldownSync(session.session, System.currentTimeMillis(),
                        PaperPredictionSnapshot.cooldowns(bending)));
    }
}
