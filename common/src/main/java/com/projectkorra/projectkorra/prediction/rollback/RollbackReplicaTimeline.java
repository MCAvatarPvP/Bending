package com.projectkorra.projectkorra.prediction.rollback;

import java.util.UUID;

/** Client-only replacement/finalization operations, driven by the authenticated authority. */
public interface RollbackReplicaTimeline<S, I, E> extends RollbackTimeline<S, I, E> {
    boolean replica();
    /** Trusted imported state at the current confirmed tick; replay retains later accepted inputs. */
    RollbackEngine.Update<S, I, E> correctState(long tick, S state);
    RollbackEngine.Submission correct(UUID participant, long tick, I input);
    RollbackEngine.Update<S, I, E> confirm(long tick);
}
