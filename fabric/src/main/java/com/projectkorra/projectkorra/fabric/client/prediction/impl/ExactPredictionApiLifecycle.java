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

public abstract class ExactPredictionApiLifecycle extends ExactPredictionApiBlocks {
    public static void removeAuthoritativeAbility(Entity localPlayer, AbilityRemoved removed) {
        ExactPredictionRuntime.instance().removeAuthoritativeAbility0(localPlayer, removed);
    }

    public static void transferAuthoritativeAbility(Entity localPlayer, AbilityTransfer transfer) {
        ExactPredictionRuntime.instance().transferAuthoritativeAbility0(localPlayer, transfer);
    }

    public static boolean removalReceiptMayResolve(boolean externallyCaused, boolean actionRetained, boolean nativeActionConfirmed) {
        return externallyCaused || actionRetained && nativeActionConfirmed;
    }

    public static boolean authoritativeEmptyTypeFenceCoversCandidate(
            boolean externallyCaused, int remainingTypeInstances, long localAcknowledgedSequence, Long candidateLatestSequence
    ) {
        return externallyCaused
                && remainingTypeInstances == 0
                && localAcknowledgedSequence > 0L
                && candidateLatestSequence != null
                && candidateLatestSequence <= localAcknowledgedSequence;
    }

    public static boolean completesRaiseEarthFrame(final String abilityType,
                                            final int remainingActionInstances) {
        return (RaiseEarth.class.getName().equals(abilityType)
                || RaiseEarthWall.class.getName().equals(abilityType))
                && remainingActionInstances == 0;
    }

    public static boolean authoritativeVelocity(int entityId, Vec3d velocity) {
        return ExactPredictionRuntime.instance().authoritativeVelocity0(entityId, velocity);
    }

    public static void notePredictedAbilityState(
            boolean invulnerable, boolean flying, boolean allowFlying, boolean creativeMode, float flySpeed, float walkSpeed
    ) {
        ExactPredictionRuntime.instance().notePredictedAbilityState0(invulnerable, flying, allowFlying, creativeMode, flySpeed, walkSpeed);
    }

    public static void notePredictedExperience(float barProgress, int experience, int level) {
        ExactPredictionRuntime.instance().notePredictedExperience0(barProgress, experience, level);
    }

    public static boolean suppressAuthoritativeAbilityState(PlayerAbilitiesS2CPacket packet) {
        return ExactPredictionRuntime.instance().suppressAuthoritativeAbilityState0(packet);
    }

    public static boolean suppressAuthoritativeExperience(ExperienceBarUpdateS2CPacket packet) {
        return ExactPredictionRuntime.instance().suppressAuthoritativeExperience0(packet);
    }

    public static boolean notePredictedSelectedSlot(int slot) {
        return ExactPredictionRuntime.instance().notePredictedSelectedSlot0(slot);
    }

    public static boolean suppressAuthoritativeEntityData(int entityId) {
        return ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().entityReconciliation.suppressAuthoritativeData(entityId);
    }

    public static boolean suppressAuthoritativeBreakAnimation(ClientWorld world, BlockPos pos) {
        return ExactPredictionRuntime.instance().ready && (ExactPredictionRuntime.instance().directBlockAuthority.suppressBreakAnimation(world, pos)
                || ExactPredictionRuntime.instance().tempBlockAuthority.suppressBreakAnimation(world, pos));
    }

    public static boolean suppressLocalBlockBreaking(ClientWorld world, BlockPos pos) {
        return ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().tempBlockAuthority.suppressLocalBreaking(world, pos);
    }

    public static boolean isPredictedOwned(Entity entity) {
        return ExactPredictionRuntime.instance().entityReconciliation.isPredictedOwned(entity);
    }

    /**
     * Gives only locally predicted EarthShard falling blocks a brief launch
     * window in which delayed source/ready blocks cannot stop their movement.
     */
    public static void moveTempFallingBlock(final FallingBlockEntity entity,
                                            final MovementType movementType,
                                            final Vec3d movement) {
        if (entity == null) return;
        final ClientEntityReconciliation.PredictedTempFallingOwner owner =
                ExactPredictionRuntime.instance().entityReconciliation.predictedTempFallingOwner(entity);
        final boolean earthShardGrace = ExactPredictionRuntime.instance().ready && owner != null
                && EarthShardFallingCollisionPolicy.ignoresBlocks(
                owner.ability(), owner.spawnedAtNanos(), System.nanoTime());
        if (!earthShardGrace) {
            entity.move(movementType, movement);
            return;
        }
        final boolean previousNoClip = entity.noClip;
        entity.noClip = true;
        try {
            entity.move(movementType, movement);
        } finally {
            entity.noClip = previousNoClip;
        }
    }

    public static void trackSpawn(Entity entity) {
        ExactPredictionRuntime.instance().trackSpawn0(entity);
    }

    public static boolean reconcileSpawn(EntitySpawnS2CPacket packet) {
        return ExactPredictionRuntime.instance().reconcileSpawn0(packet);
    }

    public static Entity aliasedEntity(int serverEntityId) {
        return ExactPredictionRuntime.instance().entityReconciliation.aliasedEntity(serverEntityId);
    }

    public static boolean hasEntityAlias(int serverEntityId) {
        return ExactPredictionRuntime.instance().entityReconciliation.hasAlias(serverEntityId);
    }

    public static boolean tracksVelocityEntity(int entityId) {
        return ExactPredictionRuntime.instance().tracksVelocityEntity0(entityId);
    }

    public static boolean removeHiddenEntity(int serverEntityId) {
        return ExactPredictionRuntime.instance().entityReconciliation.removeHidden(serverEntityId);
    }

    public static boolean removeAliasedEntity(int serverEntityId) {
        return ExactPredictionRuntime.instance().entityReconciliation.removeAlias(serverEntityId);
    }

    public static boolean toggleServerTempBlockDebug() {
        return ExactPredictionRuntime.instance().tempBlockAuthority.toggleDebugView();
    }

    public static boolean showsServerTempBlocks() {
        return ExactPredictionRuntime.instance().tempBlockAuthority.showsServerLayers();
    }

    public static long captureAction() {
        return ExactPredictionRuntime.instance().currentAction();
    }

    public static void runWithAction(long action, Runnable task) {
        if (action <= 0L) {
            task.run();
        } else {
            Long previous = INPUT_ACTION.get();
            INPUT_ACTION.set(action);
            Action correlated = ExactPredictionRuntime.instance().actions.get(action);
            long deterministicSeed = correlated == null ? action : correlated.deterministicSeed;

            try {
                PredictionDeterminism.run(action, deterministicSeed, task);
            } finally {
                if (previous == null) {
                    INPUT_ACTION.remove();
                } else {
                    INPUT_ACTION.set(previous);
                }
            }
        }
    }
}
