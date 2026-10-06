package com.projectkorra.projectkorra.fabric.prediction.protocol;

import com.projectkorra.projectkorra.prediction.rollback.RollbackAuthorityChunk;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Objects;

/** Typed S2C envelope over the same bounded bytes emitted by Paper. */
public record RollbackAuthorityPayload(RollbackAuthorityChunk chunk) implements CustomPayload {
    public static final Id<RollbackAuthorityPayload> ID = new Id<>(Identifier.of(RollbackAuthorityChunk.CHANNEL));
    public static final PacketCodec<RegistryByteBuf, RollbackAuthorityPayload> CODEC = PacketCodec.of(RollbackAuthorityPayload::write, RollbackAuthorityPayload::read);
    public RollbackAuthorityPayload { Objects.requireNonNull(chunk); }
    @Override public Id<RollbackAuthorityPayload> getId() { return ID; }
    private void write(RegistryByteBuf buffer) { buffer.writeBytes(chunk.encode()); }
    private static RollbackAuthorityPayload read(RegistryByteBuf buffer) {
        if (buffer.readableBytes() > RollbackAuthorityChunk.MAXIMUM_BYTES) throw new IllegalArgumentException("Authority payload byte budget");
        byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
        return new RollbackAuthorityPayload(RollbackAuthorityChunk.decode(bytes));
    }
}
