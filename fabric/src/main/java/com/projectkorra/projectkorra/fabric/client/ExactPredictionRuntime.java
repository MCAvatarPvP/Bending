package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.EmbeddedAddonBootstrap;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.airbending.flight.FlightMultiAbility;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.fabric.prediction.PredictionPayloads;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricClientPredictionPlatform;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.PredictionConfigSync;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.PredictionEchoPolicy;
import com.projectkorra.projectkorra.prediction.PredictionStateOrdering;
import com.projectkorra.projectkorra.prediction.TempBlockOwnershipPolicy;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.AbilityLagCompensator;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.waterbending.WaterSpout;
import com.jedk1.jedcore.ability.passive.WallRun;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.logging.Level;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the real common ProjectKorra ability implementation in the logical
 * client. This is deliberately not a visual approximation: constructors,
 * progress methods, particles, sounds, ray traces, temp blocks and velocity
 * calls all pass through the same common classes used by the server.
 */
public final class ExactPredictionRuntime implements CooldownSync.Listener {
    private static final ExactPredictionRuntime INSTANCE = new ExactPredictionRuntime();
    private static final ThreadLocal<Long> INPUT_ACTION = new ThreadLocal<>();
    private static final int ACTION_RETENTION_TICKS = 160;
    private static final int BLOCK_CONFIRMATION_TICKS = 40;
    private static final int MIN_ACTION_BLOCK_CONFIRMATION_TICKS = 4;
    private static final int ACTION_BLOCK_CONFIRMATION_MARGIN_TICKS = 2;
    private static final int OWNED_TEMP_RECEIPT_TICKS = 40;
    private static final int VELOCITY_RECEIPT_TICKS = 4;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "true"));

    private final Map<Long, Action> actions = new LinkedHashMap<>();
    private final Map<CoreAbility, Long> abilityActions = new IdentityHashMap<>();
    private final Map<BlockKey, BlockMutation> blocks = new HashMap<>();
    private final List<BlockEcho> blockEchoes = new ArrayList<>();
    // Packet echoes are disposable when vanilla authority arrives. TempBlock
    // metadata is ordered separately and can arrive afterwards, so retain an
    // independent lifecycle history for ownership/state confirmation.
    private final List<BlockEcho> localBlockHistory = new ArrayList<>();
    private final Map<BlockKey, OwnedBatchRestore> ownedBatchRestores = new HashMap<>();
    private final List<VelocityMutation> velocities = new ArrayList<>();
    private final List<VelocityReceipt> velocityReceipts = new ArrayList<>();
    private final List<AbilityStateMutation> abilityStates = new ArrayList<>();
    private final List<ExperienceMutation> experiences = new ArrayList<>();
    private final List<SelectedSlotMutation> selectedSlots = new ArrayList<>();
    private final Map<Integer, Entity> authoritativeEntityAliases = new HashMap<>();
    private final Map<Entity, Vec3d> predictedSpawnOrigins = new IdentityHashMap<>();
    private final PredictionCooldownAuthority cooldownAuthority = new PredictionCooldownAuthority();
    private FabricClientPredictionPlatform platform;
    private BendingManager bendingManager;
    private BendingPlayer bendingPlayer;
    private long tick;
    private long blockEchoOrdinal;
    private boolean ready;
    private boolean initializing;
    private boolean managersStarted;

    private ExactPredictionRuntime() { }

    public static boolean start(MinecraftClient client, List<PredictionPayloads.ConfigEntry> config,
                                Map<Integer, String> binds, Map<String, Long> cooldowns,
                                List<String> elements, List<String> subElements, double airBlastDecay) {
        return INSTANCE.start0(client, config, binds, cooldowns, elements, subElements, airBlastDecay);
    }

    public static void updatePlayerState(Map<Integer, String> binds, Map<String, Long> cooldowns,
                                         List<String> elements, List<String> subElements, double airBlastDecay) {
        INSTANCE.updatePlayerState0(binds, cooldowns, elements, subElements, airBlastDecay);
    }

    public static void reconcileActiveFlightAbilities(List<String> activeAbilities, long acknowledgedSequence) {
        INSTANCE.reconcileActiveFlightAbilities0(activeAbilities, acknowledgedSequence);
    }

    public static void tick(MinecraftClient client) { INSTANCE.tick0(client); }
    public static void stop(MinecraftClient client) { INSTANCE.stop0(client); }
    public static boolean isReady() { return INSTANCE.ready; }
    public static boolean supports(String abilityName) {
        return INSTANCE.ready && abilityName != null
                && (CoreAbility.getAbility(abilityName) != null
                || abilityName.equalsIgnoreCase("FireBlastCharged")
                && CoreAbility.getAbility(FireBlastCharged.class) != null);
    }
    public static boolean shouldPredictInput(String abilityName, PredictionPayloads.InputKind kind) {
        if (!supports(abilityName)) return false;
        // Freezing adds collision and is safe to show immediately. Melting
        // removes collision, so it remains server-owned to prevent the player
        // entering ice which the server has not removed yet.
        return !abilityName.equalsIgnoreCase("PhaseChange")
                || kind == PredictionPayloads.InputKind.LEFT_CLICK;
    }
    public static boolean canActivate(String abilityName) {
        return supports(abilityName) && INSTANCE.bendingPlayer != null && !INSTANCE.bendingPlayer.isOnCooldown(abilityName);
    }
    public static boolean isOnLocalCooldown(String abilityName) {
        if (!supports(abilityName) || INSTANCE.bendingPlayer == null) return false;
        INSTANCE.expireCooldowns();
        return INSTANCE.bendingPlayer.isOnCooldown(abilityName);
    }
    public static void removeLocalCooldown(String abilityName) {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null && abilityName != null && !abilityName.isBlank()) {
            INSTANCE.bendingPlayer.removeCooldown(abilityName);
        }
    }
    public static void enforceLocalCooldown(String abilityName, long clientUntilMillis) {
        if (!INSTANCE.ready || INSTANCE.bendingPlayer == null || abilityName == null || abilityName.isBlank()) return;
        long now = System.currentTimeMillis();
        long existing = INSTANCE.bendingPlayer.getCooldown(abilityName);
        if (clientUntilMillis > now && clientUntilMillis > existing) {
            INSTANCE.bendingPlayer.addCooldown(abilityName, clientUntilMillis - now, false);
        }
    }
    public static void resetLocalAirBlast() {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null) INSTANCE.bendingPlayer.resetAirBlast();
    }
    public static void setLocalAirBlastDecay(double value) {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null && Double.isFinite(value)) {
            INSTANCE.bendingPlayer.setAirBlastDecay(Math.max(0.0, Math.min(1.0, value)));
        }
    }

    public static String inputAbilityName(int selectedSlot, String fallback,
                                          PredictionPayloads.InputKind kind) {
        return INSTANCE.inputAbilityName0(selectedSlot, fallback, kind);
    }

    public static boolean input(long sequence, PredictionPayloads.InputKind kind,
                                PredictionClient.ServerPose pose) {
        return INSTANCE.input0(sequence, kind, pose);
    }

    public static PredictionClient.ServerPose executionPose() {
        return INSTANCE.executionPose0();
    }

    public static void predictMovement(MinecraftClient client, PredictionClient.ServerPose from,
                                       PredictionClient.ServerPose to) {
        INSTANCE.predictMovement0(client, from, to);
    }

    public static void reconcile(long sequence, boolean accepted, Vec3d authoritativeOrigin,
                                 String ability, long cooldownUntil) {
        INSTANCE.reconcile0(sequence, accepted, authoritativeOrigin, ability, cooldownUntil);
    }

    public static BlockState blockState(ClientWorld world, BlockPos pos) {
        return world.getBlockState(pos);
    }

    public static void setPredictedBlock(ClientWorld world, BlockPos pos, BlockState state) {
        INSTANCE.setBlock0(world, pos.toImmutable(), state);
    }

    public static boolean authoritativeBlock(ClientWorld world, BlockPos pos, BlockState state) {
        return INSTANCE.authoritativeBlock0(world, pos.toImmutable(), state);
    }

    public static boolean authoritativeBlockBatch(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        return INSTANCE.authoritativeBlockBatch0(world, positions, states);
    }

    public static void acceptAuthoritativeBlockBatch(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        INSTANCE.acceptAuthoritativeBlockBatch0(world, positions, states);
    }

    public static void acceptAuthoritativeChunk(ClientWorld world, int chunkX, int chunkZ) {
        INSTANCE.acceptAuthoritativeChunk0(world, chunkX, chunkZ);
    }

    public static void applyTempBlockBatch(ClientWorld world, PredictionPayloads.TempBlockBatch batch) {
        INSTANCE.applyTempBlockBatch0(world, batch);
    }

    public static List<PredictionDesyncBlock> ownedTempDesyncs(ClientWorld world) {
        return INSTANCE.ownedTempDesyncs0(world);
    }

    public static void setPredictedVelocity(Entity entity, Vec3d velocity) {
        INSTANCE.setVelocity0(entity, velocity);
    }

    public static void noteVelocityOwner(Entity localPlayer, PredictionPayloads.VelocityOwner owner) {
        INSTANCE.noteVelocityOwner0(localPlayer, owner);
    }

    public static void noteVelocityOwner(Entity localPlayer, PredictionPayloads.VelocityOwnerV2 owner) {
        INSTANCE.noteVelocityOwner0(localPlayer, owner);
    }

    public static void removeAuthoritativeAbility(Entity localPlayer, PredictionPayloads.AbilityRemoved removed) {
        INSTANCE.removeAuthoritativeAbility0(localPlayer, removed);
    }

    /** @return true when vanilla must not apply the same impulse a second time. */
    public static boolean authoritativeVelocity(int entityId, Vec3d velocity) {
        return INSTANCE.authoritativeVelocity0(entityId, velocity);
    }

    public static void notePredictedAbilityState(boolean invulnerable, boolean flying, boolean allowFlying,
                                                 boolean creativeMode, float flySpeed, float walkSpeed) {
        INSTANCE.notePredictedAbilityState0(invulnerable, flying, allowFlying, creativeMode, flySpeed, walkSpeed);
    }
    public static void notePredictedExperience(float barProgress, int experience, int level) {
        INSTANCE.notePredictedExperience0(barProgress, experience, level);
    }
    public static boolean suppressAuthoritativeAbilityState(PlayerAbilitiesS2CPacket packet) {
        return INSTANCE.suppressAuthoritativeAbilityState0(packet);
    }
    public static boolean suppressAuthoritativeExperience(ExperienceBarUpdateS2CPacket packet) {
        return INSTANCE.suppressAuthoritativeExperience0(packet);
    }
    public static void notePredictedSelectedSlot(int slot) {
        INSTANCE.notePredictedSelectedSlot0(slot);
    }
    public static boolean suppressAuthoritativeSelectedSlot(UpdateSelectedSlotS2CPacket packet) {
        return INSTANCE.suppressAuthoritativeSelectedSlot0(packet);
    }
    public static boolean suppressAuthoritativeEntityData(int entityId) {
        return INSTANCE.ready && INSTANCE.authoritativeEntityAliases.containsKey(entityId);
    }
    public static boolean suppressAuthoritativeBreakAnimation(ClientWorld world, BlockPos pos) {
        BlockMutation mutation = INSTANCE.blocks.get(new BlockKey(world, pos.toImmutable()));
        return INSTANCE.ready && mutation != null && mutation.locallyPredicted;
    }

    public static void claimDamage(LivingEntity target, double amount) {
        INSTANCE.claimDamage0(target, amount);
    }
    public static void trackSpawn(Entity entity) { INSTANCE.trackSpawn0(entity); }
    public static boolean reconcileSpawn(EntitySpawnS2CPacket packet) { return INSTANCE.reconcileSpawn0(packet); }
    public static Entity aliasedEntity(int serverEntityId) { return INSTANCE.authoritativeEntityAliases.get(serverEntityId); }
    public static boolean hasEntityAlias(int serverEntityId) { return INSTANCE.authoritativeEntityAliases.containsKey(serverEntityId); }
    public static boolean tracksVelocityEntity(int entityId) { return INSTANCE.tracksVelocityEntity0(entityId); }
    public static boolean removeAliasedEntity(int serverEntityId) { return INSTANCE.removeAliasedEntity0(serverEntityId); }
    public static long captureAction() { return INSTANCE.currentAction(); }
    public static void runWithAction(long action, Runnable task) {
        if (action <= 0) { task.run(); return; }
        Long previous = INPUT_ACTION.get();
        INPUT_ACTION.set(action);
        try { task.run(); }
        finally { if (previous == null) INPUT_ACTION.remove(); else INPUT_ACTION.set(previous); }
    }

    private boolean start0(MinecraftClient client, List<PredictionPayloads.ConfigEntry> entries,
                           Map<Integer, String> binds, Map<String, Long> cooldowns,
                           List<String> elements, List<String> subElements, double airBlastDecay) {
        debug("runtime start requested ready=" + ready + " initializing=" + initializing
                + " integratedServer=" + (client.getServer() != null)
                + " player=" + (client.player != null) + " world=" + (client.world != null)
                + " configEntries=" + entries.size() + " binds=" + binds);
        if (ready) {
            applyConfig(entries);
            updatePlayerState0(binds, cooldowns, elements, subElements, airBlastDecay);
            debug("runtime already ready; state refreshed");
            return true;
        }
        // A logical client and an integrated server share one JVM and therefore
        // cannot safely own the old global Platform singleton simultaneously.
        if (client.getServer() != null || client.player == null || client.world == null || initializing) {
            debug("runtime start refused integratedServer=" + (client.getServer() != null)
                    + " player=" + (client.player != null) + " world=" + (client.world != null)
                    + " initializing=" + initializing);
            return false;
        }
        initializing = true;
        try {
            platform = new FabricClientPredictionPlatform(client);
            Platform.install(platform);
            ProjectKorra.initCommon();
            Manager.startup();
            managersStarted = true;
            applyConfig(entries);
            ElementalAbility.clearBendableMaterials();
            ElementalAbility.setupBendableMaterials();
            new MultiAbilityManager();
            new ComboManager();
            CoreAbility.registerAbilities();
            EmbeddedAddonBootstrap.enable();
            // Addon startup registers its own Config instances. Apply the same
            // snapshot again so those late sources receive server values too.
            applyConfig(entries);
            AbilityActivationManager.reload();
            ComboManager.registerCombos();
            FallHandler.loadNoFallDamageAbilities();
            bendingManager = new BendingManager();
            Player player = FabricPredictionMC.player(client.player);
            bendingPlayer = new BendingPlayer(player);
            BendingPlayer.getPlayers().put(player.getUniqueId(), bendingPlayer);
            BendingPlayer.getOfflinePlayers().put(player.getUniqueId(), bendingPlayer);
            CooldownSync.install(this);
            updatePlayerState0(binds, cooldowns, elements, subElements, airBlastDecay);
            ready = true;
            ProjectKorra.log.info("Exact client prediction enabled with " + CoreAbility.getAbilities().size() + " local abilities");
            debug("runtime ready abilities=" + CoreAbility.getAbilities().size()
                    + " activeInstances=" + CoreAbility.getAbilitiesByInstances().size()
                    + " playerElements=" + bendingPlayer.getElements()
                    + " playerSubElements=" + bendingPlayer.getSubElements());
            return true;
        } catch (Throwable failure) {
            ProjectKorra.log.log(Level.SEVERE, "Exact client prediction could not start", failure);
            debug("runtime start failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            stop0(client);
            return false;
        } finally {
            initializing = false;
        }
    }

    private void applyConfig(List<PredictionPayloads.ConfigEntry> entries) {
        Map<String, Map<String, Object>> namespaces = new HashMap<>();
        for (PredictionPayloads.ConfigEntry entry : entries) {
            int dot = entry.path().indexOf('.');
            if (dot <= 0 || dot == entry.path().length() - 1) continue;
            namespaces.computeIfAbsent(entry.path().substring(0, dot), ignored -> new LinkedHashMap<>())
                    .put(entry.path().substring(dot + 1), decode(entry));
        }
        apply(ConfigManager.defaultConfig, namespaces.get("config"));
        apply(ConfigManager.avatarStateConfig, namespaces.get("avatarstate"));
        apply(ConfigManager.collisionConfig, namespaces.get("collision"));
        apply(ConfigManager.fireColorsConfig, namespaces.get("fire_colors"));
        apply(ConfigManager.airColorsConfig, namespaces.get("air_colors"));
        apply(ConfigManager.waterCosmeticsConfig, namespaces.get("water_cosmetics"));
        apply(ConfigManager.earthCosmeticsConfig, namespaces.get("earth_cosmetics"));
        apply(ConfigManager.fallDamageConfig, namespaces.get("fall_damage"));
        if (ConfigManager.styleConfigs != null) {
            for (int i = 0; i < ConfigManager.styleConfigs.size(); i++) apply(ConfigManager.styleConfigs.get(i), namespaces.get("style_" + i));
        }
        PredictionConfigSync.sources().forEach((namespace, source) ->
                apply(source, namespaces.get(namespace)));
    }

    private static void apply(Config config, Map<String, Object> values) {
        if (config != null && values != null) config.applyRemoteValues(values);
    }

    private static Object decode(PredictionPayloads.ConfigEntry entry) {
        if (entry.type() == PredictionPayloads.ValueType.STRING_LIST) return List.copyOf(entry.values());
        String value = entry.values().isEmpty() ? "" : entry.values().get(0);
        try {
            return switch (entry.type()) {
                case BOOLEAN -> Boolean.parseBoolean(value);
                case INTEGER -> Long.parseLong(value);
                case DECIMAL -> Double.parseDouble(value);
                default -> value;
            };
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private void updatePlayerState0(Map<Integer, String> binds, Map<String, Long> cooldowns,
                                    List<String> elements, List<String> subElements, double airBlastDecay) {
        if (!ready && !initializing || bendingPlayer == null) {
            debug("runtime state ignored ready=" + ready + " initializing=" + initializing
                    + " hasBendingPlayer=" + (bendingPlayer != null));
            return;
        }
        // A state packet sent before the server processes the WaterArms cast
        // can arrive after the client has already entered its multi-bind. Do
        // not replace those local mode slots with the stale pre-cast binds.
        // Rejection/removal calls unbindMultiAbility and restores them.
        if (!MultiAbilityManager.hasMultiAbilityBound(bendingPlayer.getPlayer())) {
            bendingPlayer.getAbilities().clear();
            bendingPlayer.getAbilities().putAll(binds);
        }
        bendingPlayer.getElements().clear();
        for (String name : elements) {
            Element element = Element.getElement(name);
            if (element != null && !(element instanceof Element.SubElement)) bendingPlayer.getElements().add(element);
        }
        bendingPlayer.getSubElements().clear();
        for (String name : subElements) {
            Element element = Element.getElement(name);
            if (element instanceof Element.SubElement subElement) bendingPlayer.getSubElements().add(subElement);
        }
        PassiveManager.registerPassives(bendingPlayer.getPlayer());
        // The exact Fabric runtime owns cooldown expiry times so latency never
        // extends a locally started cooldown. Server state still reconciles
        // presence transitions: once the server has confirmed a predicted
        // cooldown, its later absence must release a stale local gate.
        expireCooldowns();
        reconcileAuthoritativeCooldowns(cooldowns);
        // Seed stamina once when prediction starts. Periodic server snapshots
        // arrive on a different clock and otherwise fight the locally
        // progressed AirAgility XP bar. Explicit resets use StateDirective.
        if (initializing || !ready) {
            bendingPlayer.setAirBlastDecay(airBlastDecay);
        }
        debug("runtime state applied binds=" + bendingPlayer.getAbilities()
                + " elements=" + bendingPlayer.getElements()
                + " subElements=" + bendingPlayer.getSubElements()
                + " cooldowns=" + bendingPlayer.getCooldowns().keySet()
                + " airBlastStamina=" + airBlastStamina());
    }

    private String inputAbilityName0(int selectedSlot, String fallback, PredictionPayloads.InputKind kind) {
        if (!ready || bendingPlayer == null) return fallback == null ? "" : fallback;
        Player player = bendingPlayer.getPlayer();
        if (kind == PredictionPayloads.InputKind.SNEAK_START && canStartFastSwim(player)) {
            return "FastSwim";
        }
        String multi = MultiAbilityManager.getBoundMultiAbility(player);
        if (multi != null && !multi.isBlank()) return multi;
        String local = bendingPlayer.getAbilities().get(selectedSlot + 1);
        String selected = local == null || local.isBlank() ? (fallback == null ? "" : fallback) : local;
        if (selected.equalsIgnoreCase("FireBlast")
                && (kind == PredictionPayloads.InputKind.SNEAK_START || kind == PredictionPayloads.InputKind.SNEAK_STOP)) {
            return "FireBlastCharged";
        }
        // WallRun is a global passive click and deliberately does not need a
        // bind. Give an otherwise unbound left click a logical ability so it
        // enters the exact prediction/reconciliation path.
        if (selected.isBlank() && kind == PredictionPayloads.InputKind.LEFT_CLICK
                && bendingPlayer.isToggled() && CoreAbility.getAbility(WallRun.class) != null) {
            return "WallRun";
        }
        return selected;
    }

    private boolean canStartFastSwim(Player player) {
        if (player == null || CoreAbility.hasAbility(player, FastSwim.class)) return false;
        CoreAbility bound = bendingPlayer.getBoundAbility();
        CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
        return (bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive);
    }

    private boolean input0(long sequence, PredictionPayloads.InputKind kind, PredictionClient.ServerPose pose) {
        if (!ready || bendingPlayer == null) {
            debug("runtime input skipped sequence=" + sequence + " kind=" + kind
                    + " ready=" + ready + " hasBendingPlayer=" + (bendingPlayer != null));
            return false;
        }
        expireCooldowns();
        Set<CoreAbility> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(CoreAbility.getAbilitiesByInstances());
        Player player = bendingPlayer.getPlayer();
        String boundName = inputAbilityName0(player.getInventory().getHeldItemSlot(), bendingPlayer.getBoundAbilityName(), kind);
        Vec3d origin = pose.eyePos();
        Action action = new Action(sequence, tick, origin, pose.yaw(), pose.pitch(), pose.eyeHeight(), boundName);
        actions.put(sequence, action);
        boolean failed = false;
        boolean handled = false;
        debug("runtime input start sequence=" + sequence + " kind=" + kind
                + " bound=" + boundName
                + " activeBefore=" + before.size() + " tick=" + tick);
        INPUT_ACTION.set(sequence);
        AbilityActivationManager.beginTracking();
        try {
            PredictionDeterminism.run(sequence, () -> {
                switch (kind) {
                    case LEFT_CLICK -> {
                        CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>());
                        com.projectkorra.projectkorra.platform.mc.entity.Entity target =
                                GeneralMethods.getTargetedEntity(player, 3);
                        if (target instanceof com.projectkorra.projectkorra.platform.mc.entity.LivingEntity living
                                && !target.equals(player)) {
                            CommonInputHandler.handleEntityLeftClick(player, living);
                        }
                    }
                    case RIGHT_CLICK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
                    case RIGHT_CLICK_BLOCK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
                    case RIGHT_CLICK_ENTITY -> CommonInputHandler.handleRightClickEntity(player);
                    case SNEAK_START -> CommonInputHandler.handleSneak(player, false);
                    case SNEAK_STOP -> CommonInputHandler.handleSneak(player, true);
                }
                if (kind == PredictionPayloads.InputKind.LEFT_CLICK && "AirBlast".equalsIgnoreCase(bendingPlayer.getBoundAbilityName())) {
                    ensurePredictedAirBlastShot(player);
                }
            });
        } catch (Throwable failure) {
            ProjectKorra.log.warning("Predicted input " + sequence + " failed: " + failure.getMessage());
            debug("runtime input failed sequence=" + sequence + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            failed = true;
        } finally {
            handled = AbilityActivationManager.finishTracking();
            INPUT_ACTION.remove();
        }
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability)) {
                abilityActions.put(ability, sequence);
                action.abilities.add(ability);
                debug("runtime created ability sequence=" + sequence + " ability=" + ability.getName()
                        + " instance=" + System.identityHashCode(ability));
            }
        }
        debug("runtime input finish sequence=" + sequence + " created=" + action.abilities.size()
                + " activeAfter=" + CoreAbility.getAbilitiesByInstances().size() + " failed=" + failed);
        if (failed) {
            rollback(action);
            return false;
        }
        // Sneak transitions are also consumed indirectly by abilities which
        // inspect Player#isSneaking from their next progress tick.  There is
        // no activation callback for that path, so AbilityActivationManager
        // quite correctly reports handled=false even though the input changes
        // the running ability.  Associate the ability with this action anyway
        // so delayed source selection (EarthSmash and similar abilities) uses
        // the exact release pose on both Fabric and Paper.
        boolean hasMatchingExistingAbility = affectedExistingAbility(before, boundName);
        boolean deferredSneakTransition = (kind == PredictionPayloads.InputKind.SNEAK_START
                || kind == PredictionPayloads.InputKind.SNEAK_STOP) && hasMatchingExistingAbility;
        boolean affectedExisting = (handled || deferredSneakTransition) && hasMatchingExistingAbility;
        boolean locallyPredicted = !action.abilities.isEmpty() || !action.spawned.isEmpty() || affectedExisting
                || (kind == PredictionPayloads.InputKind.LEFT_CLICK
                && "AirBlast".equalsIgnoreCase(bendingPlayer.getBoundAbilityName())
                && CoreAbility.getAbilities(bendingPlayer.getPlayer(), AirBlast.class).stream().anyMatch(AirBlast::isProgressing));
        action.locallyPredicted = locallyPredicted;
        if (affectedExisting) {
            for (CoreAbility ability : before) {
                if (ability != null && !ability.isRemoved() && matchesInputAbility(ability, boundName)
                        && ability.getPlayer() != null
                        && ability.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                    action.affectedAbilities.add(ability);
                    abilityActions.put(ability, sequence);
                }
            }
        }
        debug("runtime input localPrediction sequence=" + sequence + " immediateEffect=" + locallyPredicted
                + " affectedExisting=" + affectedExisting);
        // The server processes every frame regardless of this flag. This flag
        // only says whether the client produced an effect that must be hidden
        // or rolled back during reconciliation.
        return locallyPredicted;
    }

    private boolean affectedExistingAbility(Set<CoreAbility> before, String boundName) {
        if (before == null || before.isEmpty() || boundName == null || boundName.isBlank() || bendingPlayer == null) return false;
        Player player = bendingPlayer.getPlayer();
        if (player == null) return false;
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability) || ability.isRemoved() || !matchesInputAbility(ability, boundName)) continue;
            if (ability.getPlayer() == null || !ability.getPlayer().getUniqueId().equals(player.getUniqueId())) continue;
            return true;
        }
        return false;
    }

    private static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName())
                || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    private void predictMovement0(MinecraftClient client, PredictionClient.ServerPose fromPose,
                                  PredictionClient.ServerPose toPose) {
        if (!ready || bendingPlayer == null || client == null || client.player == null || client.world == null
                || fromPose == null || toPose == null) {
            return;
        }
        Location from = FabricPredictionMC.location(client.world,
                new Vec3d(fromPose.x(), fromPose.y(), fromPose.z()), fromPose.yaw(), fromPose.pitch());
        Location to = FabricPredictionMC.location(client.world,
                new Vec3d(toPose.x(), toPose.y(), toPose.z()), toPose.yaw(), toPose.pitch());
        CommonPlayerListenerCore.MovementResult result = CommonPlayerListenerCore.handleMove(
                bendingPlayer.getPlayer(), from, to, false, false, 0.0);
        if (result.cancelEvent()) {
            debug("runtime movement prediction requested cancel from common listener");
        }
    }

    private void tick0(MinecraftClient client) {
        tick++;
        if (!ready || client.player == null || client.world == null) {
            if (DEBUG && tick % 20 == 0) debug("runtime tick skipped ready=" + ready
                    + " player=" + (client.player != null) + " world=" + (client.world != null));
            return;
        }
        try {
            expireCooldowns();
            platform.tick();
            bendingManager.run();
        } catch (Throwable failure) {
            ProjectKorra.log.warning("Predicted ability tick failed: " + failure.getMessage());
            debug("runtime tick failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        Set<CoreAbility> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(CoreAbility.getAbilitiesByInstances());
        abilityActions.keySet().removeIf(ability -> !live.contains(ability));
        velocities.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        velocityReceipts.removeIf(receipt -> tick - receipt.receivedTick > VELOCITY_RECEIPT_TICKS);
        blockEchoes.removeIf(echo -> tick - echo.tick > ACTION_RETENTION_TICKS);
        localBlockHistory.removeIf(echo -> tick - echo.tick > ACTION_RETENTION_TICKS);
        ownedBatchRestores.entrySet().removeIf(entry -> tick - entry.getValue().tick > 1);
        abilityStates.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        experiences.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        selectedSlots.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        blocks.entrySet().removeIf(entry -> {
            BlockMutation mutation = entry.getValue();
            mutation.ownedTempReceipts.removeIf(receipt -> tick - receipt.tick > OWNED_TEMP_RECEIPT_TICKS);
            long age = tick - mutation.lastTick;
            int confirmationTicks = mutation.serverTempActive
                    ? BLOCK_CONFIRMATION_TICKS : blockConfirmationTicks(mutation.lastAction);
            if (age <= confirmationTicks) return false;
            BlockState latestAuthority = mutation.serverTempActive && mutation.serverTempState != null
                    ? mutation.serverTempState : mutation.authoritative;
            // Retire the common TempBlock object as part of the same authority
            // transition. Otherwise its later ability cleanup can write the old
            // captured state back after this correction and create a second ghost.
            invalidateClientTempStack(mutation.world, mutation.pos);
            if (mutation.world == client.world && !mutation.world.getBlockState(mutation.pos).equals(latestAuthority)) {
                mutation.world.setBlockState(mutation.pos, latestAuthority, 19);
                debug("runtime corrected unconfirmed predicted block pos=" + mutation.pos
                        + " predicted=" + mutation.predicted + " authority=" + latestAuthority);
            }
            mutation.adoptAuthority(latestAuthority);
            clearBlockEchoes(mutation.world, mutation.pos);
            // Keep only the layer metadata needed to interpret a later REVERT.
            // The visible world has still been forced back to latest authority.
            return !mutation.serverTempActive;
        });
        Iterator<Action> iterator = actions.values().iterator();
        while (iterator.hasNext()) {
            Action action = iterator.next();
            action.abilities.removeIf(ability -> !live.contains(ability));
            if (tick - action.createdTick > ACTION_RETENTION_TICKS && action.abilities.isEmpty()) {
                action.spawned.forEach(predictedSpawnOrigins::remove);
                iterator.remove();
            }
        }
        if (DEBUG && tick % 20 == 0) {
            debug("runtime tick=" + tick + " active=" + live.size()
                    + " actions=" + actions.size() + " predictedBlocks=" + blocks.size()
                    + " velocities=" + velocities.size());
        }
    }

    private void expireCooldowns() {
        if (bendingPlayer == null) return;
        long now = System.currentTimeMillis();
        bendingPlayer.getCooldowns().entrySet().removeIf(entry -> entry.getValue().getCooldown() <= now);
        cooldownAuthority.retainLocallyActive(Set.copyOf(bendingPlayer.getCooldowns().keySet()));
    }

    private void reconcileAuthoritativeCooldowns(final Map<String, Long> authoritativeCooldowns) {
        if (bendingPlayer == null) return;
        final Set<String> authoritative = authoritativeCooldowns == null
                ? Set.of() : Set.copyOf(authoritativeCooldowns.keySet());
        final Set<String> local = Set.copyOf(bendingPlayer.getCooldowns().keySet());
        for (final String ability : cooldownAuthority.reconcile(authoritative, local)) {
            bendingPlayer.removeCooldown(ability);
            debug("runtime released server-expired predicted cooldown ability=" + ability);
        }
    }

    private void ensurePredictedAirBlastShot(Player player) {
        if (bendingPlayer == null || bendingPlayer.isOnCooldown("AirBlast")) {
            debug("runtime did not repair AirBlast while cooldown is active");
            return;
        }
        if (!AirBlast.hasSufficientStamina(bendingPlayer)) {
            debug("runtime did not repair AirBlast without sufficient stamina=" + airBlastStamina());
            return;
        }
        boolean hadShootableBlast = false;
        for (AirBlast blast : CoreAbility.getAbilities(player, AirBlast.class)) {
            if (blast.getSource() == null && !blast.isProgressing()) {
                hadShootableBlast = true;
                shootPredictedAirBlast(player);
                debug("runtime repaired predicted AirBlast staged shot progressing="
                        + CoreAbility.getAbilities(player, AirBlast.class).stream().anyMatch(AirBlast::isProgressing));
                return;
            }
        }
        if (!hadShootableBlast && CoreAbility.getAbilities(player, AirBlast.class).stream().noneMatch(AirBlast::isProgressing)) {
            shootPredictedAirBlast(player);
            debug("runtime repaired predicted AirBlast direct shot progressing="
                    + CoreAbility.getAbilities(player, AirBlast.class).stream().anyMatch(AirBlast::isProgressing));
        }
    }

    private void shootPredictedAirBlast(Player player) {
        Cooldown previous = bendingPlayer == null ? null : bendingPlayer.getCooldowns().remove("AirBlast");
        int before = CoreAbility.getAbilities(player, AirBlast.class).size();
        AirBlast.shoot(player);
        boolean progressed = CoreAbility.getAbilities(player, AirBlast.class).stream().anyMatch(AirBlast::isProgressing);
        if (!progressed) {
            for (AirBlast blast : CoreAbility.getAbilities(player, AirBlast.class)) {
                if (blast.getSource() == null && !blast.isProgressing()) {
                    progressed = forcePredictedAirBlast(blast, player);
                    break;
                }
            }
        }
        if (!progressed && previous != null && bendingPlayer != null) {
            bendingPlayer.getCooldowns().put("AirBlast", previous);
        }
        debug("runtime predicted AirBlast shoot before=" + before
                + " after=" + CoreAbility.getAbilities(player, AirBlast.class).size()
                + " progressed=" + progressed
                + " restoredCooldown=" + (!progressed && previous != null));
    }

    private boolean forcePredictedAirBlast(AirBlast blast, Player player) {
        if (blast == null || player == null) return false;
        Location origin = blast.getOrigin() == null ? player.getEyeLocation() : blast.getOrigin().clone();
        Vector direction = player.getEyeLocation().getDirection();
        if (direction == null || direction.lengthSquared() == 0
                || !Double.isFinite(direction.getX()) || !Double.isFinite(direction.getY()) || !Double.isFinite(direction.getZ())) {
            return false;
        }
        blast.setFromOtherOrigin(true);
        blast.setOrigin(origin);
        blast.setLocation(origin.clone());
        blast.setDirection(direction.normalize());
        blast.setTicks(0);
        blast.setProgressing(true);
        try {
            Field lag = AirBlast.class.getDeclaredField("lagCompensator");
            lag.trySetAccessible();
            Method affect = AirBlast.class.getDeclaredMethod("affect",
                    com.projectkorra.projectkorra.platform.mc.entity.Entity.class, Location.class);
            affect.trySetAccessible();
            lag.set(blast, new AbilityLagCompensator((target, snapshot) -> {
                try {
                    if (target.getUniqueId().equals(player.getUniqueId())) {
                        debug("runtime AirBlast affect local before stamina=" + airBlastStamina()
                                + " velocity=" + velocityString(MinecraftClient.getInstance().player == null ? null : MinecraftClient.getInstance().player.getVelocity())
                                + " blastLocation=" + snapshot.getLocation()
                                + " blastProgressing=" + blast.isProgressing()
                                + " blastTicks=" + blast.getTicks()
                                + " blastRadius=" + blast.getRadius());
                    }
                    affect.invoke(blast, target, snapshot.getLocation());
                    if (target.getUniqueId().equals(player.getUniqueId())) {
                        debug("runtime AirBlast affect local after stamina=" + airBlastStamina()
                                + " velocity=" + velocityString(MinecraftClient.getInstance().player == null ? null : MinecraftClient.getInstance().player.getVelocity()));
                    }
                } catch (ReflectiveOperationException exception) {
                    debug("runtime forced AirBlast affect failed: " + exception.getMessage());
                }
            }));
        } catch (ReflectiveOperationException exception) {
            debug("runtime forced AirBlast setup failed: " + exception.getMessage());
            blast.setProgressing(false);
            return false;
        }
        debug("runtime forced predicted AirBlast origin=" + origin + " direction=" + direction);
        return true;
    }

    private void reconcile0(long sequence, boolean accepted, Vec3d authoritativeOrigin,
                            String ability, long cooldownUntil) {
        // Never import the server timestamp here. The locally executed PK
        // ability is the sole source of the Fabric client's cooldown length.
        Action action = actions.get(sequence);
        if (action == null) {
            debug("runtime reconcile missing action sequence=" + sequence + " accepted=" + accepted + " ability=" + ability);
            return;
        }
        action.accepted = accepted;
        if (!accepted) {
            debug("runtime reconcile rejected sequence=" + sequence + " ability=" + ability);
            if (isEarthSmashAction(action) && !action.affectedAbilities.isEmpty()) {
                handoffEarthSmashToAuthority(action, "rejected existing-state input");
            }
            rollback(action);
            actions.remove(sequence);
            return;
        }
        if (isEarthSmashAction(action) && !action.locallyPredicted) {
            handoffEarthSmashToAuthority(action, "server accepted an unpredicted state transition");
        }
        // The time from local execution to this accepted reconcile is a direct
        // measurement of how far the client simulation runs ahead of the server.
        // A client-only TempBlock that remains unconfirmed beyond that window is
        // a negative receipt: the server has reached the same ability age and did
        // not create it. This retires moving Water/Earth trails without the old
        // unconditional two-second ghost window.
        action.blockConfirmationTicks = Math.max(MIN_ACTION_BLOCK_CONFIRMATION_TICKS,
                Math.min(BLOCK_CONFIRMATION_TICKS,
                        (int) Math.max(0L, tick - action.createdTick)
                                + ACTION_BLOCK_CONFIRMATION_MARGIN_TICKS));
        Vec3d correction = authoritativeOrigin.subtract(action.origin);
        if (correction.lengthSquared() > 1.0E-6 && correction.lengthSquared() < 16.0) {
            for (CoreAbility activeAbility : List.copyOf(action.abilities)) correctLocations(activeAbility, action.origin, correction);
            debug("runtime reconcile corrected sequence=" + sequence + " correction=" + correction + " ability=" + ability);
        } else {
            debug("runtime reconcile accepted sequence=" + sequence + " ability=" + ability + " correctionSquared=" + correction.lengthSquared());
        }
    }

    private void rollback(Action action) {
        debug("runtime rollback sequence=" + action.sequence + " abilities=" + action.abilities.size()
                + " spawned=" + action.spawned.size());
        for (CoreAbility ability : List.copyOf(action.abilities)) {
            try { ability.remove(); } catch (Throwable ignored) { }
            abilityActions.remove(ability);
        }
        action.abilities.clear();
        for (Entity entity : action.spawned) if (entity != null && !entity.isRemoved()) entity.discard();
        authoritativeEntityAliases.entrySet().removeIf(entry -> action.spawned.contains(entry.getValue()));
        action.spawned.forEach(predictedSpawnOrigins::remove);
        action.spawned.clear();
        blockEchoes.removeIf(echo -> echo.action == action.sequence);
        localBlockHistory.removeIf(echo -> echo.action == action.sequence);
        blocks.entrySet().removeIf(entry -> {
            BlockMutation mutation = entry.getValue();
            if (mutation.lastAction != action.sequence) return false;
            if (mutation.world == MinecraftClient.getInstance().world) {
                mutation.world.setBlockState(entry.getKey().pos,
                        mutation.serverTempActive && mutation.serverTempState != null
                                ? mutation.serverTempState : mutation.authoritative, 19);
            }
            // Keep tracking a server-confirmed layer after rolling back the
            // local action which happened to overlap it.
            return !mutation.serverTempActive;
        });
        for (int i = velocities.size() - 1; i >= 0; i--) {
            VelocityMutation mutation = velocities.get(i);
            if (mutation.action != action.sequence) continue;
            Entity entity = mutation.world.getEntityById(mutation.entityId);
            if (entity != null && close(entity.getVelocity(), mutation.predicted, .08)) entity.setVelocity(mutation.before);
            velocities.remove(i);
        }
    }

    private boolean isEarthSmashAction(Action action) {
        return action != null && "EarthSmash".equalsIgnoreCase(action.inputAbility);
    }

    private int blockConfirmationTicks(final long actionSequence) {
        final Action action = actions.get(actionSequence);
        return action == null || !action.accepted
                ? BLOCK_CONFIRMATION_TICKS : action.blockConfirmationTicks;
    }

    private void handoffEarthSmashToAuthority(Action action, String reason) {
        if (bendingPlayer == null || bendingPlayer.getPlayer() == null) return;
        for (EarthSmash smash : List.copyOf(CoreAbility.getAbilities(bendingPlayer.getPlayer(), EarthSmash.class))) {
            try { smash.remove(); } catch (Throwable ignored) { }
            abilityActions.remove(smash);
            if (action != null) {
                action.abilities.remove(smash);
                action.affectedAbilities.remove(smash);
            }
        }
        if (action != null) action.locallyPredicted = false;
        if (action != null) PredictionClient.requestAuthorityHandoff(action.sequence);
        debug("runtime handed EarthSmash to server authority sequence="
                + (action == null ? 0 : action.sequence) + " reason=" + reason);
    }

    private void stop0(MinecraftClient client) {
        debug("runtime stop ready=" + ready + " initializing=" + initializing
                + " activeInstances=" + CoreAbility.getAbilitiesByInstances().size()
                + " actions=" + actions.size());
        if (ready || initializing) {
            try { CoreAbility.removeAll(); } catch (Throwable ignored) { }
            try { EmbeddedAddonBootstrap.disable(); } catch (Throwable ignored) { }
            if (managersStarted) {
                try { Manager.shutdown(); } catch (Throwable ignored) { }
                managersStarted = false;
            }
        }
        for (BlockMutation mutation : blocks.values()) {
            if (mutation.world == client.world) {
                mutation.world.setBlockState(mutation.pos,
                        mutation.serverTempActive && mutation.serverTempState != null
                                ? mutation.serverTempState : mutation.authoritative, 19);
            }
        }
        if (bendingPlayer != null) {
            BendingPlayer.getPlayers().remove(bendingPlayer.getPlayer().getUniqueId());
            BendingPlayer.getOfflinePlayers().remove(bendingPlayer.getPlayer().getUniqueId());
        }
        if (platform != null) platform.close();
        for (Action action : actions.values()) for (Entity entity : action.spawned) {
            if (entity != null && !entity.isRemoved()) entity.discard();
        }
        actions.clear(); abilityActions.clear(); blocks.clear(); blockEchoes.clear(); localBlockHistory.clear();
        ownedBatchRestores.clear(); velocities.clear(); velocityReceipts.clear();
        abilityStates.clear(); experiences.clear(); selectedSlots.clear(); authoritativeEntityAliases.clear();
        predictedSpawnOrigins.clear();
        CooldownSync.clear(this);
        cooldownAuthority.clear();
        platform = null; bendingManager = null; bendingPlayer = null; ready = false; managersStarted = false;
        debug("runtime stopped");
    }

    @Override
    public void onAdded(BendingPlayer player, String ability, long expiresAtMillis) {
        if (player != bendingPlayer || ability == null || ability.isBlank()) return;
        cooldownAuthority.onLocalAdded(ability, expiresAtMillis);
    }

    @Override
    public void onRemoved(BendingPlayer player, String ability) {
        if (player != bendingPlayer || ability == null) return;
        cooldownAuthority.onLocalRemoved(ability);
    }

    private void setBlock0(ClientWorld world, BlockPos pos, BlockState state) {
        if (!ready) return;
        long action = currentAction();
        BlockKey key = new BlockKey(world, pos);
        BlockMutation mutation = blocks.computeIfAbsent(key, ignored -> new BlockMutation(world, pos, world.getBlockState(pos)));
        if (action > 0L) {
            // Once the local simulation has acted on this coordinate, its
            // remaining server TempBlock stack is logical authority. Falling
            // through an owned lower ICE layer to original WATER would make a
            // PhaseChange REVERT resolve to a block that does not exist server-side.
            mutation.localPredictionObserved = true;
            if (mutation.serverTempActive) {
                mutation.serverTempState = visibleServerLayer(mutation, mutation.authoritative);
            }
        }
        // A predicted action may advance its own confirmed TempBlock layer.
        // Non-owned layers remain protected until server lifecycle metadata or
        // ordinary vanilla authority changes them.
        boolean advancesOwnedLayer = action > 0L && isTopLayerOwnedByLocalPlayer(mutation);
        if (!advancesOwnedLayer && !PredictionEchoPolicy.mayApplyLocalMutationOverServerTemp(mutation.serverTempActive,
                mutation.serverTempState != null && mutation.serverTempState.equals(state))) {
            // A local ability instance is cleaning up ahead of the server.
            // Keep the confirmed server layer until its explicit REVERT.
            world.setBlockState(pos, mutation.serverTempState, 19);
            debug("runtime deferred local block mutation over active server temp pos=" + pos
                    + " requested=" + state + " server=" + mutation.serverTempState);
            return;
        }
        // Both halves of a predicted move are ordered echoes. In particular,
        // delayed AIR packets from EarthBlast/WaterManipulation must not punch
        // a temporary hole after the client has already advanced and restored
        // that position.
        BlockEcho echo = new BlockEcho(world, pos, state, tick, false, action, ++blockEchoOrdinal);
        blockEchoes.add(echo);
        localBlockHistory.add(echo);
        if (state.equals(mutation.authoritative)) {
            if (mutation.serverTempActive) {
                mutation.lastAction = action;
                mutation.lastTick = tick;
                mutation.predicted = state;
                mutation.confirmed = false;
                mutation.locallyPredicted = true;
            } else {
                blocks.remove(key);
            }
            world.setBlockState(pos, state, 19);
            return;
        }
        mutation.lastAction = action;
        mutation.lastTick = tick;
        mutation.predicted = state;
        mutation.confirmed = false;
        mutation.locallyPredicted = true;
        world.setBlockState(pos, state, 19);
    }

    private boolean authoritativeBlock0(ClientWorld world, BlockPos pos, BlockState state) {
        BlockKey key = new BlockKey(world, pos);
        BlockMutation ownedMutation = blocks.get(key);
        OwnedTempReceipt ownedReceipt = consumeOwnedTempReceipt(ownedMutation, state);
        if (ownedReceipt != null) {
            applyOwnedTempReceipt(ownedMutation, state, ownedReceipt);
            debug("runtime suppressed owned temp-block authority pos=" + pos
                    + " operation=" + ownedReceipt.operation + " packet=" + state
                    + " desired=" + ownedReceipt.desiredState);
            return true;
        }
        if (consumeBlockEcho(world, pos, state)) {
            BlockMutation echoed = blocks.get(key);
            if (echoed != null) {
                echoed.recordAuthority(state);
                if (echoed.serverTempActive && state.equals(echoed.serverTempState)) {
                    echoed.adoptAuthority(state);
                }
            }
            debug("runtime suppressed predicted block echo pos=" + pos + " state=" + state);
            return true;
        }
        BlockMutation mutation = blocks.get(key);
        if (mutation == null) {
            invalidateClientTempStack(world, pos);
            return false;
        }
        mutation.recordAuthority(state);
        if (mutation.confirmed) {
            return world.getBlockState(pos).equals(state);
        }
        if (mutation.serverTempActive && state.equals(mutation.serverTempState)) {
            // Adopt the confirmed server layer in the ledger, but only cancel
            // the packet when the actual client world already matches it.
            // Layer ownership protects against local cleanup; it must never
            // conceal an authoritative correction.
            mutation.adoptAuthority(state);
            return world.getBlockState(pos).equals(state);
        }
        clearBlockEchoes(world, pos);
        invalidateClientTempStack(world, pos);
        blocks.remove(key);
        return false;
    }

    private boolean authoritativeBlockBatch0(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        if (!ready || world == null || positions == null || states == null || positions.isEmpty() || positions.size() != states.size()) return false;
        List<Integer> echoMatches = new ArrayList<>(positions.size());
        List<Integer> ownedMatches = new ArrayList<>(positions.size());
        Set<Integer> reserved = new HashSet<>();
        boolean suppressWholeBatch = true;
        for (int i = 0; i < positions.size(); i++) {
            BlockMutation mutation = blocks.get(new BlockKey(world, positions.get(i).toImmutable()));
            int ownedMatch = findOwnedTempReceipt(mutation, states.get(i));
            ownedMatches.add(ownedMatch);
            if (ownedMatch >= 0) {
                echoMatches.add(-1);
                continue;
            }
            int match = findBlockEcho(world, positions.get(i), states.get(i), reserved);
            echoMatches.add(match);
            if (match < 0) {
                suppressWholeBatch = false;
                continue;
            }
            BlockEcho echo = blockEchoes.get(match);
            boolean newer = hasNewerBlockEcho(world, positions.get(i), match);
            if (PredictionEchoPolicy.shouldSuppress(echo.force, newer,
                    world.getBlockState(positions.get(i)).equals(states.get(i)))) {
                reserved.add(match);
            } else {
                suppressWholeBatch = false;
            }
        }
        if (!suppressWholeBatch) {
            for (int i = 0; i < positions.size(); i++) {
                if (ownedMatches.get(i) < 0) continue;
                BlockPos pos = positions.get(i).toImmutable();
                BlockKey key = new BlockKey(world, pos);
                BlockMutation mutation = blocks.get(key);
                OwnedTempReceipt receipt = consumeOwnedTempReceipt(mutation, states.get(i));
                if (receipt == null) continue;
                applyOwnedTempReceipt(mutation, states.get(i), receipt);
                ownedBatchRestores.put(key, new OwnedBatchRestore(
                        receipt.desiredState, receipt.locallyPredicted, tick));
            }
            return false;
        }
        echoMatches.stream().filter(index -> index >= 0).distinct().sorted(Comparator.reverseOrder())
                .forEach(index -> blockEchoes.remove((int) index));
        for (int i = 0; i < positions.size(); i++) {
            BlockMutation mutation = blocks.get(new BlockKey(world, positions.get(i).toImmutable()));
            if (mutation == null) continue;
            BlockState authoritative = states.get(i);
            if (ownedMatches.get(i) >= 0) {
                OwnedTempReceipt receipt = consumeOwnedTempReceipt(mutation, authoritative);
                if (receipt != null) applyOwnedTempReceipt(mutation, authoritative, receipt);
                continue;
            }
            mutation.recordAuthority(authoritative);
            if (mutation.serverTempActive && authoritative.equals(mutation.serverTempState)) {
                mutation.adoptAuthority(authoritative);
            }
        }
        debug("runtime suppressed predicted block batch echoes=" + positions.size());
        return true;
    }

    private void acceptAuthoritativeBlockBatch0(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        if (!ready || world == null || positions == null || states == null || positions.isEmpty() || positions.size() != states.size()) return;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i).toImmutable();
            BlockKey key = new BlockKey(world, pos);
            BlockMutation mutation = blocks.get(key);
            BlockState incoming = states.get(i);
            OwnedBatchRestore restore = ownedBatchRestores.remove(key);
            if (restore != null) {
                restoreOwnedTempAfterBatch(world, pos, mutation, incoming, restore);
                continue;
            }
            clearBlockEchoes(world, pos);
            if (mutation == null) {
                invalidateClientTempStack(world, pos);
                continue;
            }
            if (!mutation.serverTempActive || !incoming.equals(mutation.serverTempState)) {
                invalidateClientTempStack(world, pos);
            }
            mutation.adoptAuthority(incoming);
            if (!mutation.serverTempActive || !incoming.equals(mutation.serverTempState)) blocks.remove(key);
        }
    }

    private void acceptAuthoritativeChunk0(final ClientWorld world, final int chunkX, final int chunkZ) {
        if (!ready || world == null) return;
        Set<BlockPos> preservedOwned = new HashSet<>();
        for (Map.Entry<BlockKey, BlockMutation> entry : blocks.entrySet()) {
            BlockKey key = entry.getKey();
            if (key.world == world && key.pos.getX() >> 4 == chunkX && key.pos.getZ() >> 4 == chunkZ
                    && hasOwnedLayer(entry.getValue())) preservedOwned.add(key.pos);
        }
        String worldName = FabricPredictionMC.world(world).getName();
        Set<BlockPos> invalidated = new HashSet<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            com.projectkorra.projectkorra.platform.mc.block.Block block = layer.getBlock();
            if (block.getWorld() == null || !block.getWorld().getName().equals(worldName)
                    || block.getX() >> 4 != chunkX || block.getZ() >> 4 != chunkZ) continue;
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable();
            if (!preservedOwned.contains(pos) && invalidated.add(pos)) invalidateClientTempStack(world, pos);
        }
        blockEchoes.removeIf(echo -> echo.world == world && echo.pos.getX() >> 4 == chunkX
                && echo.pos.getZ() >> 4 == chunkZ);
        blocks.entrySet().removeIf(entry -> {
            final BlockKey key = entry.getKey();
            if (key.world != world || key.pos.getX() >> 4 != chunkX || key.pos.getZ() >> 4 != chunkZ) return false;
            final BlockMutation mutation = entry.getValue();
            if (!hasOwnedLayer(mutation)) return true;
            BlockState desired = mutation.serverTempState;
            if (desired == null) return true;
            world.setBlockState(key.pos, desired, 19);
            mutation.predicted = desired;
            mutation.confirmed = mutation.authoritative.equals(mutation.predicted);
            return false;
        });
    }

    private void applyTempBlockBatch0(ClientWorld world, PredictionPayloads.TempBlockBatch batch) {
        if (!ready || world == null || batch == null) return;
        debug("runtime temp-block batch serverTick=" + batch.serverTick() + " ops=" + batch.operations().size());
        String worldName = world.getRegistryKey().getValue().toString();
        for (PredictionPayloads.TempBlockOp operation : batch.operations()) {
            if (!matchesWorld(worldName, operation.world())) continue;
            BlockPos pos = new BlockPos(operation.x(), operation.y(), operation.z()).toImmutable();
            BlockKey key = new BlockKey(world, pos);
            BlockMutation mutation = blocks.get(key);
            if (mutation == null) {
                mutation = new BlockMutation(world, pos, world.getBlockState(pos));
                blocks.put(key, mutation);
            }
            final long layerRevision = mutation.serverLayerRevisions.getOrDefault(operation.layerId(), 0L);
            if (operation.revision() <= layerRevision) {
                debug("runtime ignored stale temp-block revision=" + operation.revision()
                        + " current=" + layerRevision + " layer=" + operation.layerId() + " pos=" + pos);
                continue;
            }
            mutation.serverLayerRevisions.put(operation.layerId(), operation.revision());
            Action serverAction = actions.get(operation.actionSequence());
            boolean ownPredictedAction = serverAction != null && serverAction.locallyPredicted;
            ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
            boolean ownedByLocalPlayer = localPlayer != null && operation.ownerId() != null
                    && operation.ownerId().equals(localPlayer.getUuid());
            mutation.serverAction = operation.actionSequence();
            mutation.lastTick = tick;
            switch (operation.operation()) {
                case CREATE, UPDATE_EXPIRY -> {
                    final BlockState material = materialState(operation.material());
                    ServerTempLayer previousLayer = mutation.serverLayers.get(operation.layerId());
                    // An action/coordinate match is not a TempBlock receipt. PhaseChange can
                    // predict WATER on the same coordinate where the server still has ICE,
                    // and moving water abilities can be one lifecycle ahead. Only the exact
                    // physical state proves that this particular CREATE was predicted.
                    final boolean localPredictionAtCoordinate = (ownPredictedAction
                            && mutation.locallyPredicted
                            && mutation.lastAction == operation.actionSequence())
                            || hasActionBlockHistory(world, pos, operation.actionSequence());
                    if (localPredictionAtCoordinate) mutation.localPredictionObserved = true;
                    final long matchingLocalCreateOrdinal = nextBlockEchoOrdinal(world, pos, material,
                            operation.actionSequence(), mutation.lastMatchedLocalCreateOrdinal);
                    final boolean exactLocalCreate = localPredictionAtCoordinate
                            && matchingLocalCreateOrdinal >= 0L
                            && mutation.predicted.equals(material)
                            && world.getBlockState(pos).equals(material);
                    // A mismatched local prediction must expose the metadata authority so
                    // WATER cannot mask authoritative ICE. A coordinate the client never
                    // predicted remains suppressed, preserving owner-side TempBlock hiding.
                    boolean locallyPredictedLayer = localPredictionAtCoordinate
                            || previousLayer != null && previousLayer.locallyPredicted;
                    long localCreateOrdinal = exactLocalCreate
                            ? matchingLocalCreateOrdinal
                            : previousLayer == null ? -1L : previousLayer.localCreateOrdinal;
                    if (exactLocalCreate) {
                        mutation.lastMatchedLocalCreateOrdinal = matchingLocalCreateOrdinal;
                    }
                    mutation.serverLayers.put(operation.layerId(), new ServerTempLayer(
                            material, ownedByLocalPlayer, locallyPredictedLayer,
                            operation.actionSequence(), localCreateOrdinal));
                    mutation.serverTempActive = true;
                    mutation.serverTempState = visibleServerLayer(mutation,
                            materialState(operation.viewerMaterial()));
                    mutation.recordAuthority(mutation.serverTempState);
                    if (operation.operation() == PredictionPayloads.TempOperation.CREATE && ownedByLocalPlayer) {
                        BlockState desired = exactLocalCreate
                                ? mutation.predicted
                                : localPredictionAtCoordinate
                                ? visibleServerLayer(mutation, material)
                                : materialState(operation.viewerMaterial());
                        markOwnedTempDivergence(mutation, desired, mutation.serverTempState);
                        if (operation.packetExpected()) {
                            mutation.ownedTempReceipts.addLast(new OwnedTempReceipt(
                                    materialState(operation.material()),
                                    desired, locallyPredictedLayer,
                                    operation.operation(), tick));
                        }
                        if (!operation.packetExpected() && !exactLocalCreate) {
                            reconcileSuppressedOwnedTempMetadata(world, mutation, desired, operation, false);
                        }
                    }
                    // A matching local CREATE is already visible. If Paper
                    // suppressed the physical packet and this coordinate was
                    // not predicted, metadata installs the owner-visible state.
                }
                case REVERT -> {
                    ServerTempLayer removedLayer = mutation.serverLayers.remove(operation.layerId());
                    // A REVERT is tied to the layer that was actually confirmed. Merely
                    // belonging to the same action used to let an unrelated client WATER
                    // mutation override an authoritative ICE revert indefinitely.
                    boolean locallyPredictedLayer = removedLayer != null && removedLayer.locallyPredicted;
                    boolean ownedLayer = ownedByLocalPlayer
                            || removedLayer != null && removedLayer.ownedByLocalPlayer;
                    BlockState reverted = visibleServerLayer(mutation,
                            materialState(operation.viewerMaterial()));
                    mutation.recordAuthority(reverted);
                    mutation.serverTempActive = !mutation.serverLayers.isEmpty();
                    mutation.serverTempState = mutation.serverTempActive
                            ? visibleServerLayer(mutation, reverted) : null;
                    if (ownedLayer) {
                        BlockState desired = locallyPredictedLayer && mutation.locallyPredicted
                                ? mutation.predicted : materialState(operation.viewerMaterial());
                        markOwnedTempDivergence(mutation, desired, reverted);
                        if (operation.packetExpected()) {
                            mutation.ownedTempReceipts.addLast(new OwnedTempReceipt(
                                    materialState(operation.material()),
                                    desired, locallyPredictedLayer,
                                    operation.operation(), tick));
                        }
                        if (!operation.packetExpected()) {
                            reconcileSuppressedOwnedTempMetadata(world, mutation, reverted, operation, true,
                                    removedLayer == null ? -1L : removedLayer.localCreateOrdinal);
                        }
                    }
                    if (world.getBlockState(pos).equals(reverted)) {
                        mutation.adoptAuthority(reverted);
                        if (!mutation.serverTempActive && !hasRecentBlockEcho(world, pos)
                                && mutation.ownedTempReceipts.isEmpty()) {
                            blocks.remove(key);
                        }
                    }
                    debug("runtime recorded temp-block REVERT sequence="
                            + operation.actionSequence() + " pos=" + pos + " material=" + operation.material());
                }
            }
        }
    }

    private static BlockState visibleServerLayer(final BlockMutation mutation, final BlockState fallback) {
        if (mutation == null || mutation.serverLayers.isEmpty()) return fallback;
        final List<ServerTempLayer> layers = new ArrayList<>(mutation.serverLayers.values());
        // Owner suppression is only for a server TempBlock the client never
        // simulated. Once prediction touched this coordinate, the physical top
        // layer is the correction target, including lower layers revealed by a
        // REVERT. This prevents active server ICE resolving to original WATER.
        if (mutation.localPredictionObserved) return layers.get(layers.size() - 1).state;
        for (int index = layers.size() - 1; index >= 0; index--) {
            ServerTempLayer layer = layers.get(index);
            if (!TempBlockOwnershipPolicy.clientDisplaysLayer(
                    layer.ownedByLocalPlayer, layer.locallyPredicted)) continue;
            return layer.state;
        }
        return fallback;
    }

    private void setVelocity0(Entity entity, Vec3d velocity) {
        if (!ready || entity == null || velocity == null || !finite(velocity)) return;
        long actionSequence = currentAction();
        Action action = actions.get(actionSequence);
        int impulseOrdinal = action == null ? 0 : action.velocityOrdinals.merge(entity.getId(), 1, Integer::sum);
        String abilityName = currentAbilityName();
        if ("<none>".equals(abilityName) && action != null) abilityName = action.inputAbility;
        if (entity instanceof LivingEntity && !isLocalPlayerEntity(entity.getId())) claimHit0(entity);
        if (isLocalPlayerEntity(entity.getId())) {
            debug("runtime predicted velocity local action=" + actionSequence
                    + " ordinal=" + impulseOrdinal
                    + " ability=" + abilityName
                    + " before=" + velocityString(entity.getVelocity())
                    + " after=" + velocityString(velocity)
                    + " stamina=" + airBlastStamina()
                    + " activeAirBlasts=" + activeAirBlastSummary());
        }
        velocities.add(new VelocityMutation((ClientWorld) entity.getEntityWorld(), entity.getId(), actionSequence,
                impulseOrdinal, abilityName, tick, entity.getVelocity(), velocity));
        entity.setVelocity(velocity);
        entity.velocityDirty = true;
    }

    private boolean authoritativeVelocity0(int entityId, Vec3d velocity) {
        if (!ready || !finite(velocity)) return false;
        // Ownership is authoritative metadata from the server. The latest
        // receipt describes the vanilla velocity state that follows it; never
        // guess again from vector similarity or local call ordering.
        if (!velocityReceipts.isEmpty()) {
            // Custom payloads and vanilla velocity updates are emitted in
            // order. Consume the oldest ownership receipt: using the newest
            // one lets a backlog produced by high ping assign an old impulse
            // to a newer ability action.
            int receiptIndex = -1;
            for (int i = 0; i < velocityReceipts.size(); i++) {
                VelocityReceipt candidate = velocityReceipts.get(i);
                if (candidate.entityId == entityId
                        && (candidate.velocity == null || closeNetworkVelocity(candidate.velocity, velocity))) {
                    receiptIndex = i;
                    break;
                }
            }
            if (receiptIndex < 0) return false;
            VelocityReceipt receipt = velocityReceipts.remove(receiptIndex);
            MinecraftClient client = MinecraftClient.getInstance();
            boolean selfOwned = client.player != null && client.player.getUuid().equals(receipt.abilityOwner);
            if (selfOwned) {
                for (int i = 0; i < velocities.size(); i++) {
                    VelocityMutation mutation = velocities.get(i);
                    if (mutation.entityId != entityId
                            || mutation.action != receipt.actionSequence
                            || mutation.impulseOrdinal != receipt.impulseOrdinal) continue;
                    // Acknowledge exactly one predicted write. Later writes
                    // from this action must remain available for their own
                    // delayed packets, especially after ownership transfers
                    // from a surf/spout to another movement ability.
                    velocities.remove(i);
                    debug("runtime suppressed exactly-owned authoritative velocity action="
                            + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                            + " ability=" + receipt.ability);
                    return true;
                }
                // The ownership payload itself is authoritative proof that
                // this vanilla packet is an echo of a locally predicted
                // action. A missing mutation only means the two simulations
                // progressed a different number of ticks (or the local
                // ability already ended); applying it would push twice.
                debug("runtime suppressed self-owned velocity without retained mutation action="
                        + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                        + " ability=" + receipt.ability);
                return true;
            }
            debug("runtime allowed externally-owned authoritative velocity owner=" + receipt.abilityOwner
                    + " action=" + receipt.actionSequence + " ability=" + receipt.ability);
            return false;
        }
        debug("runtime allowed unowned authoritative velocity packet=" + velocityString(velocity)
                + " pendingReceipts=" + velocityReceipts.size());
        return false;
    }

    private boolean tracksVelocityEntity0(int entityId) {
        if (!ready || entityId < 0) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.getId() == entityId) return true;
        return velocities.stream().anyMatch(mutation -> mutation.entityId == entityId)
                || velocityReceipts.stream().anyMatch(receipt -> receipt.entityId == entityId);
    }

    private void noteVelocityOwner0(Entity localPlayer, PredictionPayloads.VelocityOwner owner) {
        if (owner == null) return;
        int targetEntityId = localPlayer != null && localPlayer.getUuid().equals(owner.target()) ? localPlayer.getId() : -1;
        noteVelocityOwner0(localPlayer, owner.serverTick(), owner.actionSequence(), owner.impulseOrdinal(),
                owner.abilityOwner(), targetEntityId, owner.ability(), null);
    }

    private void noteVelocityOwner0(Entity localPlayer, PredictionPayloads.VelocityOwnerV2 owner) {
        if (owner == null) return;
        noteVelocityOwner0(localPlayer, owner.serverTick(), owner.actionSequence(), owner.impulseOrdinal(),
                owner.abilityOwner(), owner.targetEntityId(), owner.ability(),
                new Vec3d(owner.velocityX(), owner.velocityY(), owner.velocityZ()));
    }

    private void noteVelocityOwner0(Entity localPlayer, long serverTick, long actionSequence, int impulseOrdinal,
                                    UUID abilityOwner, int targetEntityId, String ability, Vec3d velocity) {
        if (!ready || localPlayer == null || actionSequence <= 0 || impulseOrdinal <= 0 || targetEntityId < 0) return;
        UUID localId = localPlayer.getUuid();
        if (localId.equals(abilityOwner)) {
            Action action = actions.get(actionSequence);
            if (action == null || !action.locallyPredicted) return;
        }
        // Only the final setVelocity in a server tick is observable through
        // the vanilla entity-velocity packet. Replace an intermediate receipt
        // for the same owned action instead of letting it steal that packet.
        int replacement = -1;
        for (int i = 0; i < velocityReceipts.size(); i++) {
            if (velocityReceipts.get(i).serverTick == serverTick
                    && velocityReceipts.get(i).entityId == targetEntityId) {
                replacement = i;
                break;
            }
        }
        VelocityReceipt receipt = new VelocityReceipt(serverTick, actionSequence, targetEntityId,
                impulseOrdinal, abilityOwner, ability, velocity, tick);
        if (replacement >= 0) velocityReceipts.set(replacement, receipt);
        else velocityReceipts.add(receipt);
        debug("runtime received velocity owner action=" + actionSequence
                + " ordinal=" + impulseOrdinal + " ability=" + ability);
    }

    private void removeAuthoritativeAbility0(Entity localPlayer, PredictionPayloads.AbilityRemoved removed) {
        if (!ready || localPlayer == null || removed == null || !localPlayer.getUuid().equals(removed.player())) return;
        List<CoreAbility> matching = new ArrayList<>();
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability.getPlayer() != null && ability.getPlayer().getUniqueId().equals(removed.player())
                    && ability.getName().equalsIgnoreCase(removed.ability())) matching.add(ability);
        }
        CoreAbility selected = null;
        if (removed.actionSequence() > 0) {
            for (CoreAbility ability : matching) {
                if (Objects.equals(abilityActions.get(ability), removed.actionSequence())) {
                    selected = ability;
                    break;
                }
            }
            if (selected == null && matching.size() == 1) {
                CoreAbility only = matching.get(0);
                Long localSequence = abilityActions.get(only);
                // TCP preserves each side's packet order, but the client can
                // predict a new cast before an older server removal arrives.
                // Never remove a provably newer cast; an unmapped or older
                // singleton is the stale server instance and must stop.
                if (localSequence == null || localSequence <= removed.actionSequence()) selected = only;
            }
        } else if (matching.size() == 1) {
            selected = matching.get(0);
        }
        if (selected != null) {
            debug("runtime applied authoritative ability removal ability=" + removed.ability()
                    + " action=" + removed.actionSequence());
            selected.remove();
            abilityActions.remove(selected);
        }
    }

    private void notePredictedAbilityState0(boolean invulnerable, boolean flying, boolean allowFlying,
                                            boolean creativeMode, float flySpeed, float walkSpeed) {
        if (!ready) return;
        abilityStates.add(new AbilityStateMutation(tick, invulnerable, flying, allowFlying, creativeMode, flySpeed, walkSpeed));
    }

    private void notePredictedExperience0(float barProgress, int experience, int level) {
        if (!ready) return;
        experiences.add(new ExperienceMutation(tick, barProgress, experience, level));
    }

    private void notePredictedSelectedSlot0(int slot) {
        if (!ready || slot < 0 || slot > 8) return;
        selectedSlots.add(new SelectedSlotMutation(tick, slot));
    }

    private boolean suppressAuthoritativeAbilityState0(PlayerAbilitiesS2CPacket packet) {
        if (!ready || packet == null) return false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        boolean supersededByCurrentPrediction = false;
        for (int i = abilityStates.size() - 1; i >= 0; i--) {
            AbilityStateMutation mutation = abilityStates.get(i);
            if (tick - mutation.tick > ACTION_RETENTION_TICKS) continue;
            boolean matchesCurrent = mutation.matchesCurrent(player);
            if (!mutation.matches(packet)) {
                if (matchesCurrent) supersededByCurrentPrediction = true;
                continue;
            }
            abilityStates.remove(i);
            // Packets have no action id. If a newer local flight mutation is
            // still the state being displayed, this packet owns an older
            // transition and must not roll the newer AirScooter/spout state
            // back. A packet with no matching prediction remains vanilla
            // authority (gamemode changes and other plugins still apply).
            return matchesCurrent || supersededByCurrentPrediction;
        }
        return false;
    }

    private void reconcileActiveFlightAbilities0(List<String> activeAbilities, long acknowledgedSequence) {
        if (!ready || bendingPlayer == null || bendingPlayer.getPlayer() == null) return;
        long latestLocalSequence = actions.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
        if (!PredictionStateOrdering.snapshotCoversLatestInput(acknowledgedSequence, latestLocalSequence)) {
            debug("runtime deferred flight snapshot ack=" + acknowledgedSequence
                    + " latestLocal=" + latestLocalSequence);
            return;
        }
        Set<String> active = new HashSet<>();
        if (activeAbilities != null) {
            for (String name : activeAbilities) active.add(name.toLowerCase(Locale.ROOT));
        }
        UUID playerId = bendingPlayer.getPlayer().getUniqueId();
        for (CoreAbility ability : new ArrayList<>(CoreAbility.getAbilitiesByInstances())) {
            if (ability.getPlayer() == null || !playerId.equals(ability.getPlayer().getUniqueId())) continue;
            if (!(ability instanceof AirSpout) && !(ability instanceof WaterSpout)
                    && !(ability instanceof AirScooter) && !(ability instanceof FireJet)
                    && !(ability instanceof FlightMultiAbility)) continue;
            if (active.contains(ability.getName().toLowerCase(Locale.ROOT))) continue;
            Long sequence = abilityActions.get(ability);
            Action owner = sequence == null ? null : actions.get(sequence);
            // A state packet created immediately before the server processes
            // this cast can arrive after local prediction has started it. The
            // reconcile/removal packet for that action is authoritative; an
            // older empty flight snapshot is not.
            if (owner != null && !owner.accepted && owner.abilities.contains(ability)) {
                debug("runtime ignored stale flight snapshot for pending ability=" + ability.getName()
                        + " action=" + sequence);
                continue;
            }
            debug("runtime removed flight ability absent from authoritative state ability=" + ability.getName());
            ability.remove();
            abilityActions.remove(ability);
        }
        if (active.contains("waterspout") && !CoreAbility.hasAbility(bendingPlayer.getPlayer(), WaterSpout.class)) {
            WaterSpout restored = new WaterSpout(bendingPlayer.getPlayer());
            if (restored.isStarted() && !restored.isRemoved()) {
                debug("runtime restored WaterSpout from authoritative flight snapshot ack="
                        + acknowledgedSequence);
            } else {
                debug("runtime could not restore authoritative WaterSpout snapshot ack="
                        + acknowledgedSequence);
            }
        }
    }

    private boolean suppressAuthoritativeExperience0(ExperienceBarUpdateS2CPacket packet) {
        if (!ready || packet == null) return false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        // Never hide actual vanilla XP/level changes. Only the fractional bar
        // is prediction-owned while a bending passive is actively writing it.
        if (packet.getExperience() != player.totalExperience
                || packet.getExperienceLevel() != player.experienceLevel) {
            experiences.clear();
            return false;
        }
        for (int i = experiences.size() - 1; i >= 0; i--) {
            ExperienceMutation mutation = experiences.get(i);
            if (tick - mutation.tick <= 4) {
                return true;
            }
        }
        return false;
    }

    private boolean suppressAuthoritativeSelectedSlot0(UpdateSelectedSlotS2CPacket packet) {
        if (!ready || packet == null) return false;
        int slot = packet.slot();
        int current = MinecraftClient.getInstance().player == null
                ? -1 : MinecraftClient.getInstance().player.getInventory().getSelectedSlot();
        for (int i = selectedSlots.size() - 1; i >= 0; i--) {
            SelectedSlotMutation mutation = selectedSlots.get(i);
            if (tick - mutation.tick > ACTION_RETENTION_TICKS) continue;
            if (mutation.slot != slot) continue;
            selectedSlots.remove(i);
            debug("runtime suppressed predicted selected-slot echo slot=" + (slot + 1));
            return true;
        }
        debug("runtime suppressed authoritative selected-slot packet slot=" + (slot + 1)
                + " current=" + (current + 1));
        return true;
    }

    private static boolean isLocalPlayerEntity(int entityId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.getId() == entityId;
    }

    private String currentAbilityName() {
        CoreAbility ability = AbilityExecutionContext.current();
        return ability == null ? "<none>" : ability.getName();
    }

    private String airBlastStamina() {
        return bendingPlayer == null ? "<none>" : String.format(Locale.ROOT, "%.4f", bendingPlayer.getAirBlastDecay());
    }

    private String activeAirBlastSummary() {
        if (bendingPlayer == null) return "[]";
        List<String> summary = new ArrayList<>();
        for (AirBlast blast : CoreAbility.getAbilities(bendingPlayer.getPlayer(), AirBlast.class)) {
            summary.add("{progressing=" + blast.isProgressing()
                    + ",fromOther=" + blast.isFromOtherOrigin()
                    + ",ticks=" + blast.getTicks()
                    + ",loc=" + compactLocation(blast.getLocation())
                    + ",origin=" + compactLocation(blast.getOrigin())
                    + ",radius=" + String.format(Locale.ROOT, "%.3f", blast.getRadius())
                    + "}");
        }
        return summary.toString();
    }

    private static String velocityString(Vec3d velocity) {
        if (velocity == null) return "<null>";
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", velocity.x, velocity.y, velocity.z);
    }

    private static String compactLocation(Location location) {
        if (location == null) return "<null>";
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", location.getX(), location.getY(), location.getZ());
    }

    private void claimDamage0(LivingEntity target, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return;
        claimHit0(target);
    }

    private void claimHit0(Entity target) {
        long sequence = currentAction();
        Action action = actions.get(sequence);
        if (!ready || action == null || target == null || isLocalPlayerEntity(target.getId())
                || !action.claimedTargets.add(target.getId())) return;
        PredictionClient.sendExactHitClaim(sequence, target, target.getBoundingBox().getCenter());
    }

    private void trackSpawn0(Entity entity) {
        long action = currentAction();
        Action owner = actions.get(action);
        if (owner != null && entity != null) {
            owner.spawned.add(entity);
            predictedSpawnOrigins.put(entity, entity.getEntityPos());
        }
    }

    private boolean reconcileSpawn0(EntitySpawnS2CPacket packet) {
        if (!ready || MinecraftClient.getInstance().world == null || authoritativeEntityAliases.containsKey(packet.getEntityId())) return false;
        Vec3d serverPosition = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
        Entity best = null; double bestDistance = 32.0 * 32.0;
        for (Action action : actions.values()) {
            if (tick - action.createdTick > ACTION_RETENTION_TICKS && action.abilities.isEmpty()) continue;
            for (Entity candidate : action.spawned) {
                if (candidate == null || candidate.getType() != packet.getEntityType()
                        || authoritativeEntityAliases.containsValue(candidate)) continue;
                // Match the authoritative spawn to where the predicted entity
                // was created, not where it has moved during the round trip.
                // Removed candidates remain tombstones so a short-lived shard
                // or fragment cannot reappear when its delayed server spawn
                // finally arrives.
                Vec3d predictedOrigin = predictedSpawnOrigins.getOrDefault(candidate, candidate.getEntityPos());
                double distance = predictedOrigin.squaredDistanceTo(serverPosition);
                if (distance < bestDistance) { best = candidate; bestDistance = distance; }
            }
        }
        if (best == null) return false;
        // Keep the local UUID: ClientWorld indexed this entity under that UUID
        // when prediction spawned it. Paper's numeric ID is translated by the
        // alias mixin without corrupting that client-side UUID index.
        authoritativeEntityAliases.put(packet.getEntityId(), best);
        return true;
    }

    private boolean removeAliasedEntity0(int serverEntityId) {
        Entity entity = authoritativeEntityAliases.remove(serverEntityId);
        if (entity == null) return false;
        if (!entity.isRemoved()) entity.discard();
        return true;
    }

    private long currentAction() {
        Long input = INPUT_ACTION.get();
        if (input != null) return input;
        CoreAbility ability = AbilityExecutionContext.current();
        return ability == null ? 0L : abilityActions.getOrDefault(ability, 0L);
    }

    private PredictionClient.ServerPose executionPose0() {
        Action action = actions.get(currentAction());
        // Captured input pose exists to make constructors and delayed source
        // selection agree with authority. Holding it for the full ability
        // lifetime freezes player-following streams, shields and movement.
        if (action == null || tick - action.createdTick > 1L) return null;
        return new PredictionClient.ServerPose(action.origin.x, action.origin.y - action.eyeHeight,
                action.origin.z, action.yaw, action.pitch, action.eyeHeight);
    }

    private static void correctLocations(CoreAbility ability, Vec3d origin, Vec3d correction) {
        Class<?> type = ability.getClass();
        while (type != null && CoreAbility.class.isAssignableFrom(type)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Location.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.trySetAccessible();
                    Location location = (Location) field.get(ability);
                    if (location == null) continue;
                    Vec3d point = new Vec3d(location.getX(), location.getY(), location.getZ());
                    if (point.squaredDistanceTo(origin) > 36.0) continue;
                    location.add(correction.x, correction.y, correction.z);
                } catch (ReflectiveOperationException ignored) { }
            }
            type = type.getSuperclass();
        }
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static BlockState materialState(String materialName) {
        Material material;
        String[] fields = materialName == null ? new String[0] : materialName.trim().split(";");
        try {
            String key = fields.length == 0 ? "" : fields[0];
            int namespace = key.indexOf(':');
            if (namespace >= 0) key = key.substring(namespace + 1);
            material = key.isBlank()
                    ? Material.AIR
                    : Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            material = Material.AIR;
        }
        BlockData data = material.createBlockData();
        for (int i = 1; i < fields.length; i++) {
            int separator = fields[i].indexOf('=');
            if (separator <= 0) continue;
            String name = fields[i].substring(0, separator);
            String value = fields[i].substring(separator + 1);
            try {
                if (data instanceof Levelled levelled) {
                    if (name.equals("level")) levelled.setLevel(Integer.parseInt(value));
                    else if (name.equals("waterlogged")) levelled.setWaterlogged(value.equals("1"));
                } else if (data instanceof Fire fire
                        && name.equals("faces") && !value.isBlank()) {
                    for (String face : value.split(",")) {
                        fire.setFace(BlockFace.valueOf(face), true);
                    }
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return FabricMC.blockState(data);
    }

    private static boolean matchesWorld(String clientWorld, String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) return false;
        if (clientWorld.equals(serverWorld)) return true;
        return serverWorld.indexOf(':') < 0 && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
    }

    private static boolean close(Vec3d first, Vec3d second, double tolerance) {
        return first.squaredDistanceTo(second) <= tolerance * tolerance;
    }

    private static boolean closeNetworkVelocity(Vec3d expected, Vec3d actual) {
        if (expected == null || actual == null) return false;
        Vec3d clamped = new Vec3d(
                Math.max(-3.9, Math.min(3.9, expected.x)),
                Math.max(-3.9, Math.min(3.9, expected.y)),
                Math.max(-3.9, Math.min(3.9, expected.z)));
        return close(clamped, actual, 0.001);
    }

    private boolean consumeBlockEcho(ClientWorld world, BlockPos pos, BlockState state) {
        int index = findBlockEcho(world, pos, state);
        if (index < 0) return false;
        BlockEcho echo = blockEchoes.get(index);
        blockEchoes.remove(index);
        boolean newer = hasNewerBlockEcho(world, pos, index - 1);
        if (PredictionEchoPolicy.shouldSuppress(echo.force, newer, world.getBlockState(pos).equals(state))) return true;
        debug("runtime stale predicted block echo allowed authority pos=" + pos
                + " packet=" + state + " client=" + world.getBlockState(pos));
        return false;
    }

    private int findBlockEcho(ClientWorld world, BlockPos pos, BlockState state) {
        return findBlockEcho(world, pos, state, Set.of());
    }

    private int findBlockEcho(ClientWorld world, BlockPos pos, BlockState state, Set<Integer> reserved) {
        for (int i = 0; i < blockEchoes.size(); i++) {
            BlockEcho echo = blockEchoes.get(i);
            if (tick - echo.tick > ACTION_RETENTION_TICKS) continue;
            if (!reserved.contains(i) && echo.world == world && echo.pos.equals(pos) && echo.state.equals(state)) return i;
        }
        return -1;
    }

    private boolean hasNewerBlockEcho(ClientWorld world, BlockPos pos, int afterIndex) {
        for (int i = afterIndex + 1; i < blockEchoes.size(); i++) {
            BlockEcho echo = blockEchoes.get(i);
            if (tick - echo.tick <= ACTION_RETENTION_TICKS && echo.world == world && echo.pos.equals(pos)) return true;
        }
        return false;
    }

    private boolean hasRecentBlockEcho(final ClientWorld world, final BlockPos pos) {
        for (BlockEcho echo : localBlockHistory) {
            if (tick - echo.tick <= ACTION_RETENTION_TICKS && echo.world == world && echo.pos.equals(pos)) return true;
        }
        return false;
    }

    private void clearBlockEchoes(final ClientWorld world, final BlockPos pos) {
        blockEchoes.removeIf(echo -> echo.world == world && echo.pos.equals(pos));
    }

    private static void invalidateClientTempStack(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return;
        com.projectkorra.projectkorra.platform.mc.block.Block block = FabricPredictionMC.block(world, pos);
        if (TempBlock.isTempBlock(block)) TempBlock.discardBlock(block);
    }

    private int findOwnedTempReceipt(final BlockMutation mutation, final BlockState state) {
        if (mutation == null || state == null) return -1;
        int index = 0;
        for (OwnedTempReceipt receipt : mutation.ownedTempReceipts) {
            if (tick - receipt.tick <= OWNED_TEMP_RECEIPT_TICKS
                    && receipt.physicalPacketState.equals(state)) return index;
            index++;
        }
        return -1;
    }

    private OwnedTempReceipt consumeOwnedTempReceipt(final BlockMutation mutation, final BlockState state) {
        int wanted = findOwnedTempReceipt(mutation, state);
        if (wanted < 0) return null;
        OwnedTempReceipt receipt = null;
        for (int index = 0; index <= wanted; index++) {
            receipt = mutation.ownedTempReceipts.pollFirst();
        }
        return receipt;
    }

    private void applyOwnedTempReceipt(final BlockMutation mutation, final BlockState authority,
                                       final OwnedTempReceipt receipt) {
        mutation.recordAuthority(authority);
        mutation.lastTick = tick;
        mutation.predicted = receipt.desiredState;
        mutation.locallyPredicted = receipt.locallyPredicted;
        mutation.confirmed = authority.equals(receipt.desiredState);
        markOwnedTempDivergence(mutation, receipt.desiredState, authority);
    }

    private void restoreOwnedTempAfterBatch(final ClientWorld world, final BlockPos pos,
                                            final BlockMutation mutation, final BlockState authority,
                                            final OwnedBatchRestore restore) {
        if (mutation == null) return;
        mutation.recordAuthority(authority);
        mutation.lastTick = tick;
        mutation.predicted = restore.desiredState;
        mutation.locallyPredicted = restore.locallyPredicted;
        mutation.confirmed = authority.equals(restore.desiredState);
        markOwnedTempDivergence(mutation, restore.desiredState, authority);
        world.setBlockState(pos, restore.desiredState, 19);
        debug("runtime restored owned temp-block after mixed authority batch pos=" + pos
                + " packet=" + authority + " desired=" + restore.desiredState);
    }

    private static boolean hasOwnedLayer(final BlockMutation mutation) {
        if (mutation == null) return false;
        for (ServerTempLayer layer : mutation.serverLayers.values()) {
            if (layer.ownedByLocalPlayer) return true;
        }
        return false;
    }

    private static boolean isTopLayerOwnedByLocalPlayer(final BlockMutation mutation) {
        if (mutation == null || mutation.serverLayers.isEmpty()) return false;
        ServerTempLayer top = null;
        for (ServerTempLayer layer : mutation.serverLayers.values()) top = layer;
        return top != null && top.ownedByLocalPlayer;
    }

    private void reconcileSuppressedOwnedTempMetadata(final ClientWorld world, final BlockMutation mutation,
                                                       final BlockState target,
                                                       final PredictionPayloads.TempBlockOp operation,
                                                       final boolean revert) {
        reconcileSuppressedOwnedTempMetadata(world, mutation, target, operation, revert, -1L);
    }

    private void reconcileSuppressedOwnedTempMetadata(final ClientWorld world, final BlockMutation mutation,
                                                       final BlockState target,
                                                       final PredictionPayloads.TempBlockOp operation,
                                                       final boolean revert,
                                                       final long localCreateOrdinal) {
        if (world == null || mutation == null || target == null || operation == null) return;
        boolean newerLocalMutation = mutation.locallyPredicted
                && (mutation.lastAction > operation.actionSequence()
                || revert && localCreateOrdinal >= 0L
                && mutation.lastAction == operation.actionSequence()
                && hasNewerLocalBlockEcho(world, mutation.pos, target,
                operation.actionSequence(), localCreateOrdinal));
        if (newerLocalMutation) {
            markOwnedTempDivergence(mutation, mutation.predicted, target);
            return;
        }
        // Metadata is authoritative when the physical packet was suppressed.
        // Discard the local TempBlock lifecycle before changing the world so a
        // delayed local revert cannot resurrect the state being corrected.
        invalidateClientTempStack(world, mutation.pos);
        if (!world.getBlockState(mutation.pos).equals(target)) {
            world.setBlockState(mutation.pos, target, 19);
        }
        mutation.adoptAuthority(target);
        mutation.lastTick = tick;
        clearBlockEchoes(world, mutation.pos);
        debug("runtime reconciled suppressed owned temp metadata pos=" + mutation.pos
                + " operation=" + operation.operation() + " revert=" + revert + " target=" + target);
    }

    private long nextBlockEchoOrdinal(final ClientWorld world, final BlockPos pos,
                                      final BlockState state, final long actionSequence,
                                      final long afterOrdinal) {
        long next = Long.MAX_VALUE;
        for (BlockEcho echo : localBlockHistory) {
            if (tick - echo.tick <= ACTION_RETENTION_TICKS && echo.world == world
                    && echo.action == actionSequence && echo.pos.equals(pos)
                    && echo.ordinal > afterOrdinal && echo.state.equals(state)) {
                next = Math.min(next, echo.ordinal);
            }
        }
        return next == Long.MAX_VALUE ? -1L : next;
    }

    private boolean hasActionBlockHistory(final ClientWorld world, final BlockPos pos,
                                          final long actionSequence) {
        for (BlockEcho echo : localBlockHistory) {
            if (tick - echo.tick <= ACTION_RETENTION_TICKS && echo.world == world
                    && echo.action == actionSequence && echo.pos.equals(pos)) return true;
        }
        return false;
    }

    private boolean hasNewerLocalBlockEcho(final ClientWorld world, final BlockPos pos,
                                            final BlockState revertedState,
                                            final long actionSequence,
                                            final long afterCreateOrdinal) {
        int matchingRevert = -1;
        for (int index = 0; index < localBlockHistory.size(); index++) {
            BlockEcho echo = localBlockHistory.get(index);
            if (tick - echo.tick <= ACTION_RETENTION_TICKS && echo.world == world
                    && echo.action == actionSequence && echo.ordinal > afterCreateOrdinal
                    && echo.pos.equals(pos) && echo.state.equals(revertedState)) {
                matchingRevert = index;
            }
        }
        if (matchingRevert < 0) return false;
        BlockState current = world.getBlockState(pos);
        for (int index = matchingRevert + 1; index < localBlockHistory.size(); index++) {
            BlockEcho echo = localBlockHistory.get(index);
            if (tick - echo.tick <= ACTION_RETENTION_TICKS && echo.world == world
                    && echo.action == actionSequence && echo.ordinal > afterCreateOrdinal
                    && echo.pos.equals(pos) && echo.state.equals(current)
                    && !current.equals(revertedState)) return true;
        }
        return false;
    }

    private void markOwnedTempDivergence(final BlockMutation mutation, final BlockState desired,
                                         final BlockState authority) {
        mutation.ownedTempDivergedUntilTick = desired != null
                && (!mutation.world.getBlockState(mutation.pos).equals(desired)
                || authority != null && !desired.equals(authority))
                ? tick + BLOCK_CONFIRMATION_TICKS : 0L;
    }

    private List<PredictionDesyncBlock> ownedTempDesyncs0(final ClientWorld world) {
        if (!ready || world == null) return List.of();
        final List<PredictionDesyncBlock> result = new ArrayList<>();
        for (BlockMutation mutation : blocks.values()) {
            if (mutation.world != world || mutation.ownedTempDivergedUntilTick < tick) continue;
            BlockState actual = world.getBlockState(mutation.pos);
            BlockState target = mutation.serverTempActive && mutation.serverTempState != null
                    ? mutation.serverTempState : mutation.authoritative;
            if (actual.equals(target)) continue;
            result.add(new PredictionDesyncBlock(mutation.pos, actual, target));
        }
        return List.copyOf(result);
    }

    private static void debug(String message) {
        if (DEBUG) System.out.println("[ProjectKorraPrediction] " + message);
    }

    private static final class Action {
        final long sequence;
        final long createdTick;
        final Vec3d origin;
        final float yaw;
        final float pitch;
        final double eyeHeight;
        final String inputAbility;
        final Set<CoreAbility> abilities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<CoreAbility> affectedAbilities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Entity> spawned = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Integer> claimedTargets = new HashSet<>();
        final Map<Integer, Integer> velocityOrdinals = new HashMap<>();
        boolean accepted;
        boolean locallyPredicted;
        int blockConfirmationTicks = BLOCK_CONFIRMATION_TICKS;
        private Action(long sequence, long createdTick, Vec3d origin, float yaw, float pitch,
                       double eyeHeight, String inputAbility) {
            this.sequence = sequence;
            this.createdTick = createdTick;
            this.origin = origin;
            this.yaw = yaw;
            this.pitch = pitch;
            this.eyeHeight = eyeHeight;
            this.inputAbility = inputAbility == null ? "" : inputAbility;
        }
    }

    private record BlockKey(ClientWorld world, BlockPos pos) { }
    public record PredictionDesyncBlock(BlockPos pos, BlockState predicted, BlockState authoritative) { }
    private record BlockEcho(ClientWorld world, BlockPos pos, BlockState state, long tick, boolean force,
                             long action, long ordinal) { }
    private record OwnedTempReceipt(BlockState physicalPacketState, BlockState desiredState,
                                    boolean locallyPredicted,
                                    PredictionPayloads.TempOperation operation, long tick) { }
    private record OwnedBatchRestore(BlockState desiredState, boolean locallyPredicted, long tick) { }
    private static final class BlockMutation {
        final ClientWorld world; final BlockPos pos; BlockState authoritative; BlockState predicted; long lastAction; long lastTick; boolean confirmed; boolean locallyPredicted;
        boolean serverTempActive; long serverAction; BlockState serverTempState;
        long ownedTempDivergedUntilTick;
        long lastMatchedLocalCreateOrdinal = -1L;
        boolean localPredictionObserved;
        final LinkedHashMap<Long, ServerTempLayer> serverLayers = new LinkedHashMap<>();
        final Map<Long, Long> serverLayerRevisions = new HashMap<>();
        final java.util.ArrayDeque<OwnedTempReceipt> ownedTempReceipts = new java.util.ArrayDeque<>();
        private BlockMutation(ClientWorld world, BlockPos pos, BlockState authoritative) { this.world = world; this.pos = pos; this.authoritative = authoritative; this.predicted = authoritative; }
        private void recordAuthority(BlockState state) {
            this.authoritative = state;
            this.confirmed = PredictionEchoPolicy.confirmedByLatestAuthority(
                    this.confirmed, state.equals(this.predicted));
        }
        private void adoptAuthority(BlockState state) {
            this.authoritative = state;
            this.predicted = state;
            this.confirmed = true;
            this.locallyPredicted = false;
            this.ownedTempDivergedUntilTick = 0L;
        }
    }
    private record ServerTempLayer(BlockState state, boolean ownedByLocalPlayer, boolean locallyPredicted,
                                   long actionSequence, long localCreateOrdinal) { }
    private static final class VelocityMutation {
        final ClientWorld world; final int entityId; final long action; final int impulseOrdinal; final String ability;
        final long tick; final Vec3d before; final Vec3d predicted;
        private VelocityMutation(ClientWorld world, int entityId, long action, int impulseOrdinal, String ability,
                                 long tick, Vec3d before, Vec3d predicted) {
            this.world = world; this.entityId = entityId; this.action = action; this.impulseOrdinal = impulseOrdinal;
            this.ability = ability == null ? "" : ability; this.tick = tick; this.before = before; this.predicted = predicted;
        }
    }
    private record VelocityReceipt(long serverTick, long actionSequence, int entityId, int impulseOrdinal,
                                   UUID abilityOwner, String ability, Vec3d velocity, long receivedTick) { }
    private static final class AbilityStateMutation {
        final long tick; final boolean invulnerable; final boolean flying; final boolean allowFlying; final boolean creativeMode; final float flySpeed; final float walkSpeed;
        private AbilityStateMutation(long tick, boolean invulnerable, boolean flying, boolean allowFlying, boolean creativeMode, float flySpeed, float walkSpeed) {
            this.tick = tick; this.invulnerable = invulnerable; this.flying = flying; this.allowFlying = allowFlying; this.creativeMode = creativeMode; this.flySpeed = flySpeed; this.walkSpeed = walkSpeed;
        }
        private boolean matches(PlayerAbilitiesS2CPacket packet) {
            return packet.isInvulnerable() == invulnerable
                    && packet.isFlying() == flying
                    && packet.allowFlying() == allowFlying
                    && packet.isCreativeMode() == creativeMode
                    && Math.abs(packet.getFlySpeed() - flySpeed) <= 1.0E-6F
                    && Math.abs(packet.getWalkSpeed() - walkSpeed) <= 1.0E-6F;
        }
        private boolean matchesCurrent(ClientPlayerEntity player) {
            PlayerAbilities current = player.getAbilities();
            return current.invulnerable == invulnerable
                    && current.flying == flying
                    && current.allowFlying == allowFlying
                    && current.creativeMode == creativeMode
                    && Math.abs(current.getFlySpeed() - flySpeed) <= 1.0E-6F
                    && Math.abs(current.getWalkSpeed() - walkSpeed) <= 1.0E-6F;
        }
    }
    private static final class ExperienceMutation {
        final long tick; final float barProgress; final int experience; final int level;
        private ExperienceMutation(long tick, float barProgress, int experience, int level) {
            this.tick = tick; this.barProgress = barProgress; this.experience = experience; this.level = level;
        }
    }
    private static final class SelectedSlotMutation {
        final long tick; final int slot;
        private SelectedSlotMutation(long tick, int slot) {
            this.tick = tick;
            this.slot = slot;
        }
    }
}
