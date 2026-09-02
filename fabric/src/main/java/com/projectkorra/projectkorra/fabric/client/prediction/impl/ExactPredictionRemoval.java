package com.projectkorra.projectkorra.fabric.client.prediction.impl;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.BendingManager.TempElementsRunnable;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager.TrackingResult;
import com.projectkorra.projectkorra.ability.util.CollisionInitializer;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.EmbeddedAddonBootstrap;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.chiblocking.util.ChiblockingManager;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.earthbending.RaiseEarthWall;
import com.projectkorra.projectkorra.earthbending.EarthTunnel;
import com.projectkorra.projectkorra.earthbending.EarthSmash.PredictionBlock;
import com.projectkorra.projectkorra.earthbending.EarthSmash.PredictionTransfer;
import com.projectkorra.projectkorra.earthbending.util.EarthbendingManager;
import com.projectkorra.projectkorra.fabric.client.prediction.action.ClientNativeActionCorrelation;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientDirectBlockAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientBlockVisualOverlay;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientTempBlockAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.config.ClientPredictionConfig;
import com.projectkorra.projectkorra.fabric.client.prediction.entity.ClientEntityReconciliation;
import com.projectkorra.projectkorra.fabric.client.prediction.entity.EarthShardFallingCollisionPolicy;
import com.projectkorra.projectkorra.fabric.client.prediction.effect.ClientSoundAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.movement.ClientVelocityAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.state.ClientPlayerStateAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.state.PredictionCooldownAuthority;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityRemoved;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityStateOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityTransfer;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AirGliderState;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ConfigEntry;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.DirectBlockReceipt;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.InputKind;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.GlidingStateOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.NativeAction;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.PlayerCosmetics;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempBlockBatch;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempFallingBlockPrepare;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempFallingBlockReceipt;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.VelocityOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.VelocityOwnerV2;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.util.FirebendingManager;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.listener.CommonInputHandler.SlotResult;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore.MovementResult;
import com.projectkorra.projectkorra.object.CosmeticColor;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.object.WaterCosmetic;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricClientPredictionPlatform;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority.Snapshot;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.GlidingStateSync;
import com.projectkorra.projectkorra.prediction.state.PredictionStateOrdering;
import com.projectkorra.projectkorra.prediction.state.CooldownSync.Listener;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.CooldownDisplayHandler;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.RegenHandler;
import com.projectkorra.projectkorra.util.RevertChecker;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.waterbending.util.WaterbendingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Supplier;
import java.util.logging.Level;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;

