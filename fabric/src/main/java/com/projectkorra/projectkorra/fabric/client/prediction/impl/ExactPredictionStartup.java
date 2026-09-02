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

public abstract class ExactPredictionStartup extends ExactPredictionState {
    protected boolean start0(
            MinecraftClient client,
            List<ConfigEntry> entries,
            Map<Integer, String> binds,
            Map<String, Long> cooldowns,
            List<String> elements,
            List<String> subElements,
            List<String> permissions,
            double airBlastDecay,
            boolean chiBlocked,
            PlayerCosmetics cosmetics,
            Snapshot regionProtection
    ) {
        debug(
                "runtime start requested ready="
                        + this.ready
                        + " initializing="
                        + this.initializing
                        + " integratedServer="
                        + (client.getServer() != null)
                        + " player="
                        + (client.player != null)
                        + " world="
                        + (client.world != null)
                        + " configEntries="
                        + entries.size()
                        + " binds="
                        + binds
        );
        if (this.ready) {
            ClientPredictionConfig.apply(entries);
            this.updatePlayerState0(binds, cooldowns, elements, subElements, permissions,
                    airBlastDecay, chiBlocked, cosmetics, regionProtection);
            debug("runtime already ready; state refreshed");
            return true;
        }

        if (client.getServer() == null && client.player != null && client.world != null && !this.initializing) {
            this.initializing = true;
            this.grantedPermissions = ClientPredictionConfig.normalizePermissions(permissions);

            try {
                this.platform = new FabricClientPredictionPlatform(client);
                Platform.install(this.platform);
                this.commonRuntimeInstalled = true;
                ProjectKorra.initCommon();
                Manager.startup();
                this.managersStarted = true;
                ClientPredictionConfig.apply(entries);
                ElementalAbility.clearBendableMaterials();
                ElementalAbility.setupBendableMaterials();
                EarthTunnel.clearBendableMaterials();
                EarthTunnel.setupBendableMaterials();
                Bloodbending.loadBloodlessFromConfig();
                this.bendingManager = new BendingManager();
                Platform.scheduler().runTimer(this.bendingManager, 0L, 1L);
                Platform.scheduler().runTimer(new WaterbendingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new EarthbendingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new FirebendingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new ChiblockingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new CooldownDisplayHandler(), 0L, 1L);
                Platform.scheduler().runTimer(new RegenHandler(ProjectKorra.plugin), 0L, 20L);
                Platform.scheduler().runTimer(new TempElementsRunnable(), 20L, 20L);
                ProjectKorra.plugin.revertChecker = Platform.scheduler().runTimerAsync(new RevertChecker(ProjectKorra.plugin), 0L, 200L);
                new MultiAbilityManager();
                new ComboManager();
                if (ProjectKorra.collisionManager != null) {
                    ProjectKorra.collisionManager.stopCollisionDetection();
                }

                ProjectKorra.collisionManager = new CollisionManager();
                ProjectKorra.collisionInitializer = new CollisionInitializer(ProjectKorra.collisionManager);
                CoreAbility.registerAbilities();
                EmbeddedAddonBootstrap.enable();
                ClientPredictionConfig.apply(entries);
                AbilityActivationManager.reload();
                ComboManager.registerCombos();
                FallHandler.loadNoFallDamageAbilities();
                ProjectKorra.collisionInitializer.initializeDefaultCollisions();
                Player player = FabricPredictionMC.player(client.player);
                this.bendingPlayer = new BendingPlayer(player);
                BendingPlayer.getPlayers().put(player.getUniqueId(), this.bendingPlayer);
                BendingPlayer.getOfflinePlayers().put(player.getUniqueId(), this.bendingPlayer);
                CooldownSync.install(this);
                TempBlockSync.install(this.tempBlockAuthority);
                TempFallingBlockSync.install(this);
                PredictedContactSync.install(this);
                GlidingStateSync.install(this);
                this.updatePlayerState0(binds, cooldowns, elements, subElements, permissions,
                        airBlastDecay, chiBlocked, cosmetics, regionProtection);
                this.ready = true;
                this.lastStartFailure = "";
                ProjectKorra.log.info("Exact client prediction enabled with " + CoreAbility.getAbilities().size() + " local abilities");
                debug(
                        "runtime ready abilities="
                                + CoreAbility.getAbilities().size()
                                + " activeInstances="
                                + CoreAbility.getAbilitiesByInstances().size()
                                + " playerElements="
                                + this.bendingPlayer.getElements()
                                + " playerSubElements="
                                + this.bendingPlayer.getSubElements()
                );
                return true;
            } catch (Throwable failure) {
                this.lastStartFailure = failure.getClass().getSimpleName()
                        + (failure.getMessage() != null && !failure.getMessage().isBlank() ? ": " + failure.getMessage() : "");
                ProjectKorra.log.log(Level.SEVERE, "Exact client prediction could not start", failure);
                debug("runtime start failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                this.stop0(client);
                return false;
            } finally {
                this.initializing = false;
            }
        } else {
            debug(
                    "runtime start refused integratedServer="
                            + (client.getServer() != null)
                            + " player="
                            + (client.player != null)
                            + " world="
                            + (client.world != null)
                            + " initializing="
                            + this.initializing
            );
            return false;
        }
    }


    protected void updatePlayerState0(
            Map<Integer, String> binds,
            Map<String, Long> cooldowns,
            List<String> elements,
            List<String> subElements,
            List<String> permissions,
            double airBlastDecay,
            boolean chiBlocked,
            PlayerCosmetics cosmetics,
            Snapshot regionProtection
    ) {
        this.grantedPermissions = ClientPredictionConfig.normalizePermissions(permissions);
        if ((this.ready || this.initializing) && this.bendingPlayer != null) {
            if (!MultiAbilityManager.hasMultiAbilityBound(this.bendingPlayer.getPlayer())) {
                this.bendingPlayer.getAbilities().clear();
                this.bendingPlayer.getAbilities().putAll(binds);
            }

            this.bendingPlayer.getElements().clear();

            for (String name : elements) {
                Element element = Element.getElement(name);
                if (element != null && !(element instanceof SubElement)) {
                    this.bendingPlayer.getElements().add(element);
                }
            }

            this.bendingPlayer.getSubElements().clear();

            for (String name : subElements) {
                if (Element.getElement(name) instanceof SubElement subElement) {
                    this.bendingPlayer.getSubElements().add(subElement);
                }
            }

            if (chiBlocked) {
                this.bendingPlayer.blockChi();
            } else {
                this.bendingPlayer.unblockChi();
            }

            final PlayerCosmetics safeCosmetics = cosmetics == null ? PlayerCosmetics.empty() : cosmetics;
            this.bendingPlayer.applyCosmeticState(
                    safeCosmetics.fireColor().isBlank() ? null : CosmeticColor.getFireColor(safeCosmetics.fireColor()),
                    safeCosmetics.airColor().isBlank() ? null : CosmeticColor.getAirColor(safeCosmetics.airColor()),
                    safeCosmetics.waterCosmetic().isBlank() ? null : WaterCosmetic.getCosmetic(safeCosmetics.waterCosmetic()),
                    safeCosmetics.earthCosmetic().isBlank() ? null : EarthCosmetic.getCosmetic(safeCosmetics.earthCosmetic()),
                    safeCosmetics.sprinkle());

            RegionProtectionAuthority.install(this.bendingPlayer.getPlayer(), regionProtection);
            PassiveManager.registerPassives(this.bendingPlayer.getPlayer());
            this.reconcileAuthoritativeCooldowns(cooldowns);
            if (this.initializing || !this.ready) {
                this.bendingPlayer.setAirBlastDecay(airBlastDecay);
            }

            debug(
                    "runtime state applied binds="
                            + this.bendingPlayer.getAbilities()
                            + " elements="
                            + this.bendingPlayer.getElements()
                            + " subElements="
                            + this.bendingPlayer.getSubElements()
                            + " cosmetics="
                            + safeCosmetics
                            + " cooldowns="
                            + this.bendingPlayer.getCooldowns().keySet()
                            + " airBlastStamina="
                            + this.airBlastStamina()
            );
        } else {
            debug("runtime state ignored ready=" + this.ready + " initializing=" + this.initializing + " hasBendingPlayer=" + (this.bendingPlayer != null));
        }
    }


    protected String inputAbilityName0(int selectedSlot, String fallback, InputKind kind) {
        if (this.ready && this.bendingPlayer != null) {
            Player player = this.bendingPlayer.getPlayer();
            if (kind == InputKind.SNEAK_START && this.canStartFastSwim(player)) {
                return "FastSwim";
            } else {
                String multi = MultiAbilityManager.getBoundMultiAbility(player);
                if (multi != null && !multi.isBlank()) {
                    return multi;
                } else {
                    String local = (String) this.bendingPlayer.getAbilities().get(selectedSlot + 1);
                    String selected = local != null && !local.isBlank() ? local : (fallback == null ? "" : fallback);
                    if (!selected.equalsIgnoreCase("FireBlast") || kind != InputKind.SNEAK_START && kind != InputKind.SNEAK_STOP) {
                        return selected.isBlank() && kind == InputKind.LEFT_CLICK && this.bendingPlayer.isToggled() && CoreAbility.getAbility(WallRun.class) != null
                                ? "WallRun"
                                : selected;
                    } else {
                        return "FireBlastCharged";
                    }
                }
            }
        } else {
            return fallback == null ? "" : fallback;
        }
    }

    protected boolean canStartFastSwim(Player player) {
        if (player != null && !CoreAbility.hasAbility(player, FastSwim.class)) {
            CoreAbility bound = this.bendingPlayer.getBoundAbility();
            CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
            return (bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive);
        } else {
            return false;
        }
    }

    protected void recordNativeOnlyInput0(
            long sequence, InputKind kind, int selectedSlot,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose,
            String ability, boolean cooldownActiveAtInput
    ) {
        if (this.ready && this.bendingPlayer != null && pose != null && kind != null) {
            String inputAbility = ability != null && !ability.isBlank()
                    ? ability
                    : this.inputAbilityName0(selectedSlot, (String) this.bendingPlayer.getAbilities().get(selectedSlot + 1), kind);
            Action action = new Action(
                    sequence, this.tick, pose.eyePos(), pose.yaw(), pose.pitch(), pose.eyeHeight(), inputAbility, kind, selectedSlot
            );
            action.cooldownActiveAtInput = cooldownActiveAtInput;
            this.actions.put(sequence, action);
            debug("runtime recorded native-only input sequence=" + sequence + " kind=" + kind + " ability=" + inputAbility + " slot=" + (selectedSlot + 1));
        }
    }
}
