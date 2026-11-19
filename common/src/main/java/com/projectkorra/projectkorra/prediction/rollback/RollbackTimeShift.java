package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Translates the confirmed frame's timestamps when an enrolled roster returns to live gameplay. */
public final class RollbackTimeShift {
    public static final RollbackTimeShift NONE = new RollbackTimeShift(0, 0);
    private final long offset, nanoOffset;
    private final Set<Object> shifted = Collections.newSetFromMap(new IdentityHashMap<>());

    private RollbackTimeShift(long offset, long nanoOffset) { this.offset = offset; this.nanoOffset = nanoOffset; }
    public static RollbackTimeShift between(long confirmedMillis, long liveMillis) {
        return between(confirmedMillis, liveMillis, 0, 0);
    }
    public static RollbackTimeShift between(long confirmedMillis, long liveMillis, long confirmedNanos, long liveNanos) {
        if (confirmedMillis < 0 || liveMillis < 0) throw new IllegalArgumentException("Invalid wall clock at handoff");
        return confirmedMillis == liveMillis && confirmedNanos == liveNanos ? NONE
                : new RollbackTimeShift(liveMillis - confirmedMillis, liveNanos - confirmedNanos);
    }
    public boolean active() { return offset != 0 || nanoOffset != 0; }

    /** Nonpositive markers and an unlimited deadline keep their original meaning. */
    public long time(long timestamp) {
        if (timestamp <= 0 || timestamp == Long.MAX_VALUE || offset == 0) return timestamp;
        if (offset > 0 && timestamp > Long.MAX_VALUE - offset) return Long.MAX_VALUE;
        return Math.max(1L, timestamp + offset);
    }

    /** Monotonic timestamps use signed subtraction and wrapping arithmetic, just like System.nanoTime(). */
    public long nanos(long timestamp) { return timestamp + nanoOffset; }

    /** Shared cooldown objects and tables can appear under multiple owners in the transferred graph. */
    public void once(Object value, Runnable adjust) {
        if (!active() || !shifted.add(value)) return;
        try {
            adjust.run();
        } catch (RuntimeException | Error failure) {
            shifted.remove(value);
            throw failure;
        }
    }
}
