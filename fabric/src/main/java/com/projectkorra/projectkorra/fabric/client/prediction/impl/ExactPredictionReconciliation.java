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

public abstract class ExactPredictionReconciliation extends ExactPredictionTick {
    protected boolean noteNativeAction0(NativeAction receipt) {
        if (this.ready && receipt != null && receipt.predictable()) {
            final long localSequence = this.nativeActions.correlate(receipt,
                    this.actions.values().stream().map(Action::correlationCandidate).toList());
            final Action action = this.actions.get(localSequence);
            if (action == null) {
                debug(
                        "runtime rejected non-identical native action sequence="
                                + receipt.actionSequence()
                                + " kind="
                                + receipt.kind()
                                + " slot="
                                + receipt.selectedSlot()
                                + " ability="
                                + receipt.ability()
                );
                return false;
            } else {
                action.nativeConfirmed = true;
                debug(
                        "runtime confirmed native action sequence="
                                + receipt.actionSequence()
                                + " localSequence="
                                + action.sequence
                                + " taggedLocalSequence="
                                + receipt.clientActionSequence()
                                + " originDeltaSquared="
                                + new Vec3d(receipt.originX(), receipt.originY(), receipt.originZ()).squaredDistanceTo(action.origin)
                );
                return true;
            }
        } else {
            return false;
        }
    }

    protected long localActionSequence(long paperSequence) {
        return this.nativeActions.localSequence(paperSequence);
    }

    protected long paperActionSequence(long localSequence) {
        return this.nativeActions.paperSequence(localSequence);
    }

    protected long localAcknowledgedSequence(long paperSequence) {
        return this.nativeActions.acknowledgedLocalSequence(paperSequence);
    }

    public static long mappedActionSequence(Map<Long, Long> aliases, long paperSequence) {
        return ClientNativeActionCorrelation.mappedActionSequence(aliases, paperSequence);
    }

    public static long mappedAcknowledgedSequence(Map<Long, Long> aliases, long paperSequence) {
        return ClientNativeActionCorrelation.mappedAcknowledgedSequence(aliases, paperSequence);
    }

    protected void reconcile0(
            long sequence, Vec3d authoritativeOrigin, String ability, long cooldownUntil, boolean inputHandled, boolean comboRecorded, List<String> createdAbilities
    ) {
        long localSequence = this.localActionSequence(sequence);
        Action action = this.actions.get(localSequence);
        if (action != null && action.nativeConfirmed && (ability == null || ability.isBlank() || action.inputAbility.equalsIgnoreCase(ability))) {
            List<String> authoritativeCreated = createdAbilities == null
                    ? List.of()
                    : createdAbilities.stream().filter(name -> name != null && !name.isBlank()).limit(64L).toList();
            if (!action.executed && (inputHandled || comboRecorded || !authoritativeCreated.isEmpty())) {
                action = this.replayNativeOnlyAction(action);
                if (action == null) {
                    debug("runtime failed to recover accepted native input paperSequence=" + sequence + " localSequence=" + localSequence + " ability=" + ability);
                    return;
                }
            }

            if (action.comboRecorded && !comboRecorded && action.comboInput != null && this.bendingPlayer != null) {
                ComboManager.removeRecentAbility(this.bendingPlayer.getPlayer(), action.comboInput);
                action.comboRecorded = false;
                action.comboInput = null;
            }

            this.reconcileCreatedAbilities(action, authoritativeCreated);
            action.reconciled = true;
            if (cooldownUntil > System.currentTimeMillis() && ability != null && !ability.isBlank()) {
                ExactPredictionRuntime.enforceLocalCooldown(ability, cooldownUntil);
            }

            action.previousAbilityActions.clear();
            action.blockConfirmationTicks = Math.max(4, Math.min(40, (int) Math.max(0L, this.tick - action.createdTick) + 2));
            debug(
                    "runtime reconcile confirmed paperSequence="
                            + sequence
                            + " localSequence="
                            + localSequence
                            + " ability="
                            + ability
                            + " recovered="
                            + action.recoveredFromAuthority
                            + " handled="
                            + inputHandled
                            + " comboRecorded="
                            + comboRecorded
                            + " created="
                            + authoritativeCreated
                            + " originDeltaSquared="
                            + authoritativeOrigin.squaredDistanceTo(action.origin)
            );
        } else {
            debug("runtime reconcile missing action paperSequence=" + sequence + " localSequence=" + localSequence + " ability=" + ability);
        }
    }

    protected Action replayNativeOnlyAction(
            Action recorded
    ) {
        if (recorded != null && !recorded.executed) {
            long sequence = recorded.sequence;
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose = new com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose(
                    recorded.origin.x, recorded.origin.y - recorded.eyeHeight, recorded.origin.z, recorded.yaw, recorded.pitch, recorded.eyeHeight
            );
            this.actions.remove(sequence, recorded);
            this.input0(sequence, recorded.kind, recorded.selectedSlot, pose,
                    recorded.cooldownActiveAtInput);
            Action replayed = this.actions.get(sequence);
            if (replayed == null) {
                this.actions.put(sequence, recorded);
                return null;
            } else {
                replayed.nativeConfirmed = true;
                replayed.recoveredFromAuthority = true;
                debug("runtime replayed Paper-accepted native input sequence=" + sequence + " kind=" + replayed.kind + " ability=" + replayed.inputAbility);
                return replayed;
            }
        } else {
            return recorded;
        }
    }

