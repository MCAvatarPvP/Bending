package com.projectkorra.projectkorra.fabric.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.CapturedInputPose;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.PredictionCooldownTimeline;
import com.projectkorra.projectkorra.fabric.FabricGameplayBridge;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.PredictionTiming;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import com.projectkorra.projectkorra.prediction.VelocitySync;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.jedk1.jedcore.ability.passive.WallRun;
import com.jedk1.jedcore.ability.firebending.FirePunch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server authority for client prediction. Client coordinates are evidence, not
 * state: origin, cooldown, damage, source selection and final blocks always
 * come from the server runtime.
 */
public final class PredictionServer implements TempBlockSync.Listener,
        CooldownSync.Listener, VelocitySync.Listener,
        AbilityRemovalSync.Listener {
    public static final int MAX_REWIND_TICKS = 12;
    private static final double MAX_ORIGIN_ERROR = 8.0;
    private static final int INPUTS_PER_SECOND = 80;
    private static final int CLAIMS_PER_SECOND = 48;
    private static PredictionServer active;
    private static boolean receiversRegistered;
    private static final ThreadLocal<UUID> INPUT_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<Long> INPUT_SEQUENCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> INPUT_LOCALLY_PREDICTED = new ThreadLocal<>();

    private final MinecraftServer server;
    private final FabricGameplayBridge gameplay;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<EntityFrame>> history = new HashMap<>();
    private final Map<CoreAbility, Action> abilityActions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final List<PredictionPayloads.TempBlockOp> pendingTempBlocks = new ArrayList<>();
    private volatile List<PredictionPayloads.ConfigEntry> publicConfig = List.of();
    private volatile List<PredictionPayloads.AbilityProfile> profiles = List.of();
    private volatile Map<String, PredictionPayloads.AbilityProfile> profilesByName = Map.of();
    private volatile long configEpoch;
    private volatile boolean snapshotReady;
    private final AtomicBoolean snapshotBuildRunning = new AtomicBoolean();
    private long tick;
    private final ToLongFunction<CoreAbility> timingProvider = this::latencyCompensationMillis;

    private PredictionServer(MinecraftServer server, FabricGameplayBridge gameplay) {
        this.server = server;
        this.gameplay = gameplay;
    }

    public static synchronized PredictionServer start(MinecraftServer server, FabricGameplayBridge gameplay) {
        PredictionPayloads.registerTypes();
        registerReceivers();
        PredictionServer prediction = new PredictionServer(server, gameplay);
        active = prediction;
        TempBlockSync.install(prediction);
        VelocitySync.install(prediction);
        AbilityRemovalSync.install(prediction);
        PredictionTiming.install(prediction.timingProvider);
        CooldownSync.install(prediction);
        prediction.requestPublicSnapshotRebuild(false);
        return prediction;
    }

    public void stop() {
        TempBlockSync.clear(this);
        VelocitySync.clear(this);
        AbilityRemovalSync.clear(this);
        PredictionTiming.clear(this.timingProvider);
        CooldownSync.clear(this);
        sessions.clear();
        history.clear();
        abilityActions.clear();
        pendingTempBlocks.clear();
        if (active == this) active = null;
    }

    @Override
    public void onAdded(BendingPlayer player, String ability, long expiresAtMillis) {
        ServerPlayerEntity predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUuid().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, "", ability == null ? "" : ability, expiresAtMillis, false, Double.NaN);
    }

    @Override
    public void onRemoved(BendingPlayer player, String ability) {
        ServerPlayerEntity predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUuid().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, ability == null ? "" : ability, "", 0L, false, Double.NaN);
    }

    @Override
    public void onAirBlastReset(BendingPlayer player) {
        ServerPlayerEntity predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUuid().equals(player.getPlayer().getUniqueId())) return;
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
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(bending.getPlayer().getUniqueId());
        if (session != null && player != null && ServerPlayNetworking.canSend(player, PredictionPayloads.StateDirective.ID)) {
            ServerPlayNetworking.send(player, new PredictionPayloads.StateDirective(session.sessionId,
                    removedCooldown, addedCooldown, cooldownUntil, System.currentTimeMillis(), resetAirBlast, airBlastDecay));
        }
    }

    public void tick() {
        tick++;
        recordHistory();
        flushTempBlocks();
        sessions.entrySet().removeIf(entry -> server.getPlayerManager().getPlayer(entry.getKey()) == null);
        abilityActions.entrySet().removeIf(entry -> entry.getKey().isRemoved() || !sessions.containsKey(entry.getValue().owner));

        if (tick % 20 == 0) syncPlayerStateChanges();
        if (tick % 100 == 0) {
            requestPublicSnapshotRebuild(true);
        }
    }

    public static void augmentNearbyPlayers(ServerWorld world, Box query, CoreAbility ability,
                                            Map<Integer, com.projectkorra.projectkorra.platform.mc.entity.Entity> result) {
        PredictionServer prediction = active;
        if (prediction == null) return;
        Action action = ability == null ? null : prediction.actionForEffect(ability);
        if (action == null) {
            UUID owner = INPUT_OWNER.get();
            Long sequence = INPUT_SEQUENCE.get();
            Session inputSession = owner == null ? null : prediction.sessions.get(owner);
            action = inputSession == null || sequence == null ? null : inputSession.actions.get(sequence);
        }
        if (action == null) return;
        Session session = prediction.sessions.get(action.owner);
        if (session == null) return;

        var iterator = action.claims.values().iterator();
        while (iterator.hasNext()) {
            Claim claim = iterator.next();
            if (claim.expiresTick < prediction.tick || claim.consumedTick >= 0 && claim.consumedTick < prediction.tick) {
                iterator.remove();
                continue;
            }
            Entity target = world.getEntityById(claim.targetEntityId);
            if (!(target instanceof net.minecraft.entity.LivingEntity) || target.getUuid().equals(action.owner)) continue;
            // Trust the client claim for this diagnostic path; the claimed
            // entity must not be discarded by the server's divergent box.
            com.projectkorra.projectkorra.platform.mc.entity.Entity wrapped = FabricMC.entity(target);
            if (wrapped != null) {
                result.put(target.getId(), wrapped);
                claim.consumedTick = prediction.tick;
            }
        }
    }

    /**
     * Returns the modded owner who already rendered the currently executing
     * predicted effect locally. Server adapters exclude only this viewer;
     * everybody else still receives the normal particle/sound packet.
     */
    public static ServerPlayerEntity predictedEffectOwner() {
        PredictionServer prediction = active;
        if (prediction == null) return null;
        UUID owner = INPUT_OWNER.get();
        if (owner == null) {
            CoreAbility ability = AbilityExecutionContext.current();
            Action action = ability == null ? null : prediction.actionForEffect(ability);
            if (action != null && !action.locallyPredicted) return null;
            owner = action == null ? null : action.owner;
        } else if (!Boolean.TRUE.equals(INPUT_LOCALLY_PREDICTED.get())) {
            return null;
        }
        Session session = owner == null ? null : prediction.sessions.get(owner);
        return session != null && (session.capabilities & 8) != 0
                ? prediction.server.getPlayerManager().getPlayer(owner) : null;
    }

    public static ServerPlayerEntity predictedSoundEffectOwner() {
        return predictedEffectOwner();
    }

    public static Vec3d claimedEffectPosition(Entity target) {
        PredictionServer prediction = active;
        if (prediction == null || target == null) return null;
        CoreAbility ability = AbilityExecutionContext.current();
        Action action = ability == null ? null : prediction.actionForEffect(ability);
        if (action == null) {
            UUID owner = INPUT_OWNER.get();
            Long sequence = INPUT_SEQUENCE.get();
            Session session = owner == null ? null : prediction.sessions.get(owner);
            action = session == null || sequence == null ? null : session.actions.get(sequence);
        }
        if (action == null) return null;
        Claim claim = action.claims.get(target.getId());
        if (claim == null || claim.expiresTick < prediction.tick
                || claim.consumedTick >= 0 && claim.consumedTick < prediction.tick) return null;
        return new Vec3d(claim.contact.x, claim.contact.y - target.getHeight() * 0.5, claim.contact.z);
    }

    public static CapturedInputPose capturedEffectPose(ServerPlayerEntity player) {
        PredictionServer prediction = active;
        if (prediction == null || player == null) return null;
        CoreAbility ability = AbilityExecutionContext.current();
        Action action = ability == null ? null : prediction.actionForEffect(ability);
        if (action == null) {
            UUID owner = INPUT_OWNER.get();
            Long sequence = INPUT_SEQUENCE.get();
            Session session = owner == null ? null : prediction.sessions.get(owner);
            action = session == null || sequence == null ? null : session.actions.get(sequence);
        }
        if (action == null || !action.locallyPredicted || !action.owner.equals(player.getUuid())
                || prediction.tick - action.acceptedServerTick > 1L) return null;
        return new CapturedInputPose(
                action.eyeX, action.eyeY, action.eyeZ, action.yaw, action.pitch);
    }

    private Action actionForEffect(CoreAbility ability) {
        Action action = abilityActions.get(ability);
        if (action != null || ability == null || ability.getPlayer() == null) return action;
        Session session = sessions.get(ability.getPlayer().getUniqueId());
        if (session == null) return null;
        List<Action> recent = new ArrayList<>(session.actions.values());
        for (int i = recent.size() - 1; i >= 0; i--) {
            Action candidate = recent.get(i);
            if (candidate.locallyPredicted && tick - candidate.acceptedServerTick <= 4) {
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

    /** Captures prediction ownership for delayed tasks created by an ability. */
    public static UUID captureEffectOwner() {
        ServerPlayerEntity owner = predictedEffectOwner();
        return owner == null ? null : owner.getUuid();
    }

    /** Restores captured ownership while a delayed ability task emits effects. */
    public static void runWithEffectOwner(UUID owner, Runnable task) {
        if (owner == null) {
            task.run();
            return;
        }
        UUID previous = INPUT_OWNER.get();
        Boolean previousPredicted = INPUT_LOCALLY_PREDICTED.get();
        INPUT_OWNER.set(owner);
        INPUT_LOCALLY_PREDICTED.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            if (previous == null) INPUT_OWNER.remove(); else INPUT_OWNER.set(previous);
            if (previousPredicted == null) INPUT_LOCALLY_PREDICTED.remove(); else INPUT_LOCALLY_PREDICTED.set(previousPredicted);
        }
    }

    public static boolean shouldSuppressVanillaInput(ServerPlayerEntity player, PredictionPayloads.InputKind kind) {
        PredictionServer prediction = active;
        if (prediction == null || player == null || kind == null) return false;
        Session session = prediction.sessions.get(player.getUuid());
        if (session == null) return false;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
        String abilityName = logicalInputAbility(FabricMC.player(player), bending, kind,
                bending == null ? "" : bending.getBoundAbilityName());
        if (abilityName.isBlank()) return false;
        boolean supported = prediction.profilesByName.containsKey(abilityName.toLowerCase(Locale.ROOT))
                || abilityName.equalsIgnoreCase("FireBlastCharged")
                && CoreAbility.getAbility(FireBlastCharged.class) != null;
        if (supported) {
            System.out.println("[ProjectKorraPrediction] server suppressed early vanilla input kind=" + kind
                    + " ability=" + abilityName + " player=" + player.getName().getString());
        }
        return supported;
    }

    @Override
    public void onChange(TempBlockSync.Operation operation, Block block,
                         BlockData data, long revertAtMillis, CoreAbility ability) {
        PredictionPayloads.TempOperation wireOperation = switch (operation) {
            case CREATE -> PredictionPayloads.TempOperation.CREATE;
            case UPDATE_EXPIRY -> PredictionPayloads.TempOperation.UPDATE_EXPIRY;
            case REVERT -> PredictionPayloads.TempOperation.REVERT;
        };
        CoreAbility effectiveAbility = ability == null ? AbilityExecutionContext.current() : ability;
        Action action = effectiveAbility == null ? null : actionForEffect(effectiveAbility);
        Long inputSequence = INPUT_SEQUENCE.get();
        pendingTempBlocks.add(new PredictionPayloads.TempBlockOp(wireOperation, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(data), revertAtMillis,
                action == null ? (inputSequence == null ? 0L : inputSequence) : action.sequence));
    }

    @Override
    public void onVelocity(Ability ability,
                           com.projectkorra.projectkorra.platform.mc.entity.Entity target,
                           Vector velocity) {
        if (!(ability instanceof CoreAbility coreAbility) || ability.getPlayer() == null || target == null) return;
        UUID ownerId = ability.getPlayer().getUniqueId();
        UUID targetId = target.getUniqueId();
        Session ownerSession = sessions.get(ownerId);
        Session targetSession = sessions.get(targetId);
        ServerPlayerEntity nativeTarget = server.getPlayerManager().getPlayer(targetId);
        ServerPlayerEntity nativeOwner = server.getPlayerManager().getPlayer(ownerId);
        if (nativeTarget == null) return;

        // Do not use the recent-action fallback for velocity ownership.
        Action action = abilityActions.get(coreAbility);
        Long inputSequence = INPUT_SEQUENCE.get();
        if (action == null && inputSequence != null && ownerSession != null) action = ownerSession.actions.get(inputSequence);
        if (action == null || !action.locallyPredicted || !action.owner.equals(ownerId)) return;

        int ordinal = action.velocityOrdinals.merge(targetId, 1, Integer::sum);
        PredictionPayloads.VelocityOwnerV2 receipt = new PredictionPayloads.VelocityOwnerV2(tick, action.sequence, ordinal,
                ownerId, targetId, nativeTarget.getId(), ability.getName(),
                velocity.getX(), velocity.getY(), velocity.getZ());
        if (ownerSession != null && nativeOwner != null && (ownerSession.capabilities & 8) != 0
                && ServerPlayNetworking.canSend(nativeOwner, PredictionPayloads.VelocityOwnerV2.ID)) {
            ServerPlayNetworking.send(nativeOwner, receipt);
        }
        if (!ownerId.equals(targetId) && targetSession != null && (targetSession.capabilities & 8) != 0
                && ServerPlayNetworking.canSend(nativeTarget, PredictionPayloads.VelocityOwnerV2.ID)) {
            ServerPlayNetworking.send(nativeTarget, receipt);
        }
    }

    @Override
    public void onRemoved(CoreAbility ability) {
        if (ability.getPlayer() == null || !ability.isStarted()) return;
        Action action = abilityActions.get(ability);
        UUID playerId = ability.getPlayer().getUniqueId();
        Session session = sessions.get(playerId);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (session != null && player != null && ServerPlayNetworking.canSend(player, PredictionPayloads.AbilityRemoved.ID)) {
            long sequence = action != null && action.locallyPredicted ? action.sequence : 0L;
            ServerPlayNetworking.send(player, new PredictionPayloads.AbilityRemoved(playerId, ability.getName(), sequence));
        }
    }

    private static synchronized void registerReceivers() {
        if (receiversRegistered) return;
        receiversRegistered = true;
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.ClientHello.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onHello(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.InputFrame.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onInput(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.ActionPrepare.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onPrepare(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.HitClaim.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onHitClaim(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.AuthorityHandoff.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onAuthorityHandoff(context.player(), payload);
        });
    }

    private void onHello(ServerPlayerEntity player, PredictionPayloads.ClientHello hello) {
        if (hello.protocolVersion() != PredictionPayloads.PROTOCOL_VERSION) return;
        Session session = new Session(player.getUuid(), UUID.randomUUID(), hello.clientTick(), tick, hello.capabilities());
        sessions.put(player.getUuid(), session);
        if (snapshotReady) sendSnapshot(session); else requestPublicSnapshotRebuild(false);
    }

    private void onPrepare(ServerPlayerEntity player, PredictionPayloads.ActionPrepare prepare) {
        Session session = validSession(player, prepare.sessionId());
        if (session == null || prepare.sequence() <= session.lastSequence
                || prepare.selectedSlot() < 0 || prepare.selectedSlot() > 8
                || !finite(prepare.claimedOriginX(), prepare.claimedOriginY(), prepare.claimedOriginZ(), prepare.yaw(), prepare.pitch())) return;
        Vec3d claimed = new Vec3d(prepare.claimedOriginX(), prepare.claimedOriginY(), prepare.claimedOriginZ());
        if (claimed.squaredDistanceTo(player.getEyePos()) > MAX_ORIGIN_ERROR * MAX_ORIGIN_ERROR) return;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
        String ability = logicalInputAbility(FabricMC.player(player), bending, prepare.kind(),
                bending == null ? "" : bending.getAbilities().get(prepare.selectedSlot() + 1));
        if (ability.isBlank()) return;
        long mappedTick = session.mapClientTick(prepare.clientTick(), tick, player.networkHandler.getLatency());
        session.actions.put(prepare.sequence(), new Action(player.getUuid(), prepare.sequence(), prepare.clientTick(),
                mappedTick, tick, ability, prepare.claimedOriginX(), prepare.claimedOriginY(), prepare.claimedOriginZ(),
                prepare.yaw(), prepare.pitch(), profile(ability), false));
        while (session.actions.size() > 128) session.actions.remove(session.actions.keySet().iterator().next());
    }

    private void onInput(ServerPlayerEntity player, PredictionPayloads.InputFrame input) {
        Session session = validSession(player, input.sessionId());
        if (session == null || !session.inputLimiter.allow(tick, INPUTS_PER_SECOND)) return;
        Vec3d origin = player.getEyePos();
        if (input.sequence() <= session.lastSequence) {
            reconcile(player, session, input.sequence(), false, "stale_sequence", "", origin, 0L);
            return;
        }
        session.lastSequence = input.sequence();
        if (!finite(input.claimedOriginX(), input.claimedOriginY(), input.claimedOriginZ(), input.yaw(), input.pitch())) {
            reconcile(player, session, input.sequence(), false, "non_finite_input", "", origin, 0L);
            return;
        }
        Vec3d claimedOrigin = new Vec3d(input.claimedOriginX(), input.claimedOriginY(), input.claimedOriginZ());
        if (claimedOrigin.squaredDistanceTo(origin) > MAX_ORIGIN_ERROR * MAX_ORIGIN_ERROR) {
            reconcile(player, session, input.sequence(), false, "origin_out_of_bounds", "", origin, 0L);
            return;
        }
        if (input.selectedSlot() < 0 || input.selectedSlot() > 8) {
            reconcile(player, session, input.sequence(), false, "invalid_selected_slot", "", origin, 0L);
            return;
        }

        BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
        String abilityName = logicalInputAbility(FabricMC.player(player), bending, input.kind(),
                bending == null ? "" : bending.getAbilities().get(input.selectedSlot() + 1));
        if (abilityName.isBlank()) {
            reconcile(player, session, input.sequence(), false, "no_bound_ability", "", origin, 0L);
            return;
        }
        if (!gameplay.applyPredictedSlot(player, input.selectedSlot())) {
            reconcile(player, session, input.sequence(), false, "invalid_selected_slot", abilityName, origin,
                    bending == null ? 0L : bending.getCooldown(abilityName));
            return;
        }
        if (input.locallyBlockedByCooldown()) {
            // Preserve the input-time decision. A one-second trip must not
            // turn a locally blocked click into a cast merely because the
            // authoritative cooldown expired before this frame arrived. The
            // physical input still proceeds through combo tracking below.
            gameplay.suppressPredictedVanillaInput(player, input.kind());
        }

        long mappedTick = session.mapClientTick(input.clientTick(), tick, player.networkHandler.getLatency());
        Action action = session.actions.get(input.sequence());
        if (action != null && (action.clientTick != input.clientTick()
                || !action.abilityName.equalsIgnoreCase(abilityName)
                || Math.abs(action.yaw - input.yaw()) > 0.01F || Math.abs(action.pitch - input.pitch()) > 0.01F)) {
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "prepare_mismatch", abilityName, origin,
                    bending == null ? 0L : bending.getCooldown(abilityName));
            return;
        }
        if (action == null) {
            action = new Action(player.getUuid(), input.sequence(), input.clientTick(), mappedTick, tick,
                    abilityName, input.claimedOriginX(), input.claimedOriginY(), input.claimedOriginZ(),
                    input.yaw(), input.pitch(), profile(abilityName), input.locallyPredicted());
            session.actions.put(input.sequence(), action);
        }
        action.locallyPredicted = input.locallyPredicted();

        Set<CoreAbility> before = identitySet(CoreAbility.getAbilitiesByInstances());
        boolean hadExistingMatchingAbility = before.stream().anyMatch(candidate -> candidate.getPlayer() != null
                && candidate.getPlayer().getUniqueId().equals(player.getUuid())
                && matchesInputAbility(candidate, abilityName));
        long cooldownBefore = bending == null ? 0L : bending.getCooldown(abilityName);
        boolean dispatched;
        boolean handled;
        INPUT_OWNER.set(player.getUuid());
        INPUT_SEQUENCE.set(input.sequence());
        INPUT_LOCALLY_PREDICTED.set(input.locallyPredicted());
        Cooldown previousGuardedCooldown = null;
        boolean guardedCooldown = input.locallyBlockedByCooldown() && bending != null;
        if (guardedCooldown) {
            previousGuardedCooldown = bending.getCooldowns().put(abilityName,
                    new Cooldown(System.currentTimeMillis() + 60_000L, false));
        }
        AbilityActivationManager.beginTracking();
        try {
            dispatched = gameplay.handlePredictedInput(player, input.kind());
        } finally {
            handled = AbilityActivationManager.finishTracking();
            if (guardedCooldown) {
                if (previousGuardedCooldown == null) bending.getCooldowns().remove(abilityName);
                else bending.getCooldowns().put(abilityName, previousGuardedCooldown);
            }
            INPUT_OWNER.remove();
            INPUT_SEQUENCE.remove();
            INPUT_LOCALLY_PREDICTED.remove();
        }
        if (!dispatched) {
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "input_rejected", abilityName, origin,
                    bending == null ? 0L : bending.getCooldown(abilityName));
            return;
        }

        long cooldownAfter = bending == null ? 0L : bending.getCooldown(abilityName);
        boolean createdAny = createdAnyAbility(before, player.getUuid());
        if (input.locallyBlockedByCooldown() && !PredictionCooldownTimeline.allowsCooldownGuardedInput(
                createdAny, handled, hadExistingMatchingAbility)) {
            flushTempBlocks();
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "client_cooldown_combo_recorded", abilityName,
                    claimedOrigin, cooldownAfter);
            return;
        }
        boolean directTargetTransition = "FirePunch".equalsIgnoreCase(abilityName);
        if (input.locallyPredicted() && !handled && !directTargetTransition
                && cooldownAfter <= cooldownBefore
                && !createdAbility(before, player.getUuid(), abilityName)) {
            flushTempBlocks();
            session.actions.remove(input.sequence());
            reconcile(player, session, input.sequence(), false, "cooldown", abilityName, claimedOrigin, cooldownAfter);
            return;
        }

        cooldownAfter = PredictionCooldownTimeline.alignNewCooldown(bending, abilityName, cooldownBefore,
                Math.max(0L, tick - mappedTick) * 50L, System.currentTimeMillis());
        while (session.actions.size() > 128) session.actions.remove(session.actions.keySet().iterator().next());

        for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
            if (candidate.getPlayer() == null || !candidate.getPlayer().getUniqueId().equals(player.getUuid())) continue;
            if (!before.contains(candidate) || matchesInputAbility(candidate, abilityName)) {
                abilityActions.put(candidate, action);
            }
        }

        long cooldownUntil = cooldownAfter;
        flushTempBlocks();
        reconcile(player, session, input.sequence(), true, "accepted", abilityName,
                isSneakTransition(input.kind()) ? claimedOrigin : origin, cooldownUntil);
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

    private void onHitClaim(ServerPlayerEntity player, PredictionPayloads.HitClaim payload) {
        Session session = validSession(player, payload.sessionId());
        if (session == null || !session.claimLimiter.allow(tick, CLAIMS_PER_SECOND)) return;
        Action action = session.actions.get(payload.actionSequence());
        if (action == null || tick - action.acceptedServerTick > 200 || action.claims.containsKey(payload.targetEntityId())) return;
        if (!finite(payload.contactX(), payload.contactY(), payload.contactZ())) return;
        Entity nativeTarget = player.getEntityWorld().getEntityById(payload.targetEntityId());
        if (!(nativeTarget instanceof net.minecraft.entity.LivingEntity target) || target == player
                || target instanceof ServerPlayerEntity targetPlayer && targetPlayer.isSpectator()) return;

        long rewindTick = session.mapClientTick(payload.clientTick(), tick, player.networkHandler.getLatency());
        Vec3d contact = new Vec3d(payload.contactX(), payload.contactY(), payload.contactZ());
        Claim claim = new Claim(target.getId(), rewindTick, contact,
                tick + MAX_REWIND_TICKS + 4);
        action.claims.put(target.getId(), claim);
        if ("FirePunch".equalsIgnoreCase(action.abilityName)) {
            consumeFirePunchClaim(player, target, action, claim);
        }
        reconcile(player, session, action.sequence, true, "hit_claim_accepted", action.abilityName,
                player.getEyePos(), BendingPlayer.getBendingPlayer(FabricMC.player(player)).getCooldown(action.abilityName));
    }

    private void consumeFirePunchClaim(ServerPlayerEntity player, net.minecraft.entity.LivingEntity target,
                                       Action action, Claim claim) {
        Player commonPlayer = FabricMC.player(player);
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
        UUID previousOwner = INPUT_OWNER.get();
        Long previousSequence = INPUT_SEQUENCE.get();
        Boolean previousPredicted = INPUT_LOCALLY_PREDICTED.get();
        INPUT_OWNER.set(player.getUuid());
        INPUT_SEQUENCE.set(action.sequence);
        INPUT_LOCALLY_PREDICTED.set(action.locallyPredicted);
        try {
            AbilityExecutionContext.run(executing,
                    () -> executing.punch((LivingEntity)
                            FabricMC.entity(target)));
        } finally {
            if (previousOwner == null) INPUT_OWNER.remove(); else INPUT_OWNER.set(previousOwner);
            if (previousSequence == null) INPUT_SEQUENCE.remove(); else INPUT_SEQUENCE.set(previousSequence);
            if (previousPredicted == null) INPUT_LOCALLY_PREDICTED.remove(); else INPUT_LOCALLY_PREDICTED.set(previousPredicted);
        }
    }

    private void onAuthorityHandoff(ServerPlayerEntity player, PredictionPayloads.AuthorityHandoff payload) {
        Session session = validSession(player, payload.sessionId());
        if (session == null) return;
        Action action = session.actions.get(payload.actionSequence());
        if (action != null && action.owner.equals(player.getUuid())) action.locallyPredicted = false;
    }

    private boolean lineOfSight(ServerPlayerEntity player, Vec3d start, Vec3d end, double tolerance) {
        HitResult hit = player.getEntityWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getPos().squaredDistanceTo(end) <= (tolerance + 1.0) * (tolerance + 1.0);
    }

    private void recordHistory() {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Deque<EntityFrame> frames = history.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>());
            frames.addLast(new EntityFrame(tick, player.getEntityWorld().getRegistryKey().getValue().toString(),
                    player.getEntityPos(), player.getEyePos(), player.getBoundingBox(), player.getYaw(), player.getPitch()));
            while (!frames.isEmpty() && tick - frames.getFirst().serverTick > MAX_REWIND_TICKS + 4) frames.removeFirst();
        }
        history.keySet().removeIf(uuid -> server.getPlayerManager().getPlayer(uuid) == null);
    }

    private EntityFrame frameAt(UUID uuid, long wantedTick) {
        Deque<EntityFrame> frames = history.get(uuid);
        if (frames == null || frames.isEmpty()) return null;
        EntityFrame best = frames.getFirst();
        long bestDistance = Math.abs(best.serverTick - wantedTick);
        for (EntityFrame frame : frames) {
            long distance = Math.abs(frame.serverTick - wantedTick);
            if (distance < bestDistance) { best = frame; bestDistance = distance; }
        }
        return best;
    }

    private void flushTempBlocks() {
        if (pendingTempBlocks.isEmpty()) return;
        List<PredictionPayloads.TempBlockOp> batch = List.copyOf(pendingTempBlocks);
        pendingTempBlocks.clear();
        PredictionPayloads.TempBlockBatch payload = new PredictionPayloads.TempBlockBatch(tick, System.currentTimeMillis(), batch);
        for (Session session : sessions.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
            if (player != null && ServerPlayNetworking.canSend(player, PredictionPayloads.TempBlockBatch.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private void syncPlayerStateChanges() {
        for (Session session : sessions.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
            if (player == null) continue;
            BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
            Map<Integer, String> binds = PredictionSnapshotBuilder.binds(bending);
            Map<String, Long> cooldowns = PredictionSnapshotBuilder.cooldowns(bending);
            List<String> elements = PredictionSnapshotBuilder.elements(bending);
            List<String> subElements = PredictionSnapshotBuilder.subElements(bending);
            double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
            List<String> activeFlights = activeFlightAbilities(player.getUuid());
            int digest = (((31 * binds.hashCode() + cooldowns.hashCode()) * 31 + elements.hashCode()) * 31 + subElements.hashCode()) * 31
                    + Double.hashCode(airBlastDecay) * 31 + activeFlights.hashCode();
            if (digest == session.playerStateDigest) continue;
            session.playerStateDigest = digest;
            if (ServerPlayNetworking.canSend(player, PredictionPayloads.PlayerState.ID)) {
                ServerPlayNetworking.send(player, new PredictionPayloads.PlayerState(session.sessionId, tick,
                        System.currentTimeMillis(), binds, cooldowns, elements, subElements, airBlastDecay, activeFlights));
            }
        }
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

    private void requestPublicSnapshotRebuild(boolean broadcastChanges) {
        if (!snapshotBuildRunning.compareAndSet(false, true)) return;
        CompletableFuture.supplyAsync(() -> {
            List<PredictionPayloads.ConfigEntry> config = PredictionSnapshotBuilder.publicConfig();
            List<PredictionPayloads.AbilityProfile> abilityProfiles = PredictionSnapshotBuilder.profiles(config);
            return new PublicSnapshot(config, abilityProfiles,
                    Integer.toUnsignedLong(31 * config.hashCode() + abilityProfiles.hashCode()));
        }).whenComplete((snapshot, failure) -> server.execute(() -> {
            snapshotBuildRunning.set(false);
            if (active != this || failure != null || snapshot == null) {
                if (failure != null) System.err.println("[ProjectKorraPrediction] async config snapshot failed: " + failure.getMessage());
                return;
            }
            boolean first = !snapshotReady;
            boolean changed = snapshot.epoch != configEpoch;
            publicConfig = snapshot.config;
            profiles = snapshot.profiles;
            Map<String, PredictionPayloads.AbilityProfile> byName = new LinkedHashMap<>();
            for (PredictionPayloads.AbilityProfile profile : profiles) byName.put(profile.name().toLowerCase(Locale.ROOT), profile);
            profilesByName = Map.copyOf(byName);
            configEpoch = snapshot.epoch;
            snapshotReady = true;
            if (first || broadcastChanges && changed) for (Session session : sessions.values()) sendSnapshot(session);
        }));
    }

    private void sendSnapshot(Session session) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
        if (player == null || !ServerPlayNetworking.canSend(player, PredictionPayloads.ServerSnapshot.ID)) return;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
        Map<Integer, String> binds = PredictionSnapshotBuilder.binds(bending);
        Map<String, Long> cooldowns = PredictionSnapshotBuilder.cooldowns(bending);
        List<String> elements = PredictionSnapshotBuilder.elements(bending);
        List<String> subElements = PredictionSnapshotBuilder.subElements(bending);
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        session.playerStateDigest = (((31 * binds.hashCode() + cooldowns.hashCode()) * 31 + elements.hashCode()) * 31 + subElements.hashCode()) * 31
                + Double.hashCode(airBlastDecay);
        ServerPlayNetworking.send(player, new PredictionPayloads.ServerSnapshot(PredictionPayloads.PROTOCOL_VERSION,
                session.sessionId, tick, System.currentTimeMillis(), configEpoch, MAX_REWIND_TICKS,
                publicConfig, profiles, binds, cooldowns, elements, subElements, airBlastDecay));
    }

    private void reconcile(ServerPlayerEntity player, Session session, long sequence, boolean accepted, String reason,
                           String ability, Vec3d origin, long cooldownUntil) {
        if (ServerPlayNetworking.canSend(player, PredictionPayloads.Reconcile.ID)) {
            ServerPlayNetworking.send(player, new PredictionPayloads.Reconcile(session.sessionId, sequence, accepted,
                    reason, tick, System.currentTimeMillis(), ability, origin.x, origin.y, origin.z, cooldownUntil));
        }
    }

    private Session validSession(ServerPlayerEntity player, UUID sessionId) {
        Session session = sessions.get(player.getUuid());
        return session != null && session.sessionId.equals(sessionId) ? session : null;
    }

    private PredictionPayloads.AbilityProfile profile(String abilityName) {
        return profilesByName.getOrDefault(abilityName.toLowerCase(Locale.ROOT),
                new PredictionPayloads.AbilityProfile(abilityName, "Unknown", PredictionPayloads.VisualKind.CAST,
                        0.0, 8.0, 0.75, 0L, 0L, "minecraft:air", false, false));
    }

    private static Set<CoreAbility> identitySet(Iterable<CoreAbility> abilities) {
        Set<CoreAbility> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CoreAbility ability : abilities) result.add(ability);
        return result;
    }

    private static String materialName(Material material) {
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean isSneakTransition(PredictionPayloads.InputKind kind) {
        return kind == PredictionPayloads.InputKind.SNEAK_START || kind == PredictionPayloads.InputKind.SNEAK_STOP;
    }

    private static String logicalInputAbility(Player player,
                                              BendingPlayer bending, PredictionPayloads.InputKind kind,
                                              String fallback) {
        if (player == null || bending == null) return safe(fallback);
        if (kind == PredictionPayloads.InputKind.SNEAK_START && !CoreAbility.hasAbility(player, FastSwim.class)) {
            CoreAbility bound = bending.getBoundAbility();
            CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
            if ((bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive)) {
                return "FastSwim";
            }
        }
        String multi = MultiAbilityManager.getBoundMultiAbility(player);
        if (multi != null && !multi.isBlank()) return multi;
        String selected = safe(fallback);
        if (selected.equalsIgnoreCase("FireBlast") && isSneakTransition(kind)) {
            return "FireBlastCharged";
        }
        if (selected.isBlank() && kind == PredictionPayloads.InputKind.LEFT_CLICK
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

    private static final class Session {
        final UUID playerId;
        final UUID sessionId;
        final long helloClientTick;
        final long helloServerTick;
        final int capabilities;
        final RateLimiter inputLimiter = new RateLimiter();
        final RateLimiter claimLimiter = new RateLimiter();
        final LinkedHashMap<Long, Action> actions = new LinkedHashMap<>();
        long lastSequence;
        int playerStateDigest;

        Session(UUID playerId, UUID sessionId, long helloClientTick, long helloServerTick, int capabilities) {
            this.playerId = playerId;
            this.sessionId = sessionId;
            this.helloClientTick = helloClientTick;
            this.helloServerTick = helloServerTick;
            this.capabilities = capabilities;
        }

        long mapClientTick(long clientTick, long now, int pingMillis) {
            long receiptMapped = helloServerTick + (clientTick - helloClientTick);
            long oneWayTicks = Math.max(0L, Math.min(MAX_REWIND_TICKS,
                    Math.round(Math.max(0, pingMillis) / 100.0)));
            long mapped = receiptMapped - oneWayTicks;
            return Math.max(now - MAX_REWIND_TICKS, Math.min(now, mapped));
        }
    }

    private record PublicSnapshot(List<PredictionPayloads.ConfigEntry> config,
                                  List<PredictionPayloads.AbilityProfile> profiles, long epoch) { }

    private static final class RateLimiter {
        long windowStart;
        int count;
        boolean allow(long tick, int maximum) {
            if (tick - windowStart >= 20) { windowStart = tick; count = 0; }
            return ++count <= maximum;
        }
    }

    private static final class Action {
        final UUID owner;
        final long sequence;
        final long clientTick;
        final long rewindTick;
        final long acceptedServerTick;
        final String abilityName;
        final double eyeX;
        final double eyeY;
        final double eyeZ;
        final float yaw;
        final float pitch;
        final PredictionPayloads.AbilityProfile profile;
        boolean locallyPredicted;
        final Map<Integer, Claim> claims = new HashMap<>();
        final Map<UUID, Integer> velocityOrdinals = new HashMap<>();
        Action(UUID owner, long sequence, long clientTick, long rewindTick, long acceptedServerTick,
               String abilityName, double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
               PredictionPayloads.AbilityProfile profile, boolean locallyPredicted) {
            this.owner = owner; this.sequence = sequence; this.clientTick = clientTick; this.rewindTick = rewindTick;
            this.acceptedServerTick = acceptedServerTick; this.abilityName = abilityName;
            this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ; this.yaw = yaw;
            this.pitch = pitch; this.profile = profile; this.locallyPredicted = locallyPredicted;
        }
    }

    private static final class Claim {
        final int targetEntityId;
        final long rewindTick;
        final Vec3d contact;
        final long expiresTick;
        long consumedTick = -1;
        private Claim(int targetEntityId, long rewindTick, Vec3d contact, long expiresTick) {
            this.targetEntityId = targetEntityId;
            this.rewindTick = rewindTick;
            this.contact = contact;
            this.expiresTick = expiresTick;
        }
    }
    private record EntityFrame(long serverTick, String world, Vec3d position, Vec3d eyePosition, Box box,
                               float yaw, float pitch) { }
}
