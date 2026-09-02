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

public abstract class ExactPredictionApiBlocks extends ExactPredictionApiCore {
    public static BlockState blockState(ClientWorld world, BlockPos pos) {
        final BlockState temp = ExactPredictionRuntime.instance().tempBlockAuthority.simulatedState(
                world, pos.toImmutable());
        return temp == null
                ? ExactPredictionRuntime.instance().directBlockAuthority.simulatedState(world, pos.toImmutable())
                : temp;
    }

    /** Render-thread safe composition over the unmodified authoritative chunk. */
    public static BlockState visualBlockState(final ClientWorld world, final BlockPos pos,
                                              final BlockState authoritativeState) {
        return ExactPredictionRuntime.instance().blockVisualOverlay.composeTerrain(world, pos, authoritativeState);
    }

    /** Avoids coordinate allocation in renderer-replacement hot loops at rest. */
    public static boolean hasBlockVisualOverrides() {
        return !ExactPredictionRuntime.instance().blockVisualOverlay.isEmpty();
    }

    /** Logical render state, including prediction, without the terrain cutout. */
    public static BlockState composedVisualBlockState(final ClientWorld world,
                                                      final BlockPos pos,
                                                      final BlockState authoritativeState) {
        return ExactPredictionRuntime.instance().blockVisualOverlay.compose(world, pos, authoritativeState);
    }

    /** Locally proven non-air predictions submitted every rendered frame. */
    public static List<ClientBlockVisualOverlay.VisualBlock> foregroundBlocks(
            final ClientWorld world) {
        return ExactPredictionRuntime.instance().blockVisualOverlay.foregroundBlocks(world);
    }

    /**
     * Composes prediction for movement owned by this client. Remote and
     * authoritative entities continue to collide with the untouched backing
     * chunk, so a local visual cannot alter anyone else's simulation.
     */
    public static BlockState collisionBlockState(final ClientWorld world,
                                                 final Entity entity,
                                                 final BlockPos pos,
                                                 final BlockState authoritativeState) {
        if (!ExactPredictionRuntime.instance().ready || world == null || entity == null || pos == null) {
            return authoritativeState;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        final boolean predictedFallingBlock = entity instanceof FallingBlockEntity
                && ExactPredictionRuntime.instance().entityReconciliation.isPredictedOwned(entity);
        if (entity != client.player && !predictedFallingBlock) {
            return authoritativeState;
        }
        return ExactPredictionRuntime.instance().blockVisualOverlay.compose(world, pos, authoritativeState);
    }

    public static void setPredictedBlock(ClientWorld world, BlockPos pos, BlockState state) {
        if (!ExactPredictionRuntime.instance().discardingWorldState) {
            if (TempBlockSync.currentWorldMutation() != null) {
                ExactPredictionRuntime.instance().tempBlockAuthority.predict(world, pos.toImmutable(), state);
            } else if (ExactPredictionRuntime.instance().ready) {
                ExactPredictionRuntime.instance().directBlockAuthority.predict(world, pos.toImmutable(), state);
            }
        }
    }

    public static boolean authoritativeBlock(ClientWorld world, BlockPos pos, BlockState state) {
        return ExactPredictionRuntime.instance().tempBlockAuthority.acceptBlock(world, pos.toImmutable(), state);
    }

    public static boolean authoritativeBlockBatch(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        return ExactPredictionRuntime.instance().tempBlockAuthority.acceptBatch(world, positions, states);
    }

    public static void acceptAuthoritativeChunk(ClientWorld world, int chunkX, int chunkZ) {
        ExactPredictionRuntime.instance().tempBlockAuthority.acceptChunk(world, chunkX, chunkZ);
    }

    public static ClientTempBlockAuthority.BatchResult applyTempBlockBatch(
            ClientWorld world, TempBlockBatch batch) {
        return ExactPredictionRuntime.instance().tempBlockAuthority.applyAuthoritativeBatch(world, batch);
    }

    public static void noteDirectBlock(Entity localPlayer, DirectBlockReceipt receipt) {
        ExactPredictionRuntime.instance().noteDirectBlock0(localPlayer, receipt);
    }

    public static List<com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.PredictionDesyncBlock> ownedTempDesyncs(ClientWorld world) {
        return List.of();
    }

    public static void setPredictedVelocity(Entity entity, Vec3d velocity) {
        ExactPredictionRuntime.instance().setVelocity0(entity, velocity);
    }

    public static void noteVelocityOwner(Entity localPlayer, VelocityOwner owner) {
        ExactPredictionRuntime.instance().noteVelocityOwner0(localPlayer, owner);
    }

    public static void noteVelocityOwner(Entity localPlayer, VelocityOwnerV2 owner) {
        ExactPredictionRuntime.instance().noteVelocityOwner0(localPlayer, owner);
    }

    public static void noteAbilityStateOwner(Entity localPlayer, AbilityStateOwner owner) {
        ExactPredictionRuntime.instance().noteAbilityStateOwner0(localPlayer, owner);
    }

    public static void noteGlidingStateOwner(final Entity localPlayer, final GlidingStateOwner owner) {
        ExactPredictionRuntime.instance().noteGlidingStateOwner0(localPlayer, owner);
    }

    public static void applyAirGliderState(final Entity localPlayer, final AirGliderState state) {
        try {
            ExactPredictionRuntime.instance().applyAirGliderState0(localPlayer, state);
        } catch (RuntimeException failure) {
            ProjectKorra.log.log(Level.WARNING,
                    "Rejected an unsafe AirGlider prediction checkpoint", failure);
        }
    }

    public static void reassertPredictedGliding(final int entityId) {
        ExactPredictionRuntime.instance().reassertPredictedGliding0(entityId);
    }

    /** Keeps the rendered pose while excluding vanilla Elytra travel and audio. */
    public static boolean suppressVanillaAirGliderEffects(final ClientPlayerEntity player) {
        final AirGlider glider = ExactPredictionRuntime.instance().localAirGlider(player);
        return glider != null && glider.getState() == AirGlider.State.GLIDING;
    }

    public static void notePredictedAirGliderSound(final Identifier sound,
                                                   final SoundCategory category,
                                                   final double x, final double y, final double z,
                                                   final float volume, final float pitch) {
        if (AbilityExecutionContext.current() instanceof AirGlider) {
            ExactPredictionRuntime.instance().soundAuthority.predict(sound == null ? "" : sound.toString(),
                    category == null ? "" : category.name(), x, y, z,
                    volume, pitch, ExactPredictionRuntime.instance().tick);
        }
    }

    public static boolean suppressAuthoritativeSound(final PlaySoundS2CPacket packet) {
        return packet != null && ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().soundAuthority.accept(
                packet.getSound().value().id().toString(), packet.getCategory().name(),
                packet.getX(), packet.getY(), packet.getZ(), packet.getVolume(),
                packet.getPitch(), ExactPredictionRuntime.instance().tick);
    }

    public static void noteTempFallingBlock(Entity localPlayer, TempFallingBlockReceipt receipt) {
        ExactPredictionRuntime.instance().noteTempFallingBlock0(localPlayer, receipt);
    }

    public static void noteTempFallingBlockPrepare(Entity localPlayer, TempFallingBlockPrepare prepare) {
        ExactPredictionRuntime.instance().noteTempFallingBlockPrepare0(localPlayer, prepare);
    }
}
