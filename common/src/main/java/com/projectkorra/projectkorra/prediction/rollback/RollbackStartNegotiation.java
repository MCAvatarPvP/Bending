package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Server-owned start barrier after private bootstrap and client content validation.
 * All ticks are counters with the same negotiated step, never client wall-clock time.
 * The loader sends the returned messages and polls this barrier on its main thread.
 */
public final class RollbackStartNegotiation {
    public static final int VERSION = 3;
    public enum Phase { PROBING, SCHEDULING, ARMED, COMMITTED, STARTED, ABORTED }
    public enum Reply { ACCEPTED, DUPLICATE, UNKNOWN_CONNECTION, STALE, WRONG_PHASE, CONFLICT, LATE, ABORTED }

    /** Prepared state is supplied by the authenticated loader, not by a gameplay packet. */
    public record PreparedPeer(UUID player, Object connection, int version, boolean optedIn, String contentHash) {
        public PreparedPeer { Objects.requireNonNull(player); Objects.requireNonNull(connection); requireHash(contentHash); }
    }
    public record Probe(UUID session, UUID challenge) implements RollbackStartPacket.Message {
        public Probe { Objects.requireNonNull(session); Objects.requireNonNull(challenge); }
    }
    public record ClockReply(UUID session, UUID challenge, long clientTick) implements RollbackStartPacket.Message {
        public ClockReply { Objects.requireNonNull(session); Objects.requireNonNull(challenge); if (clientTick < 0) throw new IllegalArgumentException("Client tick"); }
    }
    public record Schedule(UUID session, UUID challenge, long serverTick, long clientTick) implements RollbackStartPacket.Message {
        public Schedule {
            Objects.requireNonNull(session); Objects.requireNonNull(challenge);
            if (serverTick < 0 || clientTick < 0) throw new IllegalArgumentException("Start tick");
        }
    }
    public record Scheduled(Schedule schedule, long receivedAtClientTick) implements RollbackStartPacket.Message {
        public Scheduled { Objects.requireNonNull(schedule); if (receivedAtClientTick < 0) throw new IllegalArgumentException("Client tick"); }
        @Override public UUID session() { return schedule.session(); }
        @Override public UUID challenge() { return schedule.challenge(); }
    }
    public record Limits(int timeoutTicks, int maximumRoundTripTicks, int safetyTicks) {
        public Limits {
            if (timeoutTicks < 4 || timeoutTicks > 1200 || maximumRoundTripTicks < 1 || maximumRoundTripTicks > 200
                    || safetyTicks < 1 || safetyTicks > 40) throw new IllegalArgumentException("Start negotiation limits");
        }
    }

    private static final class Member {
        final PreparedPeer peer;
        ClockReply reply;
        long receivedAt;
        Schedule schedule;
        Scheduled acknowledgement;
        Member(PreparedPeer peer) { this.peer = peer; }
    }
    private final Thread owner = Thread.currentThread();
    private final UUID session, challenge;
    private final long probeTick, expiresAt;
    private final Limits limits;
    private final BooleanSupplier ready;
    private final Map<UUID, Member> players = new TreeMap<>();
    private final IdentityHashMap<Object, Member> connections = new IdentityHashMap<>();
    private Phase phase = Phase.PROBING;
    private long lastTick, serverStartTick = -1, commitDeadline = -1;
    private String failure;
    private RollbackSession<?, ?> startedSession;

    /** Readiness must recheck the duel, preferences, exact connections and drained latency lease. */
    public RollbackStartNegotiation(UUID session, UUID challenge, String expectedContentHash,
                                    Collection<PreparedPeer> peers, long serverTick, Limits limits, BooleanSupplier ready) {
        this.session = Objects.requireNonNull(session); this.challenge = Objects.requireNonNull(challenge);
        this.limits = Objects.requireNonNull(limits); this.ready = Objects.requireNonNull(ready);
        requireHash(expectedContentHash);
        if (serverTick < 0 || peers.size() < 2 || peers.size() > 128) throw new IllegalArgumentException("Start roster/tick");
        probeTick = lastTick = serverTick;
        expiresAt = Math.addExact(serverTick, limits.timeoutTicks());
        for (PreparedPeer peer : peers) {
            if (peer.version() != VERSION || !peer.optedIn() || !expectedContentHash.equals(peer.contentHash())) {
                throw new IllegalArgumentException("Unprepared, incompatible or opted-out rollback peer");
            }
            var member = new Member(peer);
            if (players.putIfAbsent(peer.player(), member) != null || connections.put(peer.connection(), member) != null) {
                throw new IllegalArgumentException("Duplicate rollback player/connection");
            }
        }
        if (!ready.getAsBoolean()) throw new IllegalStateException("Rollback start prerequisites are not ready");
    }