public abstract class ExactPredictionRemoval extends ExactPredictionLifecycle {
    protected void removeAuthoritativeAbility0(Entity localPlayer, AbilityRemoved removed) {
        if (this.ready && localPlayer != null && removed != null && localPlayer.getUuid().equals(removed.player())) {
            long localCreationSequence = this.localActionSequence(removed.actionSequence());
            long localAcknowledgement = this.localAcknowledgedSequence(removed.acknowledgedSequence());
            // Composite inputs can replace a short-lived orchestrator with
            // differently typed children that share one direct-effect name
            // and cause (RaiseEarthWall -> RaiseEarth, Shockwave -> Ripple).
            // Only the last same-input/name instance may close that cause.
            final boolean sharedDirectCauseStillActive =
                    removed.remainingActionInstances() > 0;
            final boolean completesRaiseEarth = ExactPredictionRuntime.completesRaiseEarthFrame(
                    removed.abilityType(), removed.remainingActionInstances());
            if (completesRaiseEarth) {
                /*
                 * Complete before installing the generic close tombstone. If
                 * the exact cause has not arrived locally yet, completion must
                 * still recognize that it was previously unknown and sweep
                 * any acknowledged fallback frame.
                 */
                final int completed = this.directBlockAuthority.completeAuthoritativeFrames(
                        removed.ability(), localCreationSequence, localAcknowledgement,
                        removed.remainingNamedInstances() == 0);
                debug("runtime completed authoritative RaiseEarth frame exact="
                        + localCreationSequence + " ack=" + localAcknowledgement
                        + " coordinates=" + completed);
            }
            final int closedDirectCauses = sharedDirectCauseStillActive ? 0
                    : this.directBlockAuthority.closeAuthoritativeCause(
                    removed.ability(), localCreationSequence, localAcknowledgement,
                    removed.remainingNamedInstances() == 0);
            if (closedDirectCauses > 0) {
                debug("runtime closed authoritative direct-block cause ability="
                        + removed.ability() + " localCreation=" + localCreationSequence
                        + " causes=" + closedDirectCauses);
            }
            if (removed.actionSequence() > 0L) {
                Action action = this.actions.get(localCreationSequence);
                if (!ExactPredictionRuntime.removalReceiptMayResolve(removed.externallyCaused(), action != null, action != null && action.nativeConfirmed)) {
                    this.recordAbilityRemoval(removed, "IGNORED missing/unconfirmed correlated creation action local=" + localCreationSequence, List.of());
                    return;
                }
            }

            List<CoreAbility> matching = new ArrayList<>();

            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (ability.getPlayer() != null
                        && ability.getPlayer().getUniqueId().equals(removed.player())
                        && ability.getName().equalsIgnoreCase(removed.ability())
                        && AbilityRemovalSync.isType(ability, removed.abilityType())) {
                    matching.add(ability);
                }
            }

            List<CoreAbility> coveredByEmptyTypeFence = matching.stream()
                    .filter(
                            ability -> ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                                    removed.externallyCaused(),
                                    removed.remainingTypeInstances(),
                                    localAcknowledgement,
                                    this.abilityActions.getOrDefault(ability, this.abilityCreationActions.get(ability))
                            )
                    )
                    .toList();
            if (!coveredByEmptyTypeFence.isEmpty()) {
                int removedCount = 0;

                try {
                    for (CoreAbility ability : coveredByEmptyTypeFence) {
                        this.forceRemoveAbility(ability);
                        if (ability.isRemoved()) {
                            removedCount++;
                        }
                    }

                    this.recordAbilityRemoval(
                            removed,
                            (removedCount == coveredByEmptyTypeFence.size() ? "APPLIED" : "FAILED")
                                    + " authoritative-empty-type-fence removed="
                                    + removedCount
                                    + "/"
                                    + coveredByEmptyTypeFence.size(),
                            matching
                    );
                } catch (RuntimeException failure) {
                    this.recordAbilityRemoval(
                            removed, "FAILED " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + " authoritative-empty-type-fence", matching
                    );
                    ProjectKorra.log.log(Level.WARNING, "Authoritative empty-type cleanup failed for " + removed.abilityType(), failure);
                }
            } else {
                CoreAbility selected = null;
                if (localCreationSequence > 0L) {
                    for (CoreAbility ability : matching) {
                        if (Objects.equals(this.abilityCreationActions.get(ability), localCreationSequence)) {
                            selected = ability;
                            break;
                        }
                    }
                }

                if (selected == null) {
                    this.recordAbilityRemoval(removed, "NO_MATCH", matching);
                } else {
                    Long creationSequence = this.abilityCreationActions.get(selected);
                    Action predictedAction = creationSequence == null
                            ? null
                            : this.actions.get(creationSequence);
                    if (retainsAcceptedPredictedLifecycle(
                            HitRegistrationPolicy.forAbility(selected),
                            removed.externallyCaused(),
                            predictedAction != null && predictedAction.reconciled,
                            predictedAction != null && predictedAction.locallyPredicted)) {
                        debug(
                                "runtime retained accepted client ability lifecycle after server close ability="
                                        + removed.ability()
                                        + " type="
                                        + removed.abilityType()
                                        + " action="
                                        + removed.actionSequence()
                        );
                        this.recordAbilityRemoval(removed, "RETAINED ordinary predicted lifecycle", matching);
                    } else {
                        debug(
                                "runtime applied authoritative ability removal ability="
                                        + removed.ability()
                                        + " type="
                                        + removed.abilityType()
                                        + " paperAction="
                                        + removed.actionSequence()
                                        + " localCreation="
                                        + localCreationSequence
                                        + " external="
                                        + removed.externallyCaused()
                        );

                        try {
                            this.forceRemoveAbility(selected);
                            this.recordAbilityRemoval(
                                    removed, (selected.isRemoved() ? "APPLIED" : "FAILED instance remained active") + " selectedCreation=" + creationSequence, matching
                            );
                        } catch (RuntimeException failure) {
                            this.recordAbilityRemoval(
                                    removed,
                                    "FAILED " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + " selectedCreation=" + creationSequence,
                                    matching
                            );
                            ProjectKorra.log.log(Level.WARNING, "Authoritative ability cleanup failed for " + removed.abilityType(), failure);
                        }
                    }
                }
            }
            if (removed.predictionRejected()) {
                this.bendingPlayer.removeCooldown(removed.ability());
                this.cooldownAuthority.onLocalRemoved(removed.ability());
                debug("runtime cleared cooldown from authoritative activation rejection ability="
                        + removed.ability());
            }
        }
    }

    public static boolean retainsAcceptedPredictedLifecycle(
            final HitRegistrationPolicy policy,
            final boolean externallyCaused,
            final boolean reconciled,
            final boolean locallyPredicted) {
        return !externallyCaused && reconciled && locallyPredicted
                && policy != HitRegistrationPolicy.SERVER_CURRENT;
    }
}
