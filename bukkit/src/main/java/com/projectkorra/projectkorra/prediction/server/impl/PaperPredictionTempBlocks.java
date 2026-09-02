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

public abstract class PaperPredictionTempBlocks extends PaperPredictionTransport {
    protected PaperPredictionTempBlocks(final JavaPlugin plugin) {
        super(plugin);
    }

    protected CommonInputHandler.InputResult handleVanilla0(
            final Player player, final PaperPredictionProtocol.InputKind kind,
            final Supplier<CommonInputHandler.InputResult> nativeInput) {
        final Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.ready) return nativeInput.get();
        return processInput(player, session, kind, nativeInput);
    }

    protected Action actionForEffect(CoreAbility ability) {
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
        // Fall AirBurst is created by the authoritative fall-damage event,
        // not by a native input the Fabric client can replay. Associating it
        // with an older AirBurst input by name hides Paper's particles and
        // sound from the exact client even though no local fall burst exists.
        if (ability instanceof AirBurst burst && burst.isFallBurst()) return null;
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
    protected Action currentInputAction(final UUID ownerId) {
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
        if (action != null) action.directBlockOrdinals.put(cause.ability(), ordinal);
        if (!packetExpected) return;
        send(owner, PaperPredictionProtocol.DIRECT_BLOCK,
                PaperPredictionProtocol.directBlock(tick, actionSequence, ordinal, ownerId,
                        abilityName, worldKey(block.getWorld()), block.getX(), block.getY(), block.getZ(),
                        TempBlockSync.encode(replacement), lifecycle != null && lifecycle.valid()));
    }

    protected void scheduleTicker() {
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
        // Physical changes are consumed by beforeWorldChange. This callback
        // therefore only receives metadata-only layers and expiry changes.
        queueTempBlock(change);
    }

    @Override
    public boolean receivesPostWorldChange() {
        return false;
    }

    @Override
    public boolean capturesUnderlay() {
        return false;
    }

    @Override
    public boolean copiesChangeData() {
        return false;
    }

    @Override
    public boolean capturesOwnerViews() {
        return false;
    }

    protected PendingTempBlock queueTempBlock(final TempBlockSync.Change change) {
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
        final String worldIdentity = block.getWorld() != null && block.getWorld().handle() instanceof World world
                ? world.getUID().toString() : null;
        if (worldIdentity == null) return null;

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
        final String effectAbility = effect == null ? change.effectAbility() : effect.ability();
        final long effectStep = effect == null ? change.effectStep() : effect.step();
        final int effectOrdinal = effect == null ? change.effectOrdinal() : effect.ordinal();
        // Ownership is a property of the complete ability lifecycle, not of
        // whether this particular progress tick can still find its input
        // Action object. Water normally retained that association; moved and
        // delayed earth frequently did not. Mark every layer from an ability
        // the owner's client advertised as supported, while leaving unknown
        // server-only addons fully vanilla-visible.
        // A supported ownership transfer has already sent the exact ability
        // payload before TempBlock.refreshAbilityOwnership republishes these
        // layers. Attribute that refresh to the new owner so their client can
        // conceal the old stationary bridge as its continuation moves. Truly
        // unsupported transfers remain wholly server-visible.
        final UUID predictedOwner = unpredictedOwnershipTransfer
                ? null : predictedTempBlockOwner(change.ownerId(), action, effectAbility);
        final Map<UUID, BlockData> ownerViews = predictedOwnerViews(block, predictedOwner, change.data());
        final String encodedData = TempBlockSync.encode(change.data());
        final PendingTempBlock pending = new PendingTempBlock(worldIdentity,
                new PaperPredictionProtocol.TempBlockOp(wireOperation, worldKey(block.getWorld()),
                block.getX(), block.getY(), block.getZ(), encodedData,
                (change.operation() == TempBlockSync.Operation.REVERT
                        || change.operation() == TempBlockSync.Operation.DISCARD) ? 0L : change.revertAtMillis(),
                action == null ? 0L : action.sequence,
                effectAbility, change.effectState(), effectStep, effectOrdinal,
                change.layerId(), change.revision(), predictedOwner,
                encodedData,
                change.packetExpected()), ownerViews);
        pendingTempBlocks.add(pending);
        if (change.operation() == TempBlockSync.Operation.REVERT
                || change.operation() == TempBlockSync.Operation.DISCARD) {
            tempLayerActions.remove(change.layerId());
            tempLayerEffects.remove(change.layerId());
            serverOwnedTempLayers.remove(change.layerId());
        }
        return pending;
    }

    protected UUID predictedTempBlockOwner(final UUID layerOwner, final Action action,
                                         final String abilityName) {
        final UUID candidate = layerOwner != null ? layerOwner : action == null ? null : action.owner;
        if (candidate == null) return null;
        final Session session = sessions.get(candidate);
        if (session == null || !session.ready || (session.capabilities & CAPABILITY_EXACT) == 0) return null;
        if (action != null && candidate.equals(action.owner)) return candidate;
        return abilityName != null && session.supportedAbilities.contains(abilityName.toLowerCase(Locale.ROOT))
                ? candidate : null;
    }

    protected Map<UUID, BlockData> predictedOwnerViews(final Block block, final UUID closingOwner,
                                                     final BlockData fallbackData) {
        final Map<UUID, BlockData> captured = TempBlock.getOwnerViews(block, closingOwner);
        if (closingOwner == null || captured.containsKey(closingOwner)) return captured;
        final Map<UUID, BlockData> views = new HashMap<>(captured);
        views.put(closingOwner, predictedViewerData(block, closingOwner, fallbackData));
        return Map.copyOf(views);
    }

    protected BlockData predictedViewerData(final Block block, final UUID viewer,
                                          final BlockData fallbackData) {
        final BlockData visible = TempBlock.getVisibleData(block, viewer);
        return visible != null ? visible
                : fallbackData == null ? block.getBlockData().clone() : fallbackData.clone();
    }
}
