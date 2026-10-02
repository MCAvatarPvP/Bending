package com.projectkorra.projectkorra.prediction.rollback;

import java.util.UUID;

/** Client-only replacement/finalization operations, driven by the authenticated authority. */
public interface RollbackReplicaTimeline<S, I, E> extends RollbackTimeline<S, I, E> {
    boolean replica();
    RollbackEngine.Submission correct(UUID participant, long tick, I input);
    RollbackEngine.Update<S, I, E> confirm(long tick);
}
