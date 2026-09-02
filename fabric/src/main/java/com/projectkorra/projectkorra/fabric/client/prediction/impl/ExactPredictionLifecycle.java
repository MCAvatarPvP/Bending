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

public abstract class ExactPredictionLifecycle extends ExactPredictionReconciliation {
    protected void stop0(MinecraftClient client) {
        if (!this.commonRuntimeInstalled) {
            debug("runtime stop skipped before common startup actions=" + this.actions.size());
            if (this.platform != null) {
                try {
                    this.platform.close();
                } catch (Throwable var25) {
                }

                this.platform = null;
            }

            this.grantedPermissions = Set.of();
        } else {
            debug(
                    "runtime stop ready="
                            + this.ready
                            + " initializing="
                            + this.initializing
                            + " activeInstances="
                            + CoreAbility.getAbilitiesByInstances().size()
                            + " actions="
                            + this.actions.size()
            );
            this.discardingWorldState = true;

            try {
                if (this.ready || this.initializing) {
                    try {
                        GeneralMethods.stopBending();
                    } catch (Throwable var40) {
                    }

                    try {
                        EmbeddedAddonBootstrap.disable();
                    } catch (Throwable var39) {
                    }

                    if (ProjectKorra.collisionManager != null) {
                        try {
                            ProjectKorra.collisionManager.stopCollisionDetection();
                        } catch (Throwable var38) {
                        }

                        ProjectKorra.collisionManager = null;
                        ProjectKorra.collisionInitializer = null;
                    }

                    if (this.managersStarted) {
                        try {
                            Manager.shutdown();
                        } catch (Throwable var37) {
                        }

                        this.managersStarted = false;
                    }
                }

                try {
                    TempBlock.discardAll();
                } catch (Throwable var36) {
                }

                try {
                    TempFallingBlock.discardAll();
                } catch (Throwable var35) {
                }

                try {
                    CoreAbility.discardAllInstances();
                } catch (Throwable var34) {
                }

                try {
                    AirAbility.discardAllAirbendingState();
                } catch (Throwable var33) {
                }

                try {
                    EarthAbility.discardAllEarthbendingState();
                } catch (Throwable var32) {
                }

                try {
                    WaterAbility.discardAllWaterbendingState();
                } catch (Throwable var31) {
                }

                try {
                    FireAbility.discardAllFirebendingState();
                } catch (Throwable var30) {
                }

                try {
                    RevertChecker.discardAll();
                } catch (Throwable var29) {
                }

                try {
                    BlockSource.clearAll();
                } catch (Throwable var28) {
                }

                if (this.bendingPlayer != null) {
                    RegionProtectionAuthority.clear(this.bendingPlayer.getPlayer());
                    BendingPlayer.getPlayers().remove(this.bendingPlayer.getPlayer().getUniqueId());
                    BendingPlayer.getOfflinePlayers().remove(this.bendingPlayer.getPlayer().getUniqueId());
                }

                if (this.platform != null) {
                    try {
                        this.platform.close();
                    } catch (Throwable var27) {
                    }
                }

                for (Action action : this.actions.values()) {
                    for (Entity entity : action.spawned) {
                        if (entity != null && !entity.isRemoved()) {
                            try {
                                entity.discard();
                            } catch (Throwable var26) {
                            }
                        }
                    }
                }

                this.actions.clear();
                this.abilityActions.clear();
                this.abilityCreationActions.clear();
                this.abilityTransitionActions.clear();
                this.authoritativelyEstablishedAbilities.clear();
                this.abilityRemovalHistory.clear();
                this.nativeActions.clear();
                this.authoritativeFlightAbilities = Set.of();
                this.authoritativeFlightSequence = -1L;
                this.grantedPermissions = Set.of();
                this.directBlockAuthority.clear();
                this.tempBlockAuthority.clear();
                this.velocityAuthority.clear();
                this.soundAuthority.clear();
                this.entityReconciliation.clear();
                this.playerStateAuthority.clear();
                TempBlockSync.clear(this.tempBlockAuthority);
                TempFallingBlockSync.clear(this);
                PredictedContactSync.clear(this);
                GlidingStateSync.clear(this);
                CooldownSync.clear(this);
                this.cooldownAuthority.clear();
                this.platform = null;
                this.bendingManager = null;
                this.bendingPlayer = null;
                this.ready = false;
                this.managersStarted = false;
                this.commonRuntimeInstalled = false;
                debug("runtime stopped");
            } finally {
                this.discardingWorldState = false;
            }
        }
    }

    public boolean isAuthoritative() {
        return false;
    }


