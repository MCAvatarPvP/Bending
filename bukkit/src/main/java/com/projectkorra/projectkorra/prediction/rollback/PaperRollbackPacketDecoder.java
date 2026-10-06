package com.projectkorra.projectkorra.prediction.rollback;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import java.util.Base64;
import java.util.Objects;

/** Reconstructs trusted server-internal journal packets; never accepts client wire input or sends packets. */
final class PaperRollbackPacketDecoder {
    private static final int MAXIMUM_BYTES = 1_048_576;
    private final java.util.Map<Integer, String> packetTypes;
    private final Thread owner = Thread.currentThread();
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;

    PaperRollbackPacketDecoder(RegistryAccess registries) {
        requireLive();
        var types = new java.util.HashMap<Integer, String>();
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (PaperRollbackConnection.auditedType(type)) types.put(id, type.id().toString());
        });
        packetTypes = java.util.Map.copyOf(types);
        codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(Objects.requireNonNull(registries))).codec();
    }

    Packet<?> decode(PaperRollbackConnection.PacketOutput output) {
        requireLive(); Objects.requireNonNull(output, "packet output");
        Objects.requireNonNull(output.target(), "packet recipient");
        Objects.requireNonNull(output.type(), "packet type");
        String payload = Objects.requireNonNull(output.payload(), "packet payload");
        if (payload.length() > ((MAXIMUM_BYTES + 2) / 3) * 4) throw new IllegalArgumentException("Packet output exceeds byte budget");
        byte[] data = Base64.getDecoder().decode(payload);
        if (data.length == 0 || data.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Packet output byte budget");
        var bytes = Unpooled.wrappedBuffer(data);
        try {
            int start = bytes.readerIndex();
            int id = net.minecraft.network.VarInt.read(bytes);
            if (!output.type().equals(packetTypes.get(id)))
                throw new IllegalArgumentException("Packet output ID/type is not audited");
            bytes.readerIndex(start);
            var packet = codec.decode(bytes);
            if (bytes.isReadable() || !PaperRollbackConnection.auditedPacket(packet.getClass())
                    || !output.type().equals(packet.type().id().toString()))
                throw new IllegalArgumentException("Packet output type or trailing data mismatch");
            return packet;
        } finally { bytes.release(); }
    }

    private void requireLive() {
        if (Thread.currentThread() != owner || RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Decode delivery packets on the owning thread outside replay");
    }
}
