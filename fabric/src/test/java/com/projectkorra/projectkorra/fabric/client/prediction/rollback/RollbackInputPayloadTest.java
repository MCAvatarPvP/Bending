package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.prediction.rollback.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RollbackInputPayloadTest {
    @Test void fabricPayloadMatchesPaperCommonBytesInBothDirectionsWithoutExtraFraming() {
        var packet = new RollbackInputPacket(UUID.randomUUID(), 123, new RollbackMovementInput(-1, 1, true, 90, -35), true,
                List.of(new RollbackInputPacket.Edge(7, RollbackInputActions.Kind.SWING, -1, 95, -30)));
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            PredictionPayloads.RollbackInput.CODEC.encode(buffer, new PredictionPayloads.RollbackInput(packet));
            byte[] written = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), written);
            assertArrayEquals(packet.encode(), written);
            assertEquals(packet, RollbackInputPacket.decode(written));
            assertEquals(packet, PredictionPayloads.RollbackInput.CODEC.decode(buffer).input());
            assertEquals(0, buffer.readableBytes());
            assertEquals(RollbackInputPacket.CHANNEL, PredictionPayloads.RollbackInput.ID.id().toString());
            buffer.clear();
            buffer.writeBytes(packet.encode());
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> PredictionPayloads.RollbackInput.CODEC.decode(buffer));
            buffer.clear();
            buffer.writeZero(RollbackInputPacket.MAXIMUM_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> PredictionPayloads.RollbackInput.CODEC.decode(buffer));
            assertEquals(0, buffer.readerIndex()); // Reject before allocating/copying an oversized payload.
        } finally { buffer.release(); }
    }
}