    public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) {
        if (player == this.bendingPlayer && ability != null && !ability.isBlank()) {
            this.cooldownAuthority.onLocalAdded(ability, expiresAtMillis);
        }
    }

    public void onRemoved(BendingPlayer player, String ability) {
        if (player == this.bendingPlayer && ability != null) {
            this.cooldownAuthority.onLocalRemoved(ability);
        }
    }


    public static <T> T completedTempBlockRestoreState(boolean followLiveClientState, T liveState, T finalUnderlay) {
        return ClientTempBlockAuthority.completedRestoreState(
                followLiveClientState, liveState, finalUnderlay
        );
    }


    protected void noteDirectBlock0(Entity localPlayer, DirectBlockReceipt receipt) {
        if (this.ready && receipt != null) {
            this.directBlockAuthority.noteReceipt(localPlayer, receipt, this.localActionSequence(receipt.actionSequence()), MinecraftClient.getInstance().world);
        }
    }

    public void onPredictedContact(CoreAbility ability, com.projectkorra.projectkorra.platform.mc.entity.Entity target) {
        if (this.ready && ability != null
                && HitRegistrationPolicy.forAbility(ability)
                == HitRegistrationPolicy.REWIND_ASSISTED
                && target instanceof Player && !target.isDead() && target.isValid()) {
            long sequence = this.abilityActions.getOrDefault(ability, this.currentAction());
            Action action = this.actions.get(sequence);
            if (action != null && action.claimedTargets.add(target.getUniqueId())) {
                Vector contact = target.getBoundingBox().getCenter();
                com.projectkorra.projectkorra.fabric.client.PredictionClient.queueExactHitClaim(
                        sequence,
                        this.paperActionSequence(sequence),
                        action.inputAbility,
                        target.getUniqueId(),
                        target.getEntityId(),
                        contact.getX(),
                        contact.getY(),
                        contact.getZ()
                );
                debug(
                        "runtime queued rewound hit claim action="
                                + sequence
                                + " paperAction="
                                + this.paperActionSequence(sequence)
                                + " ability="
                                + action.inputAbility
                                + " target="
                                + target.getUniqueId()
                );
            }
        }
    }

    @Override
    public boolean isLocallyOwned(
            final com.projectkorra.projectkorra.platform.mc.entity.Entity target) {
        if (!this.ready || target == null) {
            return false;
        }
        final Object handle = target.handle();
        return handle instanceof Entity nativeTarget
                && this.entityReconciliation.isPredictedOwned(nativeTarget);
    }

    protected void setVelocity0(Entity entity, Vec3d velocity) {
        if (this.ready && entity != null && velocity != null && finite(velocity)) {
            if (ExactPredictionRuntime.isLocalPlayerEntity(entity.getId()) && this.velocityAuthority.blocksPredictedWrite(entity.getId())) {
                debug(
                        "runtime suppressed late predicted velocity behind external authority entity="
                                + entity.getId()
                                + " ability="
                                + this.currentAbilityName()
                                + " attempted="
                                + ExactPredictionRuntime.velocityString(velocity)
                );
            } else {
                long actionSequence = this.currentAction();
                Action action = this.actions.get(actionSequence);
                int impulseOrdinal = action == null ? 0 : action.velocityOrdinals.merge(entity.getId(), 1, Integer::sum);
                String abilityName = this.currentAbilityName();
                if ("<none>".equals(abilityName) && action != null) {
                    abilityName = action.inputAbility;
                }

                if (ExactPredictionRuntime.isLocalPlayerEntity(entity.getId())) {
                    debug(
                            "runtime predicted velocity local action="
                                    + actionSequence
                                    + " ordinal="
                                    + impulseOrdinal
                                    + " ability="
                                    + abilityName
                                    + " before="
                                    + ExactPredictionRuntime.velocityString(entity.getVelocity())
                                    + " after="
                                    + ExactPredictionRuntime.velocityString(velocity)
                                    + " stamina="
                                    + this.airBlastStamina()
                                    + " activeAirBlasts="
                                    + this.activeAirBlastSummary()
                    );
                }

                this.velocityAuthority.predict(entity, velocity, actionSequence, impulseOrdinal, abilityName, this.tick);
            }
        }
    }

    protected boolean authoritativeVelocity0(int entityId, Vec3d velocity) {
        if (this.ready && finite(velocity)) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            return this.velocityAuthority
                    .acceptAuthoritative(entityId, velocity, this.tick, player == null ? null : player.getUuid(), this::hasLivePredictedVelocityWriter);
        } else {
            return false;
        }
    }

    protected boolean hasLivePredictedVelocityWriter(int entityId) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && player.getId() == entityId) {
            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (ability != null && !ability.isRemoved() && ability.getPlayer() != null && player.getUuid().equals(ability.getPlayer().getUniqueId())) {
                    Long sequence = this.abilityActions.containsKey(ability) ? this.abilityActions.get(ability) : this.abilityCreationActions.get(ability);
                    if (this.velocityAuthority.hasMutation(entityId, sequence, ability.getName())) {
                        return true;
                    }
                }
            }

            return false;
        } else {
            return false;
        }
    }

    protected boolean tracksVelocityEntity0(int entityId) {
        if (this.ready && entityId >= 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            return this.velocityAuthority.tracks(entityId, client.player == null ? -1 : client.player.getId());
        } else {
            return false;
        }
    }

    protected void noteVelocityOwner0(Entity localPlayer, VelocityOwner owner) {
        if (this.ready) {
            this.velocityAuthority.recordOwner(localPlayer, owner, this.tick, this::localActionSequence);
        }
    }

    protected void noteVelocityOwner0(Entity localPlayer, VelocityOwnerV2 owner) {
        if (this.ready) {
            this.velocityAuthority.recordOwner(localPlayer, owner, this.tick, this::localActionSequence);
        }
    }
}
