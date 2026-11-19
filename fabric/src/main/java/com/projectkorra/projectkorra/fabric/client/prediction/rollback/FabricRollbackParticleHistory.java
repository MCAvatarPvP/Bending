package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackArenaActions.ParticleOutput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import java.util.*;
import java.util.function.Consumer;

/** Tracks only unconfirmed visual handles. Tick and effect ordinal identify a draw across replay. */
final class FabricRollbackParticleHistory<P> {
    interface Draw<P> { List<P> draw(long tick, int ordinal, ParticleOutput effect, int count); }
    private record Visual(ParticleOutput effect, int count) {}
    private record Drawn<P>(Visual visual, List<P> handles) {}
    private final UUID viewer;
    private final Draw<P> draw;
    private final Consumer<P> remove;
    private final NavigableMap<Long, Map<Integer, Drawn<P>>> pending = new TreeMap<>();

    FabricRollbackParticleHistory(UUID viewer, Draw<P> draw, Consumer<P> remove) {
        this.viewer = viewer;
        this.draw = draw;
        this.remove = remove;
    }

    void frame(long tick, List<?> effects) {
        Map<Integer, Drawn<P>> previous = pending.remove(tick);
        Map<Integer, Drawn<P>> next = null;
        int remaining = 16_384;
        for (int i = 0; i < effects.size() && remaining > 0; i++) {
            if (!(effects.get(i) instanceof ParticleOutput effect) || effect.viewer() != null && !viewer.equals(effect.viewer())) continue;
            int count = Math.min(remaining, Math.max(1, effect.count()));
            remaining -= count;
            Visual visual = new Visual(effect, count);
            Drawn<P> existing = previous == null ? null : previous.remove(i);
            if (existing == null || !existing.visual().equals(visual)) {
                if (existing != null) existing.handles().forEach(remove);
                existing = new Drawn<>(visual, draw.draw(tick, i, effect, count));
            }
            if (next == null) next = new HashMap<>();
            next.put(i, existing);
        }
        if (previous != null) previous.values().forEach(entry -> entry.handles().forEach(remove));
        if (next != null) pending.put(tick, next);
    }

    void confirm(long frontier, List<RollbackEngine.Effect<Object>> finalized) {
        if (finalized.isEmpty() && (pending.isEmpty() || pending.firstKey() > frontier)) return;
        // Finalization removes the original frame from the timeline. The journal still carries
        // every effect in ordinal order, including effects corrected in the same update.
        int offset = 0;
        while (offset < finalized.size()) {
            long tick = finalized.get(offset).tick();
            int start = offset;
            while (offset < finalized.size() && finalized.get(offset).tick() == tick) offset++;
            var row = finalized.subList(start, offset);
            frame(tick, new AbstractList<Object>() {
                @Override public Object get(int index) { return row.get(index).value(); }
                @Override public int size() { return row.size(); }
            });
            pending.remove(tick);
        }
        // A previously drawn tick whose corrected journal is empty lost all its effects.
        var vanished = pending.headMap(frontier, true);
        vanished.values().forEach(this::cancel);
        vanished.clear();
    }
    private void cancel(Map<Integer, Drawn<P>> frame) {
        frame.values().forEach(entry -> entry.handles().forEach(remove));
    }
    void clear() { pending.values().forEach(this::cancel); pending.clear(); }
}
