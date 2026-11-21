package dev.lrxh.neptune.feature.latency;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStartNegotiation;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RollbackStartLatencyContractTest {
    @Test void negotiationWaitsForBothRealQueuesAndConnectionLossInvalidatesTheWholeStart() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2), id = UUID.randomUUID();
        try (var first = new Fixture(); var second = new Fixture()) {
            var transports = Map.of(a, first.connection, b, second.connection);
            var suspensions = new LatencySuspensions(transports::get);
            first.handler.setDelayNanos(TimeUnit.MILLISECONDS.toNanos(100));
            second.handler.setDelayNanos(TimeUnit.MILLISECONDS.toNanos(50));
            first.channel.writeInbound("old-action");
            second.channel.writeAndFlush("old-snapshot");
            var lease = suspensions.suspend(id, List.of(a, b));
            var peers = List.of(new RollbackStartNegotiation.PreparedPeer(a, first.connection, RollbackStartNegotiation.VERSION, true, "a".repeat(64)),
                    new RollbackStartNegotiation.PreparedPeer(b, second.connection, RollbackStartNegotiation.VERSION, true, "a".repeat(64)));
            assertThrows(IllegalStateException.class, () -> new RollbackStartNegotiation(id, UUID.randomUUID(), "a".repeat(64),
                    peers, 100, new RollbackStartNegotiation.Limits(80, 20, 2), lease::ready));
            first.channel.runPendingTasks();
            assertFalse(lease.ready());
            second.channel.runPendingTasks();
            assertEquals("old-action", first.channel.readInbound());
            assertEquals("old-snapshot", second.channel.readOutbound());
            lease.requireReady();
            var negotiation = new RollbackStartNegotiation(id, UUID.randomUUID(), "a".repeat(64),
                    peers, 100, new RollbackStartNegotiation.Limits(80, 20, 2), lease::ready);
            assertTrue(negotiation.poll(101));
            second.channel.close();
            assertFalse(negotiation.poll(102));
            assertEquals(RollbackStartNegotiation.Phase.ABORTED, negotiation.phase());
            assertTrue(suspensions.suspended(a)); assertTrue(suspensions.suspended(b));
            lease.close();
            assertFalse(suspensions.suspended(a)); assertFalse(suspensions.suspended(b));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final PacketDelayHandler handler = new PacketDelayHandler(new PacketDelayHandler.KeepAliveAccess() {
            @Override public Long outboundId(Object packet) { return null; }
            @Override public Long inboundId(Object packet) { return null; }
        }, () -> 0L);
        final EmbeddedChannel channel = new EmbeddedChannel(handler);
        final LatencyConnection connection = new LatencyConnection(channel, handler);
        Fixture() { channel.freezeTime(); }
        @Override public void close() { channel.finishAndReleaseAll(); }
    }
}
