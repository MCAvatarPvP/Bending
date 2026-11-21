package com.projectkorra.projectkorra.fabric.prediction.protocol;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction.*;

/** Typed wrappers over the same bounded control bytes used by Paper. */
public final class RollbackStartPayloads {
    private RollbackStartPayloads() { }

    public record ToServer(RollbackStartPacket.Message message) implements CustomPayload {
        public static final Id<ToServer> ID = new Id<>(Identifier.of(RollbackStartPacket.CLIENT_CHANNEL));
        public static final PacketCodec<RegistryByteBuf, ToServer> CODEC = PacketCodec.of(ToServer::write, ToServer::read);
        public ToServer { RollbackStartPacket.requireDirection(message, CLIENT_TO_SERVER); }
        private void write(RegistryByteBuf buffer) { buffer.writeBytes(RollbackStartPacket.encode(message, CLIENT_TO_SERVER)); }
        private static ToServer read(RegistryByteBuf buffer) { return new ToServer(RollbackStartPacket.decode(bytes(buffer), CLIENT_TO_SERVER)); }
        @Override public Id<ToServer> getId() { return ID; }
    }

    public record ToClient(RollbackStartPacket.Message message) implements CustomPayload {
        public static final Id<ToClient> ID = new Id<>(Identifier.of(RollbackStartPacket.SERVER_CHANNEL));
        public static final PacketCodec<RegistryByteBuf, ToClient> CODEC = PacketCodec.of(ToClient::write, ToClient::read);
        public ToClient { RollbackStartPacket.requireDirection(message, SERVER_TO_CLIENT); }
        private void write(RegistryByteBuf buffer) { buffer.writeBytes(RollbackStartPacket.encode(message, SERVER_TO_CLIENT)); }
        private static ToClient read(RegistryByteBuf buffer) { return new ToClient(RollbackStartPacket.decode(bytes(buffer), SERVER_TO_CLIENT)); }
        @Override public Id<ToClient> getId() { return ID; }
    }

    static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(ToServer.ID, ToServer.CODEC);
        PayloadTypeRegistry.playS2C().register(ToClient.ID, ToClient.CODEC);
    }

    private static byte[] bytes(RegistryByteBuf buffer) {
        int size = buffer.readableBytes();
        if (size > RollbackStartPacket.MAXIMUM_BYTES) throw new IllegalArgumentException("Rollback start byte budget");
        byte[] bytes = new byte[size];
        buffer.readBytes(bytes);
        return bytes;
    }
}
