package com.projectkorra.projectkorra.prediction;

import com.jedk1.jedcore.ability.firebending.FirePunch;
import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;

/**
 * Authoritative Paper endpoint for Fabric client prediction.
 */
public final class PaperPredictionServer implements PluginMessageListener, Runnable, TempBlockSync.Listener,
        CooldownSync.Listener, VelocitySync.Listener,
        AbilityRemovalSync.Listener {
    public static final int MAX_REWIND_TICKS = 12;
    private static final int CAPABILITY_EXACT = 8;
    private static final int INPUTS_PER_SECOND = 80;
    private static final int CLAIMS_PER_SECOND = 48;
    private static final double MAX_ORIGIN_ERROR = 8.0;
    private static final ThreadLocal<UUID> EFFECT_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> EFFECT_PREDICTED = new ThreadLocal<>();
    private static final ThreadLocal<Long> INPUT_SEQUENCE = new ThreadLocal<>();
    private static volatile PaperPredictionServer active;

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<EntityFrame>> history = new HashMap<>();
    private final Map<CoreAbility, Action> abilityActions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<UUID, EnumMap<PaperPredictionProtocol.InputKind, Long>> pendingVanilla = new HashMap<>();
    private final List<PendingTempBlock> pendingTempBlocks = new ArrayList<>();
    private final AtomicBoolean snapshotBuildRunning = new AtomicBoolean();
    private volatile List<PaperPredictionProtocol.ConfigEntry> publicConfig = List.of();
    private volatile List<PaperPredictionProtocol.AbilityProfile> profiles = List.of();
    private volatile long configEpoch;
    private volatile boolean snapshotReady;
    private long tick;
    private final ToLongFunction<CoreAbility> timingProvider = this::latencyCompensationMillis;
    private BukkitTask task;

    private PaperPredictionServer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static PaperPredictionServer start(JavaPlugin plugin) {
        PaperPredictionServer server = new PaperPredictionServer(plugin);
        server.registerChannels();
        active = server;
        TempBlockSync.install(server);
        VelocitySync.install(server);
        AbilityRemovalSync.install(server);
        PredictionTiming.install(server.timingProvider);
        CooldownSync.install(server);
        server.scheduleTicker();
        server.requestSnapshotRebuild(false);
        plugin.getLogger().info("Fabric client prediction endpoint enabled on Paper (protocol " + PaperPredictionProtocol.VERSION + ")");
        return server;
    }

    public static boolean consumeVanilla(Player player, PaperPredictionProtocol.InputKind kind) {
        PaperPredictionServer server = active;
        if (server == null || player == null) return false;
        EnumMap<PaperPredictionProtocol.InputKind, Long> pending = server.pendingVanilla.get(player.getUniqueId());
        if (pending == null) return false;
        // A single physical action can produce both interact and animation
        // events. Keep the marker for its short lifetime so every native echo
        // of the already-dispatched predicted input is ignored.
        Long until = pending.get(kind);
        return until != null && until >= server.tick;
    }

    public static boolean consumeLeftClick(Player player) {
        return consumeVanilla(player, PaperPredictionProtocol.InputKind.LEFT_CLICK);
    }

    public static boolean consumeRightClick(Player player, boolean block) {
        return consumeVanilla(player, block ? PaperPredictionProtocol.InputKind.RIGHT_CLICK_BLOCK : PaperPredictionProtocol.InputKind.RIGHT_CLICK);
    }

    public static boolean consumeRightClickEntity(Player player) {
        return consumeVanilla(player, PaperPredictionProtocol.InputKind.RIGHT_CLICK_ENTITY);
    }

    public static boolean consumeSneak(Player player, boolean sneakingNow) {
        return consumeVanilla(player, sneakingNow ? PaperPredictionProtocol.InputKind.SNEAK_START : PaperPredictionProtocol.InputKind.SNEAK_STOP);
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
        // Predicted clients already ran the ability sound locally. Hit claims
        // now validate registration only; they must not opt the owner back
        // into the authoritative sound broadcast and play a second hit sound.
        return predictedEffectOwner();
    }

    public static Location claimedEffectLocation(Entity target) {
        PaperPredictionServer server = active;
        if (server == null || target == null) return null;
        CoreAbility ability = AbilityExecutionContext.current();
        Action action = ability == null ? null : server.actionForEffect(ability);
        if (action == null) {
            UUID owner = EFFECT_OWNER.get();
            Long sequence = INPUT_SEQUENCE.get();
            Session session = owner == null ? null : server.sessions.get(owner);
            action = session == null || sequence == null ? null : session.actions.get(sequence);
        }
        if (action == null) return null;
        Claim claim = action.claims.values().stream()
                .filter(candidate -> candidate.target.equals(target.getUniqueId())).findFirst().orElse(null);
        if (claim == null || claim.expiresTick < server.tick
                || claim.consumedTick >= 0 && claim.consumedTick < server.tick) return null;
        return new Location(target.getWorld(), claim.contact.getX(),
                claim.contact.getY() - target.getHeight() * 0.5, claim.contact.getZ());
    }

    /**
     * Returns the exact eye pose attached to the input driving the current ability tick.
     */
    public static CapturedInputPose capturedEffectPose(Player player) {
        PaperPredictionServer server = active;
        if (server == null || player == null) return null;
        CoreAbility ability = AbilityExecutionContext.current();
        Action action = ability == null ? null : server.actionForEffect(ability);
        if (action == null) {
            UUID owner = EFFECT_OWNER.get();
            Long sequence = INPUT_SEQUENCE.get();
            Session session = owner == null ? null : server.sessions.get(owner);
            action = session == null || sequence == null ? null : session.actions.get(sequence);
        }
        if (action == null || !action.locallyPredicted || !action.owner.equals(player.getUniqueId())
                || server.tick - action.acceptedTick > 1L) return null;
        Session session = server.sessions.get(action.owner);
        if (session == null || (session.capabilities & CAPABILITY_EXACT) == 0) return null;
        return new CapturedInputPose(action.eyeX, action.eyeY, action.eyeZ, action.yaw, action.pitch);
    }

    public static Runnable contextual(Runnable task) {
        Player owner = predictedEffectOwner();
        if (owner == null) return task;
        UUID uuid = owner.getUniqueId();
        return () -> runWithOwner(uuid, task);
    }

    public static <T> Callable<T> contextual(Callable<T> task) {
        Player owner = predictedEffectOwner();
        if (owner == null) return task;
        UUID uuid = owner.getUniqueId();
        return () -> {
            UUID previous = EFFECT_OWNER.get();
            Boolean previousPredicted = EFFECT_PREDICTED.get();
            EFFECT_OWNER.set(uuid);
            EFFECT_PREDICTED.set(Boolean.TRUE);
            try {
                return task.call();
            } finally {
                if (previous == null) EFFECT_OWNER.remove();
                else EFFECT_OWNER.set(previous);
                if (previousPredicted == null) EFFECT_PREDICTED.remove();
                else EFFECT_PREDICTED.set(previousPredicted);
            }
        };
    }

    /**
     * GeneralMethods reloads cancel this plugin's tasks; restore the endpoint ticker.
     */
    public static void schedulerReset() {
        PaperPredictionServer server = active;
        if (server != null) server.scheduleTicker();
    }

    private static void runWithOwner(UUID owner, Runnable task) {
        runWithOwner(owner, true, task);
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

    public static void augmentNearbyPlayers(World world, BoundingBox query, CoreAbility ability,
                                            Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.Entity> result) {
        PaperPredictionServer server = active;
        if (server == null) return;
        Action action = ability == null ? null : server.actionForEffect(ability);
        if (action == null) {
            UUID owner = EFFECT_OWNER.get();
            Long sequence = INPUT_SEQUENCE.get();
            Session inputSession = owner == null ? null : server.sessions.get(owner);
            action = inputSession == null || sequence == null ? null : inputSession.actions.get(sequence);
        }
        if (action == null) return;
        var iterator = action.claims.values().iterator();
        while (iterator.hasNext()) {
            Claim claim = iterator.next();
            if (claim.expiresTick < server.tick || claim.consumedTick >= 0 && claim.consumedTick < server.tick) {
                iterator.remove();
                continue;
            }
            Entity target = Bukkit.getEntity(claim.target);
            if (!(target instanceof org.bukkit.entity.LivingEntity) || !target.getWorld().equals(world)) continue;
            // The client claim is authoritative for this diagnostic path.
            // Do not gate it a second time using the server's rewound box.
            result.put(target.getUniqueId(), BukkitMC.entity(target));
            claim.consumedTick = server.tick;
        }
    }

    private static boolean createdAbility(Set<CoreAbility> before, UUID owner, String abilityName) {
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability) && ability.getPlayer() != null
                    && ability.getPlayer().getUniqueId().equals(owner)
                    && matchesInputAbility(ability, abilityName)) return true;
        }
        return false;
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
        return switch (bukkitWorld.getEnvironment()) {
            case NORMAL -> "minecraft:overworld";
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> bukkitWorld.getName();
        };
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    public void stop() {
        if (task != null) task.cancel();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.HELLO, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.INPUT, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.PREPARE, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.HIT, this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, PaperPredictionProtocol.HANDOFF, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
        sessions.clear();
        history.clear();
        abilityActions.clear();
        pendingVanilla.clear();
        pendingTempBlocks.clear();
        TempBlockSync.clear(this);
        VelocitySync.clear(this);
        AbilityRemovalSync.clear(this);
        PredictionTiming.clear(this.timingProvider);
        CooldownSync.clear(this);
        if (active == this) active = null;
    }

    private void registerChannels() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.HELLO, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.INPUT, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.PREPARE, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.HIT, this);
        messenger.registerIncomingPluginChannel(plugin, PaperPredictionProtocol.HANDOFF, this);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.SNAPSHOT);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.STATE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.CONFIG_CHUNK);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.RECONCILE);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.TEMP_BLOCKS);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.VELOCITY_OWNER);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.VELOCITY_OWNER_V2);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.ABILITY_REMOVED);
        messenger.registerOutgoingPluginChannel(plugin, PaperPredictionProtocol.STATE_DIRECTIVE);
    }

    @Override
    public void onAdded(BendingPlayer player, String ability, long expiresAtMillis) {
        Player predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUniqueId().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, "", ability == null ? "" : ability, expiresAtMillis, false, Double.NaN);
    }

    @Override
    public void onRemoved(BendingPlayer player, String ability) {
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
                    case PaperPredictionProtocol.INPUT -> onInput(player, PaperPredictionProtocol.readInput(message));
                    case PaperPredictionProtocol.PREPARE ->
                            onPrepare(player, PaperPredictionProtocol.readPrepare(message));
                    case PaperPredictionProtocol.HIT -> onHit(player, PaperPredictionProtocol.readHit(message));
                    case PaperPredictionProtocol.HANDOFF ->
                            onAuthorityHandoff(player, PaperPredictionProtocol.readHandoff(message));
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

    private void onAuthorityHandoff(Player player, PaperPredictionProtocol.Handoff handoff) {
        Session session = valid(player, handoff.session());
        if (session == null) return;
        Action action = session.actions.get(handoff.sequence());
        if (action != null && action.owner.equals(player.getUniqueId())) action.locallyPredicted = false;
    }

    @Override
    public void run() {
        tick++;
        recordHistory();
        flushTempBlocks();
        pendingVanilla.values().forEach(map -> map.entrySet().removeIf(entry -> entry.getValue() < tick));
        pendingVanilla.entrySet().removeIf(entry -> entry.getValue().isEmpty() || Bukkit.getPlayer(entry.getKey()) == null);
        sessions.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);
        abilityActions.entrySet().removeIf(entry -> entry.getKey().isRemoved() || !sessions.containsKey(entry.getValue().owner));
        if (tick % 20 == 0) syncState();
        if (tick % 100 == 0) {
            requestSnapshotRebuild(true);
        }
    }

    private Action actionForEffect(CoreAbility ability) {
        Action action = abilityActions.get(ability);
        if (action != null || ability == null || ability.getPlayer() == null) return action;
        Session session = sessions.get(ability.getPlayer().getUniqueId());
        if (session == null) return null;
        List<Action> recent = new ArrayList<>(session.actions.values());
        for (int i = recent.size() - 1; i >= 0; i--) {
            Action candidate = recent.get(i);
            if (candidate.locallyPredicted && tick - candidate.acceptedTick <= 4) {
                abilityActions.put(ability, candidate);
                return candidate;
            }
        }
        return null;
    }

    private long latencyCompensationMillis(CoreAbility ability) {
        Action action = actionForEffect(ability);
        return action == null || !action.locallyPredicted ? 0L : Math.max(0L, tick - action.rewindTick) * 50L;
    }

    private void scheduleTicker() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1, 1);
    }

    @Override
    public void onChange(TempBlockSync.Operation operation, Block block,
                         BlockData data, long revertAtMillis, CoreAbility ability) {
        PaperPredictionProtocol.TempOperation wireOperation = switch (operation) {
            case CREATE -> PaperPredictionProtocol.TempOperation.CREATE;
            case UPDATE_EXPIRY -> PaperPredictionProtocol.TempOperation.UPDATE_EXPIRY;
            case REVERT -> PaperPredictionProtocol.TempOperation.REVERT;
        };
        CoreAbility effectiveAbility = ability == null ? AbilityExecutionContext.current() : ability;
        Action action = effectiveAbility == null ? null : actionForEffect(effectiveAbility);
        Long inputSequence = INPUT_SEQUENCE.get();
        UUID worldId = block.getWorld() != null && block.getWorld().handle() instanceof World world
                ? world.getUID() : null;
        if (worldId == null) return;
        pendingTempBlocks.add(new PendingTempBlock(worldId,
                new PaperPredictionProtocol.TempBlockOp(wireOperation, worldKey(block.getWorld()),
                block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(data), revertAtMillis,
                action == null ? (inputSequence == null ? 0L : inputSequence) : action.sequence)));
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
        Action action = abilityActions.get(coreAbility);
        Long inputSequence = INPUT_SEQUENCE.get();
        if (action == null && inputSequence != null && ownerSession != null)
            action = ownerSession.actions.get(inputSequence);
        if (action == null || !action.locallyPredicted || !action.owner.equals(ownerId)) return;

        int ordinal = action.velocityOrdinals.merge(targetId, 1, Integer::sum);
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
    public void onRemoved(CoreAbility ability) {
        if (ability.getPlayer() == null || !ability.isStarted()) return;
        Action action = abilityActions.get(ability);
        UUID playerId = ability.getPlayer().getUniqueId();
        Player player = Bukkit.getPlayer(playerId);
        if (sessions.containsKey(playerId) && player != null) {
            send(player, PaperPredictionProtocol.ABILITY_REMOVED,
                    PaperPredictionProtocol.abilityRemoved(playerId, ability.getName(),
                            action != null && action.locallyPredicted ? action.sequence : 0L));
            sendState(player, sessions.get(playerId), true);
        }
    }

    private void onHello(Player player, PaperPredictionProtocol.Hello hello) {
        if (hello.version() != PaperPredictionProtocol.VERSION) return;
        Session session = new Session(player.getUniqueId(), UUID.randomUUID(), hello.clientTick(), tick, hello.capabilities());
        sessions.put(player.getUniqueId(), session);
        if (snapshotReady) sendSnapshot(session);
        else requestSnapshotRebuild(false);
    }

    private void onPrepare(Player player, PaperPredictionProtocol.Prepare prepare) {
        Session session = valid(player, prepare.session());
        if (session == null || prepare.sequence() <= session.lastSequence
                || prepare.selectedSlot() < 0 || prepare.selectedSlot() > 8
                || !finite(prepare.x(), prepare.y(), prepare.z(), prepare.yaw(), prepare.pitch())) return;
        Location origin = player.getEyeLocation();
        if (new Vector(prepare.x(), prepare.y(), prepare.z()).distanceSquared(origin.toVector())
                > MAX_ORIGIN_ERROR * MAX_ORIGIN_ERROR) return;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        String fallback = bending == null ? "" : bending.getAbilities().get(prepare.selectedSlot() + 1);
        String ability = logicalInputAbility(BukkitMC.player(player), bending, prepare.kind(), fallback);
        if (ability.isBlank()) return;
        long mappedTick = session.mapClientTick(prepare.clientTick(), tick, player.getPing());
        session.actions.put(prepare.sequence(), new Action(player.getUniqueId(), prepare.sequence(), prepare.clientTick(),
                mappedTick, tick, ability, prepare.x(), prepare.y(), prepare.z(), prepare.yaw(), prepare.pitch(), false));
        while (session.actions.size() > 128) session.actions.remove(session.actions.keySet().iterator().next());
    }

    private void onInput(Player player, PaperPredictionProtocol.Input input) {
        Session session = valid(player, input.session());
        if (session == null || !session.inputLimiter.allow(tick, INPUTS_PER_SECOND)) return;
        Location origin = player.getEyeLocation();
        if (input.sequence() <= session.lastSequence) {
            reconcile(player, session, input.sequence(), false, "stale_sequence", "", origin, 0);
            return;
        }
        session.lastSequence = input.sequence();
        if (!finite(input.x(), input.y(), input.z(), input.yaw(), input.pitch())) {
            reconcile(player, session, input.sequence(), false, "non_finite_input", "", origin, 0);
            return;
        }
        if (new Vector(input.x(), input.y(), input.z()).distanceSquared(origin.toVector()) > MAX_ORIGIN_ERROR * MAX_ORIGIN_ERROR) {
            reconcile(player, session, input.sequence(), false, "origin_out_of_bounds", "", origin, 0);
            return;
        }
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        if (input.selectedSlot() < 0 || input.selectedSlot() > 8) {
            reconcile(player, session, input.sequence(), false, "invalid_selected_slot", "", origin, 0);
            return;
        }
        com.projectkorra.projectkorra.platform.mc.entity.Player commonPlayer = BukkitMC.player(player);
        if (player.getInventory().getHeldItemSlot() != input.selectedSlot()
                || bending != null && bending.getCurrentSlot() != input.selectedSlot()) {
            CommonInputHandler.SlotResult slotResult = CommonInputHandler.handleSlotChange(commonPlayer, input.selectedSlot());
            if (!slotResult.accepted()) {
                reconcile(player, session, input.sequence(), false, "invalid_selected_slot", "", origin, 0);
                return;
            }
        }
        String fallback = bending == null ? "" : bending.getAbilities().get(input.selectedSlot() + 1);
        String abilityName = logicalInputAbility(commonPlayer, bending, input.kind(), fallback);
        if (abilityName.isBlank()) {
            reconcile(player, session, input.sequence(), false, "no_bound_ability", "", origin, 0);
            return;
        }

        // The custom payload is sent immediately before the matching vanilla
        // packet. Process it exactly once here and consume every native echo.
        pendingVanilla.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(PaperPredictionProtocol.InputKind.class))
                .put(input.kind(), tick + 4);
        if (input.locallyBlockedByCooldown()) {
            // Continue through combo tracking and existing-instance actions,
            // while guarding against a delayed standalone activation.
        }
        long mappedTick = session.mapClientTick(input.clientTick(), tick, player.getPing());
        Action action = session.actions.get(input.sequence());
        if (action != null && (action.clientTick != input.clientTick()
                || !action.ability.equalsIgnoreCase(abilityName)
                || Math.abs(action.yaw - input.yaw()) > 0.01F || Math.abs(action.pitch - input.pitch()) > 0.01F)) {
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "prepare_mismatch", abilityName, origin,
                    bending == null ? 0L : bending.getCooldown(abilityName));
            return;
        }
        if (action == null) {
            action = new Action(player.getUniqueId(), input.sequence(), input.clientTick(), mappedTick, tick,
                    abilityName, input.x(), input.y(), input.z(), input.yaw(), input.pitch(), input.locallyPredicted());
            session.actions.put(input.sequence(), action);
        }
        action.locallyPredicted = input.locallyPredicted();
        Set<CoreAbility> before = identitySet(CoreAbility.getAbilitiesByInstances());
        boolean hadExistingMatchingAbility = before.stream().anyMatch(candidate -> candidate.getPlayer() != null
                && candidate.getPlayer().getUniqueId().equals(player.getUniqueId())
                && matchesInputAbility(candidate, abilityName));
        long cooldownBefore = bending.getCooldown(abilityName);
        boolean handled;
        Long previousSequence = INPUT_SEQUENCE.get();
        INPUT_SEQUENCE.set(input.sequence());
        Cooldown previousGuardedCooldown = null;
        boolean guardedCooldown = input.locallyBlockedByCooldown() && bending != null;
        if (guardedCooldown) {
            previousGuardedCooldown = bending.getCooldowns().put(abilityName,
                    new Cooldown(System.currentTimeMillis() + 60_000L, false));
        }
        AbilityActivationManager.beginTracking();
        try {
            runWithOwner(player.getUniqueId(), input.locallyPredicted(),
                    () -> dispatch(player, input.kind(), input.x(), input.y(), input.z(), input.yaw(), input.pitch()));
        } finally {
            handled = AbilityActivationManager.finishTracking();
            if (guardedCooldown) {
                if (previousGuardedCooldown == null) bending.getCooldowns().remove(abilityName);
                else bending.getCooldowns().put(abilityName, previousGuardedCooldown);
            }
            if (previousSequence == null) INPUT_SEQUENCE.remove();
            else INPUT_SEQUENCE.set(previousSequence);
        }
        long cooldownAfter = bending.getCooldown(abilityName);
        boolean createdAny = createdAnyAbility(before, player.getUniqueId());
        if (input.locallyBlockedByCooldown() && !PredictionCooldownTimeline.allowsCooldownGuardedInput(
                createdAny, handled, hadExistingMatchingAbility)) {
            flushTempBlocks();
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "client_cooldown_combo_recorded", abilityName,
                    new Location(player.getWorld(), input.x(), input.y(), input.z(), input.yaw(), input.pitch()), cooldownAfter);
            return;
        }
        boolean deferredSneakTransition = isSneakTransition(input.kind()) && hadExistingMatchingAbility;
        boolean directTargetTransition = "FirePunch".equalsIgnoreCase(abilityName);
        if (input.locallyPredicted() && !handled && !deferredSneakTransition && !directTargetTransition
                && cooldownAfter <= cooldownBefore
                && !createdAbility(before, player.getUniqueId(), abilityName)) {
            flushTempBlocks();
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "cooldown", abilityName,
                    new Location(player.getWorld(), input.x(), input.y(), input.z(), input.yaw(), input.pitch()),
                    cooldownAfter);
            return;
        }
        cooldownAfter = PredictionCooldownTimeline.alignNewCooldown(bending, abilityName, cooldownBefore,
                Math.max(0L, tick - mappedTick) * 50L, System.currentTimeMillis());
        while (session.actions.size() > 128) session.actions.remove(session.actions.keySet().iterator().next());
        for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
            if (candidate.getPlayer() == null || !candidate.getPlayer().getUniqueId().equals(player.getUniqueId()))
                continue;
            if (!before.contains(candidate) || matchesInputAbility(candidate, abilityName))
                abilityActions.put(candidate, action);
        }
        flushTempBlocks();
        Location reconcileOrigin = isSneakTransition(input.kind())
                ? new Location(player.getWorld(), input.x(), input.y(), input.z(), input.yaw(), input.pitch())
                : player.getEyeLocation();
        reconcile(player, session, input.sequence(), true, "accepted", abilityName, reconcileOrigin, cooldownAfter);
    }

    private void dispatch(Player nativePlayer, PaperPredictionProtocol.InputKind kind,
                          double eyeX, double eyeY, double eyeZ, float yaw, float pitch) {
        com.projectkorra.projectkorra.platform.mc.entity.Player player = BukkitMC.player(nativePlayer);
        Boolean sneakOverride = kind == PaperPredictionProtocol.InputKind.SNEAK_START ? Boolean.TRUE
                : kind == PaperPredictionProtocol.InputKind.SNEAK_STOP ? Boolean.FALSE : null;
        if (sneakOverride != null) BukkitMC.setSneakOverride(nativePlayer, sneakOverride);
        BukkitMC.setViewOverride(nativePlayer, eyeX, eyeY, eyeZ, yaw, pitch);
        try {
            switch (kind) {
                case LEFT_CLICK -> CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>());
                case RIGHT_CLICK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
                case RIGHT_CLICK_BLOCK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
                case RIGHT_CLICK_ENTITY -> CommonInputHandler.handleRightClickEntity(player);
                case SNEAK_START -> CommonInputHandler.handleSneak(player, false);
                case SNEAK_STOP -> CommonInputHandler.handleSneak(player, true);
            }
        } finally {
            if (sneakOverride != null) BukkitMC.setSneakOverride(nativePlayer, null);
            BukkitMC.setViewOverride(nativePlayer, null, null, null, null, null);
        }
    }

    private void onHit(Player player, PaperPredictionProtocol.Hit hit) {
        Session session = valid(player, hit.session());
        if (session == null || !session.claimLimiter.allow(tick, CLAIMS_PER_SECOND)) return;
        Action action = session.actions.get(hit.sequence());
        if (action == null || tick - action.acceptedTick > 200 || action.claims.containsKey(hit.entityId())
                || hit.clientTick() < action.clientTick - 1 || hit.clientTick() > action.clientTick + 200
                || !finite(hit.x(), hit.y(), hit.z())) return;
        Entity nativeTarget = player.getWorld().getEntities().stream().filter(entity -> entity.getEntityId() == hit.entityId()).findFirst().orElse(null);
        if (!(nativeTarget instanceof org.bukkit.entity.LivingEntity target) || target.equals(player) || target.isDead())
            return;
        long rewindTick = session.mapClientTick(hit.clientTick(), tick, player.getPing());
        Vector contact = new Vector(hit.x(), hit.y(), hit.z());
        Claim claim = new Claim(target.getUniqueId(), rewindTick,
                tick + MAX_REWIND_TICKS + 4, contact.clone());
        action.claims.put(hit.entityId(), claim);
        if ("FirePunch".equalsIgnoreCase(action.ability)) {
            consumeFirePunchClaim(player, target, action, claim);
        }
        BendingPlayer bending = BendingPlayer.getBendingPlayer(BukkitMC.player(player));
        reconcile(player, session, action.sequence, true, "hit_claim_accepted", action.ability,
                player.getEyeLocation(), bending == null ? 0 : bending.getCooldown(action.ability));
    }

    private void consumeFirePunchClaim(Player player, org.bukkit.entity.LivingEntity target,
                                       Action action, Claim claim) {
        com.projectkorra.projectkorra.platform.mc.entity.Player commonPlayer = BukkitMC.player(player);
        FirePunch punch = CoreAbility.getAbility(commonPlayer, FirePunch.class);
        BendingPlayer bending = BendingPlayer.getBendingPlayer(commonPlayer);
        if (punch == null) {
            CoreAbility descriptor = CoreAbility.getAbility(FirePunch.class);
            if (bending == null || descriptor == null || !bending.canBend(descriptor)) return;
            punch = new FirePunch(commonPlayer, null);
        }
        abilityActions.put(punch, action);
        claim.consumedTick = tick;
        FirePunch executing = punch;
        Long previousSequence = INPUT_SEQUENCE.get();
        INPUT_SEQUENCE.set(action.sequence);
        try {
            runWithOwner(player.getUniqueId(), action.locallyPredicted,
                    () -> AbilityExecutionContext.run(executing,
                            () -> executing.punch((LivingEntity)
                                    BukkitMC.entity(target))));
        } finally {
            if (previousSequence == null) INPUT_SEQUENCE.remove();
            else INPUT_SEQUENCE.set(previousSequence);
        }
    }

    private boolean lineOfSight(Player player, Vector start, Vector end, double tolerance) {
        Vector delta = end.clone().subtract(start);
        double distance = delta.length();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(start.toLocation(player.getWorld()), delta.normalize(), distance,
                FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitPosition().distanceSquared(end) <= tolerance * tolerance;
    }

    private void recordHistory() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Deque<EntityFrame> frames = history.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
            frames.addLast(new EntityFrame(tick, player.getWorld().getUID(), player.getLocation().toVector(),
                    player.getEyeLocation().toVector(), player.getBoundingBox(), player.getYaw(), player.getPitch()));
            while (!frames.isEmpty() && tick - frames.getFirst().tick > MAX_REWIND_TICKS + 4) frames.removeFirst();
        }
        history.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private EntityFrame frameAt(UUID player, long wanted) {
        Deque<EntityFrame> frames = history.get(player);
        if (frames == null || frames.isEmpty()) return null;
        EntityFrame best = frames.getFirst();
        long distance = Math.abs(best.tick - wanted);
        for (EntityFrame frame : frames) {
            long candidate = Math.abs(frame.tick - wanted);
            if (candidate < distance) {
                best = frame;
                distance = candidate;
            }
        }
        return best;
    }

    private void flushTempBlocks() {
        if (pendingTempBlocks.isEmpty()) return;
        List<PendingTempBlock> operations = List.copyOf(pendingTempBlocks);
        pendingTempBlocks.clear();
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.player);
            if (player == null) continue;
            Location location = player.getLocation();
            String viewerWorld = player.getWorld().getUID().toString();
            List<PaperPredictionProtocol.TempBlockOp> visible = operations.stream()
                    .filter(pending -> PredictionVisibility.tracksBlock(viewerWorld, pending.worldId.toString(),
                            location.getBlockX(), location.getBlockZ(), pending.operation.x(), pending.operation.z(),
                            player.getClientViewDistance()))
                    .map(PendingTempBlock::operation)
                    .toList();
            if (!visible.isEmpty()) {
                send(player, PaperPredictionProtocol.TEMP_BLOCKS,
                        PaperPredictionProtocol.tempBlocks(tick, System.currentTimeMillis(), visible));
            }
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
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        List<String> activeFlights = activeFlightAbilities(player.getUniqueId());
        int digest = (((31 * binds.hashCode() + cooldowns.hashCode()) * 31 + elements.hashCode()) * 31 + subs.hashCode()) * 31
                + Double.hashCode(airBlastDecay) * 31 + activeFlights.hashCode();
        if (!force && digest == session.stateDigest) return;
        session.stateDigest = digest;
        send(player, PaperPredictionProtocol.STATE, PaperPredictionProtocol.state(session.session, tick,
                System.currentTimeMillis(), binds, cooldowns, elements, subs, airBlastDecay, activeFlights));
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
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        session.stateDigest = (((31 * binds.hashCode() + cooldowns.hashCode()) * 31 + elements.hashCode()) * 31 + subs.hashCode()) * 31
                + Double.hashCode(airBlastDecay);
        List<PaperPredictionProtocol.ConfigEntry> config = publicConfig;
        List<PaperPredictionProtocol.AbilityProfile> profileSnapshot = profiles;
        long epoch = configEpoch;
        long serverTick = tick;
        long serverNow = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<OutboundPayload> outbound = new ArrayList<>();
            byte[] payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                    MAX_REWIND_TICKS, config, profileSnapshot, binds, cooldowns, elements, subs, airBlastDecay);
            if (payload.length > Messenger.MAX_MESSAGE_SIZE) {
                List<List<PaperPredictionProtocol.ConfigEntry>> chunks = configChunks(config, Messenger.MAX_MESSAGE_SIZE - 128);
                for (int i = 0; i < chunks.size(); i++) {
                    outbound.add(new OutboundPayload(PaperPredictionProtocol.CONFIG_CHUNK,
                            PaperPredictionProtocol.configChunk(session.session, epoch, i, chunks.size(), chunks.get(i))));
                }
                payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                        MAX_REWIND_TICKS, List.of(), profileSnapshot, binds, cooldowns, elements, subs, airBlastDecay);
            }
            if (payload.length > Messenger.MAX_MESSAGE_SIZE) {
                int keep = profileSnapshot.size();
                while (payload.length > Messenger.MAX_MESSAGE_SIZE && keep > 0) {
                    keep /= 2;
                    payload = PaperPredictionProtocol.snapshot(session.session, serverTick, serverNow, epoch,
                            MAX_REWIND_TICKS, List.of(), profileSnapshot.subList(0, keep), binds, cooldowns, elements, subs, airBlastDecay);
                }
            }
            outbound.add(new OutboundPayload(PaperPredictionProtocol.SNAPSHOT, payload));
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(session.player);
                if (current == null || sessions.get(session.player) != session) return;
                for (OutboundPayload message : outbound) send(current, message.channel(), message.payload());
            });
        });
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
                           String ability, Location origin, long cooldown) {
        send(player, PaperPredictionProtocol.RECONCILE, PaperPredictionProtocol.reconcile(session.session, sequence, accepted,
                reason, tick, System.currentTimeMillis(), ability, origin.getX(), origin.getY(), origin.getZ(), cooldown));
    }

    private void send(Player player, String channel, byte[] payload) {
        if (payload.length <= Messenger.MAX_MESSAGE_SIZE) player.sendPluginMessage(plugin, channel, payload);
    }

    private Session valid(Player player, UUID session) {
        Session current = sessions.get(player.getUniqueId());
        return current != null && current.session.equals(session) ? current : null;
    }

    private static final class RateLimiter {
        long start;
        int count;

        boolean allow(long tick, int maximum) {
            if (tick - start >= 20) {
                start = tick;
                count = 0;
            }
            return ++count <= maximum;
        }
    }

    private record OutboundPayload(String channel, byte[] payload) {
    }

    private record PendingTempBlock(UUID worldId, PaperPredictionProtocol.TempBlockOp operation) {
    }

    private static final class Session {
        final UUID player, session;
        final long helloClientTick, helloServerTick;
        final int capabilities;
        final RateLimiter inputLimiter = new RateLimiter(), claimLimiter = new RateLimiter();
        final LinkedHashMap<Long, Action> actions = new LinkedHashMap<>();
        long lastSequence;
        int stateDigest;

        Session(UUID player, UUID session, long clientTick, long serverTick, int capabilities) {
            this.player = player;
            this.session = session;
            this.helloClientTick = clientTick;
            this.helloServerTick = serverTick;
            this.capabilities = capabilities;
        }

        long mapClientTick(long clientTick, long now, int pingMillis) {
            long receiptMapped = helloServerTick + clientTick - helloClientTick;
            long oneWayTicks = Math.max(0, Math.min(MAX_REWIND_TICKS, Math.round(Math.max(0, pingMillis) / 100.0)));
            long mapped = receiptMapped - oneWayTicks;
            return Math.max(now - MAX_REWIND_TICKS, Math.min(now, mapped));
        }
    }

    private static final class Action {
        final UUID owner;
        final long sequence, clientTick, rewindTick, acceptedTick;
        final String ability;
        final double eyeX, eyeY, eyeZ;
        final float yaw, pitch;
        final Map<Integer, Claim> claims = new HashMap<>();
        final Map<UUID, Integer> velocityOrdinals = new HashMap<>();
        boolean locallyPredicted;

        Action(UUID owner, long sequence, long clientTick, long rewindTick, long acceptedTick, String ability,
               double eyeX, double eyeY, double eyeZ, float yaw, float pitch, boolean locallyPredicted) {
            this.owner = owner;
            this.sequence = sequence;
            this.clientTick = clientTick;
            this.rewindTick = rewindTick;
            this.acceptedTick = acceptedTick;
            this.ability = ability;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.locallyPredicted = locallyPredicted;
        }
    }

    private static final class Claim {
        final UUID target;
        final long rewindTick, expiresTick;
        final Vector contact;
        long consumedTick = -1;

        Claim(UUID target, long rewindTick, long expiresTick, Vector contact) {
            this.target = target;
            this.rewindTick = rewindTick;
            this.expiresTick = expiresTick;
            this.contact = contact;
        }
    }

    private record EntityFrame(long tick, UUID world, Vector position, Vector eye, BoundingBox box, float yaw,
                               float pitch) {
    }
}
