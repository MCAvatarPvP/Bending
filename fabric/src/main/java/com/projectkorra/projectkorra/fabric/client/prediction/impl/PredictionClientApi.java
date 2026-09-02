package com.projectkorra.projectkorra.fabric.client.prediction.impl;

import com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientTempBlockAuthority;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Network/session owner for exact ProjectKorra client prediction. */
import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;

public abstract class PredictionClientApi extends PredictionClientLifecycle {
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        debug("client prediction networking initialized");
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ServerSnapshot.ID,
                (payload, context) -> PredictionClient.instance().onSnapshot(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ServerWorldState.ID,
                (payload, context) -> PredictionClient.instance().onServerWorldState(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.NativeAction.ID,
                (payload, context) -> PredictionClient.instance().onNativeAction(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.PlayerState.ID,
                (payload, context) -> PredictionClient.instance().onPlayerState(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.StateDirective.ID,
                (payload, context) -> PredictionClient.instance().onStateDirective(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.CooldownState.ID,
                (payload, context) -> PredictionClient.instance().onCooldownState(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ConfigChunk.ID,
                (payload, context) -> PredictionClient.instance().onConfigChunk(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.Reconcile.ID,
                (payload, context) -> PredictionClient.instance().onReconcile(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.TempBlockBatch.ID,
                (payload, context) -> PredictionClient.instance().onTempBlocks(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.VelocityOwner.ID,
                (payload, context) -> PredictionClient.instance().onVelocityOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.VelocityOwnerV2.ID,
                (payload, context) -> PredictionClient.instance().onVelocityOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityStateOwner.ID,
                (payload, context) -> PredictionClient.instance().onAbilityStateOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.GlidingStateOwner.ID,
                (payload, context) -> PredictionClient.instance().onGlidingStateOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.TempFallingBlockReceipt.ID,
                (payload, context) -> PredictionClient.instance().onTempFallingBlock(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.TempFallingBlockPrepare.ID,
                (payload, context) -> PredictionClient.instance().onTempFallingBlockPrepare(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.DirectBlockReceipt.ID,
                (payload, context) -> PredictionClient.instance().onDirectBlock(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityRemoved.ID,
                (payload, context) -> PredictionClient.instance().onAbilityRemoved(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityTransfer.ID,
                (payload, context) -> PredictionClient.instance().onAbilityTransfer(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AirGliderState.ID,
                (payload, context) -> PredictionClient.instance().onAirGliderState(context.client(), payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PredictionClient.instance().onJoin(sender, client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PredictionClient.instance().reset(client));
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register(PredictionClient.instance()::onClientWorldChange);
        ClientTickEvents.END_CLIENT_TICK.register(PredictionClient.instance()::tick);
    }

    public static void recordMovementPacket(MinecraftClient client, PlayerMoveC2SPacket packet) {
        if (!ClientBendingConfig.isEnabled() || client == null || client.player == null || packet == null) return;
        ClientPlayerEntity player = client.player;
        ServerPose previous = serverVisiblePose(client);
        if (previous == null) {
            previous = new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                    player.getEyeY() - player.getY());
        }
        PredictionClient.instance().serverPose = new ServerPose(
                packet.getX(previous.x()),
                packet.getY(previous.y()),
                packet.getZ(previous.z()),
                packet.getYaw(previous.yaw()),
                packet.getPitch(previous.pitch()),
                previous.eyeHeight());
        if (DEBUG && PredictionClient.instance().active && (Math.abs(previous.yaw() - PredictionClient.instance().serverPose.yaw()) > 0.001F
                || Math.abs(previous.pitch() - PredictionClient.instance().serverPose.pitch()) > 0.001F
                || Math.abs(previous.x() - PredictionClient.instance().serverPose.x()) > 1.0E-5
                || Math.abs(previous.y() - PredictionClient.instance().serverPose.y()) > 1.0E-5
                || Math.abs(previous.z() - PredictionClient.instance().serverPose.z()) > 1.0E-5)) {
            debug("server-pose movement yaw=" + PredictionClient.instance().serverPose.yaw()
                    + " pitch=" + PredictionClient.instance().serverPose.pitch()
                    + " eyeY=" + PredictionClient.instance().serverPose.eyePos().y
                    + " pos=(" + PredictionClient.instance().serverPose.x() + ", " + PredictionClient.instance().serverPose.y() + ", " + PredictionClient.instance().serverPose.z() + ")");
        }
        ExactPredictionRuntime.predictMovement(client, previous, PredictionClient.instance().serverPose);
    }

    /**
     * Commits only movement/look packets after ClientConnection accepted its
     * send method. Native input itself remains on ClientCommonNetworkHandler's
     * render-thread boundary; moving that work into the connection internals
     * caused valid swings to reach Paper without a local prediction action.
     */
    public static void acceptedMovementPacket(MinecraftClient client, Packet<?> packet) {
        if (!ClientBendingConfig.isEnabled()) return;
        if (packet instanceof PlayerMoveC2SPacket movement) recordMovementPacket(client, movement);
    }

    /**
     * Sends the exact local action identity after ClientConnection accepted
     * the outer packet, but immediately before that vanilla input is written.
     */
    public static void prepareAcceptedNativeInputPacket(MinecraftClient client, Packet<?> packet) {
        if (!ClientBendingConfig.isEnabled()) return;
        PredictionClient.instance().prepareAcceptedNativeInputPacket0(packet);
    }

    /** Runs after the outer vanilla input packet has entered the connection. */
    public static void acceptedNativeInputPacket(MinecraftClient client, Packet<?> packet) {
        if (!ClientBendingConfig.isEnabled()) return;
        PredictionClient.instance().acceptedNativeInputPacket0(packet);
    }

    public static void beforeVanillaPacket(MinecraftClient client, Packet<?> packet) {
        if (!ClientBendingConfig.isEnabled()) return;
        PredictionClient owner = PredictionClient.instance();
        if (isNativeAbilityInputPacket(packet)) {
            if (owner.currentNativeInputPacket != null && owner.currentNativeInputPacket != packet) {
                // The preceding outer packet never reached sendImmediately
                // (for example, another networking mixin cancelled it). Its
                // metadata cannot be attached to this later vanilla action.
                owner.pendingHitClaims.clear();
                owner.pendingTaggedPacket = null;
                owner.pendingActionTag = null;
            }
            owner.currentNativeInputPacket = packet;
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket selectedSlot) {
            owner.recordServerVisibleSelectedSlot(client, selectedSlot.getSelectedSlot());
            return;
        }
        if (packet instanceof HandSwingC2SPacket) {
            // PKListener deliberately does not filter PlayerAnimationEvent by
            // hand (the legacy check is commented out), so every vanilla arm
            // animation advances the Paper input order.
            owner.captureLeftClick(client);
            return;
        }
        if (packet instanceof PlayerActionC2SPacket action) {
            if ((action.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM
                    || action.getAction() == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS)
                    && ExactPredictionRuntime.shouldTrackDrop()) {
                owner.droppedItem = true;
            } else if (action.getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
                owner.capture(client, PredictionPayloads.InputKind.SWAP_HANDS);
            }
            return;
        }
        if (packet instanceof ClientCommandC2SPacket command) {
            final String mode = command.getMode().name();
            final Boolean sneaking = switch (mode) {
                case "PRESS_SHIFT_KEY", "START_SNEAKING" -> Boolean.TRUE;
                case "RELEASE_SHIFT_KEY", "STOP_SNEAKING" -> Boolean.FALSE;
                default -> null;
            };
            // Retain this as a version-compatible fallback. Minecraft 1.21.11
            // reports the actual shift edge through PlayerInput below.
            if (sneaking != null) owner.captureSneakState(client, sneaking);
            return;
        }
        if (packet instanceof PlayerInputC2SPacket input) {
            owner.captureSneakState(client, input.input().sneak());
            return;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket block) {
            // Legacy PKListener installs the swing-suppression marker for a
            // RIGHT_CLICK_BLOCK event before it checks which hand caused it.
            owner.rightClickBlockUntilTick = owner.clientTick + 2;
            if (block.getHand() == Hand.MAIN_HAND) {
                owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_BLOCK);
            }
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket item) {
            if (item.getHand() == Hand.MAIN_HAND && owner.rightClickBlockUntilTick <= owner.clientTick) {
                owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK);
            }
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket entity) {
            entity.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override
                public void interact(Hand hand) {
                    // PK's legacy listener is PlayerInteractAtEntityEvent, not
                    // the broader PlayerInteractEntityEvent. An INTERACT
                    // packet therefore is not a bending input on Paper.
                }

                @Override
                public void interactAt(Hand hand, Vec3d pos) {
                    if (hand == Hand.MAIN_HAND) {
                        owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY);
                    } else {
                        ExactPredictionRuntime.prepareOffHandRightClickEntity();
                    }
                }

                @Override
                public void attack() {
                }
            });
            return;
        }
    }

    public static void queueExactHitClaim(final long clientActionSequence,
                                   final long serverActionSequence,
                                   final String ability,
                                   final UUID targetUuid,
                                   final int targetEntityId,
                                   final double contactX,
                                   final double contactY,
                                   final double contactZ) {
        final PredictionClient owner = PredictionClient.instance();
        if (!owner.active || owner.sessionId == null || clientActionSequence <= 0L
                || targetUuid == null || ability == null || ability.isBlank()
                || !finite(contactX, contactY, contactZ)) return;
        final PendingHitClaim claim = new PendingHitClaim(clientActionSequence,
                Math.max(0L, serverActionSequence), owner.clientTick, targetUuid,
                targetEntityId, ability, contactX, contactY, contactZ);
        if (owner.currentNativeInputPacket != null) {
            owner.pendingHitClaims.add(claim);
        } else {
            owner.sendHitClaim(claim);
        }
    }
}
