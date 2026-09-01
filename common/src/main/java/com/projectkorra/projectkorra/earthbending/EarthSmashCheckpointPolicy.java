package com.projectkorra.projectkorra.earthbending;

/**
 * Pure ordering and position rules for sparse EarthSmash checkpoints.
 *
 * <p>A transition checkpoint describes Paper's position at the transition
 * boundary. By the time it reaches the client, the local smash can already
 * have rendered several newer motion frames. Rebasing the local displacement
 * onto Paper's transition anchor corrects the disagreement without moving the
 * client back to the older network-time frame.</p>
 */
public final class EarthSmashCheckpointPolicy {
    private static final double AIM_BLEND_LIMIT = 0.35;
    private static final double LEVEL_FLIGHT_OFFSET = -2.2;
    private static final double DOWN_FLIGHT_OFFSET = -3.2;
    private static final double UP_FLIGHT_OFFSET = -1.7;

    private EarthSmashCheckpointPolicy() {
    }

    /**
     * Returns whether {@code incoming} is newer than the last accepted
     * checkpoint. Action sequence is the transition epoch; the remaining
     * fields order the sparse checkpoints which can share that transition.
     */
    public static boolean isNewer(final CheckpointOrder incoming,
                                  final CheckpointOrder accepted) {
        if (incoming == null) return false;
        if (accepted == null) return true;
        if (incoming.actionSequence() != accepted.actionSequence()) {
            return incoming.actionSequence() > accepted.actionSequence();
        }
        if (incoming.progressCounter() != accepted.progressCounter()) {
            return incoming.progressCounter() > accepted.progressCounter();
        }
        if (incoming.predictionFrame() != accepted.predictionFrame()) {
            return incoming.predictionFrame() > accepted.predictionFrame();
        }
        return incoming.animationCounter() > accepted.animationCounter();
    }

    /**
     * Moves Paper's transition-anchor correction onto the newest local point.
     * The displacement rendered since the local transition is retained exactly.
     */
    public static Position rebase(final Position current,
                                  final Position localTransitionAnchor,
                                  final Position authoritativeTransitionAnchor) {
        if (current == null || localTransitionAnchor == null
                || authoritativeTransitionAnchor == null) return current;
        return new Position(
                authoritativeTransitionAnchor.x() + current.x() - localTransitionAnchor.x(),
                authoritativeTransitionAnchor.y() + current.y() - localTransitionAnchor.y(),
                authoritativeTransitionAnchor.z() + current.z() - localTransitionAnchor.z());
    }

    /** Reconciles the complete pose-derived transition parameters. */
    public static Kinematics reconcile(final Position current,
                                       final Position localTransitionAnchor,
                                       final Position authoritativeTransitionAnchor,
                                       final Position authoritativeDestination,
                                       final double authoritativeGrabbedDistance) {
        return new Kinematics(
                rebase(current, localTransitionAnchor, authoritativeTransitionAnchor),
                authoritativeDestination, Math.max(0.0, authoritativeGrabbedDistance));
    }

    /**
     * Continuous replacement for EarthSmash's former three pitch bands.
     * Values outside the old +/-0.35 thresholds retain the old saturated
     * offsets, while values between them interpolate from the level pose.
     */
    public static double flightVerticalOffset(final double directionY) {
        if (!Double.isFinite(directionY)) return LEVEL_FLIGHT_OFFSET;
        final double blend = Math.max(-1.0,
                Math.min(1.0, directionY / AIM_BLEND_LIMIT));
        if (blend < 0.0) {
            return LEVEL_FLIGHT_OFFSET
                    + (LEVEL_FLIGHT_OFFSET - DOWN_FLIGHT_OFFSET) * blend;
        }
        return LEVEL_FLIGHT_OFFSET
                + (UP_FLIGHT_OFFSET - LEVEL_FLIGHT_OFFSET) * blend;
    }

    public record CheckpointOrder(long actionSequence, int progressCounter,
                                  long predictionFrame, int animationCounter) {
    }

    public record Position(double x, double y, double z) {
    }

    public record Kinematics(Position center, Position destination,
                             double grabbedDistance) {
    }
}
