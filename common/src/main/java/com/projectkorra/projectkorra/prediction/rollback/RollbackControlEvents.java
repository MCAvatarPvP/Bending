package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import java.util.Map;
import java.util.UUID;

/** Native control events read the same private players and bending registries as the current combat tick. */
public final class RollbackControlEvents implements AutoCloseable {
    private static final ThreadLocal<RollbackControlEvents> ACTIVE = new ThreadLocal<>();
    private final Thread thread = Thread.currentThread();
    private final Map<UUID, RollbackPlayer> players;
    private boolean closed;

    RollbackControlEvents(Map<UUID, RollbackPlayer> players) {
        if (!RollbackDomain.active() || !RollbackClock.active() || ACTIVE.get() != null) {
            throw new IllegalStateException("Control events require one private combat tick");
        }
        this.players = Map.copyOf(players); ACTIVE.set(this);
    }
    public static boolean cancelFlight(UUID player) {
        var logical = player(player);
        return logical != null && CommonInputHandler.handleToggleFlight(logical).cancelEvent();
    }
    public static boolean cancelGlide(UUID player) {
        var logical = player(player);
        return logical != null && CommonInputHandler.handleToggleGlide(logical).cancelEvent();
    }
    private static RollbackPlayer player(UUID id) {
        var scope = ACTIVE.get();
        if (scope == null) {
            if (RollbackDomain.active()) throw new IllegalStateException("Native control event outside combat execution");
            // Native-only import/component operations have no installed bending registries.
            return null;
        }
        var player = scope.players.get(id);
        if (player == null) throw new IllegalArgumentException("Control event for a foreign combat participant");
        return player;
    }
    @Override public void close() {
        if (thread != Thread.currentThread()) throw new IllegalStateException("Control event scope crossed threads");
        if (closed) return;
        if (ACTIVE.get() != this) throw new IllegalStateException("Control event scope was replaced");
        ACTIVE.remove(); closed = true;
    }
}
