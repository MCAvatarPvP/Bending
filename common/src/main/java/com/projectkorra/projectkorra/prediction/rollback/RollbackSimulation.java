package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Map;
import java.util.UUID;

/**
 * The complete simulation boundary, shared by the authority and predicting clients.
 * There are deliberately no ability names or projectile/defence-specific branches here.
 *
 * <p>Snapshots and inputs must be detached immutable values. Restoring a snapshot must
 * restore everything affecting subsequent steps, including registries, scheduled work,
 * geometry, random state and provisional consequences. A step must only mutate that
 * simulation and emit descriptions of external effects into its context. It must never
 * deliver those effects to the live platform.</p>
 */
public interface RollbackSimulation<S, I, E> {
    S snapshot();

    void restore(S snapshot);

    /** Predict held state only; one-shot actions must not repeat across missing frames. */
    I predict(UUID participant, I previous);

    void step(long tick, Map<UUID, I> inputs, RollbackStep<E> context);
}
