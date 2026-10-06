package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

import java.util.Objects;
import java.util.function.Consumer;

/** Prepared Paper match runtime; native capture and ownership must be ready before start negotiation. */
public final class PaperRollbackMatchRuntime<E> implements RollbackStartServerEndpoint.Runtime {
    private final RollbackServerRuntime<RollbackDomain.Checkpoint, E> runtime;

    public PaperRollbackMatchRuntime(PaperRollbackMatchBootstrap.Request request, long seed,
            RollbackCombatRuntime<RollbackPlayerInput, E> combat, PaperPredictionServer server,
            RollbackServerRuntime.Output<RollbackDomain.Checkpoint, E> output) {
        Objects.requireNonNull(request); Objects.requireNonNull(combat); Objects.requireNonNull(server);
        if (!request.sides().keySet().equals(new java.util.HashSet<>(combat.participants())))
            throw new IllegalArgumentException("Native match roster differs from Neptune request");
        runtime = new RollbackServerRuntime<>(request.session(), seed, combat, new RollbackServerRuntime.Transport() {
            @Override public RollbackIngress.Registration enroll(RollbackSession<?, ?> session, Consumer<RollbackIngress.Registration> stopped) {
                return server.enrollRollback(session, stopped);
            }
            @Override public void publish(RollbackSession<?, ?> session) { server.publishRollback(session); }
        }, output, () -> combat.deliverConfirmedDefeats(request.confirmedDefeats()));
    }
    @Override public void start(RollbackStartNegotiation negotiation, long tick) { runtime.start(negotiation, tick); }
    @Override public void tick(long tick) { runtime.tick(tick); }
    @Override public void stop(RollbackStartServerEndpoint.Failure failure) { runtime.stop(failure); }
}
