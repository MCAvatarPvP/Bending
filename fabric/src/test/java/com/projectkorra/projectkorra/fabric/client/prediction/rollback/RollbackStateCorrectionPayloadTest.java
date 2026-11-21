package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.prediction.protocol.RollbackStateCorrectionPayload;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCorrection;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCorrectionChunk;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RollbackStateCorrectionPayloadTest {
    @Test void fabricDecodesPaperChunksWithoutASecondLengthPrefix() {
        byte[] state = new byte[RollbackStateCorrectionChunk.DATA_BYTES + 31];
        new Random(101).nextBytes(state);
        var correction = new RollbackStateCorrection(UUID.randomUUID(), 10, 2, 9, "ab".repeat(32), state);
        var chunks = RollbackStateCorrectionChunk.split(correction);
        var assembler = new RollbackStateCorrectionChunk.Assembler(correction.session(), correction.definitions(),
                state.length + RollbackStateCorrection.HEADER_BYTES, 0, 20);
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            assertEquals(2, chunks.size());
            for (var chunk : chunks.reversed()) {
                buffer.clear();
                buffer.writeBytes(chunk.encode());
                var received = RollbackStateCorrectionPayload.CODEC.decode(buffer);
                assertEquals(RollbackStateCorrectionChunk.CHANNEL, received.getId().id().toString());
                assertEquals(0, buffer.readableBytes());
                buffer.clear();
                RollbackStateCorrectionPayload.CODEC.encode(buffer, received);
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                assertArrayEquals(chunk.encode(), bytes);
                assertEquals(chunk.index() == 0 ? correction : null, assembler.receive(received.chunk(), 1));
            }
            buffer.clear();
            buffer.writeZero(RollbackStateCorrectionChunk.MAXIMUM_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> RollbackStateCorrectionPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }
}
