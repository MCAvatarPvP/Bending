package com.projectkorra.projectkorra.prediction.rollback;

/** Simulation time advances by fixed steps and is restored during replay. */
public final class RollbackClock {
    private static final ThreadLocal<Time> CURRENT = new ThreadLocal<>();

    private RollbackClock() {}

    public static long millis() {
        final Time time = CURRENT.get();
        return time == null ? System.currentTimeMillis() : time.millis;
    }

    public static long nanos() {
        final Time time = CURRENT.get();
        return time == null ? System.nanoTime() : time.nanos;
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    static Object identity() { return CURRENT.get(); }

    static long nextRandomSeed() {
        final Time time = CURRENT.get();
        if (time == null) throw new IllegalStateException("No simulation clock");
        long seed = time.millis ^ time.elapsedNanos ^ (++time.randomOrdinal * 0x9E3779B97F4A7C15L);
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        return seed ^ (seed >>> 31);
    }

    static Scope at(final long epochMillis, final long tick, final long stepNanos) {
        return at(epochMillis, 0, tick, stepNanos);
    }

    static Scope at(final long epochMillis, final long epochNanos, final long tick, final long stepNanos) {
        final long elapsed = Math.multiplyExact(tick, stepNanos);
        final Time previous = CURRENT.get();
        final Time current = new Time(Math.addExact(epochMillis, elapsed / 1_000_000L),
                epochNanos + elapsed, elapsed);
        CURRENT.set(current);
        return new Scope(previous, current);
    }

    static RollbackRandom random() {
        final Time time = CURRENT.get();
        if (time == null) throw new IllegalStateException("No simulation clock");
        if (time.random == null) time.random = new RollbackRandom(nextRandomSeed());
        return time.random;
    }

    private static final class Time {
        final long millis, nanos, elapsedNanos;
        long randomOrdinal;
        RollbackRandom random;

        Time(long millis, long nanos, long elapsedNanos) {
            this.millis = millis;
            this.nanos = nanos;
            this.elapsedNanos = elapsedNanos;
        }
    }

    static final class Scope implements AutoCloseable {
        private final Time previous;
        private final Time current;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope(final Time previous, final Time current) {
            this.previous = previous;
            this.current = current;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Simulation time crossed threads");
            if (closed) return;
            if (CURRENT.get() != current) throw new IllegalStateException("Simulation clock scopes closed out of order");
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
