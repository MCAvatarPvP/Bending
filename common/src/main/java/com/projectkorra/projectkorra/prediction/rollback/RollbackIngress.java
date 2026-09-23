package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Authenticated transport ownership outside rewindable state. Enrollment requires a
 * fully negotiated, bootstrapped session; this does not negotiate or start simulation.
 * Stopping keeps the entire roster gated until the loader finishes coordinated teardown.
 */
public final class RollbackIngress {
    public enum StopReason { REQUESTED, CONNECTION_CHANGED, CLIENT_RESET, WORLD_CHANGED, SESSION_CLOSED, FAILURE, SHUTDOWN }
    public enum Dispatch { UNENROLLED, STOPPING, MALFORMED, THROTTLED, DELIVERED }
    public record Result(Dispatch dispatch, RollbackSession.Receipt receipt) { }

    private final Thread owner = Thread.currentThread();
    private final int maximumPacketsPerPeerTick;
    private final Map<UUID, Registration> sessions = new LinkedHashMap<>();
    private final Map<UUID, Registration> players = new HashMap<>();
    private final IdentityHashMap<Object, Member> connections = new IdentityHashMap<>();
    private volatile Set<UUID> blocked = Set.of();
    private boolean closed;

    public RollbackIngress(int maximumPacketsPerPeerTick) {
        if (maximumPacketsPerPeerTick < 1 || maximumPacketsPerPeerTick > 1024) throw new IllegalArgumentException("Ingress packet budget");
        this.maximumPacketsPerPeerTick = maximumPacketsPerPeerTick;
    }

    public Registration enroll(RollbackSession<?, ?> session, Consumer<Registration> stop) {
        checkThread();
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(stop, "stop callback");
        if (closed || session.closed() || sessions.containsKey(session.id())) throw new IllegalStateException("Ingress or session is closed/already enrolled");
        var roster = session.peers();
        if (roster.size() < 2) throw new IllegalArgumentException("Rollback combat requires opposing peers");
        for (var peer : roster) {
            if (players.containsKey(peer.player()) || connections.containsKey(peer.connection())) throw new IllegalStateException("Rollback peer already enrolled");
        }
        var registration = new Registration(session, stop);
        sessions.put(session.id(), registration);
        for (var peer : roster) {
            players.put(peer.player(), registration);
            connections.put(peer.connection(), new Member(registration));
        }
        blocked = Set.copyOf(players.keySet()); // Publish the whole roster together to read-only packet/event checks.
        return registration;
    }

    public boolean blocksLegacy(UUID player) { return player != null && blocked.contains(player); }
    public boolean owns(RollbackSession<?, ?> session) {
        checkThread();
        var registration = sessions.get(session.id());
        return registration != null && registration.session == session && registration.stopping == null && !session.closed();
    }

    /** Decode only after authenticating the transport; payloads cannot select their owner. */
    public Result receive(Object authenticatedConnection, byte[] bytes) {
        checkThread();
        Member member = connections.get(authenticatedConnection);
        if (member == null) return new Result(Dispatch.UNENROLLED, null);
        Registration registration = member.registration;
        if (registration.stopping != null) return new Result(Dispatch.STOPPING, null);
        if (member.packets >= maximumPacketsPerPeerTick) return new Result(Dispatch.THROTTLED, null);
        member.packets++;
        final RollbackInputPacket packet;
        try { packet = RollbackInputPacket.decode(bytes); }
        catch (IllegalArgumentException | NullPointerException malformed) { return new Result(Dispatch.MALFORMED, null); }
        try {
            var receipt = registration.session.receive(authenticatedConnection, packet);
            if (receipt.status() == RollbackSession.Status.CLOSED) registration.stop(StopReason.SESSION_CLOSED);
            return new Result(Dispatch.DELIVERED, receipt);
        } catch (RuntimeException | Error failure) {
            try { registration.stop(StopReason.FAILURE); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** One call per loader tick; never rewind this network work budget. */
    public void beginTick() { checkThread(); connections.values().forEach(member -> member.packets = 0); }

    public void validateConnections(Predicate<RollbackSession.Peer> current) {
        checkThread(); Objects.requireNonNull(current, "connection validator");
        for (Registration registration : List.copyOf(sessions.values())) {
            if (registration.stopping != null) continue;
            if (registration.session.closed()) registration.stop(StopReason.SESSION_CLOSED);
            else if (!registration.session.peers().stream().allMatch(current)) registration.stop(StopReason.CONNECTION_CHANGED);
        }
    }

    public void stopPlayer(UUID player, StopReason reason) {
        checkThread();
        Registration registration = players.get(player);
        if (registration != null) registration.stop(reason);
    }

    public void shutdown() {
        checkThread(); closed = true;
        Throwable failure = null;
        for (Registration registration : List.copyOf(sessions.values())) {
            try { registration.stop(StopReason.SHUTDOWN); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem; else if (failure != problem) failure.addSuppressed(problem);
            } finally { registration.finishStop(); }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    public final class Registration {
        private final RollbackSession<?, ?> session;
        private final Consumer<Registration> onStop;
        private StopReason stopping;
        private boolean released;

        private Registration(RollbackSession<?, ?> session, Consumer<Registration> onStop) { this.session = session; this.onStop = onStop; }
        public UUID sessionId() { return session.id(); }
        public List<RollbackSession.Peer> peers() { checkThread(); return session.peers(); }
        public StopReason stopReason() { checkThread(); return stopping; }

        public void stop(StopReason reason) {
            checkThread(); Objects.requireNonNull(reason, "reason");
            if (stopping != null || released) return;
            stopping = reason;
            session.close();
            onStop.accept(this);
        }

        /** Loader calls only after every peer's normal mode and live state have been restored. */
        public void finishStop() {
            checkThread();
            if (stopping == null) throw new IllegalStateException("Cannot release an active rollback session");
            if (released) return;
            released = true;
            sessions.remove(session.id(), this);
            for (var peer : session.peers()) {
                players.remove(peer.player(), this);
                var member = connections.get(peer.connection());
                if (member != null && member.registration == this) connections.remove(peer.connection());
            }
            blocked = Set.copyOf(players.keySet());
        }
    }

    private final class Member {
        final Registration registration;
        int packets;
        Member(Registration registration) { this.registration = registration; }
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Rollback ingress crossed threads");
    }
}
