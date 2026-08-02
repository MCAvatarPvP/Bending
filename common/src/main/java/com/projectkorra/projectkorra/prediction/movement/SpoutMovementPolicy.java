package com.projectkorra.projectkorra.prediction.movement;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Objects;

/** Selects the velocity basis used by the shared spout movement cap. */
public final class SpoutMovementPolicy {
    private SpoutMovementPolicy() {
    }

    /**
     * The predicting client has a real flight velocity, so corrections must
     * preserve it on every axis. An authoritative server instead observes
     * vanilla flight through position updates and needs that displacement to
     * enforce the controlled axes.
     *
     * <p>WaterSpout controls only horizontal speed. Its vertical component
     * must therefore remain the entity's live velocity; feeding observed
     * ascent back through a horizontal velocity correction compounds the
     * ascent on every movement packet. AirSpout also controls vertical speed,
     * so its authoritative correction may use the observed Y displacement.</p>
     */
    public static Vector initialVelocity(final boolean hasAirSpout,
                                         final boolean locallySimulated,
                                         final Vector movement,
                                         final Vector currentVelocity) {
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(currentVelocity, "currentVelocity");
        if (locallySimulated) {
            return currentVelocity.clone();
        }

        final Vector selected = movement.clone();
        if (!hasAirSpout) {
            selected.setY(currentVelocity.getY());
        }
        return selected;
    }
}
