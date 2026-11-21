package com.projectkorra.projectkorra.prediction.rollback;

import java.util.List;
import java.util.Objects;

/**
 * Immutable intent for one combat tick. Actions retain their own aim, even when
 * the player turns again before the tick ends. Session identity, authentication,
 * action seeds and tick mapping are assigned/validated by the transport separately.
 */
public record RollbackPlayerInput(RollbackMovementInput movement, boolean sprinting, List<Edge> actions) {
    public static final int MAXIMUM_ACTIONS = 64;

    public record Edge(RollbackInputActions.Action action, float yaw, float pitch) {
        public Edge {
            Objects.requireNonNull(action, "action");
            var look = new RollbackMovementInput(0, 0, false, yaw, pitch);
            yaw = look.yaw(); pitch = look.pitch();
        }
    }

    public RollbackPlayerInput {
        Objects.requireNonNull(movement, "movement");
        if (Objects.requireNonNull(actions, "actions").size() > MAXIMUM_ACTIONS) {
            throw new IllegalArgumentException("Too many actions in one combat tick");
        }
        actions = List.copyOf(actions);
        long sequence = 0;
        for (var edge : actions) {
            if (edge.action().sequence() <= sequence) throw new IllegalArgumentException("Actions must be in sequence order");
            sequence = edge.action().sequence();
        }
    }

    /** Continue held movement without repeating clicks, slot changes or other edges. */
    public RollbackPlayerInput predict() {
        return actions.isEmpty() ? this : new RollbackPlayerInput(movement, sprinting, List.of());
    }
}
