package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.prediction.protocol.RollbackAuthorityPayload;
import com.projectkorra.projectkorra.prediction.rollback.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackAuthorityPayloadTest {
    @Test void nativePayloadUsesPaperBytesWithoutAnExtraLengthPrefixAndEnforcesTheBudget() {
        UUID session = UUID.randomUUID(), player = UUID.randomUUID();
        var edges = new ArrayList<RollbackPlayerInput.Edge>();
        for (int i = 1; i <= 64; i++) edges.add(new RollbackPlayerInput.Edge(
                new RollbackInputActions.Action(i, i * 31, RollbackInputActions.Kind.RIGHT_CLICK, -1), 90, -45));
        var input = new RollbackPlayerInput(new RollbackMovementInput(1, 0, true, 90, -45), true, edges);
        var update = new RollbackAuthorityUpdate(session, 1, 0, 20, 0, 1,
                Collections.nCopies(20, Map.of(player, input)), Map.of(player, List.of(1L, 20L)));
        var chunks = RollbackAuthorityChunk.split(update);
        assertTrue(chunks.size() > 1);
        var assembler = new RollbackAuthorityChunk.Assembler(session, 0, 40);
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            RollbackAuthorityUpdate delivered = null;
            for (var chunk : chunks) {
                buffer.clear();
                RollbackAuthorityPayload.CODEC.encode(buffer, new RollbackAuthorityPayload(chunk));
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(0, bytes);
                assertArrayEquals(chunk.encode(), bytes);
                var payload = RollbackAuthorityPayload.CODEC.decode(buffer);
                assertEquals(RollbackAuthorityChunk.CHANNEL, payload.getId().id().toString());
                assertEquals(chunk, payload.chunk()); assertEquals(0, buffer.readableBytes());
                assertNull(delivered);
                delivered = assembler.receive(payload.chunk(), 1);
            }
            assertEquals(update, delivered);
            buffer.clear(); buffer.writeZero(RollbackAuthorityChunk.MAXIMUM_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readerIndex());
            buffer.clear(); buffer.writeBytes(new byte[] { 0, 0, 0, 1 });
            assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
