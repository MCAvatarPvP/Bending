package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Drives the start protocol and retains cancellation routing after runtime handoff. */
public final class RollbackStartServerEndpoint {
    public interface Transport {
        boolean current(RollbackStartNegotiation.PreparedPeer peer);
        void send(RollbackStartNegotiation.PreparedPeer peer, RollbackStartPacket.Message message);
    }
    public interface Runtime {
        /** Must call negotiation.start and enroll its session before returning. */
        void start(RollbackStartNegotiation negotiation, long serverTick);
        void tick(long serverTick);
        /** Owns coordinated cleanup; the endpoint does not release Neptune's latency lease. */
        void stop(Failure failure);
    }
    public record Failure(RollbackStartPacket.AbortReason reason, String detail, Throwable cause) { }

    private final Thread owner = Thread.currentThread();
    private final Transport transport;
    private final Map<UUID, Registration> sessions = new LinkedHashMap<>();
    private final Map<UUID, Registration> players = new HashMap<>();
    private final IdentityHashMap<Object, Registration> connections = new IdentityHashMap<>();
    private boolean closed;

    public RollbackStartServerEndpoint(Transport transport) { this.transport = Objects.requireNonNull(transport); }

    public Registration begin(UUID session, UUID challenge, String contentHash,
                               Collection<RollbackStartNegotiation.PreparedPeer> peers, long tick,
                               RollbackStartNegotiation.Limits limits, BooleanSupplier ready, Runtime runtime) {
        checkThread(); Objects.requireNonNull(runtime); Objects.requireNonNull(ready);
        if (closed || sessions.containsKey(session)) throw new IllegalStateException("Start endpoint is closed or session already exists");
        var roster = List.copyOf(peers);
        for (var peer : roster) {
            if (players.containsKey(peer.player()) || connections.containsKey(peer.connection())) throw new IllegalStateException("Rollback peer is already reserved");
        }
        var negotiation = new RollbackStartNegotiation(session, challenge, contentHash, roster, tick, limits,
                () -> ready.getAsBoolean() && roster.stream().allMatch(transport::current));
        var registration = new Registration(negotiation, roster, ready, runtime);
        sessions.put(session, registration);
        roster.forEach(peer -> { players.put(peer.player(), registration); connections.put(peer.connection(), registration); });
        try {
            for (var peer : roster) transport.send(peer, negotiation.probe());
        } catch (RuntimeException | Error failure) {
            registration.fail(new Failure(RollbackStartPacket.AbortReason.SERVER_FAILURE, "Could not send clock probes", failure));
            throw failure;
        }
        return registration;
    }

    /** Unknown connections are ignored before decoding or allocating message state. */
    public void receive(Object connection, byte[] bytes, long tick) {
        checkThread();
        Registration registration = connections.get(connection);
        if (registration == null || registration.ended) return;
        if (registration.packets.getOrDefault(connection, 0) >= 32) return;
        registration.packets.merge(connection, 1, Integer::sum);
        final RollbackStartPacket.Message message;
        try { message = RollbackStartPacket.decode(bytes, RollbackStartPacket.Direction.CLIENT_TO_SERVER); }
        catch (IllegalArgumentException | NullPointerException malformed) { return; }
        var negotiation = registration.negotiation;
        if (!negotiation.sessionId().equals(message.session()) || !negotiation.challenge().equals(message.challenge())) return;
        if (message instanceof RollbackStartPacket.Abort aborted) {
            registration.fail(new Failure(aborted.reason(), "Client cancelled rollback", null));
            return;
        }
        if (negotiation.phase() == RollbackStartNegotiation.Phase.STARTED) return;
        try {
            negotiation.receive(connection, message, tick);
            registration.progress(tick, false);
        } catch (RuntimeException | Error failure) {
            if (registration.ended) throw failure;
            registration.fail(new Failure(RollbackStartPacket.AbortReason.SERVER_FAILURE, "Start packet handling failed", failure));
        }
    }

    public void tick(long tick) {
        checkThread();
        for (Registration registration : List.copyOf(sessions.values())) {
            registration.packets.clear();
            if (registration.ended) continue;
            try { registration.progress(tick, true); }
            catch (RuntimeException | Error failure) {
                if (registration.ended) throw failure;
                registration.fail(new Failure(RollbackStartPacket.AbortReason.SERVER_FAILURE, "Rollback runtime/start tick failed", failure));
            }
        }
    }

    public void stopPlayer(UUID player, RollbackStartPacket.AbortReason reason) {
        checkThread();
        Registration registration = players.get(player);
        if (registration != null) registration.close(reason);
    }
    public boolean owns(UUID player) { checkThread(); return players.containsKey(player); }

