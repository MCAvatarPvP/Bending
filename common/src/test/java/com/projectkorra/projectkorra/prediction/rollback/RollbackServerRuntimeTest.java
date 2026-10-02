package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartNegotiation.*;

class RollbackServerRuntimeTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), ID = new UUID(0, 10), CHALLENGE = new UUID(0, 11);
    private static final String HASH = "a".repeat(64);
    private static final RollbackPlayerInput EMPTY = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
    private static final RollbackStartServerEndpoint.Failure STOP = new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "test", null);

    @Test void negotiatedAuthorityPublishesBeforeEffectsAndOnceOnlyDefeats() {
        var f = new Fixture(); f.start();
        assertTrue(f.ingress.blocksLegacy(A)); assertTrue(f.ingress.blocksLegacy(B));
        for (int i = 1; i <= 5; i++) f.runtime.tick(122 + i);
        assertEquals(List.of(new RollbackRound.Defeat(ID, 1, B, A)), f.results);
        assertEquals(List.of("hit"), f.effects);
        assertEquals(5, f.published.size());
        assertEquals(List.of("publish", "output", "results"), f.order.subList(0, 3));
        f.runtime.stop(STOP); f.runtime.stop(STOP);
        assertEquals(1, f.restores); assertFalse(f.ingress.blocksLegacy(A)); assertFalse(f.ingress.blocksLegacy(B));
        assertThrows(IllegalStateException.class, () -> f.runtime.tick(128));
    }

    @Test void authenticatedLateDefenceRetractsTheProvisionalHitBeforeDelivery() {
        var f = new Fixture(); f.start(); f.runtime.tick(123);
        assertTrue(f.round.ended()); assertTrue(f.results.isEmpty());
        var packet = new RollbackInputPacket(ID, 5023, new RollbackMovementInput(0, 1, false, 0, 0), false, List.of());
        assertEquals(RollbackSession.Status.ACCEPTED, f.ingress.receive(f.b, packet.encode()).receipt().status());
        for (int i = 2; i <= 5; i++) f.runtime.tick(122 + i);
        assertFalse(f.round.ended()); assertTrue(f.results.isEmpty()); assertTrue(f.effects.isEmpty());
        assertEquals(1, f.published.get(1).firstTick());
        f.runtime.stop(STOP);
    }

    @Test void failedPublicationDoesNotDeliverEffectsOrResultsAndCannotAdvanceAgain() {
        var f = new Fixture(); f.start();
        for (int i = 1; i <= 3; i++) f.runtime.tick(122 + i);
        f.failPublish = true;
        assertThrows(IllegalArgumentException.class, () -> f.runtime.tick(126));
        assertTrue(f.results.isEmpty()); assertTrue(f.effects.isEmpty());
        assertThrows(IllegalStateException.class, () -> f.runtime.tick(127));
        assertTrue(f.ingress.blocksLegacy(A));
        f.runtime.stop(STOP); assertFalse(f.ingress.blocksLegacy(A));
    }

    @Test void partialOutputFailureIsNeverRetriedOrFollowedByMatchResults() {
        var f = new Fixture(); f.start(); f.failOutput = true;
        assertThrows(IllegalArgumentException.class, () -> f.runtime.tick(123));
        assertEquals(List.of("publish", "output"), f.order);
        assertThrows(IllegalStateException.class, () -> f.runtime.tick(124));
        f.runtime.stop(STOP); assertEquals(1, f.restores);
    }

    @Test void failedLiveRestorationKeepsBothPlayersGatedUntilRetrySucceeds() {
        var f = new Fixture(); f.start(); f.failRestore = true;
        assertThrows(IllegalArgumentException.class, () -> f.ingress.stopPlayer(A, RollbackIngress.StopReason.CONNECTION_CHANGED));
        assertTrue(f.ingress.blocksLegacy(A)); assertTrue(f.ingress.blocksLegacy(B));
        assertThrows(IllegalStateException.class, () -> f.runtime.tick(123));
        f.failRestore = false; f.runtime.stop(STOP);
        assertEquals(2, f.restores); assertFalse(f.ingress.blocksLegacy(A)); assertFalse(f.ingress.blocksLegacy(B));
    }

    @Test void shutdownCannotReleasePlayersWhoseNativeRestorationFailed() {
        var f = new Fixture(); f.start(); f.failRestore = true;
        assertThrows(IllegalArgumentException.class, f.ingress::shutdown);
        assertTrue(f.ingress.blocksLegacy(A)); assertTrue(f.ingress.blocksLegacy(B));
        f.ingress.shutdown(); // An already-stopping registration still needs native repair.
        assertTrue(f.ingress.blocksLegacy(A));
        f.failRestore = false; f.runtime.stop(STOP);
        assertFalse(f.ingress.blocksLegacy(A)); assertFalse(f.ingress.blocksLegacy(B));
    }

    @Test void stopDuringPublicationPreventsAnyFurtherTickDelivery() {
        var f = new Fixture(); f.start(); f.stopOnPublish = true;
        f.runtime.tick(123);
        assertEquals(List.of("publish"), f.order); assertEquals(1, f.restores);
        assertFalse(f.ingress.blocksLegacy(A)); assertTrue(f.results.isEmpty());
    }

    @Test void skippedTicksAndForeignNegotiationFailWithoutRunningCombat() {
        var f = new Fixture(); f.start();
        assertThrows(IllegalStateException.class, () -> f.runtime.tick(124));
        assertEquals(0, f.engine.diagnostics().tick()); f.runtime.stop(STOP);
        var g = new Fixture();
        var foreign = new RollbackStartNegotiation(UUID.randomUUID(), CHALLENGE, HASH, g.peers(), 100, new Limits(80, 20, 2), () -> true);
        assertThrows(IllegalArgumentException.class, () -> g.runtime.start(foreign, 122));
        g.runtime.stop(STOP); assertEquals(1, g.restores);
    }

    private static final class Fixture {
        final Object a = new Object(), b = new Object();
        final RollbackIngress ingress = new RollbackIngress(20);
        final RollbackRound round = new RollbackRound(ID, Map.of(A, A, B, B));
        final List<String> order = new ArrayList<>(), effects = new ArrayList<>();
        final List<RollbackRound.Defeat> results = new ArrayList<>();
        final List<RollbackAuthorityUpdate> published = new ArrayList<>();
        int restores;
        boolean failPublish, failOutput, failRestore, stopOnPublish;
        final RollbackEngine<RollbackRound.Checkpoint, RollbackPlayerInput, String> engine = new RollbackEngine<>(new RollbackSimulation<>() {
            @Override public RollbackRound.Checkpoint snapshot() { return round.snapshot(); }
            @Override public void restore(RollbackRound.Checkpoint state) { round.restore(state); }
            @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> output) {
                round.beginTick(tick);
                if (tick == 1 && inputs.get(B).movement().forward() == 0) {
                    round.damage(B, A, 4, 5, false, false); output.emit("hit");
                }
            }
        }, Map.of(A, EMPTY, B, EMPTY), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1000);
        final RollbackServerRuntime<RollbackRound.Checkpoint, String> runtime = new RollbackServerRuntime<>(ID, 77, engine, new RollbackServerRuntime.Transport() {
            @Override public RollbackIngress.Registration enroll(RollbackSession<?, ?> session, Consumer<RollbackIngress.Registration> stopped) {
                return ingress.enroll(session, stopped);
            }
            @Override public void publish(RollbackSession<?, ?> session) {
                assertFalse(RollbackClock.active()); order.add("publish");
                var packet = session.publish();
                if (failPublish) throw new IllegalArgumentException("send");
                published.add(packet);
                if (stopOnPublish) ingress.stopPlayer(A, RollbackIngress.StopReason.REQUESTED);
            }
        }, new RollbackServerRuntime.Output<>() {
            @Override public void update(RollbackEngine.Update<RollbackRound.Checkpoint, RollbackPlayerInput, String> update) {
                assertFalse(RollbackClock.active()); order.add("output");
                assertEquals(update.confirmed().tick(), published.getLast().finalizedTick());
                if (failOutput) throw new IllegalArgumentException("partial output");
                update.finalizedEffects().forEach(effect -> effects.add(effect.value()));
            }
            @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
                restores++; if (failRestore) throw new IllegalArgumentException("restore");
            }
        }, () -> {
            assertFalse(RollbackClock.active()); order.add("results");
            assertEquals(engine.diagnostics().confirmedTick(), published.getLast().finalizedTick());
            round.finalizeThrough(engine.diagnostics().confirmedTick(), results::add);
        });
        List<PreparedPeer> peers() { return List.of(new PreparedPeer(A, a, VERSION, true, HASH), new PreparedPeer(B, b, VERSION, true, HASH)); }
        void start() {
            var negotiation = new RollbackStartNegotiation(ID, CHALLENGE, HASH, peers(), 100, new Limits(80, 20, 2), () -> true);
            negotiation.receive(a, new ClockReply(ID, CHALLENGE, 1001), 102);
            negotiation.receive(b, new ClockReply(ID, CHALLENGE, 5003), 106);
            var schedule = negotiation.schedule(106);
            negotiation.receive(a, new Scheduled(schedule.get(A), 1007), 108);
            negotiation.receive(b, new Scheduled(schedule.get(B), 5009), 112);
            negotiation.commit(112); runtime.start(negotiation, 122);
        }
    }
}
