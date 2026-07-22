package com.projectkorra.projectkorra.prediction.server;

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
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.AbilityCheckpointSync;
import com.projectkorra.projectkorra.prediction.state.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
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

/**
 * Authoritative Paper endpoint for Fabric client prediction.
 */
public final class PaperPredictionServer implements PluginMessageListener, Runnable, TempBlockSync.Listener,
        TempFallingBlockSync.Listener, CooldownSync.Listener, VelocitySync.Listener,
        AbilityRemovalSync.Listener, DirectBlockSync.Listener,
        AbilityStateSync.Listener, AbilityCheckpointSync.Listener {
    public static final int MAX_REWIND_TICKS = 12;
    private static final int CAPABILITY_EXACT = 8;
    private static final int TEMP_BLOCK_OPS_PER_PACKET = 4;
    private static final int MAX_PREDICTION_PERMISSIONS = 512;
    private static final int CLAIMS_PER_SECOND = 48;
    private static final double CLAIM_CONTACT_TOLERANCE = 0.75;
    private static final double CLAIM_QUERY_TOLERANCE = 1.0;
    private static final double MAX_CLAIM_DISTANCE_SQUARED = 160.0 * 160.0;
    private static final ThreadLocal<UUID> EFFECT_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> EFFECT_PREDICTED = new ThreadLocal<>();
    private static final ThreadLocal<Long> INPUT_SEQUENCE = new ThreadLocal<>();
    private static volatile PaperPredictionServer active;

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<EntityFrame>> playerHistory = new HashMap<>();
    private final Map<CoreAbility, Action> abilityActions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<CoreAbility, Action> abilityCreationActions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<CoreAbility> predictedOwnershipTransfers = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Map<Long, Action> tempLayerActions = new HashMap<>();
    private final Map<Long, TempEffectIdentity> tempLayerEffects = new HashMap<>();
    private final Set<Long> serverOwnedTempLayers = new HashSet<>();
    private final Set<Long> ownershipBridgeTempLayers = new HashSet<>();
    private final List<PendingTempBlock> pendingTempBlocks = new ArrayList<>();
    private final List<PendingAbilityRemoval> pendingAbilityRemovals = new ArrayList<>();
    private final Map<UUID, Integer> uncorrelatedExternalVelocityOrdinals = new HashMap<>();
    private final AtomicBoolean snapshotBuildRunning = new AtomicBoolean();
    private volatile List<PaperPredictionProtocol.ConfigEntry> publicConfig = List.of();
    private volatile List<PaperPredictionProtocol.AbilityProfile> profiles = List.of();
    private volatile long configEpoch;
    private volatile boolean snapshotReady;
    private long tick;
    private BukkitTask task;

    private PaperPredictionServer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static PaperPredictionServer start(JavaPlugin plugin) {
        PaperPredictionServer server = new PaperPredictionServer(plugin);
        server.registerChannels();
        active = server;
        TempBlockSync.install(server);
        DirectBlockSync.install(server);
        TempFallingBlockSync.install(server);
        VelocitySync.install(server);
        AbilityStateSync.install(server);
        AbilityCheckpointSync.install(server);
        AbilityRemovalSync.install(server);
        CooldownSync.install(server);
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
        final PaperPredictionServer server = active;
        if (server == null || world == null || query == null || result == null) return;
        final Action action = ability == null ? null : server.actionForEffect(ability);
        if (action == null) return;
        final Iterator<Claim> claims = action.claims.values().iterator();
        while (claims.hasNext()) {
            final Claim claim = claims.next();
            if (claim.expiresTick < server.tick) {
                claims.remove();
                continue;
            }
            final Player target = Bukkit.getPlayer(claim.target);
            if (target == null || target.isDead() || target.getWorld() != world) continue;
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

    private static void runWithOwner(UUID owner, Runnable task) {
        runWithOwner(owner, true, task);
    }

    private static void runWithOwnerAndSequence(final UUID owner, final Long sequence,
                                                final Runnable task) {
        final Long previousSequence = INPUT_SEQUENCE.get();
        if (sequence == null) INPUT_SEQUENCE.remove();
        else INPUT_SEQUENCE.set(sequence);
        try {
            if (sequence == null || sequence <= 0L) runWithOwner(owner, true, task);
            else {
                final PaperPredictionServer server = active;
                final Session session = server == null ? null : server.sessions.get(owner);
                final Action action = session == null ? null : session.actions.get(sequence);
                final long seed = action == null ? PredictionDeterminism.currentSeed() : action.deterministicSeed;
                PredictionDeterminism.run(sequence, seed > 0L ? seed : sequence,
                        () -> runWithOwner(owner, true, task));
            }
        } finally {
            if (previousSequence == null) INPUT_SEQUENCE.remove();
            else INPUT_SEQUENCE.set(previousSequence);
        }
    }

    private static void runWithOwner(UUID owner, boolean locallyPredicted, Runnable task) {
        UUID previous = EFFECT_OWNER.get();
        Boolean previousPredicted = EFFECT_PREDICTED.get();
        EFFECT_OWNER.set(owner);
        EFFECT_PREDICTED.set(locallyPredicted);
        try {
            task.run();
        } finally {
            if (previous == null) EFFECT_OWNER.remove();
            else EFFECT_OWNER.set(previous);
            if (previousPredicted == null) EFFECT_PREDICTED.remove();
            else EFFECT_PREDICTED.set(previousPredicted);
        }
    }

    private static boolean createdAnyAbility(Set<CoreAbility> before, UUID owner) {
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability) && ability.getPlayer() != null
                    && ability.getPlayer().getUniqueId().equals(owner)) return true;
        }
        return false;
    }

    private static List<String> activeFlightAbilities(UUID playerId) {
        return CoreAbility.getAbilitiesByInstances().stream()
                .filter(ability -> !ability.isRemoved() && ability.getPlayer() != null
                        && playerId.equals(ability.getPlayer().getUniqueId()))
                .map(CoreAbility::getName)
                .filter(name -> name.equalsIgnoreCase("AirScooter") || name.equalsIgnoreCase("AirSpout")
                        || name.equalsIgnoreCase("WaterSpout") || name.equalsIgnoreCase("FireJet")
                        || name.equalsIgnoreCase("Flight"))
                .map(name -> name.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
    }

    private static Set<CoreAbility> identitySet(Iterable<CoreAbility> abilities) {
        Set<CoreAbility> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CoreAbility ability : abilities) result.add(ability);
        return result;
    }

    private static Vector direction(float yaw, float pitch) {
        return new Location(null, 0, 0, 0, yaw, pitch).getDirection().normalize();
    }

    private static boolean isSneakTransition(PaperPredictionProtocol.InputKind kind) {
        return kind == PaperPredictionProtocol.InputKind.SNEAK_START || kind == PaperPredictionProtocol.InputKind.SNEAK_STOP;
    }

    private static List<String> inputVetoCooldowns(final String ability,
                                                   final PaperPredictionProtocol.InputKind kind) {
        if (ability == null || ability.isBlank()) return List.of();
        if (ability.equalsIgnoreCase("PhaseChange")) {
            if (kind == PaperPredictionProtocol.InputKind.LEFT_CLICK) {
                return List.of(ability, "PhaseChangeFreeze");
            }
            if (kind == PaperPredictionProtocol.InputKind.SNEAK_START) {
                return List.of(ability, "PhaseChangeMelt");
            }
        }
        return List.of(ability);
    }

    private static String logicalInputAbility(com.projectkorra.projectkorra.platform.mc.entity.Player player,
                                              BendingPlayer bending, PaperPredictionProtocol.InputKind kind,
                                              String fallback) {
        if (player == null || bending == null) return fallback == null ? "" : fallback;
        if (kind == PaperPredictionProtocol.InputKind.SNEAK_START && !CoreAbility.hasAbility(player, FastSwim.class)) {
            CoreAbility bound = bending.getBoundAbility();
            CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
            if ((bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive)) {
                return "FastSwim";
            }
        }
        String multi = MultiAbilityManager.getBoundMultiAbility(player);
        if (multi != null && !multi.isBlank()) return multi;
        String selected = fallback == null ? "" : fallback;
        if (selected.equalsIgnoreCase("FireBlast") && isSneakTransition(kind)) {
            return "FireBlastCharged";
        }
        if (selected.isBlank() && kind == PaperPredictionProtocol.InputKind.LEFT_CLICK
                && bending.isToggled() && CoreAbility.getAbility(WallRun.class) != null) {
            return "WallRun";
        }
        return selected;
    }

    private static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName())
                || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    private static String materialName(Material material) {
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    private static String worldKey(com.projectkorra.projectkorra.platform.mc.World world) {
        if (world == null || !(world.handle() instanceof World bukkitWorld)) return "";
        return bukkitWorld.getKey().toString();
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    public void stop() {
        if (task != null) task.cancel();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.HELLO, this);
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
        ownershipBridgeTempLayers.clear();
        pendingTempBlocks.clear();
        pendingAbilityRemovals.clear();
        playerHistory.clear();
        TempBlockSync.clear(this);
        DirectBlockSync.clear(this);
        TempFallingBlockSync.clear(this);
        VelocitySync.clear(this);
        AbilityStateSync.clear(this);
        AbilityCheckpointSync.clear(this);
        AbilityRemovalSync.clear(this);
        CooldownSync.clear(this);
        if (active == this) active = null;
    }

    private void registerChannels() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.HELLO, this);
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
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.TEMP_FALLING_BLOCK);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.TEMP_FALLING_BLOCK_PREPARE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.DIRECT_BLOCK);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.ABILITY_REMOVED);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.ABILITY_TRANSFER);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.STATE_DIRECTIVE);
    }

    @Override
    public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) {
        // A self-predicted cooldown starts on the input frame. Re-sending its
        // later Paper expiry would extend it by network latency and make the
        // client wait after its own exact common lifecycle has completed.
        Player predictedOwner = predictedEffectOwner();
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
        Player predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUniqueId().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, ability == null ? "" : ability, "", 0L, false, Double.NaN);
    }

    @Override
    public void onAirBlastReset(BendingPlayer player) {
        Player predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUniqueId().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, "", "", 0L, true, Double.NaN);
    }

    @Override
    public void onAirBlastRegenerated(BendingPlayer player) {
        sendDirective(player, "", "", 0L, false, player == null ? Double.NaN : player.getAirBlastDecay());
    }

    private void sendDirective(BendingPlayer bending, String removedCooldown, String addedCooldown,
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
            // after a layer was created or re-handshake mid-ability. Re-send
            // the active in-range ledger so authority self-heals without
            // waiting for an ability-specific update.
            for (Session session : sessions.values()) {
                final Player player = Bukkit.getPlayer(session.player);
                if (player != null) {
                    sendWorldState(player, session);
                    sendTempBlockSnapshot(player, session);
                }
            }
        }
        if (tick % 100 == 0) {
            requestSnapshotRebuild(true);
        }
    }

    private CommonInputHandler.InputResult handleVanilla0(
            final Player player, final PaperPredictionProtocol.InputKind kind,
            final Supplier<CommonInputHandler.InputResult> nativeInput) {
        final Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.ready) return nativeInput.get();
        return processInput(player, session, kind, nativeInput);
    }

    private Action actionForEffect(CoreAbility ability) {
        if (ability == null || ability.getPlayer() == null) return null;
        final UUID ownerId = ability.getPlayer().getUniqueId();
        Action action = currentInputAction(ownerId);
        if (action != null) return action;
        action = abilityActions.get(ability);
        if (action != null) return action;
        Session session = sessions.get(ownerId);
        if (session == null) return null;

        // Child abilities created during a later progress callback inherit the
        // parent's deterministic input sequence in CoreAbility. Resolve that
        // exact identity before the legacy name fallback so RaiseEarthWall's
        // RaiseEarth children and Shockwave's Ripples use the same causal
        // ordinals as Fabric.
        final long inherited = ability.getPredictionActionSequence();
        action = inherited <= 0L ? null : session.actions.get(inherited);
        if (action != null) {
            abilityActions.put(ability, action);
            abilityCreationActions.putIfAbsent(ability, action);
            return action;
        }
        List<Action> recent = new ArrayList<>(session.actions.values());
        // Long-lived abilities (notably PhaseChange) can emit TempBlocks well
        // after the old four-tick fallback. Keep an exact owner + ability-name
        // association for the full client action lifetime so metadata never
        // degrades to sequence 0 and underlying WATER authority.
        for (int i = recent.size() - 1; i >= 0; i--) {
            Action candidate = recent.get(i);
            if (candidate.locallyPredicted
                    && candidate.ability.equalsIgnoreCase(ability.getName())) {
                abilityActions.put(ability, candidate);
                return candidate;
            }
        }
        // Combo/runtime names may differ from the bound input. Their fallback
        // remains deliberately short to avoid assigning an unrelated action.
        for (int i = recent.size() - 1; i >= 0; i--) {
            Action candidate = recent.get(i);
            if (candidate.locallyPredicted && tick - candidate.acceptedTick <= 4) {
                abilityActions.put(ability, candidate);
                return candidate;
            }
        }
        return null;
    }

    /** Mirrors the client's INPUT_ACTION precedence for synchronous effects. */
    private Action currentInputAction(final UUID ownerId) {
        final Long sequence = INPUT_SEQUENCE.get();
        if (ownerId == null || sequence == null) return null;
        final Session session = sessions.get(ownerId);
        return session == null ? null : session.actions.get(sequence);
    }

    @Override
    public void beforeChange(final CoreAbility ability, final Block block,
                             final BlockData replacement, final boolean packetExpected) {
        if (block == null || block.getWorld() == null || replacement == null) return;
        final DirectBlockSync.EarthLifecycle lifecycle = DirectBlockSync.currentEarthLifecycle();
        final UUID ownerId = ability != null && ability.getPlayer() != null
                ? ability.getPlayer().getUniqueId()
                : lifecycle != null && lifecycle.valid() ? lifecycle.ownerId() : EFFECT_OWNER.get();
        final Session session = ownerId == null ? null : sessions.get(ownerId);
        Action action = currentInputAction(ownerId);
        if (action == null && ability != null) action = actionForEffect(ability);
        if (action != null && !action.owner.equals(ownerId)) return;
        final long actionSequence = action != null ? action.sequence
                : lifecycle != null && lifecycle.valid() ? lifecycle.actionSequence() : 0L;
        final String abilityName = ability != null ? ability.getName()
                : lifecycle != null && lifecycle.valid() ? lifecycle.ability()
                : action == null ? "" : action.ability;
        if (!DirectBlockSync.isPredictable(ability, abilityName)) return;
        final Player owner = Bukkit.getPlayer(ownerId);
        if (actionSequence <= 0L || session == null || owner == null
                || (session.capabilities & CAPABILITY_EXACT) == 0) return;
        final DirectBlockCause cause = new DirectBlockCause(actionSequence,
                abilityName.toLowerCase(Locale.ROOT));
        final int ordinal;
        synchronized (session.directBlockOrdinals) {
            ordinal = session.directBlockOrdinals.merge(cause, 1, Integer::sum);
            while (session.directBlockOrdinals.size() > 4_096) {
                session.directBlockOrdinals.remove(session.directBlockOrdinals.keySet().iterator().next());
            }
        }
        if (action != null) action.directBlockOrdinals.put(cause.ability, ordinal);
        if (!packetExpected) return;
        send(owner, PaperPredictionProtocol.DIRECT_BLOCK,
                PaperPredictionProtocol.directBlock(tick, actionSequence, ordinal, ownerId,
                        abilityName, worldKey(block.getWorld()), block.getX(), block.getY(), block.getZ(),
                        TempBlockSync.encode(replacement), lifecycle != null && lifecycle.valid()));
    }

    private void scheduleTicker() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1, 1);
    }

    @Override
    public void beforeWorldChange(final TempBlockSync.Change change) {
        // This custom payload is queued before setBlockData can emit a vanilla
        // packet. Fabric therefore knows the causal action ownership before it
        // decides whether the caster should conceal Paper's physical layer.
        queueTempBlock(change);
        flushTempBlocks();
    }

    @Override
    public void onChange(TempBlockSync.Change change) {
        // Physical changes were already announced by beforeWorldChange using
        // the same revision. Publish metadata-only layers and expiry changes
        // here; duplicating physical operations is unnecessary.
        if (change.packetExpected()) return;
        queueTempBlock(change);
    }

    private PendingTempBlock queueTempBlock(final TempBlockSync.Change change) {
        PaperPredictionProtocol.TempOperation wireOperation = switch (change.operation()) {
            case CREATE -> PaperPredictionProtocol.TempOperation.CREATE;
            case UPDATE_EXPIRY -> PaperPredictionProtocol.TempOperation.UPDATE_EXPIRY;
            case REVERT -> PaperPredictionProtocol.TempOperation.REVERT;
            case DISCARD -> PaperPredictionProtocol.TempOperation.DISCARD;
        };
        Block block = change.block();
        CoreAbility effectiveAbility = change.ability() == null ? AbilityExecutionContext.current() : change.ability();
        final UUID effectOwner = change.ownerId() == null ? EFFECT_OWNER.get() : change.ownerId();
        Action currentAction = currentInputAction(effectOwner);
        if (currentAction == null && effectiveAbility != null) currentAction = actionForEffect(effectiveAbility);
        final boolean unpredictedOwnershipTransfer = effectiveAbility != null
                && effectiveAbility.hasTransferredOwnership()
                && (!effectiveAbility.supportsPredictedOwnershipTransfer()
                || !predictedOwnershipTransfers.contains(effectiveAbility)) && effectOwner != null
                && effectiveAbility.getPlayer() != null
                && effectOwner.equals(effectiveAbility.getPlayer().getUniqueId());
        final UUID worldId = block.getWorld() != null && block.getWorld().handle() instanceof World world
                ? world.getUID() : null;
        if (worldId == null) return null;

        Action action = tempLayerActions.get(change.layerId());
        if (action == null && !serverOwnedTempLayers.contains(change.layerId())
                && change.operation() != TempBlockSync.Operation.REVERT
                && change.operation() != TempBlockSync.Operation.DISCARD) {
            if (currentAction != null && currentAction.owner.equals(effectOwner)) {
                tempLayerActions.put(change.layerId(), currentAction);
                action = currentAction;
            } else {
                // Only a supported, accepted native action enters the map.
                // Effects with no such causal action remain vanilla-visible.
                serverOwnedTempLayers.add(change.layerId());
            }
        }
        TempEffectIdentity effect = tempLayerEffects.get(change.layerId());
        if (effect == null && action != null
                && change.operation() != TempBlockSync.Operation.REVERT
                && change.operation() != TempBlockSync.Operation.DISCARD) {
            final String semanticAbility = change.effectAbility() == null || change.effectAbility().isBlank()
                    ? effectiveAbility == null ? action.ability : effectiveAbility.getName()
                    : change.effectAbility();
            // EarthSmash supplies a logical draw-frame and shape-slot. Preserve
            // that exact identity so a missing physical piece cannot shift all
            // later pieces. Other abilities keep their generic action ordinal.
            final boolean stableEarthSmashSlot = effectiveAbility instanceof EarthSmash
                    && change.effectStep() > 0L && change.effectOrdinal() > 0;
            effect = stableEarthSmashSlot
                    ? new TempEffectIdentity(semanticAbility, change.effectStep(),
                    change.effectOrdinal())
                    : new TempEffectIdentity(semanticAbility, 0L, ++action.tempBlockOrdinal);
            tempLayerEffects.put(change.layerId(), effect);
        }
        final String effectAbility = effect == null ? change.effectAbility() : effect.ability;
        final long effectStep = effect == null ? change.effectStep() : effect.step;
        final int effectOrdinal = effect == null ? change.effectOrdinal() : effect.ordinal;
        // Ownership is a property of the complete ability lifecycle, not of
        // whether this particular progress tick can still find its input
        // Action object. Water normally retained that association; moved and
        // delayed earth frequently did not. Mark every layer from an ability
        // the owner's client advertised as supported, while leaving unknown
        // server-only addons fully vanilla-visible.
        // Redirection can transfer a server ability that never existed in the
        // new owner's client runtime. Its cached creation action still belongs
        // to the old owner; advertising the new layers as client-predicted
        // would hide Paper's only copy and leave an invisible/ghost trail.
        final UUID predictedOwner = unpredictedOwnershipTransfer
                || ownershipBridgeTempLayers.contains(change.layerId())
                ? null : predictedTempBlockOwner(change.ownerId(), action, effectAbility);
        final Map<UUID, BlockData> ownerViews = predictedOwnerViews(block, predictedOwner, change.data());
        final PendingTempBlock pending = new PendingTempBlock(worldId,
                new PaperPredictionProtocol.TempBlockOp(wireOperation, worldKey(block.getWorld()),
                block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(change.data()),
                (change.operation() == TempBlockSync.Operation.REVERT
                        || change.operation() == TempBlockSync.Operation.DISCARD) ? 0L : change.revertAtMillis(),
                action == null ? 0L : action.sequence,
                effectAbility, change.effectState(), effectStep, effectOrdinal,
                change.layerId(), change.revision(), predictedOwner,
                TempBlockSync.encode(change.data()),
                change.packetExpected()), Map.copyOf(ownerViews));
        pendingTempBlocks.add(pending);
        if (change.operation() == TempBlockSync.Operation.REVERT
                || change.operation() == TempBlockSync.Operation.DISCARD) {
            tempLayerActions.remove(change.layerId());
            tempLayerEffects.remove(change.layerId());
            serverOwnedTempLayers.remove(change.layerId());
            ownershipBridgeTempLayers.remove(change.layerId());
        }
        return pending;
    }

    private UUID predictedTempBlockOwner(final UUID layerOwner, final Action action,
                                         final String abilityName) {
        final UUID candidate = layerOwner != null ? layerOwner : action == null ? null : action.owner;
        if (candidate == null) return null;
        final Session session = sessions.get(candidate);
        if (session == null || !session.ready || (session.capabilities & CAPABILITY_EXACT) == 0) return null;
        if (action != null && candidate.equals(action.owner)) return candidate;
        return abilityName != null && session.supportedAbilities.contains(abilityName.toLowerCase(Locale.ROOT))
                ? candidate : null;
    }

    private Map<UUID, BlockData> predictedOwnerViews(final Block block, final UUID closingOwner,
                                                     final BlockData fallbackData) {
        final Map<UUID, BlockData> views = new HashMap<>(TempBlock.getOwnerViews(block, closingOwner));
        if (closingOwner != null) {
            views.put(closingOwner, predictedViewerData(block, closingOwner, fallbackData));
        }
        return views;
    }

    private BlockData predictedViewerData(final Block block, final UUID viewer,
                                          final BlockData fallbackData) {
        final BlockData visible = TempBlock.getVisibleData(block, viewer);
        return visible != null ? visible
                : fallbackData == null ? block.getBlockData().clone() : fallbackData.clone();
    }

    @Override
    public int beforeSpawn(final CoreAbility ability,
                           final com.projectkorra.projectkorra.platform.mc.Location location,
                           final BlockData blockData) {
        if (ability.getPlayer() == null || location == null || location.getWorld() == null
                || blockData == null) return 0;
        final UUID ownerId = ability.getPlayer().getUniqueId();
        final Session ownerSession = sessions.get(ownerId);
        final Player nativeOwner = Bukkit.getPlayer(ownerId);
        if (ownerSession == null || nativeOwner == null
                || (ownerSession.capabilities & CAPABILITY_EXACT) == 0) return 0;

        Action action = currentInputAction(ownerId);
        if (action == null) action = actionForEffect(ability);
        if (action == null || !ownerId.equals(action.owner)) return 0;

        final int ordinal = ++action.tempFallingBlockOrdinal;
        send(nativeOwner, PaperPredictionProtocol.TEMP_FALLING_BLOCK_PREPARE,
                PaperPredictionProtocol.tempFallingBlockPrepare(tick, action.sequence, ordinal, ownerId,
                        ability.getName(), worldKey(location.getWorld()), location.getX(), location.getY(),
                        location.getZ(), TempBlockSync.encode(blockData)));
        return ordinal;
    }

    @Override
    public void onSpawn(final CoreAbility ability,
                        final com.projectkorra.projectkorra.platform.mc.entity.FallingBlock fallingBlock,
                        final int spawnOrdinal) {
        if (ability.getPlayer() == null || fallingBlock.getEntityId() <= 0 || spawnOrdinal <= 0) return;
        final UUID ownerId = ability.getPlayer().getUniqueId();
        final Session ownerSession = sessions.get(ownerId);
        final Player nativeOwner = Bukkit.getPlayer(ownerId);
        if (ownerSession == null || nativeOwner == null
                || (ownerSession.capabilities & CAPABILITY_EXACT) == 0) return;
        Action action = currentInputAction(ownerId);
        if (action == null) action = actionForEffect(ability);
        if (action == null || !ownerId.equals(action.owner)) return;

        send(nativeOwner, PaperPredictionProtocol.TEMP_FALLING_BLOCK,
                PaperPredictionProtocol.tempFallingBlock(tick, action.sequence, spawnOrdinal, ownerId,
                        fallingBlock.getEntityId(), ability.getName()));
    }

    @Override
    public void onVelocity(Ability ability,
                           com.projectkorra.projectkorra.platform.mc.entity.Entity target,
                           com.projectkorra.projectkorra.platform.mc.util.Vector velocity) {
        if (!(ability instanceof CoreAbility coreAbility) || ability.getPlayer() == null || target == null) return;
        UUID ownerId = ability.getPlayer().getUniqueId();
        UUID targetId = target.getUniqueId();
        Session ownerSession = sessions.get(ownerId);
        Session targetSession = sessions.get(targetId);
        Player nativeTarget = Bukkit.getPlayer(targetId);
        Player nativeOwner = Bukkit.getPlayer(ownerId);
        if (nativeTarget == null) return;

        // Velocity ownership must be exact. Do not use actionForEffect's
        // recent-action fallback here: a nearby unrelated input must never
        // acquire this impulse.
        Action action = currentInputAction(ownerId);
        if (action == null) action = abilityActions.get(coreAbility);
        if (action == null) {
            final Session session = sessions.get(ownerId);
            final long inherited = coreAbility.getPredictionActionSequence();
            action = session == null || inherited <= 0L ? null : session.actions.get(inherited);
            if (action != null) abilityActions.put(coreAbility, action);
        }
        if (action == null || !action.owner.equals(ownerId)) {
            // The target still needs an exact ownership fence when the
            // attacker is vanilla, is not prediction-ready, or owns a
            // long-lived server ability whose creation action has expired.
            // This is external authority, so no local action correlation is
            // needed (and none is invented).
            if (!ownerId.equals(targetId) && targetSession != null
                    && (targetSession.capabilities & CAPABILITY_EXACT) != 0) {
                final int ordinal = uncorrelatedExternalVelocityOrdinals.merge(
                        targetId, 1, Integer::sum);
                flushAbilityRemovals();
                send(nativeTarget, PaperPredictionProtocol.VELOCITY_OWNER_V2,
                        PaperPredictionProtocol.velocityOwnerV2(tick, 0L, ordinal, ownerId, targetId,
                                nativeTarget.getEntityId(), ability.getName(),
                                velocity.getX(), velocity.getY(), velocity.getZ()));
            }
            return;
        }

        int ordinal = action.velocityOrdinals.merge(targetId, 1, Integer::sum);
        // A hit may synchronously remove target-owned locomotion (FireJet,
        // AirScooter CancelOnHit, etc.) before applying knockback. Publish those
        // exact removals first so the predicting target cannot run the removed
        // movement ability over the following authoritative velocity packet.
        flushAbilityRemovals();
        byte[] receipt = PaperPredictionProtocol.velocityOwnerV2(tick, action.sequence, ordinal, ownerId, targetId,
                nativeTarget.getEntityId(), ability.getName(), velocity.getX(), velocity.getY(), velocity.getZ());
        if (ownerSession != null && nativeOwner != null && (ownerSession.capabilities & CAPABILITY_EXACT) != 0) {
            send(nativeOwner, PaperPredictionProtocol.VELOCITY_OWNER_V2, receipt);
        }
        if (!ownerId.equals(targetId) && targetSession != null && (targetSession.capabilities & CAPABILITY_EXACT) != 0) {
            send(nativeTarget, PaperPredictionProtocol.VELOCITY_OWNER_V2, receipt);
        }
    }

    @Override
    public void beforeWrite(final CoreAbility ability,
                            final com.projectkorra.projectkorra.platform.mc.entity.Player target,
                            final AbilityStateSync.FlightState resultingState) {
        if (target == null) return;
        final UUID targetId = target.getUniqueId();
        final UUID contextualOwner = EFFECT_OWNER.get();
        final UUID ownerId = ability != null && ability.getPlayer() != null
                ? ability.getPlayer().getUniqueId()
                : contextualOwner == null ? targetId : contextualOwner;
        Action action = currentInputAction(ownerId);
        if (action == null && ability != null) action = abilityActions.get(ability);
        if (action == null && ability != null) {
            final Session session = sessions.get(ownerId);
            final long inherited = ability.getPredictionActionSequence();
            action = session == null || inherited <= 0L ? null : session.actions.get(inherited);
            if (action != null) abilityActions.put(ability, action);
        }
        if (action == null || !action.owner.equals(ownerId)) return;
        final Session targetSession = sessions.get(targetId);
        final Player nativeTarget = Bukkit.getPlayer(targetId);
        if (targetSession == null || nativeTarget == null
                || (targetSession.capabilities & CAPABILITY_EXACT) == 0) return;
        final int ordinal = action.abilityStateOrdinals.merge(targetId, 1, Integer::sum);
        send(nativeTarget, PaperPredictionProtocol.ABILITY_STATE_OWNER,
                PaperPredictionProtocol.abilityStateOwner(tick, action.sequence, ordinal,
                        ownerId, targetId, ability == null ? action.ability : ability.getName(),
                        resultingState.flying(), resultingState.allowFlight(), resultingState.flySpeed()));
    }

    @Override
    public void onRemoved(CoreAbility ability, boolean externallyCaused) {
        onRemoved(ability, externallyCaused, false);
    }

    @Override
    public void onRemoved(final CoreAbility ability, final boolean externallyCaused,
                          final boolean predictionRejected) {
        if (ability.getPlayer() == null || !ability.isStarted()) return;
        Action action = abilityCreationActions.get(ability);
        UUID playerId = ability.getPlayer().getUniqueId();
        pendingAbilityRemovals.add(new PendingAbilityRemoval(playerId, ability.getName(),
                AbilityRemovalSync.typeId(ability),
                action != null && action.owner.equals(playerId) && action.locallyPredicted
                        ? action.sequence : 0L,
                externallyCaused, predictionRejected, ability));
    }

    @Override
    public void onOwnerTransferred(final CoreAbility ability, final UUID previousOwner,
                                   final UUID nextOwner) {
        if (ability == null || previousOwner == null || nextOwner == null
                || previousOwner.equals(nextOwner) || !ability.isStarted()) return;
        final Action previousAction = abilityCreationActions.get(ability);
        sendAbilityRemoval(previousOwner, ability.getName(), AbilityRemovalSync.typeId(ability),
                previousAction != null && previousAction.owner.equals(previousOwner)
                        && previousAction.locallyPredicted ? previousAction.sequence : 0L,
                true, false);

        final Action transferAction = currentInputAction(nextOwner);
        if (!ability.supportsPredictedOwnershipTransfer() || transferAction == null
                || !transferAction.owner.equals(nextOwner)) return;
        if (ability instanceof EarthSmash smash) {
            final EarthSmash.PredictionTransfer transfer = smash.capturePredictionTransfer();
            final Session session = sessions.get(nextOwner);
            final Player player = Bukkit.getPlayer(nextOwner);
            if (transfer != null && session != null && session.ready && player != null
                    && (session.capabilities & CAPABILITY_EXACT) != 0) {
                abilityActions.put(ability, transferAction);
                abilityCreationActions.put(ability, transferAction);
                predictedOwnershipTransfers.add(ability);
                for (TempBlock layer : TempBlock.getActiveLayers()) {
                    if (layer.getAbility().orElse(null) == ability) {
                        // These layers were created before the redirect input.
                        // Carrying their old ordinal into the new action can
                        // collide with the first layers drawn by the grabbed
                        // continuation. Keep them as an authoritative bridge;
                        // the next progress frame creates a fresh, action-local
                        // identity on both Paper and Fabric.
                        tempLayerActions.remove(layer.getLayerId());
                        tempLayerEffects.remove(layer.getLayerId());
                        serverOwnedTempLayers.add(layer.getLayerId());
                        ownershipBridgeTempLayers.add(layer.getLayerId());
                    }
                }
                sendEarthSmashState(player, smash, transferAction, transfer, true);
            }
        }
    }

    @Override
    public void onCheckpoint(final CoreAbility ability) {
        if (!(ability instanceof EarthSmash smash) || ability.getPlayer() == null) return;
        final UUID playerId = ability.getPlayer().getUniqueId();
        final Action checkpointAction = abilityActions.getOrDefault(
                ability, abilityCreationActions.get(ability));
        final Session session = sessions.get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        final EarthSmash.PredictionTransfer checkpoint = smash.capturePredictionTransfer();
        if (checkpointAction == null || checkpoint == null || session == null || !session.ready
                || player == null || (session.capabilities & CAPABILITY_EXACT) == 0) return;
        sendEarthSmashState(player, smash, checkpointAction, checkpoint, false);
        // The ordinary cooldown echo was suppressed under the assumption that
        // the client completed the same transition. A restored checkpoint may
        // not have created it, so attach the absolute Paper expiry as well.
        final BendingPlayer bending = ability.getBendingPlayer();
        final long cooldownUntil = bending == null ? -1L : bending.getCooldown(ability.getName());
        if (cooldownUntil > System.currentTimeMillis()) {
            sendDirective(bending, "", ability.getName(), cooldownUntil, false, Double.NaN);
        }
    }

    private void sendEarthSmashState(final Player player, final EarthSmash smash,
                                     final Action action,
                                     final EarthSmash.PredictionTransfer state,
                                     final boolean ownershipTransfer) {
        if (player == null || smash == null || action == null || state == null
                || smash.getLocation() == null || smash.getLocation().getWorld() == null) return;
        send(player, PaperPredictionProtocol.ABILITY_TRANSFER,
                        PaperPredictionProtocol.abilityTransfer(player.getUniqueId(), action.sequence,
                        AbilityRemovalSync.typeId(smash),
                        worldKey(smash.getLocation().getWorld()), ownershipTransfer,
                        action.tempBlockOrdinal, state));
    }

    private void flushAbilityRemovals() {
        if (pendingAbilityRemovals.isEmpty()) return;
        final List<PendingAbilityRemoval> removals = List.copyOf(pendingAbilityRemovals);
        pendingAbilityRemovals.clear();
        for (PendingAbilityRemoval removal : removals) {
            // CoreAbility publishes removal from super.remove(), before the
            // subclass closes its layers. Keep the action association alive
            // through that synchronous cleanup and retire it only now.
            abilityActions.remove(removal.instance);
            abilityCreationActions.remove(removal.instance);
            predictedOwnershipTransfers.remove(removal.instance);
            sendAbilityRemoval(removal.playerId, removal.ability, removal.abilityType,
                    removal.actionSequence, removal.externallyCaused,
                    removal.predictionRejected);
        }
    }

    private void sendAbilityRemoval(final UUID playerId, final String ability,
                                    final String abilityType, final long actionSequence,
                                    final boolean externallyCaused,
                                    final boolean predictionRejected) {
        final Session session = sessions.get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null) return;
        final int remainingTypeInstances = AbilityRemovalSync.activeTypeCount(playerId, abilityType);
        send(player, PaperPredictionProtocol.ABILITY_REMOVED,
                PaperPredictionProtocol.abilityRemoved(playerId, ability, abilityType,
                        actionSequence, externallyCaused, predictionRejected,
                        session.lastSequence,
                        remainingTypeInstances));
        sendState(player, session, true);
    }

    private void onHello(Player player, PaperPredictionProtocol.Hello hello) {
        if (hello.version() != PaperPredictionProtocol.VERSION) return;
        Session session = new Session(player.getUniqueId(), UUID.randomUUID(), hello.capabilities(),
                hello.clientTick(), tick);
        sessions.put(player.getUniqueId(), session);
        if (snapshotReady) sendSnapshot(session);
        else requestSnapshotRebuild(false);
    }

    private void onReady(Player player, PaperPredictionProtocol.Ready ready) {
        final Session session = valid(player, ready.session());
        if (session == null) return;
        final boolean wasReady = session.ready;
        final Set<String> supported = new HashSet<>();
        for (String ability : ready.supportedAbilities()) {
            if (ability != null && !ability.isBlank()) supported.add(ability.toLowerCase(Locale.ROOT));
        }
        session.supportedAbilities = Set.copyOf(supported);
        if (!session.ready) {
            // ClientReady is ordered before every later vanilla input on the
            // same connection. Both endpoints begin their native-event ordinal
            // at zero here; no per-cast client packet participates in casting.
            session.actions.clear();
            session.inputVetoes.clear();
            session.actionTags.clear();
            synchronized (session.directBlockOrdinals) {
                session.directBlockOrdinals.clear();
            }
            session.predictedCooldowns.clear();
            session.lastSequence = 0L;
            session.ready = true;
        }
        if (wasReady) {
            sendWorldState(player, session);
            sendTempBlockSnapshot(player, session);
        }
    }

    private void onInputVeto(Player player, PaperPredictionProtocol.InputVeto veto) {
        final Session session = valid(player, veto.session());
        if (session == null || !session.ready || veto.kind() == null
                || veto.ability() == null || veto.ability().isBlank()
                || veto.sequence() <= 0L || session.inputVetoes.size() >= 128) return;
        // This negative-only payload is written immediately before its vanilla
        // input packet on the same ordered connection. The loaders do not
        // share raw native ordinals, so consume it as a one-shot stream item.
        session.inputVetoes.addLast(veto);
    }

    private void onActionTag(final Player player, final PaperPredictionProtocol.ActionTag tag) {
        final Session session = valid(player, tag.session());
        if (session == null || !session.ready || tag.clientSequence() <= 0L
                || tag.kind() == null || tag.selectedSlot() < 0 || tag.selectedSlot() > 8
                || tag.ability() == null || tag.ability().isBlank()) return;
        session.actionTags.offer(tag);
    }

    private void onHitClaim(final Player player, final PaperPredictionProtocol.HitClaim hit) {
        final Session session = valid(player, hit.session());
        if (session == null || !session.ready
                || !session.claimLimiter.allow(tick, CLAIMS_PER_SECOND)
                || hit.clientSequence() <= 0L || hit.clientTick() < 0L
                || hit.target() == null || hit.ability() == null || hit.ability().isBlank()
                || !finite(hit.x(), hit.y(), hit.z())) return;
        final Action action = findClaimAction(session, hit);
        if (action == null || !action.locallyPredicted
                || tick - action.acceptedTick > 200L
                || action.claims.containsKey(hit.target())) return;
        final Player target = Bukkit.getPlayer(hit.target());
        if (target == null || target == player || target.isDead()
                || target.getEntityId() != hit.entityId()
                || target.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || target.getWorld() != player.getWorld()) return;

        final int defenderPing = target.getPing();
        final long rewindTick = session.mapClientTick(hit.clientTick(), tick,
                player.getPing(), defenderPing);
        final EntityFrame frame = frameAt(target.getUniqueId(), rewindTick);
        if (frame == null || !frame.world.equals(target.getWorld().getUID())) return;
        final Vector contact = new Vector(hit.x(), hit.y(), hit.z());
        if (!frame.box.clone().expand(CLAIM_CONTACT_TOLERANCE).contains(contact)
                || contact.distanceSquared(new Vector(action.eyeX, action.eyeY, action.eyeZ))
                > MAX_CLAIM_DISTANCE_SQUARED) return;
        final int rewindTicks = HitRewind.combinedRewindTicks(
                player.getPing(), defenderPing, MAX_REWIND_TICKS);
        action.claims.put(target.getUniqueId(), new Claim(target.getUniqueId(), rewindTick,
                tick + Math.max(4, rewindTicks), contact, frame.box.clone()));
    }

    private Action findClaimAction(final Session session,
                                   final PaperPredictionProtocol.HitClaim hit) {
        if (hit.serverSequence() > 0L) {
            final Action exact = session.actions.get(hit.serverSequence());
            if (matchesClaimAction(exact, hit)) return exact;
        }
        final List<Action> actions = new ArrayList<>(session.actions.values());
        for (int index = actions.size() - 1; index >= 0; index--) {
            final Action candidate = actions.get(index);
            if (candidate.clientSequence == hit.clientSequence()
                    && matchesClaimAction(candidate, hit)) return candidate;
        }
        for (int index = actions.size() - 1; index >= 0; index--) {
            final Action candidate = actions.get(index);
            if (tick - candidate.acceptedTick <= 4L && matchesClaimAction(candidate, hit)) return candidate;
        }
        return null;
    }

    private static boolean matchesClaimAction(final Action action,
                                              final PaperPredictionProtocol.HitClaim hit) {
        return action != null && hit != null && action.ability.equalsIgnoreCase(hit.ability());
    }

    private void recordPlayerHistory() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            final Deque<EntityFrame> frames = playerHistory.computeIfAbsent(
                    player.getUniqueId(), ignored -> new ArrayDeque<>());
            frames.addLast(new EntityFrame(tick, player.getWorld().getUID(), player.getBoundingBox()));
            while (!frames.isEmpty()
                    && tick - frames.getFirst().serverTick > MAX_REWIND_TICKS + 4L) {
                frames.removeFirst();
            }
        }
        playerHistory.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private EntityFrame frameAt(final UUID playerId, final long wantedTick) {
        final Deque<EntityFrame> frames = playerHistory.get(playerId);
        if (frames == null || frames.isEmpty()) return null;
        EntityFrame best = frames.getFirst();
        long bestDistance = Math.abs(best.serverTick - wantedTick);
        for (EntityFrame frame : frames) {
            final long distance = Math.abs(frame.serverTick - wantedTick);
            if (distance < bestDistance) {
                best = frame;
                bestDistance = distance;
            }
        }
        return best;
    }

    private CommonInputHandler.InputResult processInput(
            Player player, Session session, PaperPredictionProtocol.InputKind kind,
            Supplier<CommonInputHandler.InputResult> nativeInput) {
        if (player == null || !player.isOnline() || sessions.get(player.getUniqueId()) != session) {
            return nativeInput.get();
        }
        final long sequence = ++session.lastSequence;
        final Location origin = player.getEyeLocation();
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        com.projectkorra.projectkorra.platform.mc.entity.Player commonPlayer = BukkitMC.player(player);
        final int selectedSlot = player.getInventory().getHeldItemSlot();
        String fallback = bending == null ? "" : bending.getAbilities().get(selectedSlot + 1);
        String abilityName = logicalInputAbility(commonPlayer, bending, kind, fallback);
        // Consume the stream item for this native callback even when Paper and
        // the client disagree about the bound ability. Retaining a mismatched
        // item would let it poison the next repeated input.
        final long clientActionSequence = session.actionTags.consume(kind, selectedSlot, abilityName);
        final boolean predictable = !abilityName.isBlank()
                && session.supportedAbilities.contains(abilityName.toLowerCase(Locale.ROOT));
        // The client action tag is written immediately before this vanilla
        // packet on the same ordered connection. Attach it before emitting any
        // authoritative receipt so every downstream subsystem receives the
        // exact cross-runtime identity instead of guessing by pose.
        final Action action = predictable
                ? new Action(player.getUniqueId(), sequence, tick,
                kind, selectedSlot, abilityName, origin.getX(), origin.getY(), origin.getZ(),
                origin.getYaw(), origin.getPitch(),
                PredictionActionSeed.from(kind.name(), selectedSlot, abilityName,
                        origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(), origin.getPitch()), true)
                : null;
        if (action != null) {
            action.clientSequence = clientActionSequence;
            session.actions.put(sequence, action);
        }
        send(player, PaperPredictionProtocol.NATIVE_ACTION,
                PaperPredictionProtocol.nativeAction(session.session, sequence,
                        action == null ? 0L : action.clientSequence, tick, kind, selectedSlot,
                        abilityName, origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(),
                        origin.getPitch(), predictable));

        final PaperPredictionProtocol.InputVeto veto = session.inputVetoes.pollFirst();
        final boolean locallyRejectedOnCooldown = predictable && veto != null
                && veto.kind() == kind && abilityName.equalsIgnoreCase(veto.ability());
        // Unknown/server-only addons follow unmodified legacy Paper behavior.
        // They still consume an ordinal so the next supported native event has
        // the same deterministic id on both endpoints.
        if (!predictable) return nativeInput.get();
        Set<CoreAbility> before = identitySet(CoreAbility.getAbilitiesByInstances());
        final ComboManager.AbilityInformation comboBefore = latestComboInput(commonPlayer);
        boolean hadExistingMatchingAbility = before.stream().anyMatch(candidate -> candidate.getPlayer() != null
                && candidate.getPlayer().getUniqueId().equals(player.getUniqueId())
                && matchesInputAbility(candidate, abilityName));
        AbilityActivationManager.TrackingResult trackingResult;
        final AtomicReference<CommonInputHandler.InputResult> nativeResult = new AtomicReference<>();
        Long previousSequence = INPUT_SEQUENCE.get();
        INPUT_SEQUENCE.set(sequence);
        AbilityActivationManager.beginTracking();
        try {
            PredictionDeterminism.run(sequence, action.deterministicSeed, () ->
                    runWithOwner(player.getUniqueId(), true,
                            () -> nativeResult.set(locallyRejectedOnCooldown
                                    ? CooldownSync.runInputVeto(player.getUniqueId(),
                                    inputVetoCooldowns(abilityName, kind), nativeInput)
                                    : nativeInput.get())));
        } finally {
            trackingResult = AbilityActivationManager.finishTrackingResult();
            if (previousSequence == null) INPUT_SEQUENCE.remove();
            else INPUT_SEQUENCE.set(previousSequence);
        }
        final boolean comboRecorded = latestComboInput(commonPlayer) != comboBefore;
        while (session.actions.size() > 128) session.actions.remove(session.actions.keySet().iterator().next());
        boolean createdAnyAbility = false;
        boolean createdMatchingAbility = false;
        final List<String> createdAbilities = new ArrayList<>();
        for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
            if (candidate.getPlayer() == null || !candidate.getPlayer().getUniqueId().equals(player.getUniqueId()))
                continue;
            if (!before.contains(candidate)) {
                createdAnyAbility = true;
                createdAbilities.add(candidate.getName());
                abilityCreationActions.putIfAbsent(candidate, action);
                abilityActions.put(candidate, action);
                if (matchesInputAbility(candidate, abilityName)) createdMatchingAbility = true;
            }
        }
        boolean explicitlyMappedExisting = false;
        for (CoreAbility candidate : trackingResult.affectedAbilities()) {
            if (!before.contains(candidate) || candidate.isRemoved() || candidate.getPlayer() == null
                    || !candidate.getPlayer().getUniqueId().equals(player.getUniqueId())) continue;
            abilityActions.put(candidate, action);
            explicitlyMappedExisting = true;
        }
        // Existing-instance transitions are generic. Any input aimed at an
        // already-running matching ability may mutate it without constructing
        // a second persistent instance (release, throw, redirect, thaw, etc.).
        boolean implicitExistingTransition = trackingResult.handled() && hadExistingMatchingAbility;
        if (!explicitlyMappedExisting && implicitExistingTransition && !createdMatchingAbility) {
            for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                if (before.contains(candidate) && !candidate.isRemoved() && candidate.getPlayer() != null
                        && candidate.getPlayer().getUniqueId().equals(player.getUniqueId())
                        && matchesInputAbility(candidate, abilityName)) {
                    abilityActions.put(candidate, action);
                }
            }
        }
        if (hadExistingMatchingAbility && abilityName.equalsIgnoreCase("EarthSmash")) {
            // Confirm every state-sensitive same-owner transition after its
            // exact affected-instance association has been installed. A failed
            // early grab remains on its prior action/state; a successful grab
            // is checkpointed on this action before its next TempBlock batch.
            // SHIFT_UP is dispatched synchronously too, so a release checkpoint
            // carries its own action rather than the already-reconciled grab.
            for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                if (candidate instanceof EarthSmash
                        && !candidate.isRemoved() && candidate.getPlayer() != null
                        && candidate.getPlayer().getUniqueId().equals(player.getUniqueId())
                        && !predictedOwnershipTransfers.contains(candidate)) {
                    onCheckpoint(candidate);
                }
            }
        }
        action.locallyPredicted = createdAnyAbility || trackingResult.handled()
                || action.tempBlockOrdinal > 0 || action.tempFallingBlockOrdinal > 0
                || !action.directBlockOrdinals.isEmpty() || !action.velocityOrdinals.isEmpty()
                || !action.abilityStateOrdinals.isEmpty();
        flushTempBlocks();
        // Every path here is a supported client-predicted native event. Its
        // common runtime already started (or deliberately did not start) the
        // cooldown on the input frame; importing Paper's arrival-time expiry
        // would add network latency to that gate.
        final boolean accepted = !locallyRejectedOnCooldown || action.locallyPredicted;
        final String reason = locallyRejectedOnCooldown
                ? action.locallyPredicted ? "accepted_combo" : "client_cooldown"
                : "accepted";
        createdAbilities.sort(String.CASE_INSENSITIVE_ORDER);
        reconcile(player, session, sequence, accepted, reason, abilityName, origin, 0L,
                trackingResult.handled(), comboRecorded, List.copyOf(createdAbilities));
        return nativeResult.get();
    }

    private void flushTempBlocks() {
        if (pendingTempBlocks.isEmpty()) return;
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
                final long layerId = pending.operation.layerId();
                final boolean inView = PredictionVisibility.tracksBlock(viewerWorld, pending.worldId.toString(),
                        location.getBlockX(), location.getBlockZ(), pending.operation.x(), pending.operation.z(),
                        player.getClientViewDistance());
                if (!session.tempLayers.route(layerId,
                        pending.operation.operation() == PaperPredictionProtocol.TempOperation.REVERT
                                || pending.operation.operation() == PaperPredictionProtocol.TempOperation.DISCARD,
                        inView)) continue;
                visible.add(pending.forViewer(session.player));
            }
            if (!visible.isEmpty()) {
                sendTempBlockOperations(player, session, visible, false);
            }
        }
    }

    private void sendTempBlockOperations(final Player player, final Session session,
                                          final List<PaperPredictionProtocol.TempBlockOp> operations,
                                          final boolean snapshot) {
        final long now = System.currentTimeMillis();
        final WorldScope scope = refreshWorldScope(player, session);
        if (operations.isEmpty()) {
            send(player, PaperPredictionProtocol.TEMP_BLOCKS,
                    PaperPredictionProtocol.tempBlocks(session.session, scope.generation(), scope.identity(), snapshot,
                            tick, now, List.of()));
            return;
        }
        for (int start = 0; start < operations.size(); start += TEMP_BLOCK_OPS_PER_PACKET) {
            send(player, PaperPredictionProtocol.TEMP_BLOCKS,
                    PaperPredictionProtocol.tempBlocks(session.session, scope.generation(), scope.identity(), snapshot, tick, now,
                            operations.subList(start, Math.min(start + TEMP_BLOCK_OPS_PER_PACKET, operations.size()))));
        }
    }

    private void syncState() {
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.player);
            if (player == null) continue;
            sendState(player, session, false);
        }
    }

    private void sendState(Player player, Session session, boolean force) {
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        Map<Integer, String> binds = PaperPredictionSnapshot.binds(bending);
        Map<String, Long> cooldowns = PaperPredictionSnapshot.cooldowns(bending);
        List<String> elements = PaperPredictionSnapshot.elements(bending), subs = PaperPredictionSnapshot.subElements(bending);
        List<String> permissions = predictionPermissions(player);
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        boolean chiBlocked = bending != null && bending.isChiBlocked();
        RegionProtectionAuthority.Snapshot regionProtection =
                regionProtectionSnapshot(player, bending, binds, session);
        List<String> activeFlights = activeFlightAbilities(player.getUniqueId());
        int digest = 31 * binds.hashCode() + cooldowns.hashCode();
        digest = 31 * digest + elements.hashCode();
        digest = 31 * digest + subs.hashCode();
        digest = 31 * digest + permissions.hashCode();
        digest = 31 * digest + Double.hashCode(airBlastDecay);
        digest = 31 * digest + Boolean.hashCode(chiBlocked);
        digest = 31 * digest + regionProtection.hashCode();
        digest = 31 * digest + activeFlights.hashCode();
        digest = 31 * digest + Long.hashCode(session.lastSequence);
        if (!force && digest == session.stateDigest) return;
        session.stateDigest = digest;
        send(player, PaperPredictionProtocol.STATE, PaperPredictionProtocol.state(session.session, tick,
                System.currentTimeMillis(), session.lastSequence, binds, cooldowns, elements, subs,
                permissions, airBlastDecay, chiBlocked, regionProtection, activeFlights));
    }

    private RegionProtectionAuthority.Snapshot regionProtectionSnapshot(
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

    private void requestSnapshotRebuild(boolean broadcastChanges) {
        if (!snapshotBuildRunning.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PaperPredictionProtocol.ConfigEntry> nextConfig;
            List<PaperPredictionProtocol.AbilityProfile> nextProfiles;
            try {
                nextConfig = PaperPredictionSnapshot.config();
                nextProfiles = PaperPredictionSnapshot.profiles();
            } catch (Throwable failure) {
                snapshotBuildRunning.set(false);
                plugin.getLogger().warning("Could not build prediction config snapshot asynchronously: " + failure.getMessage());
                return;
            }
            long nextEpoch = Integer.toUnsignedLong(31 * nextConfig.hashCode() + nextProfiles.hashCode());
            Bukkit.getScheduler().runTask(plugin, () -> {
                snapshotBuildRunning.set(false);
                if (active != this) return;
                boolean first = !snapshotReady;
                boolean changed = nextEpoch != configEpoch;
                publicConfig = nextConfig;
                profiles = nextProfiles;
                configEpoch = nextEpoch;
                snapshotReady = true;
                if (first || broadcastChanges && changed) sessions.values().forEach(this::sendSnapshot);
            });
        });
    }

    private void sendSnapshot(Session session) {
        Player player = Bukkit.getPlayer(session.player);
        if (player == null) return;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        Map<Integer, String> binds = PaperPredictionSnapshot.binds(bending);
        Map<String, Long> cooldowns = PaperPredictionSnapshot.cooldowns(bending);
        List<String> elements = PaperPredictionSnapshot.elements(bending), subs = PaperPredictionSnapshot.subElements(bending);
        List<String> permissions = predictionPermissions(player);
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        boolean chiBlocked = bending != null && bending.isChiBlocked();
        RegionProtectionAuthority.Snapshot regionProtection =
                regionProtectionSnapshot(player, bending, binds, session);
        session.stateDigest = 31 * (31 * (31 * (31 * (31 * (31 * (31 * binds.hashCode()
                + cooldowns.hashCode()) + elements.hashCode()) + subs.hashCode())
                + permissions.hashCode()) + Double.hashCode(airBlastDecay))
                + Boolean.hashCode(chiBlocked)) + regionProtection.hashCode();
        List<PaperPredictionProtocol.ConfigEntry> config = publicConfig;
        List<PaperPredictionProtocol.AbilityProfile> profileSnapshot = profiles;
        long epoch = configEpoch;
        long serverTick = tick;
        long serverNow = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<OutboundPayload> outbound = new ArrayList<>();
            byte[] payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                    MAX_REWIND_TICKS, config, profileSnapshot, binds, cooldowns, elements, subs,
                    permissions, airBlastDecay, chiBlocked, regionProtection);
            if (payload.length > Messenger.MAX_MESSAGE_SIZE) {
                List<List<PaperPredictionProtocol.ConfigEntry>> chunks = configChunks(config, Messenger.MAX_MESSAGE_SIZE - 128);
                for (int i = 0; i < chunks.size(); i++) {
                    outbound.add(new OutboundPayload(PaperPredictionProtocol.CONFIG_CHUNK,
                            PaperPredictionProtocol.configChunk(session.session, epoch, i, chunks.size(), chunks.get(i))));
                }
                payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                        MAX_REWIND_TICKS, List.of(), profileSnapshot, binds, cooldowns, elements, subs,
                        permissions, airBlastDecay, chiBlocked, regionProtection);
            }
            if (payload.length > Messenger.MAX_MESSAGE_SIZE) {
                int keep = profileSnapshot.size();
                while (payload.length > Messenger.MAX_MESSAGE_SIZE && keep > 0) {
                    keep /= 2;
                    payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                            MAX_REWIND_TICKS, List.of(), profileSnapshot.subList(0, keep), binds, cooldowns,
                            elements, subs, permissions, airBlastDecay, chiBlocked, regionProtection);
                }
            }
            outbound.add(new OutboundPayload(PaperPredictionProtocol.SNAPSHOT, payload));
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(session.player);
                if (current == null || sessions.get(session.player) != session) return;
                for (OutboundPayload message : outbound) send(current, message.channel(), message.payload());
                sendWorldState(current, session);
                sendTempBlockSnapshot(current, session);
            });
        });
    }

    /**
     * Captures decisions, not permission-plugin internals. Registered feature
     * nodes (WaterSpout.Wave, WaterArms modes, Flight modes, and addons) are
     * evaluated through Bukkit exactly as the authoritative ability will see
     * them. Unknown nodes remain denied on the client instead of silently
     * taking a branch Paper may reject.
     */
    private static List<String> predictionPermissions(final Player player) {
        if (player == null) return List.of();
        final SortedSet<String> abilityNodes = new TreeSet<>();
        final SortedSet<String> otherNodes = new TreeSet<>();
        final Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CoreAbility ability : CoreAbility.getAbilities()) {
            if (ability == null || ability.getName() == null || ability.getName().isBlank()) continue;
            candidates.add("bending.ability." + ability.getName());
        }
        final Collection<org.bukkit.permissions.Permission> registered =
                Bukkit.getPluginManager().getPermissions();
        candidates.addAll(expandPermissionCandidates(registered));
        // WaterSpoutWave is a feature branch whose ability name is
        // intentionally also "WaterSpout". Keep its child node in the
        // decision set even if a permission provider does not expose the
        // plugin.yml child graph through getPermissions().
        candidates.add("bending.ability.WaterSpout.Wave");
        player.getEffectivePermissions().forEach(info -> {
            final String node = info.getPermission();
            if (node != null && !node.isBlank()) candidates.add(node);
        });
        for (String node : candidates) {
            if (node == null || !node.regionMatches(true, 0, "bending.", 0, 8)
                    || node.indexOf('*') >= 0 || !player.hasPermission(node)) continue;
            final String normalized = node.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("bending.ability.")) abilityNodes.add(normalized);
            else otherNodes.add(normalized);
        }
        final List<String> result = new ArrayList<>(Math.min(MAX_PREDICTION_PERMISSIONS,
                abilityNodes.size() + otherNodes.size()));
        for (String node : abilityNodes) {
            if (result.size() == MAX_PREDICTION_PERMISSIONS) return List.copyOf(result);
            result.add(node);
        }
        for (String node : otherNodes) {
            if (result.size() == MAX_PREDICTION_PERMISSIONS) break;
            result.add(node);
        }
        return List.copyOf(result);
    }

    /**
     * Bukkit registers parent permissions from plugin.yml, but a child used by
     * ability code is not required to be registered as its own Permission.
     * Walk the complete parent graph so decisions such as
     * bending.ability.WaterSpout.Wave are synchronized even when they only
     * appear as a child of bending.water.
     */
    static Set<String> expandPermissionCandidates(
            final Collection<org.bukkit.permissions.Permission> registered) {
        if (registered == null || registered.isEmpty()) return Set.of();
        final Map<String, org.bukkit.permissions.Permission> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (org.bukkit.permissions.Permission permission : registered) {
            if (permission != null && permission.getName() != null && !permission.getName().isBlank()) {
                byName.put(permission.getName(), permission);
            }
        }
        final Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final Deque<String> pending = new ArrayDeque<>(byName.keySet());
        while (!pending.isEmpty()) {
            final String node = pending.removeFirst();
            if (node == null || node.isBlank() || !result.add(node)) continue;
            final org.bukkit.permissions.Permission permission = byName.get(node);
            if (permission == null) continue;
            for (String child : permission.getChildren().keySet()) {
                if (child != null && !child.isBlank() && !result.contains(child)) pending.addLast(child);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * A client can join or re-handshake while long-lived TempBlocks already
     * exist. Rebuild its complete in-range layer ledger after the normal
     * prediction snapshot so chunk packets can never leave invisible blocks.
     */
    private void sendTempBlockSnapshot(final Player player, final Session session) {
        final WorldScope scope = refreshWorldScope(player, session);
        final Location location = player.getLocation();
        final String viewerWorld = scope.identity();
        final List<PaperPredictionProtocol.TempBlockOp> operations = new ArrayList<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            final Block block = layer.getBlock();
            if (block.getWorld() == null || !(block.getWorld().handle() instanceof World world)
                    || !PredictionVisibility.tracksBlock(viewerWorld, world.getUID().toString(),
                    location.getBlockX(), location.getBlockZ(), block.getX(), block.getZ(),
                    player.getClientViewDistance())) continue;
            final Action action = tempLayerActions.get(layer.getLayerId());
            final String effectAbility = tempLayerEffects.containsKey(layer.getLayerId())
                    ? tempLayerEffects.get(layer.getLayerId()).ability : layer.getEffectAbility();
            final UUID predictedOwner = ownershipBridgeTempLayers.contains(layer.getLayerId())
                    ? null : predictedTempBlockOwner(
                    layer.getOwnerId().orElse(null), action, effectAbility);
            final BlockData viewerData = predictedViewerData(block, session.player, block.getBlockData());
            operations.add(new PaperPredictionProtocol.TempBlockOp(
                    PaperPredictionProtocol.TempOperation.CREATE, worldKey(block.getWorld()),
                    block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(layer.getBlockData()),
                    layer.getRevertTime(), action == null ? 0L : action.sequence,
                    effectAbility, layer.getAbility().map(CoreAbility::getPredictionState).orElse(""),
                    tempLayerEffects.containsKey(layer.getLayerId())
                            ? tempLayerEffects.get(layer.getLayerId()).step : layer.getEffectStep(),
                    tempLayerEffects.containsKey(layer.getLayerId())
                            ? tempLayerEffects.get(layer.getLayerId()).ordinal : layer.getEffectOrdinal(),
                    layer.getLayerId(), layer.getRevision(), predictedOwner,
                    TempBlockSync.encode(viewerData), false));
            session.tempLayers.markActive(layer.getLayerId());
        }
        sendTempBlockOperations(player, session, operations, true);
    }

    private void sendWorldState(final Player player, final Session session) {
        if (player == null || session == null || player.getWorld() == null) return;
        final WorldScope scope = refreshWorldScope(player, session);
        send(player, PaperPredictionProtocol.WORLD_STATE,
                PaperPredictionProtocol.worldState(session.session, scope.generation(), scope.identity()));
    }

    private WorldScope refreshWorldScope(final Player player, final Session session) {
        final String identity = player.getWorld().getUID().toString();
        if (!identity.equals(session.worldIdentity)) {
            session.worldIdentity = identity;
            session.worldGeneration++;
            session.tempLayers.clear();
        }
        return new WorldScope(session.worldGeneration, session.worldIdentity);
    }

    private List<List<PaperPredictionProtocol.ConfigEntry>> configChunks(
            List<PaperPredictionProtocol.ConfigEntry> source, int budget) {
        List<PaperPredictionProtocol.ConfigEntry> fragments = new ArrayList<>();
        for (PaperPredictionProtocol.ConfigEntry entry : source)
            splitEntry(entry, Math.max(16_384, budget - 64), fragments);
        List<List<PaperPredictionProtocol.ConfigEntry>> chunks = new ArrayList<>();
        List<PaperPredictionProtocol.ConfigEntry> current = new ArrayList<>();
        int size = 32;
        for (PaperPredictionProtocol.ConfigEntry entry : fragments) {
            int entrySize = PaperPredictionProtocol.configEntrySize(entry);
            if (!current.isEmpty() && size + entrySize > budget) {
                chunks.add(List.copyOf(current));
                current.clear();
                size = 32;
            }
            current.add(entry);
            size += entrySize;
        }
        if (!current.isEmpty()) chunks.add(List.copyOf(current));
        return chunks;
    }

    private void splitEntry(PaperPredictionProtocol.ConfigEntry entry, int budget,
                            List<PaperPredictionProtocol.ConfigEntry> output) {
        if (PaperPredictionProtocol.configEntrySize(entry) <= budget
                || entry.type() != PaperPredictionProtocol.ValueType.STRING_LIST || entry.values().size() <= 1) {
            output.add(entry);
            return;
        }
        List<String> part = new ArrayList<>();
        for (String value : entry.values()) {
            part.add(value);
            PaperPredictionProtocol.ConfigEntry candidate = new PaperPredictionProtocol.ConfigEntry(entry.path(), entry.type(), List.copyOf(part));
            if (PaperPredictionProtocol.configEntrySize(candidate) > budget && part.size() > 1) {
                String overflow = part.remove(part.size() - 1);
                output.add(new PaperPredictionProtocol.ConfigEntry(entry.path(), entry.type(), List.copyOf(part)));
                part.clear();
                part.add(overflow);
            }
        }
        if (!part.isEmpty())
            output.add(new PaperPredictionProtocol.ConfigEntry(entry.path(), entry.type(), List.copyOf(part)));
    }

    private void reconcile(Player player, Session session, long sequence, boolean accepted, String reason,
                           String ability, Location origin, long cooldown, boolean inputHandled,
                           boolean comboRecorded, List<String> createdAbilities) {
        send(player, PaperPredictionProtocol.RECONCILE, PaperPredictionProtocol.reconcile(session.session, sequence, accepted,
                reason, tick, System.currentTimeMillis(), ability, origin.getX(), origin.getY(), origin.getZ(), cooldown,
                inputHandled, comboRecorded, createdAbilities));
        if (isPersistentFlightAbility(ability)) sendState(player, session, true);
    }

    private static boolean isPersistentFlightAbility(final String ability) {
        return ability != null && (ability.equalsIgnoreCase("AirScooter")
                || ability.equalsIgnoreCase("AirSpout")
                || ability.equalsIgnoreCase("WaterSpout")
                || ability.equalsIgnoreCase("FireJet")
                || ability.equalsIgnoreCase("Flight"));
    }

    private static ComboManager.AbilityInformation latestComboInput(
            final com.projectkorra.projectkorra.platform.mc.entity.Player player) {
        if (player == null) return null;
        final List<ComboManager.AbilityInformation> recent = ComboManager.getRecentlyUsedAbilities(player, 1);
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    private void send(Player player, String channel, byte[] payload) {
        if (payload.length <= Messenger.MAX_MESSAGE_SIZE) player.sendPluginMessage(plugin, channel, payload);
    }

    private Session valid(Player player, UUID session) {
        Session current = sessions.get(player.getUniqueId());
        return current != null && current.session.equals(session) ? current : null;
    }

    private record OutboundPayload(String channel, byte[] payload) {
    }

    private record PendingTempBlock(UUID worldId, PaperPredictionProtocol.TempBlockOp operation,
                                    Map<UUID, BlockData> ownerViews) {
        private PaperPredictionProtocol.TempBlockOp forViewer(final UUID viewer) {
            final BlockData viewerData = ownerViews.get(viewer);
            return new PaperPredictionProtocol.TempBlockOp(operation.operation(), operation.world(),
                    operation.x(), operation.y(), operation.z(), operation.material(), operation.revertAtMillis(),
                    operation.actionSequence(), operation.effectAbility(), operation.effectState(), operation.effectStep(),
                    operation.effectOrdinal(), operation.layerId(), operation.revision(), operation.ownerId(),
                    viewerData == null ? operation.material() : TempBlockSync.encode(viewerData),
                    operation.packetExpected());
        }
    }

    private record PendingAbilityRemoval(UUID playerId, String ability, String abilityType, long actionSequence,
                                         boolean externallyCaused,
                                         boolean predictionRejected,
                                         CoreAbility instance) {
    }

    private static final class Session {
        final UUID player, session;
        final int capabilities;
        final long helloClientTick, helloServerTick;
        final LinkedHashMap<Long, Action> actions = new LinkedHashMap<>();
        final ArrayDeque<PaperPredictionProtocol.InputVeto> inputVetoes = new ArrayDeque<>();
        final NativeActionTagStream actionTags = new NativeActionTagStream();
        final RateLimiter claimLimiter = new RateLimiter();
        final LinkedHashMap<DirectBlockCause, Integer> directBlockOrdinals = new LinkedHashMap<>();
        final Set<String> predictedCooldowns = new HashSet<>();
        final TempBlockDeliveryTracker tempLayers = new TempBlockDeliveryTracker();
        Set<String> supportedAbilities = Set.of();
        long lastSequence;
        long worldGeneration;
        String worldIdentity = "";
        int stateDigest;
        long regionProtectionSpatialKey = Long.MIN_VALUE;
        long nextRegionProtectionRefreshTick;
        RegionProtectionAuthority.Snapshot regionProtectionSpatial =
                RegionProtectionAuthority.Snapshot.empty();
        boolean ready;

        Session(UUID player, UUID session, int capabilities,
                long helloClientTick, long helloServerTick) {
            this.player = player;
            this.session = session;
            this.capabilities = capabilities;
            this.helloClientTick = helloClientTick;
            this.helloServerTick = helloServerTick;
        }

        long mapClientTick(final long clientTick, final long currentServerTick,
                           final int attackerPing, final int defenderPing) {
            return HitRewind.mapClientTick(helloClientTick, helloServerTick, clientTick,
                    currentServerTick, attackerPing, defenderPing, MAX_REWIND_TICKS);
        }
    }

    private record WorldScope(long generation, String identity) {
    }

    private static final class Action {
        final UUID owner;
        final long sequence, acceptedTick;
        final PaperPredictionProtocol.InputKind kind;
        final int selectedSlot;
        final String ability;
        final double eyeX, eyeY, eyeZ;
        final float yaw, pitch;
        final long deterministicSeed;
        final Map<UUID, Integer> velocityOrdinals = new HashMap<>();
        final Map<UUID, Integer> abilityStateOrdinals = new HashMap<>();
        final Map<String, Integer> directBlockOrdinals = new HashMap<>();
        final Map<UUID, Claim> claims = new HashMap<>();
        long clientSequence;
        int tempFallingBlockOrdinal;
        int tempBlockOrdinal;
        boolean locallyPredicted;

        Action(UUID owner, long sequence, long acceptedTick,
               PaperPredictionProtocol.InputKind kind, int selectedSlot, String ability,
               double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
               long deterministicSeed, boolean locallyPredicted) {
            this.owner = owner;
            this.sequence = sequence;
            this.acceptedTick = acceptedTick;
            this.kind = kind;
            this.selectedSlot = selectedSlot;
            this.ability = ability;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.deterministicSeed = deterministicSeed;
            this.locallyPredicted = locallyPredicted;
        }
    }

    private static final class Claim {
        final UUID target;
        final long rewindTick, expiresTick;
        final Vector contact;
        final org.bukkit.util.BoundingBox rewoundBox;

        Claim(UUID target, long rewindTick, long expiresTick, Vector contact,
              org.bukkit.util.BoundingBox rewoundBox) {
            this.target = target;
            this.rewindTick = rewindTick;
            this.expiresTick = expiresTick;
            this.contact = contact;
            this.rewoundBox = rewoundBox;
        }
    }

    private record EntityFrame(long serverTick, UUID world,
                               org.bukkit.util.BoundingBox box) {
    }

    private static final class RateLimiter {
        long windowStart;
        int count;

        boolean allow(final long currentTick, final int maximum) {
            if (currentTick - windowStart >= 20L) {
                windowStart = currentTick;
                count = 0;
            }
            return ++count <= maximum;
        }
    }

    private record TempEffectIdentity(String ability, long step, int ordinal) {
    }

    private record DirectBlockCause(long sequence, String ability) {
    }
}