    public void shutdown() {
        checkThread(); closed = true;
        Throwable failures = null;
        for (Registration registration : List.copyOf(sessions.values())) {
            try { registration.close(RollbackStartPacket.AbortReason.SERVER_FAILURE); }
            catch (RuntimeException | Error failure) { if (failures == null) failures = failure; else if (failures != failure) failures.addSuppressed(failure); }
        }
        if (failures instanceof RuntimeException runtime) throw runtime;
        if (failures instanceof Error error) throw error;
    }

    public final class Registration {
        private final RollbackStartNegotiation negotiation;
        private final List<RollbackStartNegotiation.PreparedPeer> peers;
        private final Runtime runtime;
        private final BooleanSupplier ready;
        private final IdentityHashMap<Object, Integer> packets = new IdentityHashMap<>();
        private boolean ended;
        private long runtimeTick = -1;
        private Registration(RollbackStartNegotiation negotiation, List<RollbackStartNegotiation.PreparedPeer> peers, BooleanSupplier ready, Runtime runtime) {
            this.negotiation = negotiation; this.peers = peers; this.ready = ready; this.runtime = runtime;
        }
        public UUID sessionId() { return negotiation.sessionId(); }
        public boolean ended() { checkThread(); return ended; }
        public void close(RollbackStartPacket.AbortReason reason) {
            checkThread(); fail(new Failure(Objects.requireNonNull(reason), "Rollback session stopped", null));
        }

        /** Release only after the owner repairs a failed cleanup; successful stop calls this itself. */
        public void finishStop() {
            checkThread();
            if (!ended) throw new IllegalStateException("Rollback session is still running");
            sessions.remove(sessionId(), this);
            for (var peer : peers) { players.remove(peer.player(), this); connections.remove(peer.connection(), this); }
        }

        private void progress(long tick, boolean runtimeBoundary) {
            if (ended) return;
            if (!peers.stream().allMatch(transport::current)) {
                fail(new Failure(RollbackStartPacket.AbortReason.DISCONNECTED, "Connection/world changed", null)); return;
            }
            if (!ready.getAsBoolean()) { fail(new Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Duel readiness or preference changed", null)); return; }
            if (!negotiation.poll(tick)) {
                fail(new Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, negotiation.failure(), null)); return;
            }
            if (negotiation.clockRepliesComplete()) {
                var schedules = negotiation.schedule(tick);
                for (var peer : peers) transport.send(peer, schedules.get(peer.player()));
            }
            if (negotiation.phase() == RollbackStartNegotiation.Phase.ARMED) {
                var commits = negotiation.commit(tick);
                for (var peer : peers) transport.send(peer, new RollbackStartPacket.Commit(commits.get(peer.player())));
            }
            if (!runtimeBoundary) return;
            if (negotiation.phase() == RollbackStartNegotiation.Phase.COMMITTED && tick == negotiation.serverStartTick()) {
                runtime.start(negotiation, tick);
                if (ended) return;
                if (negotiation.phase() != RollbackStartNegotiation.Phase.STARTED || negotiation.startedSession() == null
                        || negotiation.startedSession().closed()) throw new IllegalStateException("Runtime did not accept the negotiated session");
                runtimeTick = tick;
            } else if (negotiation.phase() == RollbackStartNegotiation.Phase.STARTED) {
                if (negotiation.startedSession().closed()) { close(RollbackStartPacket.AbortReason.STATE_CHANGED); return; }
                if (tick == runtimeTick) return;
                if (tick != Math.incrementExact(runtimeTick)) throw new IllegalStateException("Nonsequential rollback runtime tick");
                runtime.tick(tick);
                runtimeTick = tick;
            }
        }

        private void fail(Failure failure) {
            if (ended) return;
            ended = true;
            if (negotiation.startedSession() != null) negotiation.startedSession().close();
            else negotiation.abort(failure.detail());
            Throwable problem = failure.cause();
            for (var peer : peers) {
                try {
                    if (transport.current(peer)) transport.send(peer, new RollbackStartPacket.Abort(sessionId(), negotiation.challenge(), failure.reason()));
                } catch (RuntimeException | Error sendFailure) {
                    if (problem == null) problem = sendFailure; else if (problem != sendFailure) problem.addSuppressed(sendFailure);
                }
            }
            runtime.stop(new Failure(failure.reason(), failure.detail(), problem));
            finishStop();
        }
    }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Server start endpoint crossed threads"); }
}
