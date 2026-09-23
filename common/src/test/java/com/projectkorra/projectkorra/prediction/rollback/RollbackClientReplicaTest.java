package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackEngine.Submission.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackClientReplicaTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), SESSION = new UUID(0, 3);
    private static final RollbackPlayerInput IDLE = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
    private static final Map<UUID, RollbackPlayerInput> INITIAL = Map.of(A, IDLE, B, IDLE);
    private static RollbackInputPacket packet(long tick, float forward) {
        return new RollbackInputPacket(SESSION, tick, new RollbackMovementInput(0, forward, false, 0, 0), false, List.of());
    }
    private static RollbackEngine.Limits limits(int history) { return new RollbackEngine.Limits(history, 2, 20, 50_000_000); }

    @Test void clientCannotAgeOutUnconfirmedEffectsOrOverrideAuthorityInputs() {
        var server = new RollbackEngine<>(new Simulation(), INITIAL, limits(2), 1000, 1000);
        assertEquals(ACCEPTED, server.submit(A, 1, packet(1, 1).playerInput(A, 99)));
        assertThrows(IllegalStateException.class, () -> server.correct(A, 1, IDLE));
        assertThrows(IllegalStateException.class, () -> server.confirm(0));
        var client = RollbackEngine.replica(new Simulation(), INITIAL, limits(2), 1000, 1000);
        client.submit(A, 1, packet(1, 1).playerInput(A, 99));
        assertTrue(client.advance().finalizedEffects().isEmpty());
        assertTrue(client.advance().finalizedEffects().isEmpty());
        assertThrows(IllegalStateException.class, client::advance);
        assertEquals(0, client.confirmed().tick()); assertFalse(client.diagnostics().failed());
        client.correct(A, 1, IDLE);
        client.correct(A, 2, IDLE);
        assertEquals(List.of(new RollbackEngine.Effect<>(1, 0, 0)), client.confirm(1).finalizedEffects());
        assertEquals(0, client.head().state()); assertEquals(1, client.confirmed().tick());
        assertTrue(client.confirm(1).finalizedEffects().isEmpty());
        assertEquals(FINALIZED, client.correct(A, 1, packet(1, 1).playerInput(A, 99)));
        assertDoesNotThrow(client::advance);
        assertThrows(IllegalArgumentException.class, () -> new RollbackSession<>(SESSION, 99, client,
                List.of(new RollbackSession.Peer(A, new Object(), 0), new RollbackSession.Peer(B, new Object(), 0))));
    }

    @Test void unreceivedLocalIntentStaysPredictedUntilTheServerFinalizesItAway() {
        var f = new Fixture();
        f.client.propose(1, packet(1, 1).playerInput(A, 99));
        f.client.advance(); f.client.advance();
        for (int tick = 1; tick <= 2; tick++) { f.server.advance(); f.client.receive(f.server.publish()); }
        assertEquals(2, f.clientEngine.head().state());
        assertEquals(0, f.clientEngine.confirmed().tick());
        var serverUpdate = f.server.advance();
        var update = f.server.publish();
        var corrected = f.client.receive(update);
        assertEquals(1, corrected.replayedFrom()); assertEquals(0, corrected.head().state());
        assertEquals(serverUpdate.finalizedEffects(), corrected.finalizedEffects());
        assertEquals(1, corrected.confirmed().tick());
        assertNull(f.client.receive(update));
        assertEquals(3, f.client.authoritativeHead());
    }

    @Test void acceptedLateLocalIntentAndRemoteCorrectionsReplayWithoutLosingReceipts() {
        var f = new Fixture();
        var local = packet(1, 1);
        assertEquals(ACCEPTED, f.client.propose(1, local.playerInput(A, 99)));
        assertEquals(DUPLICATE, f.client.propose(1, local.playerInput(A, 99)));
        assertEquals(CONFLICTING_INPUT, f.client.propose(1, IDLE));
        for (int tick = 1; tick <= 2; tick++) { f.client.advance(); f.server.advance(); f.client.receive(f.server.publish()); }
        assertEquals(RollbackSession.Status.ACCEPTED, f.server.receive(f.a, local).status());
        f.server.receive(f.b, packet(1, -1));
        var authority = f.server.publish();
        assertEquals(1, authority.revision()); assertEquals(1, authority.firstTick());
        assertEquals(List.of(1L), authority.receivedTicks().get(A));
        var corrected = f.client.receive(authority);
        assertEquals(f.serverEngine.head().state(), corrected.head().state());
        assertEquals(1, corrected.replayedFrom());
        assertEquals(List.of(1L), f.server.acknowledgement(f.a).receivedTicks());
        assertFalse(f.server.closed());
    }

    @Test void sequentialPublicationsCatchUpABehindClientAndFinalizeEveryEffectOnce() {
        var f = new Fixture(3);
        var publications = new ArrayList<RollbackAuthorityUpdate>();
        var expected = new ArrayList<RollbackEngine.Effect<Integer>>();
        for (int tick = 1; tick <= 10; tick++) {
            f.server.receive(f.b, packet(tick, tick % 2 == 0 ? 1 : -1));
            expected.addAll(f.server.advance().finalizedEffects());
            publications.add(f.server.publish());
        }
        var actual = new ArrayList<RollbackEngine.Effect<Integer>>();
        for (var publication : publications) actual.addAll(f.client.receive(publication).finalizedEffects());
        assertEquals(expected, actual); assertEquals(f.serverEngine.head().state(), f.clientEngine.head().state());
        assertEquals(8, f.clientEngine.confirmed().tick()); assertEquals(3, f.clientEngine.diagnostics().snapshots());
        assertNull(f.client.receive(publications.getFirst()));
    }

    @Test void staleSessionsAreIgnoredButPublicationGapsAndInputRewritesStopTheReplica() {
        var f = new Fixture(); f.server.advance(); var first = f.server.publish();
        var stale = new RollbackAuthorityUpdate(UUID.randomUUID(), first.publication(), first.revision(), first.headTick(),
                first.finalizedTick(), first.firstTick(), first.frames(), first.receivedTicks());
        assertNull(f.client.receive(stale)); assertEquals(0, f.clientEngine.head().tick());
        f.server.advance(); var second = f.server.publish();
        assertThrows(IllegalArgumentException.class, () -> f.client.receive(second)); assertTrue(f.client.failed());
        assertThrows(IllegalStateException.class, () -> f.client.receive(first));

        var changed = new Fixture(); changed.server.receive(changed.a, packet(1, 1));
        changed.client.propose(1, IDLE);
        changed.server.advance(); var rewrite = changed.server.publish();
        assertThrows(IllegalArgumentException.class, () -> changed.client.receive(rewrite));
        assertEquals(0, changed.clientEngine.head().tick(), "Validate before advancing any client simulation");
    }

    @Test void authorityCannotForgetAcceptedInputAndMalformedWindowsAreRejected() {
        var f = new Fixture(); f.server.receive(f.b, packet(1, 1)); f.server.advance();
        var first = f.server.publish(); f.client.receive(first);
        var forgotten = new RollbackAuthorityUpdate(SESSION, 2, first.revision(), 1, 0, 1, first.frames(), Map.of(A, List.of(), B, List.of()));
        assertThrows(IllegalArgumentException.class, () -> f.client.receive(forgotten));
        assertThrows(IllegalArgumentException.class, () -> new RollbackAuthorityUpdate(SESSION, 1, 0, 2, 0, 1,
                List.of(INITIAL), Map.of(A, List.of(), B, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new RollbackAuthorityUpdate(SESSION, 1, 0, 1, 0, 1,
                List.of(INITIAL), Map.of(A, List.of(2L, 1L), B, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new RollbackAuthorityUpdate(SESSION, 1, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                List.of(INITIAL), Map.of(A, List.of(), B, List.of())));
    }

    @Test void authorityFailsInsteadOfPublishingAnIncompletePrunedWindow() {
        var f = new Fixture();
        for (int tick = 1; tick <= 5; tick++) f.server.advance();
        assertThrows(IllegalArgumentException.class, f.server::publish);
        assertTrue(f.server.closed());
    }

    private static final class Fixture {
        final Object a = new Object(), b = new Object();
        final RollbackEngine<Integer, RollbackPlayerInput, Integer> serverEngine = new RollbackEngine<>(new Simulation(), INITIAL, limits(2), 1000, 1000);
        final RollbackSession<Integer, Integer> server = new RollbackSession<>(SESSION, 99, serverEngine,
                List.of(new RollbackSession.Peer(A, a, 0), new RollbackSession.Peer(B, b, 0)));
        final RollbackEngine<Integer, RollbackPlayerInput, Integer> clientEngine;
        final RollbackClientReplica<Integer, Integer> client;
        Fixture() { this(8); }
        Fixture(int capacity) {
            clientEngine = RollbackEngine.replica(new Simulation(), INITIAL, limits(capacity), 1000, 1000);
            client = new RollbackClientReplica<>(SESSION, A, clientEngine);
        }
    }
    private static final class Simulation implements RollbackSimulation<Integer, RollbackPlayerInput, Integer> {
        int sum;
        @Override public Integer snapshot() { return sum; }
        @Override public void restore(Integer snapshot) { sum = snapshot; }
        @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
        @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<Integer> effects) {
            inputs.values().forEach(input -> sum += Math.round(input.movement().forward()));
            effects.emit(sum);
        }
    }
}