    public Probe probe() { checkThread(); if (phase != Phase.PROBING) throw new IllegalStateException("Probe phase ended"); return new Probe(session, challenge); }

    /** Direction-checked dispatch for the common wire codec. */
    public Reply receive(Object authenticatedConnection, RollbackStartPacket.Message message, long serverTick) {
        checkThread();
        RollbackStartPacket.requireDirection(message, RollbackStartPacket.Direction.CLIENT_TO_SERVER);
        if (message instanceof ClockReply clock) return receive(authenticatedConnection, clock, serverTick);
        if (message instanceof Scheduled scheduled) return receive(authenticatedConnection, scheduled, serverTick);
        var cancelled = (RollbackStartPacket.Abort) message;
        if (!connections.containsKey(authenticatedConnection)) return Reply.UNKNOWN_CONNECTION;
        if (!session.equals(cancelled.session()) || !challenge.equals(cancelled.challenge())) return Reply.STALE;
        if (!poll(serverTick)) return Reply.ABORTED;
        if (phase == Phase.STARTED) return Reply.WRONG_PHASE;
        abort("Client cancelled start: " + cancelled.reason());
        return Reply.ACCEPTED;
    }

    public Reply receive(Object authenticatedConnection, ClockReply reply, long serverTick) {
        checkThread();
        var member = connections.get(authenticatedConnection);
        if (member == null) return Reply.UNKNOWN_CONNECTION;
        if (!session.equals(reply.session()) || !challenge.equals(reply.challenge())) return Reply.STALE;
        if (!poll(serverTick)) return Reply.ABORTED;
        if (phase == Phase.STARTED) return Reply.WRONG_PHASE;
        if (member.reply != null) {
            if (member.reply.equals(reply)) return Reply.DUPLICATE;
            abort("Client rewrote its clock reply"); return Reply.CONFLICT;
        }
        if (phase != Phase.PROBING) return Reply.WRONG_PHASE;
        if (serverTick - probeTick > limits.maximumRoundTripTicks()) { abort("Clock probe exceeded round-trip limit"); return Reply.LATE; }
        member.reply = reply;
        member.receivedAt = serverTick;
        return Reply.ACCEPTED;
    }

    /**
     * The midpoint estimate assumes roughly symmetric transit. Quantization and asymmetric
     * routes leave uncertainty; this does not claim an exact physical input timestamp.
     * Leave one measured RTT for schedule/ack and another for commit delivery, plus margins.
     */
    public Map<UUID, Schedule> schedule(long serverTick) {
        if (!poll(serverTick) || phase != Phase.PROBING || players.values().stream().anyMatch(member -> member.reply == null)) {
            throw new IllegalStateException("Every peer must answer the clock probe before scheduling");
        }
        try {
            long maximumRtt = players.values().stream().mapToLong(member -> member.receivedAt - probeTick).max().orElseThrow();
            long deliveryWindow = maximumRtt + limits.safetyTicks();
            serverStartTick = Math.addExact(serverTick, Math.multiplyExact(2, deliveryWindow));
            commitDeadline = serverStartTick - deliveryWindow;
            if (serverStartTick > expiresAt) throw new IllegalStateException("Start cannot fit within negotiation timeout");
            var schedules = new LinkedHashMap<UUID, Schedule>();
            for (Member member : players.values()) {
                long oneWay = (member.receivedAt - probeTick + 1) / 2;
                long clientAnchor = Math.addExact(member.reply.clientTick(), Math.addExact(serverStartTick - member.receivedAt, oneWay));
                member.schedule = new Schedule(session, challenge, serverStartTick, clientAnchor);
                schedules.put(member.peer.player(), member.schedule);
            }
            phase = Phase.SCHEDULING;
            return Collections.unmodifiableMap(schedules);
        } catch (RuntimeException failure) { abort("Invalid or overflowing start schedule"); throw failure; }
    }

