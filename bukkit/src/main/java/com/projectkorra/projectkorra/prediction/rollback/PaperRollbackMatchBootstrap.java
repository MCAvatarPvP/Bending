package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;

import java.util.*;

/**
 * Native bootstrap boundary used by Neptune's round lifecycle. Installation requires
 * complete private state capture, client transfer and restoration of live ownership.
 * Merely advertising exact prediction is not sufficient to implement this service.
 */
public interface PaperRollbackMatchBootstrap {
    /** Read-only: verifies current rollback protocol/content capability for the whole roster. */
    boolean supports(Set<UUID> players);

    /**
     * Called after Neptune's equalization queues drain, on the live server thread.
     * Return the cleanup owner before acquiring native ownership or sending bootstrap
     * state: perform that work in Preparation.poll. A failure before returning must
     * leave live state unchanged and release anything already acquired.
     */
    Preparation prepare(Request request);

    /** confirmedDefeats queues results only during Runtime.tick, after authority publication.
     * The owner restores live ownership after tick returns before applying any match result.
     * The runtime must use RollbackCombatRuntime.deliverConfirmedDefeats, never provisional results.
     */
    record Request(UUID session, UUID match, int round, UUID world, Bounds terrain, Map<UUID, UUID> sides,
                   java.util.function.Consumer<RollbackRound.Defeat> confirmedDefeats) {
        public Request {
            Objects.requireNonNull(session); Objects.requireNonNull(match); Objects.requireNonNull(world);
            Objects.requireNonNull(terrain); Objects.requireNonNull(confirmedDefeats);
            sides = Collections.unmodifiableMap(new TreeMap<>(sides));
            if (round < 0 || sides.size() < 2 || sides.size() > 128 || new HashSet<>(sides.values()).size() < 2
                    || sides.containsValue(null)) throw new IllegalArgumentException("Rollback duel roster/round");
        }
    }

    interface Preparation {
        /** Returns null while authenticated client bootstrap acknowledgements are pending. */
        Prepared poll();

        /**
         * Restore all pre-start ownership and release the bootstrap, including partial
         * initialization. The running runtime is stopped first when one was handed off.
         * Must be idempotent. Throwing keeps Neptune's equalization lease reserved.
         */
        void stop(RollbackStartServerEndpoint.Failure failure);
    }

    record Prepared(UUID challenge, String contentHash, List<RollbackStartNegotiation.PreparedPeer> peers,
                    RollbackStartNegotiation.Limits limits, RollbackStartServerEndpoint.Runtime runtime) {
        public Prepared {
            Objects.requireNonNull(challenge); Objects.requireNonNull(contentHash); Objects.requireNonNull(limits); Objects.requireNonNull(runtime);
            peers = List.copyOf(peers);
            if (peers.size() < 2 || peers.size() > 128) throw new IllegalArgumentException("Prepared duel roster");
        }
        /** No peer supplied by a completed bootstrap may be omitted or substituted at handoff. */
        public void requireRoster(Set<UUID> players) {
            var unique = new HashSet<UUID>();
            for (var peer : peers) if (!unique.add(peer.player())) throw new IllegalArgumentException("Duplicate prepared player");
            if (!unique.equals(players)) throw new IllegalArgumentException("Prepared roster differs from Neptune round");
        }
    }
}
