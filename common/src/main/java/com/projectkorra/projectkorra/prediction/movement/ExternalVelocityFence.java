package com.projectkorra.projectkorra.prediction.movement;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loader-neutral ordering fence for a server-owned impulse applied to the
 * locally predicted player. Values are intentionally opaque so this policy
 * can be tested without either Bukkit or Minecraft client classes.
 */
public final class ExternalVelocityFence<V> {
    private static final long NOT_COMMITTED = Long.MIN_VALUE;
    private final Map<Integer, Entry<V>> entries = new HashMap<>();

    public void receive(final int entityId, final V velocity) {
        receive(entityId, velocity, false);
    }

    public void receive(final int entityId, final V velocity, final boolean retainUntilAuthority) {
        if (entityId >= 0 && velocity != null) {
            entries.put(entityId, new Entry<>(velocity, retainUntilAuthority));
        }
    }

    public void release(final int entityId) {
        entries.remove(entityId);
    }

    public boolean blocksPredictedWrite(final int entityId) {
        return entries.containsKey(entityId);
    }

    public boolean isCommitted(final int entityId) {
        final Entry<V> entry = entries.get(entityId);
        return entry != null && entry.committedTick != NOT_COMMITTED;
    }

    public boolean isRetained(final int entityId) {
        final Entry<V> entry = entries.get(entityId);
        return entry != null && entry.retainUntilAuthority;
    }

    /**
     * Called after one local ability-progress heartbeat. The vector is
     * returned exactly once. Its fence remains through the following
     * heartbeat so movement can consume the impulse before Scooter/Jet writes
     * again; that second call retires the fence without replaying the vector.
     */
    public Optional<V> afterLocalProgress(final int entityId, final long clientTick) {
        final Entry<V> entry = entries.get(entityId);
        if (entry == null) return Optional.empty();
        if (entry.committedTick == NOT_COMMITTED) {
            entry.committedTick = clientTick;
            return Optional.of(entry.velocity);
        }
        if (!entry.retainUntilAuthority && clientTick > entry.committedTick) {
            entries.remove(entityId, entry);
        }
        return Optional.empty();
    }

    public Set<Integer> entityIds() {
        return Set.copyOf(entries.keySet());
    }

    public void clear() {
        entries.clear();
    }

    private static final class Entry<V> {
        private final V velocity;
        private final boolean retainUntilAuthority;
        private long committedTick = NOT_COMMITTED;

        private Entry(final V velocity, final boolean retainUntilAuthority) {
            this.velocity = velocity;
            this.retainUntilAuthority = retainUntilAuthority;
        }
    }
}
