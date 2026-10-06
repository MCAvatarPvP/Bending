package com.projectkorra.projectkorra.fabric.prediction.protocol;

import com.projectkorra.projectkorra.prediction.rollback.RollbackBootstrapPacket;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction.*;

/** Fabric uses the exact bounded bytes and direction rules used by Paper's plugin channels. */
public final class RollbackBootstrapPayloads {
    private RollbackBootstrapPayloads() { }
    public record ToServer(RollbackBootstrapPacket.Message message) implements CustomPayload {
        public static final Id<ToServer> ID = new Id<>(Identifier.of(RollbackBootstrapPacket.CLIENT_CHANNEL));
        public static final PacketCodec<RegistryByteBuf, ToServer> CODEC = PacketCodec.of(ToServer::write, ToServer::read);
        public ToServer { RollbackBootstrapPacket.requireDirection(message, CLIENT_TO_SERVER); }
        private void write(RegistryByteBuf buffer) { buffer.writeBytes(RollbackBootstrapPacket.encode(message, CLIENT_TO_SERVER)); }
        private static ToServer read(RegistryByteBuf buffer) { return new ToServer(RollbackBootstrapPacket.decode(bytes(buffer, RollbackBootstrapPacket.MAXIMUM_REPLY_BYTES), CLIENT_TO_SERVER)); }
        @Override public Id<ToServer> getId() { return ID; }
    }
    public record ToClient(RollbackBootstrapPacket.Message message) implements CustomPayload {
        public static final Id<ToClient> ID = new Id<>(Identifier.of(RollbackBootstrapPacket.SERVER_CHANNEL));
        public static final PacketCodec<RegistryByteBuf, ToClient> CODEC = PacketCodec.of(ToClient::write, ToClient::read);
        public ToClient { RollbackBootstrapPacket.requireDirection(message, SERVER_TO_CLIENT); }
        private void write(RegistryByteBuf buffer) { buffer.writeBytes(RollbackBootstrapPacket.encode(message, SERVER_TO_CLIENT)); }
        private static ToClient read(RegistryByteBuf buffer) { return new ToClient(RollbackBootstrapPacket.decode(bytes(buffer, RollbackBootstrapPacket.MAXIMUM_BYTES), SERVER_TO_CLIENT)); }
        @Override public Id<ToClient> getId() { return ID; }
    }
    static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(ToServer.ID, ToServer.CODEC);
        PayloadTypeRegistry.playS2C().register(ToClient.ID, ToClient.CODEC);
    }
    private static byte[] bytes(RegistryByteBuf buffer, int maximum) {
        if (buffer.readableBytes() > maximum) throw new IllegalArgumentException("Bootstrap packet byte budget");
        byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes;
    }
}
