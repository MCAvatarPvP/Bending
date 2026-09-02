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

public abstract class ExactPredictionEntities extends ExactPredictionPlayerState {
    protected void trackSpawn0(Entity entity) {
        long action = this.currentAction();
        Action owner = this.actions.get(action);
        if (owner != null && entity != null) {
            owner.spawned.add(entity);
            this.entityReconciliation.trackSpawn(action, entity);
        }
    }

    public int beforeSpawn(CoreAbility ability, Location location, BlockData blockData) {
        if ((this.ready || this.initializing) && ability != null) {
            long sequence = this.currentAction();
            if (sequence <= 0L) {
                sequence = this.abilityActions.getOrDefault(ability, 0L);
            }

            Action action = this.actions.get(sequence);
            return action == null ? 0 : ++action.tempFallingBlockOrdinal;
        } else {
            return 0;
        }
    }

    public void onSpawn(CoreAbility ability, FallingBlock fallingBlock, int spawnOrdinal) {
        if ((this.ready || this.initializing) && ability != null && fallingBlock != null && spawnOrdinal > 0 && fallingBlock.handle() instanceof Entity entity) {
            long sequence = this.currentAction();
            if (sequence <= 0L) {
                sequence = this.abilityActions.getOrDefault(ability, 0L);
            }

            Action action = this.actions.get(sequence);
            if (action != null) {
                this.entityReconciliation.trackTempFallingBlock(
                        sequence, spawnOrdinal, entity, ability.getName());
            }
        }
    }

    protected void noteTempFallingBlockPrepare0(Entity localPlayer, TempFallingBlockPrepare prepare) {
        if (this.ready && localPlayer != null && prepare != null && prepare.actionSequence() > 0L && localPlayer.getUuid().equals(prepare.abilityOwner())) {
            long localSequence = this.localActionSequence(prepare.actionSequence());
            this.entityReconciliation.notePrepare(localPlayer, prepare, localSequence, this.tick);
        }
    }

    protected void noteTempFallingBlock0(Entity localPlayer, TempFallingBlockReceipt receipt) {
        if (this.ready && localPlayer != null && receipt != null && receipt.actionSequence() > 0L && localPlayer.getUuid().equals(receipt.abilityOwner())) {
            long localSequence = this.localActionSequence(receipt.actionSequence());
            ClientWorld world = MinecraftClient.getInstance().world;
            this.entityReconciliation.noteReceipt(localPlayer, receipt, localSequence, world);
        }
    }

    protected boolean reconcileSpawn0(EntitySpawnS2CPacket packet) {
        return this.ready && this.entityReconciliation.reconcileSpawn(packet, MinecraftClient.getInstance().world, this.tick);
    }

    protected long currentAction() {
        Long input = INPUT_ACTION.get();
        if (input != null) {
            return input;
        }

        CoreAbility ability = AbilityExecutionContext.current();
        if (ability == null) {
            return 0L;
        }

        Long associated = this.abilityActions.get(ability);
        if (associated != null && associated > 0L) {
            return associated;
        }

        long inherited = ability.getPredictionActionSequence();
        Action action = inherited <= 0L ? null : this.actions.get(inherited);
        if (action == null) {
            return 0L;
        }

        this.abilityActions.put(ability, inherited);
        this.abilityCreationActions.putIfAbsent(ability, inherited);
        this.abilityTransitionActions.computeIfAbsent(ability,
                ignored -> new HashSet<>()).add(inherited);
        action.abilities.add(ability);
        debug("runtime inherited child action=" + inherited + " ability=" + ability.getName() + " instance=" + System.identityHashCode(ability));
        return inherited;
    }

    protected com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose executionPose0() {
        Long eventAction = INPUT_EVENT_POSE.get();
        Action action = eventAction == null ? null : this.actions.get(eventAction);
        return action == null
                ? null
                : new com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose(
                action.origin.x, action.origin.y - action.eyeHeight, action.origin.z, action.yaw, action.pitch, action.eyeHeight
        );
    }
}
