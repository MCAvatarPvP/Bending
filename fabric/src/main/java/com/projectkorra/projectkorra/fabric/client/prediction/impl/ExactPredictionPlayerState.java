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

public abstract class ExactPredictionPlayerState extends ExactPredictionTransfer {
    protected List<String> abilityRemovalReport0() {
        List<String> report = new ArrayList<>();
        if (this.abilityRemovalHistory.isEmpty()) {
            report.add("Ability removals: no Paper removal receipt has reached this client session");
        } else {
            report.add("Ability removals (oldest to newest):");
            report.addAll(this.abilityRemovalHistory);
        }

        List<String> active = new ArrayList<>();

        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability.getPlayer() != null
                    && this.bendingPlayer != null
                    && this.bendingPlayer.getPlayer() != null
                    && ability.getPlayer().getUniqueId().equals(this.bendingPlayer.getPlayer().getUniqueId())
                    && (ability.getName().equalsIgnoreCase("WaterSpout")
                    || ability.getName().equalsIgnoreCase("AirSpout")
                    || ability.getName().equalsIgnoreCase("SandSpout"))) {
                active.add(ability.getClass().getSimpleName() + "@" + this.abilityCreationActions.get(ability));
            }
        }

        report.add(
                "Local spout instances=" + active + " Paper flight snapshot=" + this.authoritativeFlightAbilities + " snapshotAck=" + this.authoritativeFlightSequence
        );
        return List.copyOf(report);
    }

    protected List<String> tempBlockReport0() {
        final List<String> report = new ArrayList<>(this.tempBlockAuthority.report());
        report.addAll(this.directBlockAuthority.report());
        final List<String> raises = new ArrayList<>();
        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!(ability instanceof RaiseEarth raise) || raise.getPlayer() == null
                    || this.bendingPlayer == null || this.bendingPlayer.getPlayer() == null
                    || !raise.getPlayer().getUniqueId().equals(
                    this.bendingPlayer.getPlayer().getUniqueId())) continue;
            final Location location = raise.getLocation();
            raises.add("RaiseEarth@" + this.abilityCreationActions.get(raise)
                    + " transition=" + this.abilityActions.get(raise)
                    + " location=" + (location == null ? "null" : "("
                    + location.getX() + "," + location.getY() + "," + location.getZ() + ")")
                    + " distance=" + raise.getDistance()
                    + " affected=" + raise.getAffectedBlocks().size()
                    + " wall=" + raise.isRaisedByWall());
        }
        report.add(raises.isEmpty() ? "Local RaiseEarth instances=[]"
                : "Local RaiseEarth instances: " + raises);
        final List<String> smashes = new ArrayList<>();
        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!(ability instanceof EarthSmash smash) || smash.getPlayer() == null
                    || this.bendingPlayer == null || this.bendingPlayer.getPlayer() == null
                    || !smash.getPlayer().getUniqueId().equals(
                    this.bendingPlayer.getPlayer().getUniqueId())) continue;
            final PredictionTransfer transfer = smash.capturePredictionTransfer();
            final Location location = smash.getLocation();
            smashes.add("EarthSmash@" + this.abilityCreationActions.get(smash)
                    + " state=" + smash.getState()
                    + " location=" + (location == null ? "null" : "("
                    + location.getX() + "," + location.getY() + "," + location.getZ() + ")")
                    + " animation=" + smash.getAnimationCounter()
                    + " progress=" + smash.getProgressCounter()
                    + " frame=" + (transfer == null ? -1L : transfer.predictionFrame())
                    + " shape=" + smash.getCurrentBlocks().size()
                    + " activeLayers=" + smash.getAffectedBlocks().stream()
                    .filter(layer -> layer != null && !layer.isReverted()).count()
                    + " tracksLayers=" + smash.tracksPredictedTempBlocks()
                    + " authoritativeEstablished=" + this.authoritativelyEstablishedAbilities.contains(smash));
        }
        if (smashes.isEmpty()) report.add("Local EarthSmash instances=[]");
        else {
            report.add("Local EarthSmash instances:");
            report.addAll(smashes);
        }
        return List.copyOf(report);
    }


    protected void notePredictedAbilityState0(boolean invulnerable, boolean flying,
                                            boolean allowFlying, boolean creativeMode, float flySpeed, float walkSpeed) {
        if (!this.ready) return;
        long actionSequence = this.currentAction();
        Action action = this.actions.get(actionSequence);
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (action == null || player == null) return;
        int ordinal = action.abilityStateOrdinals.merge(player.getId(), 1, Integer::sum);
        this.playerStateAuthority.predictAbilityState(this.tick, actionSequence, ordinal);
    }

    protected void noteAbilityStateOwner0(Entity localPlayer, AbilityStateOwner owner) {
        if (!this.ready) return;
        this.playerStateAuthority.recordAbilityOwner(
                localPlayer, owner, this.tick, this::localActionSequence
        );
    }

    @Override
    public void beforeWrite(final CoreAbility ability, final Player target, final boolean gliding) {
        if (!this.ready || target == null || this.bendingPlayer == null
                || this.bendingPlayer.getPlayer() == null
                || !target.getUniqueId().equals(this.bendingPlayer.getPlayer().getUniqueId())) return;
        final long sequence = this.currentAction();
        final Action action = this.actions.get(sequence);
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (action != null && player != null) {
            action.glidingStateOrdinals.merge(player.getId(), 1, Integer::sum);
        }
    }

    protected void noteGlidingStateOwner0(final Entity localPlayer, final GlidingStateOwner owner) {
        if (!this.ready || localPlayer == null || owner == null
                || !localPlayer.getUuid().equals(owner.target())) return;
        final long localSequence = this.localActionSequence(owner.actionSequence());
        final Action action = this.actions.get(localSequence);
        if (action == null) return;
        final AirGlider glider = this.bendingPlayer == null ? null
                : CoreAbility.getAbility(this.bendingPlayer.getPlayer(), AirGlider.class);
        final Long latest = glider == null ? null : this.abilityActions.get(glider);
        if (latest != null && latest > localSequence) return;
        final int predictedOrdinal = action.glidingStateOrdinals.getOrDefault(localPlayer.getId(), 0);
        if (owner.mutationOrdinal() > predictedOrdinal) {
            action.glidingStateOrdinals.put(localPlayer.getId(), owner.mutationOrdinal());
        }
        if (localPlayer instanceof ClientPlayerEntity player && player.isGliding() != owner.gliding()) {
            if (owner.gliding()) player.startGliding();
            else player.stopGliding();
        }
    }

    protected void applyAirGliderState0(final Entity localPlayer, final AirGliderState payload) {
        if (!this.ready || localPlayer == null || payload == null
                || !localPlayer.getUuid().equals(payload.player()) || this.bendingPlayer == null) return;
        final long localSequence = this.localActionSequence(payload.actionSequence());
        final Action action = this.actions.get(localSequence);
        if (action == null) return;
        final AirGlider.State state;
        try {
            state = AirGlider.State.valueOf(payload.state());
        } catch (IllegalArgumentException invalid) {
            return;
        }
        final AirGlider.PredictionState checkpoint = new AirGlider.PredictionState(
                state, payload.stateTicks(), payload.stalled(), payload.stallTicks(),
                payload.recoveryTicks(), payload.transitionRevision(), payload.velocityX(),
                payload.velocityY(), payload.velocityZ(), payload.gliding(), payload.previousGlidingState(),
                payload.gliderColor());
        AirGlider glider = CoreAbility.getAbility(this.bendingPlayer.getPlayer(), AirGlider.class);
        final Long latest = glider == null ? null : this.abilityActions.get(glider);
        if (latest != null && latest > localSequence) return;
        if (glider == null) {
            final AirGlider[] restored = new AirGlider[1];
            ExactPredictionRuntime.runWithAction(localSequence, () -> restored[0] = AirGlider.restorePredictionState(
                    this.bendingPlayer.getPlayer(), checkpoint));
            glider = restored[0];
            if (glider == null) return;
            this.authoritativelyEstablishedAbilities.add(glider);
            this.abilityCreationActions.putIfAbsent(glider, localSequence);
        } else if (!glider.confirmsPredictionTransition(checkpoint)) {
            glider.applyPredictionState(checkpoint);
        }
        this.associateAbility(action, glider);
        action.abilities.add(glider);
        action.locallyPredicted = true;
        action.recoveredFromAuthority = true;
    }

    protected void reassertPredictedGliding0(final int entityId) {
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (!this.ready || player == null || player.getId() != entityId || this.bendingPlayer == null) return;
        final AirGlider glider = CoreAbility.getAbility(this.bendingPlayer.getPlayer(), AirGlider.class);
        if (glider == null || glider.isRemoved()) return;
        final boolean expected = glider.getState() == AirGlider.State.GLIDING;
        if (player.isGliding() != expected) {
            if (expected) player.startGliding();
            else player.stopGliding();
        }
    }

    protected AirGlider localAirGlider(final ClientPlayerEntity player) {
        if (!this.ready || player == null || this.bendingPlayer == null
                || this.bendingPlayer.getPlayer() == null
                || !player.getUuid().equals(this.bendingPlayer.getPlayer().getUniqueId())) return null;
        final AirGlider glider = CoreAbility.getAbility(this.bendingPlayer.getPlayer(), AirGlider.class);
        return glider == null || glider.isRemoved() ? null : glider;
    }

    protected void notePredictedExperience0(float barProgress, int experience, int level) {
        if (!this.ready) return;
        this.playerStateAuthority.predictExperience(
                this.tick, barProgress, experience, level
        );
    }

    protected boolean notePredictedSelectedSlot0(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        } else if (this.ready && this.bendingPlayer != null && this.bendingPlayer.getPlayer() != null) {
            SlotResult result = CommonInputHandler.handleSlotChange(this.bendingPlayer.getPlayer(), slot);
            return result.accepted();
        } else {
            return true;
        }
    }

    protected boolean suppressAuthoritativeAbilityState0(PlayerAbilitiesS2CPacket packet) {
        return this.ready && this.playerStateAuthority.suppressAbilityPacket(
                packet, this.tick, this.hasLocalFlightLease()
        );
    }


    protected boolean hasLocalFlightLease() {
        if (this.bendingPlayer != null && this.bendingPlayer.getPlayer() != null) {
            try {
                return ((FlightHandler) Manager.getManager(FlightHandler.class)).getInstance(this.bendingPlayer.getPlayer()) != null;
            } catch (RuntimeException unavailable) {
                return false;
            }
        } else {
            return false;
        }
    }


    protected void reconcileActiveFlightAbilities0(List<String> activeAbilities, long acknowledgedSequence) {
        if (this.ready && this.bendingPlayer != null && this.bendingPlayer.getPlayer() != null) {
            long latestLocalSequence = this.latestLocalSequence();
            long localAcknowledgement = this.localAcknowledgedSequence(acknowledgedSequence);
            if (!PredictionStateOrdering.snapshotCoversLatestInput(localAcknowledgement, latestLocalSequence)) {
                debug("runtime deferred flight snapshot ack=" + acknowledgedSequence + " localAck=" + localAcknowledgement + " latestLocal=" + latestLocalSequence);
            } else {
                Set<String> next = new HashSet<>();
                if (activeAbilities != null) {
                    for (String ability : activeAbilities) {
                        if (ability != null) {
                            String normalized = ability.toLowerCase(Locale.ROOT);
                            if (PERSISTENT_FLIGHT_ABILITIES.contains(normalized)) {
                                next.add(normalized);
                            }
                        }
                    }
                }

                this.authoritativeFlightAbilities = Set.copyOf(next);
                this.authoritativeFlightSequence = acknowledgedSequence;
                debug(
                        "runtime observed sequence-fenced Paper flight snapshot ack="
                                + acknowledgedSequence
                                + " localAck="
                                + localAcknowledgement
                                + " active="
                                + this.authoritativeFlightAbilities
                );
            }
        }
    }

    protected long latestLocalSequence() {
        return this.actions.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    protected void forceRemoveAbility(CoreAbility ability) {
        if (ability != null && !ability.isRemoved()) {
            try {
                this.tempBlockAuthority.removeAbility(
                        ability,
                        () -> PredictedContactSync.forceRemoval(
                                ability, () -> AbilityExecutionContext.run(ability, ability::remove)
                        )
                );
            } finally {
                this.abilityActions.remove(ability);
                this.abilityCreationActions.remove(ability);
                this.abilityTransitionActions.remove(ability);
                this.authoritativelyEstablishedAbilities.remove(ability);
            }
        }
    }


    protected boolean suppressAuthoritativeExperience0(ExperienceBarUpdateS2CPacket packet) {
        return this.ready
                && this.playerStateAuthority.suppressExperiencePacket(packet, this.tick);
    }

    protected static boolean isLocalPlayerEntity(int entityId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.getId() == entityId;
    }

    protected String currentAbilityName() {
        CoreAbility ability = AbilityExecutionContext.current();
        return ability == null ? "<none>" : ability.getName();
    }

    protected String airBlastStamina() {
        return this.bendingPlayer == null ? "<none>" : String.format(Locale.ROOT, "%.4f", this.bendingPlayer.getAirBlastDecay());
    }

    protected String activeAirBlastSummary() {
        if (this.bendingPlayer == null) {
            return "[]";
        }

        List<String> summary = new ArrayList<>();

        for (AirBlast blast : CoreAbility.getAbilities(this.bendingPlayer.getPlayer(), AirBlast.class)) {
            summary.add(
                    "{progressing="
                            + blast.isProgressing()
                            + ",fromOther="
                            + blast.isFromOtherOrigin()
                            + ",ticks="
                            + blast.getTicks()
                            + ",action="
                            + this.abilityActions.get(blast)
                            + ",creation="
                            + this.abilityCreationActions.get(blast)
                            + ",loc="
                            + compactLocation(blast.getLocation())
                            + ",origin="
                            + compactLocation(blast.getOrigin())
                            + ",radius="
                            + String.format(Locale.ROOT, "%.3f", blast.getRadius())
                            + "}"
            );
        }

        return summary.toString();
    }

    protected static String velocityString(Vec3d velocity) {
        return velocity == null ? "<null>" : String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", velocity.x, velocity.y, velocity.z);
    }

    protected static String compactLocation(Location location) {
        return location == null ? "<null>" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", location.getX(), location.getY(), location.getZ());
    }
}
