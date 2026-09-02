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

public abstract class PaperPredictionEffects extends PaperPredictionTempBlocks {
    protected PaperPredictionEffects(final JavaPlugin plugin) {
        super(plugin);
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
        if (!ownerId.equals(targetId)) flushAbilityRemovals();
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
    public void beforeWrite(final CoreAbility ability,
                            final com.projectkorra.projectkorra.platform.mc.entity.Player target,
                            final boolean gliding) {
        if (target == null) return;
        final UUID targetId = target.getUniqueId();
        final UUID contextualOwner = EFFECT_OWNER.get();
        final UUID ownerId = ability != null && ability.getPlayer() != null
                ? ability.getPlayer().getUniqueId()
                : contextualOwner == null ? targetId : contextualOwner;
        Action action = currentInputAction(ownerId);
        if (action == null && ability != null) action = actionForEffect(ability);
        if (action == null || !action.owner.equals(ownerId)) return;
        final Session targetSession = sessions.get(targetId);
        final Player nativeTarget = Bukkit.getPlayer(targetId);
        if (targetSession == null || nativeTarget == null
                || (targetSession.capabilities & CAPABILITY_EXACT) == 0) return;
        final int ordinal = action.glidingStateOrdinals.merge(targetId, 1, Integer::sum);
        send(nativeTarget, PaperPredictionProtocol.GLIDING_STATE_OWNER,
                PaperPredictionProtocol.glidingStateOwner(tick, action.sequence, ordinal,
                        ownerId, targetId, ability == null ? action.ability : ability.getName(), gliding));
    }

    @Override
    public void onRemoved(CoreAbility ability, boolean externallyCaused) {
        onRemoved(ability, externallyCaused, false);
    }

    @Override
    public void onRemoved(final CoreAbility ability, final boolean externallyCaused,
                          final boolean predictionRejected) {
        if (ability.getPlayer() == null || !ability.isStarted()) return;
        UUID playerId = ability.getPlayer().getUniqueId();
        final Action action = creationActionForRemoval(ability, playerId);
        pendingAbilityRemovals.add(new PendingAbilityRemoval(playerId, ability.getName(),
                AbilityRemovalSync.typeId(ability),
                action != null && action.owner.equals(playerId) && action.locallyPredicted
                        ? action.sequence : 0L,
                externallyCaused, predictionRejected, ability));
    }

    protected Action creationActionForRemoval(final CoreAbility ability,
                                            final UUID playerId) {
        if (ability == null || playerId == null) return null;
        final Action mapped = abilityCreationActions.get(ability);
        if (mapped != null && playerId.equals(mapped.owner)) return mapped;
        final long inherited = ability.getPredictionActionSequence();
        final Session session = sessions.get(playerId);
        final Action recovered = inherited > 0L && session != null
                ? session.actions.get(inherited) : null;
        if (recovered == null || !playerId.equals(recovered.owner)) return null;
        abilityCreationActions.putIfAbsent(ability, recovered);
        return recovered;
    }

    @Override
    public void onOwnerTransferred(final CoreAbility ability, final UUID previousOwner,
                                   final UUID nextOwner) {
        if (ability == null || previousOwner == null || nextOwner == null
                || previousOwner.equals(nextOwner) || !ability.isStarted()) return;
        final Action previousAction = creationActionForRemoval(ability, previousOwner);
        sendAbilityRemoval(previousOwner, ability.getName(), AbilityRemovalSync.typeId(ability),
                previousAction != null && previousAction.owner.equals(previousOwner)
                        && previousAction.locallyPredicted ? previousAction.sequence : 0L,
                true, false, ability);

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
                    }
                }
                sendEarthSmashState(player, smash, transferAction, transfer, true);
            }
        }
    }

    @Override
    public void onCheckpoint(final CoreAbility ability) {
        if (ability == null || ability.getPlayer() == null) return;
        final UUID playerId = ability.getPlayer().getUniqueId();
        Action checkpointAction = currentInputAction(playerId);
        if (checkpointAction == null) checkpointAction = abilityActions.getOrDefault(
                ability, abilityCreationActions.get(ability));
        final Session session = sessions.get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (ability instanceof AirGlider glider) {
            if (checkpointAction == null || session == null || !session.ready || player == null
                    || (session.capabilities & CAPABILITY_EXACT) == 0) return;
            sendAirGliderState(player, glider, checkpointAction);
            return;
        }
        if (!(ability instanceof EarthSmash smash)) return;
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

    protected void sendAirGliderState(final Player player, final AirGlider glider,
                                    final Action preferredAction) {
        if (player == null || glider == null || glider.isRemoved()) return;
        final Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.ready || (session.capabilities & CAPABILITY_EXACT) == 0) return;
        final Action action = preferredAction != null ? preferredAction
                : abilityActions.getOrDefault(glider, abilityCreationActions.get(glider));
        final AirGlider.PredictionState state = glider.capturePredictionState();
        if (action == null || state == null) return;
        send(player, PaperPredictionProtocol.AIR_GLIDER_STATE,
                PaperPredictionProtocol.airGliderState(player.getUniqueId(), tick, action.sequence, state));
    }

    protected void sendEarthSmashState(final Player player, final EarthSmash smash,
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

    protected void flushAbilityRemovals() {
        if (pendingAbilityRemovals.isEmpty()) return;
        final List<PendingAbilityRemoval> removals = List.copyOf(pendingAbilityRemovals);
        pendingAbilityRemovals.clear();
        for (PendingAbilityRemoval removal : removals) {
            // CoreAbility publishes removal from super.remove(), before the
            // subclass closes its layers. Keep the action association alive
            // through that synchronous cleanup and retire it only now.
            abilityActions.remove(removal.instance());
            abilityCreationActions.remove(removal.instance());
            predictedOwnershipTransfers.remove(removal.instance());
            sendAbilityRemoval(removal.playerId(), removal.ability(), removal.abilityType(),
                    removal.actionSequence(), removal.externallyCaused(),
                    removal.predictionRejected(), removal.instance());
        }
    }

    protected void sendAbilityRemoval(final UUID playerId, final String ability,
                                    final String abilityType, final long actionSequence,
                                    final boolean externallyCaused,
                                    final boolean predictionRejected,
                                    final CoreAbility removedInstance) {
        final Session session = sessions.get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null) return;
        final int remainingTypeInstances = AbilityRemovalSync.activeTypeCount(playerId, abilityType);
        final int remainingActionInstances = activeCreationActionCount(
                playerId, ability, actionSequence, removedInstance);
        final int remainingNamedInstances = activeAbilityNameCount(
                playerId, ability, removedInstance);
        send(player, PaperPredictionProtocol.ABILITY_REMOVED,
                PaperPredictionProtocol.abilityRemoved(playerId, ability, abilityType,
                        actionSequence, externallyCaused, predictionRejected,
                        session.lastSequence,
                        remainingTypeInstances, remainingActionInstances,
                        remainingNamedInstances));
        sendState(player, session, true);
    }

    /** Remaining live same-name instances created by this exact predicted input. */
    protected int activeCreationActionCount(final UUID playerId, final String ability,
                                          final long actionSequence,
                                          final CoreAbility removedInstance) {
        if (playerId == null || ability == null || ability.isBlank()
                || actionSequence <= 0L) return 0;
        int count = 0;
        synchronized (abilityCreationActions) {
            for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                if (candidate == null || candidate == removedInstance || candidate.isRemoved()
                        || !ability.equalsIgnoreCase(candidate.getName())) continue;
                final Action action = abilityCreationActions.get(candidate);
                final UUID candidateOwner = action != null ? action.owner
                        : candidate.getPlayer() == null ? null
                        : candidate.getPlayer().getUniqueId();
                final long candidateSequence = action != null ? action.sequence
                        : candidate.getPredictionActionSequence();
                if (!playerId.equals(candidateOwner)
                        || candidateSequence != actionSequence) continue;
                count++;
            }
        }
        return count;
    }

    /** Remaining live instances sharing the direct-effect ability name. */
    protected static int activeAbilityNameCount(final UUID playerId, final String ability,
                                              final CoreAbility removedInstance) {
        if (playerId == null || ability == null || ability.isBlank()) return 0;
        int count = 0;
        for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
            if (candidate == null || candidate == removedInstance || candidate.isRemoved()
                    || candidate.getPlayer() == null
                    || !playerId.equals(candidate.getPlayer().getUniqueId())
                    || !ability.equalsIgnoreCase(candidate.getName())) continue;
            count++;
        }
        return count;
    }
}
