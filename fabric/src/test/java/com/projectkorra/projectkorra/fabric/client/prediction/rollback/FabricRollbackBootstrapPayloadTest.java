package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.prediction.protocol.RollbackBootstrapPayloads;
import com.projectkorra.projectkorra.prediction.rollback.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackBootstrapPayloadTest {
    @Test void bootstrapPayloadsAreExactlyPaperCompatibleAndApplyBudgetsBeforeAllocation() {
        UUID session = UUID.randomUUID(), challenge = UUID.randomUUID();
        byte[] data = new byte[RollbackBootstrapPacket.DATA_BYTES]; var hash = RollbackBootstrapData.fingerprint(data);
        var offer = new RollbackBootstrapPacket.Offer(session, challenge, RollbackStartNegotiation.VERSION, "12".repeat(32), hash, data.length);
        var ready = new RollbackBootstrapPacket.Ready(session, challenge, hash);
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            for (var message : List.<RollbackBootstrapPacket.Message>of(offer, new RollbackBootstrapPacket.Part(session, challenge, 0, data),
                    new RollbackBootstrapPacket.Cancel(session, challenge, RollbackStartPacket.AbortReason.TIMEOUT))) {
                buffer.clear(); RollbackBootstrapPayloads.ToClient.CODEC.encode(buffer, new RollbackBootstrapPayloads.ToClient(message));
                byte[] encoded = new byte[buffer.readableBytes()]; buffer.getBytes(0, encoded);
                assertArrayEquals(RollbackBootstrapPacket.encode(message, SERVER_TO_CLIENT), encoded);
                assertEquals(message, RollbackBootstrapPayloads.ToClient.CODEC.decode(buffer).message());
            }
            buffer.clear(); RollbackBootstrapPayloads.ToServer.CODEC.encode(buffer, new RollbackBootstrapPayloads.ToServer(ready));
            assertEquals(ready, RollbackBootstrapPayloads.ToServer.CODEC.decode(buffer).message());
            assertThrows(IllegalArgumentException.class, () -> new RollbackBootstrapPayloads.ToServer(offer));
            assertThrows(IllegalArgumentException.class, () -> new RollbackBootstrapPayloads.ToClient(ready));
            buffer.clear(); buffer.writeZero(RollbackBootstrapPacket.MAXIMUM_REPLY_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapPayloads.ToServer.CODEC.decode(buffer)); assertEquals(0, buffer.readerIndex());
        } finally { buffer.release(); }
    }
}
