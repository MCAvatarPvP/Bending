package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

/** Private listener boundary. Sending serializes an owned output; no network connection exists. */
public final class PaperRollbackConnection implements RollbackStateCell<Boolean> {
    // These native codecs use only primitive packet data and the supplied registry.
    // Other codecs (notably items/chat) still reach live sanitizers or registries.
    private static final java.util.Map<Class<?>, net.minecraft.network.protocol.PacketType<?>> AUDITED_PACKETS = java.util.Map.ofEntries(
            java.util.Map.entry(ClientboundSetHealthPacket.class, GamePacketTypes.CLIENTBOUND_SET_HEALTH),
            java.util.Map.entry(ClientboundUpdateAttributesPacket.class, GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES),
            java.util.Map.entry(ClientboundHurtAnimationPacket.class, GamePacketTypes.CLIENTBOUND_HURT_ANIMATION),
            java.util.Map.entry(ClientboundUpdateMobEffectPacket.class, GamePacketTypes.CLIENTBOUND_UPDATE_MOB_EFFECT),
            java.util.Map.entry(ClientboundRemoveMobEffectPacket.class, GamePacketTypes.CLIENTBOUND_REMOVE_MOB_EFFECT),
            java.util.Map.entry(ClientboundSoundPacket.class, GamePacketTypes.CLIENTBOUND_SOUND),
            java.util.Map.entry(ClientboundEntityEventPacket.class, GamePacketTypes.CLIENTBOUND_ENTITY_EVENT),
            java.util.Map.entry(ClientboundSetEntityMotionPacket.class, GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION),
            java.util.Map.entry(ClientboundSetExperiencePacket.class, GamePacketTypes.CLIENTBOUND_SET_EXPERIENCE),
            java.util.Map.entry(ClientboundDamageEventPacket.class, GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT),
            java.util.Map.entry(ClientboundPlayerCombatEnterPacket.class, GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_ENTER),
            java.util.Map.entry(ClientboundPlayerCombatEndPacket.class, GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_END),
            java.util.Map.entry(ClientboundPlayerAbilitiesPacket.class, GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES),
            java.util.Map.entry(ClientboundCooldownPacket.class, GamePacketTypes.CLIENTBOUND_COOLDOWN));
    /** Includes the play-protocol packet id, encoded against the session's captured registries. */
    public record PacketOutput(UUID target, String type, String payload) implements PaperRollbackCombatAccess.Output { }
    static boolean auditedPacket(Class<?> type) { return AUDITED_PACKETS.containsKey(type); }
    static boolean auditedType(net.minecraft.network.protocol.PacketType<?> type) { return AUDITED_PACKETS.containsValue(type); }
    private final PaperRollbackWorldAccess world;
    private final ServerPlayer player;
    private final ServerGamePacketListenerImpl listener;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private final PaperRollbackPacketData packetData;
    private boolean loaded = true;

    PaperRollbackConnection(PaperRollbackWorldAccess world, ServerPlayer player) {
        if (RollbackClock.active()) throw new IllegalStateException("Construct private listener before replay");
        this.world = world;
        this.player = player;
        codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(world.world().registryAccess())).codec();
        packetData = new PaperRollbackPacketData(world.world().registryAccess());
        listener = RollbackNativeQueryShell.create(ServerGamePacketListenerImpl.class)
                .query(ServerGamePacketListenerImpl::getPlayer, null, args -> { check(); return player; })
                .query(ServerGamePacketListenerImpl::hasClientLoaded, false, args -> { check(); return loaded; })
                // An active replica has no pending network load timeout. The native
                // timer stays zero; loading transitions are supplied by the session.
                .nativeAction(ServerGamePacketListenerImpl::tickClientLoadTimeout, args -> check())
                .outputQuery(value -> value.send(null), args -> send((Packet<?>) args[0]))
                .outputQuery(value -> value.send(null, null), args -> {
                    check();
                    if (args[1] != null) throw new IllegalStateException("Packet completion callbacks require a finalized delivery adapter");
                    send((Packet<?>) args[0]);
                }).instance();
        listener.player = player;
    }

    ServerGamePacketListenerImpl listener() { return listener; }
    void loaded(boolean value) { check(); loaded = value; }

    @SuppressWarnings("unchecked")
    private void send(Packet<?> packet) {
        check();
        if (packet == null) return;
        if (!AUDITED_PACKETS.containsKey(packet.getClass())) {
            var detached = packetData.capture(packet);
            if (detached != null) { world.output(new PaperRollbackPacketData.Direct(player.getUUID(), detached)); return; }
            throw new IllegalStateException("Packet codec needs a private-state audit: " + packet.type());
        }
        var buffer = Unpooled.buffer(256, 1_048_576);
        try {
            codec.encode(buffer, (Packet<? super ClientGamePacketListener>) packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            world.output(new PacketOutput(player.getUUID(), packet.type().id().toString(), Base64.getEncoder().encodeToString(bytes)));
        } finally { buffer.release(); }
    }

    void check() {
        if (player.level() != world.world() || player.connection != listener || listener.player != player || !world.ownsPlayer(player)) {
            throw new IllegalStateException("Private player connection ownership changed");
        }
    }

    @Override public Boolean captureRollbackState() { check(); return loaded; }
    @Override public void restoreRollbackState(Boolean value) { check(); loaded = Objects.requireNonNull(value); }
    @Override public List<?> rollbackReferences() { check(); return List.of(world); }
}
