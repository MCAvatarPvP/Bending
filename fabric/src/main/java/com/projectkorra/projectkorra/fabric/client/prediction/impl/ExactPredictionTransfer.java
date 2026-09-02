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

public abstract class ExactPredictionTransfer extends ExactPredictionRemoval {
    protected void transferAuthoritativeAbility0(Entity localPlayer, AbilityTransfer transfer) {
        if (this.ready
                && localPlayer != null
                && transfer != null
                && localPlayer.getUuid().equals(transfer.player())
                && this.bendingPlayer != null
                && EarthSmash.class.getName().equals(transfer.abilityType())) {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world != null && matchesWorld(world.getRegistryKey().getValue().toString(), transfer.world())) {
                long localSequence = this.localActionSequence(transfer.actionSequence());
                Action action = this.actions.get(localSequence);
                if (action == null) {
                    debug(
                            "runtime ignored authoritative ability state without correlated action paperAction="
                                    + transfer.actionSequence() + " type=" + transfer.abilityType()
                    );
                } else {
                    PredictionTransfer state = new PredictionTransfer(
                            transfer.world(),
                            transfer.x(),
                            transfer.y(),
                            transfer.z(),
                            transfer.hasDestination(),
                            transfer.destinationX(),
                            transfer.destinationY(),
                            transfer.destinationZ(),
                            transfer.state(),
                            transfer.grabbedDistance(),
                            transfer.animationCounter(),
                            transfer.progressCounter(),
                            transfer.predictionFrame(),
                            transfer.elapsedMillis(),
                            transfer.flightElapsedMillis(),
                            transfer.delayElapsedMillis(),
                            transfer.blocks().stream().map(block -> new PredictionBlock(block.x(), block.y(), block.z(), block.material())).toList()
                    );
                    EarthSmash selected = null;

                    for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                        if (candidate instanceof EarthSmash smash
                                && !smash.isRemoved()
                                && smash.getPlayer() != null
                                && smash.getPlayer().getUniqueId().equals(transfer.player())
                                && this.ownsEarthSmashTransition(
                                candidate, action, localSequence)) {
                            selected = smash;
                            break;
                        }
                    }

                    boolean recoveredFromCheckpoint = false;
                    boolean restoredFromAuthority = false;
                    boolean checkpointSuperseded = false;
                    if (selected == null) {
                        selected = EarthSmash.restorePredictionTransfer(this.bendingPlayer.getPlayer(), state);
                        if (selected == null || selected.isRemoved() || !selected.isStarted()) {
                            return;
                        }
                        selected.acceptPredictionCheckpoint(state, localSequence);
                        restoredFromAuthority = true;
                        recoveredFromCheckpoint = !transfer.ownershipTransfer();
                    } else {
                        final Long latestTransition = this.abilityActions.get(selected);
                        checkpointSuperseded = !transfer.ownershipTransfer()
                                && (latestTransition != null && latestTransition > localSequence
                                || selected.isPredictionCheckpointStale(state, localSequence));
                        if (!checkpointSuperseded) {
                            if (transfer.ownershipTransfer()) {
                                selected.applyPredictionTransfer(state);
                                selected.acceptPredictionCheckpoint(state, localSequence);
                            } else if (selected.matchesPredictionCheckpoint(state)) {
                                // Paper's checkpoint is a transition anchor, not
                                // a request to throw away motion already rendered
                                // during the network leg.
                                selected.reconcilePredictionCheckpoint(state, localSequence);
                                recoveredFromCheckpoint = true;
                            } else {
                                selected.applyPredictionTransfer(state);
                                selected.acceptPredictionCheckpoint(state, localSequence);
                                recoveredFromCheckpoint = true;
                            }
                        }
                    }

                    if (!checkpointSuperseded) {
                        this.associateAbility(action, selected);
                    } else {
                        // A later local grab/throw already owns this instance.
                        // Re-associating a delayed checkpoint would move it
                        // backwards to the older action and can even make the
                        // later checkpoint restore a duplicate EarthSmash.
                        debug("runtime retained newer EarthSmash transition behind checkpoint action="
                                + localSequence + " latest=" + this.abilityActions.get(selected)
                                + " state=" + selected.getState());
                    }
                    // A checkpoint describes the current transition owner; it
                    // does not recreate an already-live ability. Replacing the
                    // original creation action here makes the subsequent
                    // reconcile compare that existing EarthSmash against
                    // Paper's correctly empty created-ability list and remove
                    // it immediately. Only an authority-restored instance has
                    // no creation identity yet.
                    this.abilityCreationActions.putIfAbsent(selected, localSequence);
                    if (transfer.ownershipTransfer()) {
                        // Provisional ownership previews never enter the exact
                        // TempBlock ledger, so this payload is the first shared
                        // ordinal boundary for both runtimes.
                        action.tempBlockOrdinal = Math.max(0, transfer.tempBlockOrdinal());
                        this.authoritativelyEstablishedAbilities.add(selected);
                    } else {
                        action.tempBlockOrdinal = Math.max(action.tempBlockOrdinal,
                                transfer.tempBlockOrdinal());
                        if (restoredFromAuthority) {
                            // A sparse checkpoint is direct proof that Paper has
                            // this live instance. Its transition action quite
                            // correctly reports created=[], so creation-list
                            // reconciliation must not reject the restoration.
                            this.authoritativelyEstablishedAbilities.add(selected);
                        }
                    }
                    action.locallyPredicted = true;
                    if (recoveredFromCheckpoint) action.recoveredFromAuthority = true;
                    debug(
                            "runtime applied authoritative ability "
                                    + (transfer.ownershipTransfer() ? "ownership transfer" : "checkpoint")
                                    + " action="
                                    + localSequence
                                    + " ability="
                                    + selected.getName()
                                    + " state="
                                    + transfer.state()
                                    + " location=("
                                    + transfer.x()
                                    + ", "
                                    + transfer.y()
                                    + ", "
                                    + transfer.z()
                                    + ") blocks="
                                    + state.blocks().size()
                                    + " localShape="
                                    + selected.getCurrentBlocks().size()
                                    + " tracksLayers="
                                    + selected.tracksPredictedTempBlocks()
                    );
                }
            }
        }
    }

    /**
     * Action reconciliation clears its temporary rollback map, but delayed
     * EarthSmash checkpoints can arrive after several more sneak/aim
     * transitions. Keep a live-instance transition history so an old
     * checkpoint updates (or is superseded on) that same smash instead of
     * restoring a second client-only instance at Paper's older position.
     */
    protected boolean ownsEarthSmashTransition(final CoreAbility candidate,
                                             final Action action,
                                             final long localSequence) {
        if (candidate == null || action == null || localSequence <= 0L) return false;
        return action.abilities.contains(candidate)
                || action.previousAbilityActions.containsKey(candidate)
                || Objects.equals(this.abilityCreationActions.get(candidate), localSequence)
                || this.abilityTransitionActions.getOrDefault(candidate, Set.of())
                .contains(localSequence);
    }

    protected void recordAbilityRemoval(AbilityRemoved removed, String resolution, List<CoreAbility> matching) {
        if (removed != null) {
            List<String> candidates = new ArrayList<>();
            if (matching != null) {
                for (CoreAbility ability : matching) {
                    candidates.add(
                            ability.getClass().getSimpleName() + "@" + this.abilityCreationActions.get(ability) + (ability.isRemoved() ? "(removed)" : "(active)")
                    );
                }
            }

            this.abilityRemovalHistory
                    .add(
                            "ability="
                                    + removed.ability()
                                    + " type="
                                    + removed.abilityType()
                                    + " action="
                                    + removed.actionSequence()
                                    + " external="
                                    + removed.externallyCaused()
                                    + " ack="
                                    + removed.acknowledgedSequence()
                                    + " remainingType="
                                    + removed.remainingTypeInstances()
                                    + " result="
                                    + resolution
                                    + " candidates="
                                    + candidates
                    );

            while (this.abilityRemovalHistory.size() > 12) {
                this.abilityRemovalHistory.remove(0);
            }
        }
    }
}