    protected void reconcileCreatedAbilities(Action action, List<String> authoritativeNames) {
        if (action != null && this.bendingPlayer != null) {
            Map<String, Integer> remaining = abilityNameCounts(authoritativeNames);

            for (CoreAbility local : this.locallyCreatedAbilities(action.sequence)) {
                if (!this.authoritativelyEstablishedAbilities.contains(local)) {
                    String key = normalizedAbilityName(local.getName());
                    int count = remaining.getOrDefault(key, 0);
                    if (count > 0) {
                        remaining.put(key, count - 1);
                    } else {
                        debug("runtime retired client-only input outcome action=" + action.sequence + " ability=" + local.getName());

                        try {
                            this.forceRemoveAbility(local);
                        } catch (Throwable var9) {
                        }

                        this.abilityActions.remove(local);
                        this.abilityCreationActions.remove(local);
                        action.abilities.remove(local);
                    }
                }
            }

            Map<String, Integer> localCounts = abilityNameCounts(this.locallyCreatedAbilities(action.sequence).stream().<String>map(Ability::getName).toList());

            for (String authoritativeName : authoritativeNames) {
                String key = normalizedAbilityName(authoritativeName);
                int count = localCounts.getOrDefault(key, 0);
                if (count > 0) {
                    localCounts.put(key, count - 1);
                } else if (ComboManager.getComboAbility(authoritativeName) != null) {
                    this.recoverMissingCombo(action, authoritativeName);
                }
            }
        }
    }

    protected List<CoreAbility> locallyCreatedAbilities(long sequence) {
        return this.abilityCreationActions
                .entrySet()
                .stream()
                .filter(entry -> Objects.equals(entry.getValue(), sequence))
                .map(Entry::getKey)
                .filter(ability -> ability != null && !ability.isRemoved())
                .toList();
    }

    protected void recoverMissingCombo(Action action, String abilityName) {
        Long previousAction = INPUT_ACTION.get();
        Long previousPose = INPUT_EVENT_POSE.get();
        INPUT_ACTION.set(action.sequence);
        INPUT_EVENT_POSE.set(action.sequence);
        CoreAbility[] recovered = new CoreAbility[]{null};

        try {
            com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSelectedSlot(
                    action.selectedSlot,
                    () -> PredictionDeterminism.run(
                            action.sequence, action.deterministicSeed, () -> recovered[0] = ComboManager.createComboAbility(this.bendingPlayer.getPlayer(), abilityName)
                    )
            );
        } finally {
            if (previousAction == null) {
                INPUT_ACTION.remove();
            } else {
                INPUT_ACTION.set(previousAction);
            }

            if (previousPose == null) {
                INPUT_EVENT_POSE.remove();
            } else {
                INPUT_EVENT_POSE.set(previousPose);
            }
        }

        CoreAbility combo = recovered[0];
        if (combo != null && !combo.isRemoved()) {
            this.associateAbility(action, combo);
            this.abilityCreationActions.put(combo, action.sequence);
            action.recoveredFromAuthority = true;
            debug("runtime recovered server-created combo action=" + action.sequence + " ability=" + combo.getName());
        }
    }

    protected static Map<String, Integer> abilityNameCounts(List<String> names) {
        Map<String, Integer> counts = new HashMap<>();
        if (names == null) {
            return counts;
        }

        for (String name : names) {
            if (name != null && !name.isBlank()) {
                counts.merge(normalizedAbilityName(name), 1, Integer::sum);
            }
        }

        return counts;
    }

    protected static String normalizedAbilityName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    protected void abortFailedLocalInput(Action action) {
        debug("runtime abort failed local input sequence=" + action.sequence + " abilities=" + action.abilities.size() + " spawned=" + action.spawned.size());

        for (CoreAbility ability : List.copyOf(action.abilities)) {
            try {
                this.forceRemoveAbility(ability);
            } catch (Throwable var5) {
            }
        }

        action.abilities.clear();

        for (Entry<CoreAbility, Long> entry : action.previousAbilityActions.entrySet()) {
            CoreAbility ability = entry.getKey();
            if (Objects.equals(this.abilityActions.get(ability), action.sequence)) {
                if (entry.getValue() == null) {
                    this.abilityActions.remove(ability);
                } else {
                    this.abilityActions.put(ability, entry.getValue());
                }
            }
        }

        action.previousAbilityActions.clear();

        for (Entity entity : action.spawned) {
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }

        this.entityReconciliation.rollbackAction(action.sequence, action.spawned);
        action.spawned.clear();
        this.directBlockAuthority.rollbackAction(action.sequence);
        this.velocityAuthority.rollbackAction(action.sequence);
    }

    protected int blockConfirmationTicks(long actionSequence) {
        Action action = this.actions.get(actionSequence);
        return action != null && action.reconciled ? action.blockConfirmationTicks : 40;
    }
}
