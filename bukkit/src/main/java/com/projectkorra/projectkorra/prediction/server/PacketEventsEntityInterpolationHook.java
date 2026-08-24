package com.projectkorra.projectkorra.prediction.server;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/** Captures the final outbound movement stream without coupling collision code to PacketEvents. */
public final class PacketEventsEntityInterpolationHook extends PacketListenerAbstract {
    private final ServerEntityInterpolation interpolation;

    private PacketEventsEntityInterpolationHook(final ServerEntityInterpolation interpolation) {
        super(PacketListenerPriority.MONITOR);
        this.interpolation = interpolation;
    }

    public static PacketEventsEntityInterpolationHook register(
            final JavaPlugin plugin, final ServerEntityInterpolation interpolation) {
        final PacketEventsEntityInterpolationHook hook =
                new PacketEventsEntityInterpolationHook(interpolation);
        PacketEvents.getAPI().getEventManager().registerListener(hook);
        plugin.getLogger().info("Enabled packet-driven server entity interpolation for ability collisions.");
        return hook;
    }

    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();
        if (event.isCancelled() || !isTrackedPacket(packetType)
                || !(event.getPlayer() instanceof Player viewer)) return;
        final UUID viewerId = viewer.getUniqueId();

        switch (packetType) {
            case PacketType.Play.Server.SPAWN_ENTITY -> {
                final WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
                if (packet.getEntityType() != EntityTypes.PLAYER || packet.getUUID().isEmpty()) return;
                spawn(viewerId, packet.getEntityId(), packet.getUUID().get(), packet.getPosition());
            }
            case PacketType.Play.Server.SPAWN_PLAYER -> {
                final WrapperPlayServerSpawnPlayer packet = new WrapperPlayServerSpawnPlayer(event);
                spawn(viewerId, packet.getEntityId(), packet.getUUID(), packet.getPosition());
            }
            case PacketType.Play.Server.ENTITY_RELATIVE_MOVE -> {
                final WrapperPlayServerEntityRelativeMove packet = new WrapperPlayServerEntityRelativeMove(event);
                if (!this.interpolation.tracksPlayerPacket(viewerId, packet.getEntityId())) return;
                this.interpolation.relativeMove(viewerId, packet.getEntityId(),
                        packet.getDeltaX(), packet.getDeltaY(), packet.getDeltaZ());
            }
            case PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION -> {
                final WrapperPlayServerEntityRelativeMoveAndRotation packet =
                        new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                if (!this.interpolation.tracksPlayerPacket(viewerId, packet.getEntityId())) return;
                this.interpolation.relativeMove(viewerId, packet.getEntityId(),
                        packet.getDeltaX(), packet.getDeltaY(), packet.getDeltaZ());
            }
            case PacketType.Play.Server.ENTITY_POSITION_SYNC -> {
                final WrapperPlayServerEntityPositionSync packet = new WrapperPlayServerEntityPositionSync(event);
                if (!this.interpolation.tracksPlayerPacket(viewerId, packet.getId())) return;
                final Vector3d position = packet.getValues().getPosition();
                this.interpolation.positionSync(viewerId, packet.getId(),
                        position.getX(), position.getY(), position.getZ());
            }
            case PacketType.Play.Server.ENTITY_TELEPORT -> {
                final WrapperPlayServerEntityTeleport packet = new WrapperPlayServerEntityTeleport(event);
                if (!this.interpolation.tracksPlayerPacket(viewerId, packet.getEntityId())) return;
                final Vector3d position = packet.getPosition();
                final RelativeFlag flags = packet.getRelativeFlags();
                this.interpolation.teleport(viewerId, packet.getEntityId(),
                        position.getX(), position.getY(), position.getZ(),
                        flags != null && flags.has(RelativeFlag.X),
                        flags != null && flags.has(RelativeFlag.Y),
                        flags != null && flags.has(RelativeFlag.Z));
            }
            case PacketType.Play.Server.DESTROY_ENTITIES -> this.interpolation.destroy(viewerId,
                    new WrapperPlayServerDestroyEntities(event).getEntityIds());
            default -> {

            }
        }
    }

    private static boolean isTrackedPacket(final PacketTypeCommon type) {
        return type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.SPAWN_PLAYER
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION
                || type == PacketType.Play.Server.ENTITY_POSITION_SYNC
                || type == PacketType.Play.Server.ENTITY_TELEPORT
                || type == PacketType.Play.Server.DESTROY_ENTITIES;
    }

    private void spawn(final UUID viewer, final int entityId, final UUID target,
                       final Vector3d position) {
        this.interpolation.spawn(viewer, entityId, target,
                position.getX(), position.getY(), position.getZ());
    }
}
