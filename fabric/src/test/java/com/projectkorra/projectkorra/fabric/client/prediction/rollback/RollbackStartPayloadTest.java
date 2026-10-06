package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.prediction.protocol.RollbackStartPayloads;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartNegotiation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction.*;

class RollbackStartPayloadTest {
    @Test void controlPayloadsMatchTheCommonCodecAndCannotCrossDirections() {
        UUID session = UUID.randomUUID(), challenge = UUID.randomUUID();
        var schedule = new RollbackStartNegotiation.Schedule(session, challenge, 122, 1022);
        var server = List.<RollbackStartPacket.Message>of(new RollbackStartNegotiation.Probe(session, challenge), schedule,
                new RollbackStartPacket.Commit(schedule), new RollbackStartPacket.Abort(session, challenge, RollbackStartPacket.AbortReason.TIMEOUT));
        var client = List.<RollbackStartPacket.Message>of(new RollbackStartNegotiation.ClockReply(session, challenge, 1001),
                new RollbackStartNegotiation.Scheduled(schedule, 1007),
                new RollbackStartPacket.Abort(session, challenge, RollbackStartPacket.AbortReason.OPT_OUT));
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            for (var message : server) {
                buffer.clear();
                RollbackStartPayloads.ToClient.CODEC.encode(buffer, new RollbackStartPayloads.ToClient(message));
                assertArrayEquals(RollbackStartPacket.encode(message, SERVER_TO_CLIENT), bytes(buffer));
                assertEquals(message, RollbackStartPayloads.ToClient.CODEC.decode(buffer).message());
            }
            for (var message : client) {
                buffer.clear();
                RollbackStartPayloads.ToServer.CODEC.encode(buffer, new RollbackStartPayloads.ToServer(message));
                assertArrayEquals(RollbackStartPacket.encode(message, CLIENT_TO_SERVER), bytes(buffer));
                assertEquals(message, RollbackStartPayloads.ToServer.CODEC.decode(buffer).message());
            }
            assertThrows(IllegalArgumentException.class, () -> new RollbackStartPayloads.ToServer(schedule));
            assertThrows(IllegalArgumentException.class, () -> new RollbackStartPayloads.ToClient(client.getFirst()));
            buffer.clear(); buffer.writeZero(RollbackStartPacket.MAXIMUM_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> RollbackStartPayloads.ToServer.CODEC.decode(buffer));
            assertEquals(0, buffer.readerIndex());
        } finally { buffer.release(); }
    }
    private static byte[] bytes(RegistryByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }
}
