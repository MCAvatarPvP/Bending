package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Authenticated whole-roster import barrier before the existing clock/start negotiation. */
public final class RollbackBootstrapServerEndpoint {
    public record Peer(UUID player, Object connection) { public Peer { Objects.requireNonNull(player); Objects.requireNonNull(connection); } }
    public record Limits(int timeoutTicks, int packetsPerTick) {
        public Limits { if (timeoutTicks < 1 || timeoutTicks > 1200 || packetsPerTick < 1 || packetsPerTick > 64) throw new IllegalArgumentException("Bootstrap transfer limits"); }
    }
    public interface Transport {
        boolean current(Peer peer);
        void send(Peer peer, RollbackBootstrapPacket.Message message);
    }
    private final Thread thread = Thread.currentThread();
    private final Transport transport;
    private final Map<UUID, Registration> players = new HashMap<>(), sessions = new HashMap<>();
    private final Map<Object, Registration> connections = new IdentityHashMap<>();
    private boolean closed;
    public RollbackBootstrapServerEndpoint(Transport transport) { this.transport = Objects.requireNonNull(transport); }

    /** Reserve only. Sending begins in poll, after the caller holds the returned cleanup handle. */
    public Registration begin(RollbackBootstrapPacket.Offer offer, byte[] bytes, Collection<Peer> peers,
                              long tick, Limits limits, BooleanSupplier ready) {
        check();
        if (closed || offer.startVersion() != RollbackStartNegotiation.VERSION || bytes.length != offer.bytes()
                || !offer.fingerprint().equals(RollbackBootstrapData.fingerprint(bytes)) || sessions.containsKey(offer.session())) {
            throw new IllegalArgumentException("Bootstrap transfer envelope/session");
        }
        var candidate = new Registration(offer, bytes, peers, tick, limits, ready);
        for (var peer : candidate.peers) {
            if (players.containsKey(peer.player()) || connections.containsKey(peer.connection()) || !transport.current(peer)) throw new IllegalStateException("Bootstrap peer unavailable/reserved");
        }
        sessions.put(offer.session(), candidate);
        for (var peer : candidate.peers) { players.put(peer.player(), candidate); connections.put(peer.connection(), candidate); }
        return candidate;
    }
    public void receive(Object authenticatedConnection, byte[] bytes, long tick) {
        check(); var registration = connections.get(authenticatedConnection);
        if (registration == null) return;
        try {
            var message = RollbackBootstrapPacket.decode(bytes, RollbackStartPacket.Direction.CLIENT_TO_SERVER);
            registration.receive(authenticatedConnection, message, tick);
        } catch (RuntimeException problem) { registration.fail(RollbackStartPacket.AbortReason.INCOMPATIBLE, "Invalid bootstrap receipt"); }
    }
    public void stopPlayer(UUID player, RollbackStartPacket.AbortReason reason) { check(); var entry = players.get(player); if (entry != null) entry.fail(reason, "Bootstrap participant left"); }
    public void shutdown() {
        check(); closed = true; Throwable failure = null;
        for (var entry : List.copyOf(sessions.values())) try { entry.close(RollbackStartPacket.AbortReason.SERVER_FAILURE); }
        catch (RuntimeException | Error problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }

    public final class Registration {
        private final RollbackBootstrapPacket.Offer offer;
        private byte[] bytes;
        private final List<Peer> peers;
        private final Limits limits;
        private final BooleanSupplier ready;
        private final Set<Object> receipts = Collections.newSetFromMap(new IdentityHashMap<>());
        private final int[] sent;
        private final long deadline;
        private long lastTick, lastSendTick = -1;
        private int cursor, replies;
        private boolean handedOff, closed;
        private String failure;
        private RollbackStartPacket.AbortReason reason;
        private Registration(RollbackBootstrapPacket.Offer offer, byte[] bytes, Collection<Peer> source, long tick, Limits limits, BooleanSupplier ready) {
            this.offer = offer; this.bytes = bytes.clone(); this.limits = Objects.requireNonNull(limits); this.ready = Objects.requireNonNull(ready);
            if (tick < 0 || source.size() < 2 || source.size() > 128) throw new IllegalArgumentException("Bootstrap roster/clock");
            peers = source.stream().sorted(Comparator.comparing(Peer::player)).toList();
            var ids = new HashSet<UUID>(); var channels = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            for (var peer : peers) if (!ids.add(peer.player()) || !channels.add(peer.connection())) throw new IllegalArgumentException("Duplicate bootstrap peer");
            sent = new int[peers.size()]; deadline = Math.addExact(tick, limits.timeoutTicks()); lastTick = tick;
        }
        public RollbackBootstrapPacket.Offer offer() { return offer; }
        /** At most one bounded send batch per server tick, round-robin over the entire roster. */
        public boolean poll(long tick) {
            current(tick);
            if (lastSendTick != tick) {
                lastSendTick = tick;
                try {
                    for (int n = 0, scanned = 0; n < limits.packetsPerTick() && scanned < peers.size(); ) {
                        int index = cursor; cursor = (cursor + 1) % peers.size();
                        int part = sent[index] - 1;
                        if (part >= offer.parts()) { scanned++; continue; }
                        scanned = 0;
                        // Advance before send so a synchronous test/transport receipt sees the actual delivered prefix.
                        sent[index]++;
                        var message = part < 0 ? offer : new RollbackBootstrapPacket.Part(offer.session(), offer.challenge(), part,
                                Arrays.copyOfRange(bytes, part * RollbackBootstrapPacket.DATA_BYTES, part * RollbackBootstrapPacket.DATA_BYTES + offer.partSize(part)));
                        transport.send(peers.get(index), message); n++;
                    }
                } catch (RuntimeException | Error problem) { fail(RollbackStartPacket.AbortReason.SERVER_FAILURE, "Bootstrap send failed"); throw problem; }
            }
            return receipts.size() == peers.size();
        }
        /** The import receipts become the actual peers accepted by RollbackStartNegotiation. */
        public List<RollbackStartNegotiation.PreparedPeer> handoff(long tick) {
            current(tick);
            if (receipts.size() != peers.size()) throw new IllegalStateException("Every client must finish private import before start");
            handedOff = true; bytes = null; remove(this);
            return peers.stream().map(peer -> new RollbackStartNegotiation.PreparedPeer(peer.player(), peer.connection(),
                    RollbackStartNegotiation.VERSION, true, offer.fingerprint())).toList();
        }
        private void receive(Object connection, RollbackBootstrapPacket.Message message, long tick) {
            if (!offer.session().equals(message.session()) || !offer.challenge().equals(message.challenge())) return;
            current(tick);
            if (++replies > 512) throw new IllegalStateException("Bootstrap reply work budget");
            if (message instanceof RollbackBootstrapPacket.Cancel cancelled) { fail(cancelled.reason(), "Client rejected private import"); return; }
            var receipt = (RollbackBootstrapPacket.Ready) message;
            int index = -1;
            for (int i = 0; i < peers.size(); i++) if (peers.get(i).connection() == connection) { index = i; break; }
            if (index < 0 || sent[index] != offer.parts() + 1 || !offer.fingerprint().equals(receipt.fingerprint())) {
                fail(RollbackStartPacket.AbortReason.INCOMPATIBLE, "Client acknowledged different or incomplete bootstrap state"); return;
            }
            receipts.add(connection);
        }
        private void current(long tick) {
            check();
            if (closed || handedOff) throw new IllegalStateException("Bootstrap transfer already released");
            if (tick < lastTick || tick > deadline) fail(RollbackStartPacket.AbortReason.TIMEOUT, "Bootstrap transfer clock/timeout");
            if (tick != lastTick) replies = 0;
            lastTick = tick;
            if (failure == null && (!ready.getAsBoolean() || peers.stream().anyMatch(peer -> !transport.current(peer)))) fail(RollbackStartPacket.AbortReason.STATE_CHANGED, "Bootstrap prerequisites changed");
            if (failure != null) throw new IllegalStateException(failure);
        }
        private void fail(RollbackStartPacket.AbortReason reason, String detail) { if (failure == null) { this.reason = reason; failure = detail; bytes = null; } }
        public void close(RollbackStartPacket.AbortReason reason) {
            check(); if (closed) return;
            closed = true; bytes = null; remove(this);
            var cancel = new RollbackBootstrapPacket.Cancel(offer.session(), offer.challenge(), this.reason == null ? reason : this.reason);
            Throwable failed = null;
            for (var peer : peers) try { if (transport.current(peer)) transport.send(peer, cancel); }
            catch (RuntimeException | Error problem) { if (failed == null) failed = problem; else failed.addSuppressed(problem); }
            if (failed instanceof RuntimeException problem) throw problem;
            if (failed instanceof Error problem) throw problem;
        }
    }
    private void remove(Registration entry) {
        sessions.remove(entry.offer.session(), entry);
        for (var peer : entry.peers) { players.remove(peer.player(), entry); connections.remove(peer.connection(), entry); }
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Bootstrap endpoint crossed threads"); }
}
