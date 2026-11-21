package com.projectkorra.projectkorra.prediction.rollback;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;

/** Per-player server mining progress, captured together with the private gameplay graph. */
public final class RollbackDigging {
    public interface Blocks {
        /** Applies world, kit, border, mode, tool and block restrictions before breaking. */
        boolean mayBreak(Position pos);
        boolean creative();
        /** Calls the native block's attack hook only for the initial permitted contact. */
        void attack(Position pos);
        /** Native per-tick break fraction using the current private player, tool and block. */
        float delta(Position pos);
        void destroy(Position pos);
    }
    static final class Mining {
        private final int x, y, z;
        private final long began;
        Mining(Position pos, long began) {
            this.x = pos.x(); this.y = pos.y(); this.z = pos.z(); this.began = began;
        }
        Position position() { return new Position(x, y, z); }
    }
    private final Map<UUID, Mining> mining = new HashMap<>();
    private final Map<UUID, Mining> delayed = new HashMap<>();

    public void action(UUID player, long tick, RollbackInputActions.Kind kind, Position pos, Blocks blocks) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(pos);
        switch (kind) {
            case START_DESTROY_BLOCK -> {
                mining.remove(player);
                if (!blocks.mayBreak(pos)) return;
                if (blocks.creative()) {
                    blocks.destroy(pos);
                    return;
                }
                blocks.attack(pos);
                if (blocks.delta(pos) >= 1F) blocks.destroy(pos);
                else mining.put(player, new Mining(pos, tick));
            }
            case STOP_DESTROY_BLOCK -> {
                var current = mining.get(player);
                if (current == null || !current.position().equals(pos)) return;
                mining.remove(player);
                if (!blocks.mayBreak(pos)) return;
                if (progress(current, tick, blocks) >= .7F) blocks.destroy(pos);
                else delayed.putIfAbsent(player, current);
            }
            case ABORT_DESTROY_BLOCK -> mining.remove(player);
            default -> throw new IllegalArgumentException("Unexpected native mining intent");
        }
    }
    public void tick(UUID player, long tick, Blocks blocks) {
        var pending = delayed.get(player);
        if (pending == null) return;
        if (!blocks.mayBreak(pending.position())) delayed.remove(player);
        else if (progress(pending, tick, blocks) >= 1F) {
            delayed.remove(player);
            blocks.destroy(pending.position());
        }
    }
    private static float progress(Mining mine, long tick, Blocks blocks) {
        return blocks.delta(mine.position()) * (tick - mine.began + 1);
    }
}
