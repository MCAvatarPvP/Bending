package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Collection;
import java.util.List;

/** Adapter for mutable service types whose internal fields cannot be captured reflectively. */
public interface RollbackStateCell<S> {
    /** Must return detached immutable state. */
    S captureRollbackState();

    void restoreRollbackState(S state);

    /** Mutable referenced objects which must also be visited by the runtime graph. */
    default Collection<?> rollbackReferences() { return List.of(); }
}
