package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

/**
 * Publishes causal ownership immediately before an ability changes a player's
 * vanilla flight/abilities state.
 */
public final class AbilityStateSync {
    public interface Listener {
        void beforeWrite(CoreAbility ability, Player target, FlightState resultingState);
    }

    private static volatile Listener listener;

    private AbilityStateSync() {
    }

    public static void install(Listener value) {
        listener = value;
    }

    public static void clear(Listener value) {
        if (listener == value) listener = null;
    }

    public static void apply(CoreAbility ability, Player target, FlightState resultingState, Runnable write) {
        if (write == null) return;
        final Listener current = listener;
        // Constructors such as AirScooter and WaterSpout change vanilla flight
        // state before their CoreAbility has entered progress()/start(). The
        // native input action still provides exact ownership even though the
        // narrower AbilityExecutionContext has not been installed yet.
        if (current != null && target != null && resultingState != null) {
            current.beforeWrite(ability, target, resultingState);
        }
        write.run();
    }

    /**
     * The projection carried by Minecraft's absolute abilities packet which
     * ProjectKorra is allowed to own. Other packet fields (creative,
     * invulnerable, walk speed) remain vanilla/external authority.
     */
    public record FlightState(boolean flying, boolean allowFlight, float flySpeed) {
    }
}
