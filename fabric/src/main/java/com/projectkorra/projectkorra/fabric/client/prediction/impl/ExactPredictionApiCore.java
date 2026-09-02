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

public abstract class ExactPredictionApiCore extends ExactPredictionEntities {
    public static boolean start(
            MinecraftClient client,
            List<ConfigEntry> config,
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
        return ExactPredictionRuntime.instance().start0(client, config, binds, cooldowns, elements, subElements, permissions,
                airBlastDecay, chiBlocked, cosmetics, regionProtection);
    }

    public static void updatePlayerState(
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
        ExactPredictionRuntime.instance().updatePlayerState0(binds, cooldowns, elements, subElements, permissions,
                airBlastDecay, chiBlocked, cosmetics, regionProtection);
    }

    public static boolean hasPermission(String permission) {
        if ((ExactPredictionRuntime.instance().ready || ExactPredictionRuntime.instance().initializing) && permission != null && !permission.isBlank()) {
            String normalized = permission.toLowerCase(Locale.ROOT);
            if (!ExactPredictionRuntime.instance().grantedPermissions.contains("*") && !ExactPredictionRuntime.instance().grantedPermissions.contains(normalized)) {
                for (String granted : ExactPredictionRuntime.instance().grantedPermissions) {
                    if (granted.endsWith(".*") && normalized.startsWith(granted.substring(0, granted.length() - 1))) {
                        return true;
                    }
                }

                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    public static void reconcileActiveFlightAbilities(List<String> activeAbilities, long acknowledgedSequence) {
        ExactPredictionRuntime.instance().reconcileActiveFlightAbilities0(activeAbilities, acknowledgedSequence);
    }

    public static void tick(MinecraftClient client) {
        ExactPredictionRuntime.instance().tick0(client);
    }

    public static void stop(MinecraftClient client) {
        ExactPredictionRuntime.instance().stop0(client);
    }

    public static boolean isReady() {
        return ExactPredictionRuntime.instance().ready;
    }

    public static String lastStartFailure() {
        return ExactPredictionRuntime.instance().lastStartFailure;
    }

    public static boolean supports(String abilityName) {
        return ExactPredictionRuntime.instance().ready
                && abilityName != null
                && isExactPredictionSafe(abilityName)
                && (
                CoreAbility.getAbility(abilityName) != null
                        || abilityName.equalsIgnoreCase("FireBlastCharged") && CoreAbility.getAbility(FireBlastCharged.class) != null
        );
    }

    public static List<String> supportedAbilities() {
        if (!ExactPredictionRuntime.instance().ready) {
            return List.of();
        }

        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (CoreAbility ability : CoreAbility.getAbilities()) {
            if (ability != null && ability.getName() != null && !ability.getName().isBlank()
                    && isExactPredictionSafe(ability.getName())) {
                names.add(ability.getName());
            }
        }

        if (CoreAbility.getAbility(FireBlastCharged.class) != null) {
            names.add("FireBlastCharged");
        }

        return List.copyOf(names);
    }

    /** Common abilities, including AirGlider, execute through the exact client runtime. */
    public static boolean isExactPredictionSafe(final String abilityName) {
        return abilityName != null && !abilityName.isBlank();
    }

    public static boolean shouldPredictInput(String abilityName, InputKind kind) {
        return supports(abilityName);
    }

    public static boolean canActivate(String abilityName) {
        return supports(abilityName) && ExactPredictionRuntime.instance().bendingPlayer != null && !ExactPredictionRuntime.instance().bendingPlayer.isOnCooldown(abilityName);
    }

    public static boolean isOnLocalCooldown(String abilityName) {
        return supports(abilityName) && ExactPredictionRuntime.instance().bendingPlayer != null ? ExactPredictionRuntime.instance().bendingPlayer.isOnCooldown(abilityName) : false;
    }

    public static boolean isInputCooldownActive(String abilityName, InputKind kind) {
        if (!supports(abilityName) || ExactPredictionRuntime.instance().bendingPlayer == null || abilityName == null || abilityName.isBlank()) {
            return false;
        }
        final List<String> cooldowns = inputCooldownNames(abilityName, kind);
        return cooldowns.stream().anyMatch(ExactPredictionRuntime.instance().bendingPlayer::isOnInputCooldown);
    }

    protected static List<String> inputCooldownNames(final String abilityName, final InputKind kind) {
        if (abilityName == null || abilityName.isBlank()) return List.of();
        if (abilityName.equalsIgnoreCase("PhaseChange")) {
            if (kind == InputKind.LEFT_CLICK) return List.of(abilityName, "PhaseChangeFreeze");
            if (kind == InputKind.SNEAK_START) return List.of(abilityName, "PhaseChangeMelt");
        }
        return List.of(abilityName);
    }

    public static void removeLocalCooldown(String abilityName) {
        if (ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().bendingPlayer != null && abilityName != null && !abilityName.isBlank()) {
            if (ExactPredictionRuntime.instance().cooldownAuthority.isLocallyPredicted(abilityName)) {
                debug("runtime ignored stale server cooldown removal over newer local generation ability=" + abilityName);
                return;
            }

            ExactPredictionRuntime.instance().bendingPlayer.removeCooldown(abilityName);
        }
    }

    public static void synchronizeCooldowns(final Map<String, Long> authoritativeCooldowns) {
        ExactPredictionRuntime.instance().synchronizeCooldowns0(authoritativeCooldowns);
    }

    public static void enforceLocalCooldown(String abilityName, long clientUntilMillis) {
        if ((ExactPredictionRuntime.instance().ready || ExactPredictionRuntime.instance().initializing) && ExactPredictionRuntime.instance().bendingPlayer != null && abilityName != null && !abilityName.isBlank()) {
            long now = System.currentTimeMillis();
            long existing = ExactPredictionRuntime.instance().bendingPlayer.getCooldown(abilityName);
            if (clientUntilMillis > now && clientUntilMillis > existing) {
                Cooldown current = (Cooldown) ExactPredictionRuntime.instance().bendingPlayer.getCooldowns().get(abilityName);
                ExactPredictionRuntime.instance().bendingPlayer.getCooldowns().put(abilityName, new Cooldown(clientUntilMillis, current != null && current.isDatabase()));
                debug(
                        "runtime extended predicted cooldown to Paper expiry ability=" + abilityName + " previous=" + existing + " authoritative=" + clientUntilMillis
                );
            }
        }
    }

    public static void resetLocalAirBlast() {
        if (ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().bendingPlayer != null) {
            ExactPredictionRuntime.instance().bendingPlayer.resetAirBlast();
        }
    }

    public static void setLocalAirBlastDecay(double value) {
        if (ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().bendingPlayer != null && Double.isFinite(value)) {
            ExactPredictionRuntime.instance().bendingPlayer.setAirBlastDecay(Math.max(0.0, Math.min(1.0, value)));
        }
    }

    public static String inputAbilityName(int selectedSlot, String fallback, InputKind kind) {
        return ExactPredictionRuntime.instance().inputAbilityName0(selectedSlot, fallback, kind);
    }

    public static boolean shouldTrackDrop() {
        return ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().bendingPlayer != null && CommonInputHandler.shouldTrackDrop(ExactPredictionRuntime.instance().bendingPlayer.getPlayer());
    }

    public static void prepareOffHandRightClickEntity() {
        if (ExactPredictionRuntime.instance().ready && ExactPredictionRuntime.instance().bendingPlayer != null) {
            CommonInputHandler.prepareRightClickEntity(ExactPredictionRuntime.instance().bendingPlayer.getPlayer());
        }
    }

    public static boolean input(long sequence, InputKind kind, int selectedSlot,
                                com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose,
                                boolean cooldownActiveAtInput) {
        return ExactPredictionRuntime.instance().input0(sequence, kind, selectedSlot, pose, cooldownActiveAtInput);
    }

    public static void recordNativeOnlyInput(
            long sequence, InputKind kind, int selectedSlot,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose,
            String ability, boolean cooldownActiveAtInput
    ) {
        ExactPredictionRuntime.instance().recordNativeOnlyInput0(sequence, kind, selectedSlot, pose, ability, cooldownActiveAtInput);
    }

    public static boolean noteNativeAction(NativeAction action) {
        return ExactPredictionRuntime.instance().noteNativeAction0(action);
    }

    public static long correlatedLocalActionSequence(long paperSequence) {
        return ExactPredictionRuntime.instance().localActionSequence(paperSequence);
    }

    public static List<String> abilityRemovalReport() {
        return ExactPredictionRuntime.instance().abilityRemovalReport0();
    }

    public static List<String> tempBlockReport() {
        return ExactPredictionRuntime.instance().tempBlockReport0();
    }

    public static boolean isNativeActionConfirmed(long sequence) {
        Action action = ExactPredictionRuntime.instance().actions.get(sequence);
        return ExactPredictionRuntime.instance().ready && action != null && action.nativeConfirmed;
    }

    public static com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose executionPose() {
        return ExactPredictionRuntime.instance().executionPose0();
    }

    public static void predictMovement(
            MinecraftClient client,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose from,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose to
    ) {
        ExactPredictionRuntime.instance().predictMovement0(client, from, to);
    }

    public static void reconcile(
            long sequence, Vec3d authoritativeOrigin, String ability, long cooldownUntil, boolean inputHandled, boolean comboRecorded, List<String> createdAbilities
    ) {
        ExactPredictionRuntime.instance().reconcile0(sequence, authoritativeOrigin, ability, cooldownUntil, inputHandled, comboRecorded, createdAbilities);
    }
}
