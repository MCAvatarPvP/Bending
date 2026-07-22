package com.projectkorra.projectkorra.prediction.movement;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Objects;

/** Selects the velocity basis used by the shared spout movement cap. */
public final class SpoutMovementPolicy {
    private SpoutMovementPolicy() {
    }

    /**
     * WaterSpout is driven by observed flight displacement on both endpoints.
     * AirSpout retains the predicting client's live velocity because its
     * vertical cap operates on that local velocity.
     */
    public static Vector initialVelocity(final boolean hasWaterSpout,
                                         final boolean locallySimulated,
                                         final Vector movement,
                                         final Vector currentVelocity) {
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        return (hasWaterSpout || !locallySimulated ? movement : currentVelocity).clone();
    }
}
