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

public abstract class PaperPredictionInput extends PaperPredictionEffects {
    protected PaperPredictionInput(final JavaPlugin plugin) {
        super(plugin);
    }

    protected void onHello(Player player, PaperPredictionProtocol.Hello hello) {
        if (hello.version() != PaperPredictionProtocol.VERSION) return;
        Session session = new Session(player.getUniqueId(), UUID.randomUUID(), hello.capabilities(),
                hello.clientTick(), tick);
        sessions.put(player.getUniqueId(), session);
        if (snapshotReady) sendSnapshot(session);
        else requestSnapshotRebuild(false);
    }

    protected void onClientDisabled(final Player player,
                                  final PaperPredictionProtocol.ClientDisabled disabled) {
        if (disabled.version() != PaperPredictionProtocol.VERSION) return;
        sessions.remove(player.getUniqueId());
    }

    protected void onReady(Player player, PaperPredictionProtocol.Ready ready) {
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

    protected void onInputVeto(Player player, PaperPredictionProtocol.InputVeto veto) {
        final Session session = valid(player, veto.session());
        if (session == null || !session.ready || veto.kind() == null
                || veto.ability() == null || veto.ability().isBlank()
                || veto.sequence() <= 0L || session.inputVetoes.size() >= 128) return;
        // This negative-only payload is written immediately before its vanilla
        // input packet on the same ordered connection. The loaders do not
        // share raw native ordinals, so consume it as a one-shot stream item.
        session.inputVetoes.addLast(veto);
    }

    protected static PaperPredictionProtocol.InputVeto consumeInputVeto(final Session session,
                                                                       final long clientSequence) {
        if (session == null || clientSequence <= 0L) return null;
        while (!session.inputVetoes.isEmpty()) {
            final PaperPredictionProtocol.InputVeto veto = session.inputVetoes.peekFirst();
            if (veto.sequence() > clientSequence) return null;
            session.inputVetoes.removeFirst();
            if (veto.sequence() == clientSequence) return veto;
        }
        return null;
    }

    protected void onActionTag(final Player player, final PaperPredictionProtocol.ActionTag tag) {
        final Session session = valid(player, tag.session());
        if (session == null || !session.ready || tag.clientSequence() <= 0L
                || tag.kind() == null || tag.selectedSlot() < 0 || tag.selectedSlot() > 8
                || tag.ability() == null || tag.ability().isBlank()) return;
        session.actionTags.offer(tag);
    }

    protected void onHitClaim(final Player player, final PaperPredictionProtocol.HitClaim hit) {
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
        final CoreAbility claimedAbility = CoreAbility.getAbility(action.ability);
        if (claimedAbility != null && HitRegistrationPolicy.forAbility(claimedAbility)
                == HitRegistrationPolicy.SERVER_CURRENT) return;
        final Player target = Bukkit.getPlayer(hit.target());
        if (target == null || target == player || target.isDead()
                || target.getEntityId() != hit.entityId()
                || target.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || target.getWorld() != player.getWorld()) return;

        final int defenderPing = target.getPing();
        final long rewindTick = session.mapClientTick(hit.clientTick(), tick,
                player.getPing(), defenderPing);
        final EntityFrame frame = frameAt(target.getUniqueId(), rewindTick);
        if (frame == null || !frame.world().equals(target.getWorld().getUID())) return;
        final Vector contact = new Vector(hit.x(), hit.y(), hit.z());
        if (!frame.box().clone().expand(CLAIM_CONTACT_TOLERANCE).contains(contact)
                || contact.distanceSquared(new Vector(action.eyeX, action.eyeY, action.eyeZ))
                > MAX_CLAIM_DISTANCE_SQUARED) return;
        final int rewindTicks = HitRewind.combinedRewindTicks(
                player.getPing(), defenderPing, MAX_REWIND_TICKS);
        action.claims.put(target.getUniqueId(), new Claim(target.getUniqueId(), rewindTick,
                tick + Math.max(4, rewindTicks), contact, frame.box().clone()));
    }

    protected Action findClaimAction(final Session session,
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

    protected static boolean matchesClaimAction(final Action action,
                                              final PaperPredictionProtocol.HitClaim hit) {
        return action != null && hit != null && action.ability.equalsIgnoreCase(hit.ability());
    }

    protected void recordPlayerHistory() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            final Deque<EntityFrame> frames = playerHistory.computeIfAbsent(
                    player.getUniqueId(), ignored -> new ArrayDeque<>());
            frames.addLast(new EntityFrame(tick, player.getWorld().getUID(), player.getBoundingBox()));
            while (!frames.isEmpty()
                    && tick - frames.getFirst().serverTick() > MAX_REWIND_TICKS + 4L) {
                frames.removeFirst();
            }
        }
        playerHistory.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    protected EntityFrame frameAt(final UUID playerId, final long wantedTick) {
        final Deque<EntityFrame> frames = playerHistory.get(playerId);
        if (frames == null || frames.isEmpty()) return null;
        EntityFrame best = frames.getFirst();
        long bestDistance = Math.abs(best.serverTick() - wantedTick);
        for (EntityFrame frame : frames) {
            final long distance = Math.abs(frame.serverTick() - wantedTick);
            if (distance < bestDistance) {
                best = frame;
                bestDistance = distance;
            }
        }
        return best;
    }

    protected CommonInputHandler.InputResult processInput(
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

        final PaperPredictionProtocol.InputVeto veto = consumeInputVeto(session, clientActionSequence);
        final boolean locallyRejectedOnCooldown = predictable && clientActionSequence > 0L
                && veto != null && veto.sequence() == clientActionSequence
                && veto.kind() == kind && abilityName.equalsIgnoreCase(veto.ability());
        final boolean locallyAcceptedCooldown = predictable && clientActionSequence > 0L
                && veto == null;
        final List<String> inputCooldowns = inputVetoCooldowns(abilityName, kind);
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
                                    inputCooldowns, nativeInput)
                                    : locallyAcceptedCooldown
                                    ? CooldownSync.runInputLeniency(player.getUniqueId(), inputCooldowns,
                                    CooldownSync.INPUT_LENIENCY_MILLIS, nativeInput)
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
        if (abilityName.equalsIgnoreCase("AirGlider")) {
            for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                if (candidate instanceof AirGlider
                        && !candidate.isRemoved() && candidate.getPlayer() != null
                        && candidate.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                    abilityActions.put(candidate, action);
                    onCheckpoint(candidate);
                }
            }
        }
        action.locallyPredicted = createdAnyAbility || trackingResult.handled()
                || action.tempBlockOrdinal > 0 || action.tempFallingBlockOrdinal > 0
                || !action.directBlockOrdinals.isEmpty() || !action.velocityOrdinals.isEmpty()
                || !action.abilityStateOrdinals.isEmpty() || !action.glidingStateOrdinals.isEmpty();
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
}
