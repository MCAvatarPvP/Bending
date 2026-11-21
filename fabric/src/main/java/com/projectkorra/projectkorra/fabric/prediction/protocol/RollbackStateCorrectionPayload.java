package com.projectkorra.projectkorra.fabric.prediction.protocol;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCorrectionChunk;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Objects;

/** Typed S2C envelope over the same bounded bytes emitted by Paper. */
public record RollbackStateCorrectionPayload(RollbackStateCorrectionChunk chunk) implements CustomPayload {
    public static final Id<RollbackStateCorrectionPayload> ID = new Id<>(Identifier.of(RollbackStateCorrectionChunk.CHANNEL));
    public static final PacketCodec<RegistryByteBuf, RollbackStateCorrectionPayload> CODEC = PacketCodec.of(RollbackStateCorrectionPayload::write, RollbackStateCorrectionPayload::read);
    public RollbackStateCorrectionPayload { Objects.requireNonNull(chunk); }
    @Override public Id<RollbackStateCorrectionPayload> getId() { return ID; }
    private void write(RegistryByteBuf buffer) { buffer.writeBytes(chunk.encode()); }
    private static RollbackStateCorrectionPayload read(RegistryByteBuf buffer) {
        if (buffer.readableBytes() > RollbackStateCorrectionChunk.MAXIMUM_BYTES) throw new IllegalArgumentException("State correction payload byte budget");
        byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
        return new RollbackStateCorrectionPayload(RollbackStateCorrectionChunk.decode(bytes));
    }
}
