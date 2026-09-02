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

public abstract class ExactPredictionInput extends ExactPredictionStartup {
    protected boolean input0(long sequence, InputKind kind, int selectedSlot,
                           com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose,
                           boolean cooldownActiveAtInput) {
        if (this.ready && this.bendingPlayer != null) {
            Set<CoreAbility> before = Collections.newSetFromMap(new IdentityHashMap<>());
            before.addAll(CoreAbility.getAbilitiesByInstances());
            Player player = this.bendingPlayer.getPlayer();
            AbilityInformation comboBefore = latestComboInput(player);
            String boundName = this.inputAbilityName0(selectedSlot, (String) this.bendingPlayer.getAbilities().get(selectedSlot + 1), kind);
            Vec3d origin = pose.eyePos();
            Action action = new Action(
                    sequence, this.tick, origin, pose.yaw(), pose.pitch(), pose.eyeHeight(), boundName, kind, selectedSlot
            );
            action.executed = true;
            action.cooldownActiveAtInput = cooldownActiveAtInput;
            this.actions.put(sequence, action);
            boolean failed = false;
            TrackingResult trackingResult = new TrackingResult(false, List.of());
            debug("runtime input start sequence=" + sequence + " kind=" + kind + " bound=" + boundName + " activeBefore=" + before.size() + " tick=" + this.tick);
            INPUT_ACTION.set(sequence);
            INPUT_EVENT_POSE.set(sequence);
            AbilityActivationManager.beginTracking();

            try {
                com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSelectedSlot(
                        selectedSlot,
                        () -> PredictionDeterminism.run(
                                sequence,
                                action.deterministicSeed,
                                () -> {
                                    final Supplier<Void> nativeInput = () -> {
                                        switch (kind) {
                                            case LEFT_CLICK:
                                                CommonInputHandler.handleSwing(player, Set.of(), new HashSet());
                                                com.projectkorra.projectkorra.platform.mc.entity.Entity target = GeneralMethods.getTargetedEntity(player, 3.0);
                                                if (target instanceof LivingEntity living && !target.equals(player)) {
                                                    CommonInputHandler.handleEntityLeftClick(player, living);
                                                }
                                                break;
                                            case SNEAK_START:
                                                com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSneaking(
                                                        false, () -> CommonInputHandler.handleSneak(player, false)
                                                );
                                                break;
                                            case RIGHT_CLICK:
                                                CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
                                                break;
                                            case RIGHT_CLICK_BLOCK:
                                                CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
                                                break;
                                            case RIGHT_CLICK_ENTITY:
                                                CommonInputHandler.handleRightClickEntity(player);
                                                break;
                                            case SNEAK_STOP:
                                                com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSneaking(
                                                        true, () -> CommonInputHandler.handleSneak(player, true)
                                                );
                                                break;
                                            case SWAP_HANDS:
                                                CommonInputHandler.handleSwapHands(
                                                        player,
                                                        player.getInventory().getItemInMainHand().getType() == Material.AIR,
                                                        player.getInventory().getItemInOffHand() == null || player.getInventory().getItemInOffHand().getType() == Material.AIR
                                                );
                                        }
                                        return null;
                                    };
                                    if (cooldownActiveAtInput) {
                                        CooldownSync.runInputVeto(player.getUniqueId(),
                                                ExactPredictionRuntime.inputCooldownNames(boundName, kind), nativeInput);
                                    } else {
                                        nativeInput.get();
                                    }
                                }
                        )
                );
            } catch (Throwable failure) {
                ProjectKorra.log.warning("Predicted input " + sequence + " failed: " + failure.getMessage());
                debug("runtime input failed sequence=" + sequence + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                failed = true;
            } finally {
                trackingResult = AbilityActivationManager.finishTrackingResult();
                INPUT_EVENT_POSE.remove();
                INPUT_ACTION.remove();
                // Successful synchronous Earth writes are durable visual
                // transactions waiting for Paper's pre-write receipts. Only
                // their same-call read-through cache is transient; a full
                // rollback here used to erase EarthBlast/RaiseEarth prediction
                // before the authoritative packets could be correlated.
                this.directBlockAuthority.finishInput(sequence);
            }

            action.inputHandled = trackingResult.handled();
            action.comboInput = latestComboInput(player);
            action.comboRecorded = action.comboInput != comboBefore;
            if (!action.comboRecorded) {
                action.comboInput = null;
            }

            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (!before.contains(ability)) {
                    this.associateAbility(action, ability);
                    this.abilityCreationActions.putIfAbsent(ability, sequence);
                    action.abilities.add(ability);
                    debug("runtime created ability sequence=" + sequence + " ability=" + ability.getName() + " instance=" + System.identityHashCode(ability));
                }
            }

            debug(
                    "runtime input finish sequence="
                            + sequence
                            + " created="
                            + action.abilities.size()
                            + " activeAfter="
                            + CoreAbility.getAbilitiesByInstances().size()
                            + " failed="
                            + failed
            );
            if (failed) {
                this.abortFailedLocalInput(action);
                return false;
            }

            boolean hasMatchingExistingAbility = this.affectedExistingAbility(before, boundName);
            List<CoreAbility> explicitExisting = trackingResult.affectedAbilities()
                    .stream()
                    .filter(before::contains)
                    .filter(
                            abilityx -> abilityx != null
                                    && !abilityx.isRemoved()
                                    && abilityx.getPlayer() != null
                                    && abilityx.getPlayer().getUniqueId().equals(player.getUniqueId())
                    )
                    .toList();
            boolean createdMatchingAbility = action.abilities.stream().anyMatch(abilityx -> matchesInputAbility(abilityx, boundName));
            boolean affectedExisting = !explicitExisting.isEmpty() || trackingResult.handled() && hasMatchingExistingAbility && !createdMatchingAbility;
            boolean producedTempBlock = this.tempBlockAuthority.hasPredictionForAction(sequence);
            boolean producedDirectBlock = !action.directBlockOrdinals.isEmpty();
            boolean locallyPredicted = !action.abilities.isEmpty()
                    || !action.spawned.isEmpty()
                    || producedTempBlock
                    || producedDirectBlock
                    || affectedExisting
                    || !action.abilityStateOrdinals.isEmpty();
            action.locallyPredicted = locallyPredicted;
            if (affectedExisting) {
                if (!explicitExisting.isEmpty()) {
                    for (CoreAbility ability : explicitExisting) {
                        this.associateAbility(action, ability);
                    }
                } else {
                    for (CoreAbility ability : before) {
                        if (ability != null
                                && !ability.isRemoved()
                                && matchesInputAbility(ability, boundName)
                                && ability.getPlayer() != null
                                && ability.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                            this.associateAbility(action, ability);
                        }
                    }
                }
            }

            debug("runtime input localPrediction sequence=" + sequence + " immediateEffect=" + locallyPredicted + " affectedExisting=" + affectedExisting);
            return locallyPredicted;
        } else {
            debug("runtime input skipped sequence=" + sequence + " kind=" + kind + " ready=" + this.ready + " hasBendingPlayer=" + (this.bendingPlayer != null));
            return false;
        }
    }

