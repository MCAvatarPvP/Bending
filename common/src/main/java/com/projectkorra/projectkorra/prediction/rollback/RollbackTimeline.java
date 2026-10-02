package com.projectkorra.projectkorra.prediction.rollback;

import java.util.List;
import java.util.UUID;

/** Server-owned timeline operations; a domain-backed runtime also isolates each operation. */
public interface RollbackTimeline<S, I, E> {
    List<UUID> participants();
    RollbackEngine.Limits limits();
    RollbackEngine.Diagnostics diagnostics();
    RollbackEngine.Submission submit(UUID participant, long tick, I input);
    RollbackEngine.Update<S, I, E> advance();
    RollbackEngine.Update<S, I, E> reconcile();
    /** Retained, reconciled frames only; transport must copy detached data before sending. */
    List<RollbackEngine.Frame<S, I, E>> frames(long from, long through);
}