    public Reply receive(Object authenticatedConnection, Scheduled acknowledgement, long serverTick) {
        checkThread();
        var member = connections.get(authenticatedConnection);
        if (member == null) return Reply.UNKNOWN_CONNECTION;
        Schedule schedule = acknowledgement.schedule();
        if (!session.equals(schedule.session()) || !challenge.equals(schedule.challenge())) return Reply.STALE;
        if (!poll(serverTick)) return Reply.ABORTED;
        if (phase == Phase.STARTED) return Reply.WRONG_PHASE;
        if (member.acknowledgement != null) {
            if (member.acknowledgement.equals(acknowledgement)) return Reply.DUPLICATE;
            abort("Client rewrote its schedule acknowledgement"); return Reply.CONFLICT;
        }
        if (phase != Phase.SCHEDULING) return Reply.WRONG_PHASE;
        if (!Objects.equals(member.schedule, schedule) || acknowledgement.receivedAtClientTick() < member.reply.clientTick()) {
            abort("Client acknowledged a different start schedule"); return Reply.CONFLICT;
        }
        if (serverTick > commitDeadline || acknowledgement.receivedAtClientTick() >= schedule.clientTick()) {
            abort("Client could not arm before the start tick"); return Reply.LATE;
        }
        member.acknowledgement = acknowledgement;
        if (players.values().stream().allMatch(value -> value.acknowledgement != null)) phase = Phase.ARMED;
        return Reply.ACCEPTED;
    }

    /** The loader broadcasts these commit messages only after every exact connection acknowledges. */
    public Map<UUID, Schedule> commit(long serverTick) {
        if (!poll(serverTick) || phase != Phase.ARMED) throw new IllegalStateException("Start is not fully armed");
        phase = Phase.COMMITTED;
        var committed = new LinkedHashMap<UUID, Schedule>();
        players.forEach((id, member) -> committed.put(id, member.schedule));
        return Collections.unmodifiableMap(committed);
    }

    /** Construct the existing authenticated session at tick zero on the chosen server tick. */
    public <S, E> RollbackSession<S, E> start(long serverTick, long seed, RollbackTimeline<S, RollbackPlayerInput, E> timeline) {
        if (!poll(serverTick) || phase != Phase.COMMITTED || serverTick != serverStartTick) throw new IllegalStateException("Not the committed start tick");
        try {
            var peers = players.values().stream().map(member -> new RollbackSession.Peer(member.peer.player(), member.peer.connection(), member.schedule.clientTick())).toList();
            var started = new RollbackSession<>(session, seed, timeline, peers);
            startedSession = started;
            phase = Phase.STARTED;
            return started;
        } catch (RuntimeException failure) { abort("Private timeline could not start"); throw failure; }
    }

    /** Poll every loader tick, including while no packets arrive. */
    public boolean poll(long serverTick) {
        checkThread();
        if (phase == Phase.ABORTED) return false;
        if (serverTick < lastTick) { abort("Server clock moved backwards"); throw new IllegalArgumentException("Server tick order"); }
        lastTick = serverTick;
        if (phase == Phase.STARTED) return true; // The running session owns subsequent teardown.
        try {
            if (!ready.getAsBoolean()) abort("Rollback start prerequisites changed");
        } catch (RuntimeException | Error failure) { abort("Readiness check failed"); throw failure; }
        if (serverTick > expiresAt) abort("Start negotiation timed out");
        else if ((phase == Phase.SCHEDULING || phase == Phase.ARMED) && serverTick > commitDeadline) abort("Commit deadline passed");
        else if (phase == Phase.COMMITTED && serverTick > serverStartTick) abort("Committed start tick was missed");
        return phase != Phase.ABORTED;
    }

    public void abort(String reason) {
        checkThread(); Objects.requireNonNull(reason);
        if (phase == Phase.STARTED) throw new IllegalStateException("Stop the running session through ingress");
        if (phase == Phase.ABORTED) return;
        failure = reason; phase = Phase.ABORTED;
    }
    public Phase phase() { checkThread(); return phase; }
    public UUID sessionId() { return session; }
    public UUID challenge() { return challenge; }
    public List<PreparedPeer> peers() { checkThread(); return players.values().stream().map(member -> member.peer).toList(); }
    public boolean clockRepliesComplete() { checkThread(); return phase == Phase.PROBING && players.values().stream().allMatch(member -> member.reply != null); }
    public RollbackSession<?, ?> startedSession() { checkThread(); return startedSession; }
    public String failure() { checkThread(); return failure; }
    public long serverStartTick() { checkThread(); return serverStartTick; }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Start negotiation crossed threads"); }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA-256 content identity");
    }
}