    protected void associateAbility(Action action, CoreAbility ability) {
        if (action != null && ability != null) {
            Long previousSequence = this.abilityActions.get(ability);
            if (!action.previousAbilityActions.containsKey(ability)) {
                action.previousAbilityActions.put(ability, previousSequence);
            }

            if (previousSequence != null && previousSequence != action.sequence) {
                Action previous = this.actions.get(previousSequence);
                if (previous != null) {
                    previous.abilities.remove(ability);
                }
            }

            this.abilityActions.put(ability, action.sequence);
            this.abilityTransitionActions.computeIfAbsent(ability,
                    ignored -> new HashSet<>()).add(action.sequence);
            action.abilities.add(ability);
        }
    }

    protected boolean affectedExistingAbility(Set<CoreAbility> before, String boundName) {
        if (before != null && !before.isEmpty() && boundName != null && !boundName.isBlank() && this.bendingPlayer != null) {
            Player player = this.bendingPlayer.getPlayer();
            if (player == null) {
                return false;
            }

            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (before.contains(ability)
                        && !ability.isRemoved()
                        && matchesInputAbility(ability, boundName)
                        && ability.getPlayer() != null
                        && ability.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    protected static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null
                && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName()) || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    protected static AbilityInformation latestComboInput(Player player) {
        if (player == null) {
            return null;
        }

        List<AbilityInformation> recent = ComboManager.getRecentlyUsedAbilities(player, 1);
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    protected void predictMovement0(
            MinecraftClient client,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose fromPose,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose toPose
    ) {
        if (this.ready && this.bendingPlayer != null && client != null && client.player != null && client.world != null && fromPose != null && toPose != null) {
            Location from = FabricPredictionMC.location(client.world, new Vec3d(fromPose.x(), fromPose.y(), fromPose.z()), fromPose.yaw(), fromPose.pitch());
            Location to = FabricPredictionMC.location(client.world, new Vec3d(toPose.x(), toPose.y(), toPose.z()), toPose.yaw(), toPose.pitch());
            MovementResult result = CommonPlayerListenerCore.handlePredictedMove(this.bendingPlayer.getPlayer(), from, to, false, false, 0.0);
            if (result.cancelEvent()) {
                debug("runtime movement prediction requested cancel from common listener");
            }
        }
    }
}
