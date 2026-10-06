package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Java Random-compatible stream with explicit checkpointable state, including Gaussian caching. */
public final class RollbackRandom extends Random implements RollbackStateCell<RollbackRandom.State> {
    private static final long serialVersionUID = 1L;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long MASK = (1L << 48) - 1;
    private long bits;
    private boolean hasGaussian;
    private double gaussian;

    public record State(long bits, boolean hasGaussian, double gaussian) {}

    public RollbackRandom() {
        this(RollbackClock.active() ? RollbackClock.nextRandomSeed() : ThreadLocalRandom.current().nextLong());
    }

    public RollbackRandom(final long seed) {
        super(0L);
        setSeed(seed);
    }

    /** Per-step stream during replay; ordinary thread-local randomness outside simulation. */
    public static Random shared() {
        return RollbackClock.active() ? RollbackClock.random() : ThreadLocalRandom.current();
    }

    /** Retains Math.random's ordinary path when no rollback context is installed. */
    public static double fraction() {
        return RollbackClock.active() ? RollbackClock.random().nextDouble() : Math.random();
    }

    /** Stable simulation identity; session/connection authentication must still use secure random IDs. */
    public static UUID uuid() {
        if (!RollbackClock.active()) return UUID.randomUUID();
        final Random random = shared();
        return new UUID((random.nextLong() & 0xffffffffffff0fffL) | 0x4000L,
                (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }

    @Override public synchronized void setSeed(final long seed) {
        bits = (seed ^ MULTIPLIER) & MASK;
        hasGaussian = false;
        gaussian = 0;
    }

    @Override protected synchronized int next(final int count) {
        bits = (bits * MULTIPLIER + 0xBL) & MASK;
        return (int) (bits >>> (48 - count));
    }

    @Override public synchronized double nextGaussian() {
        if (hasGaussian) {
            hasGaussian = false;
            return gaussian;
        }
        double first, second, squared;
        do {
            first = 2 * nextDouble() - 1;
            second = 2 * nextDouble() - 1;
            squared = first * first + second * second;
        } while (squared >= 1 || squared == 0);
        final double scale = StrictMath.sqrt(-2 * StrictMath.log(squared) / squared);
        gaussian = second * scale;
        hasGaussian = true;
        return first * scale;
    }

    @Override public synchronized State captureRollbackState() {
        return new State(bits, hasGaussian, gaussian);
    }

    @Override public synchronized void restoreRollbackState(final State state) {
        bits = state.bits();
        hasGaussian = state.hasGaussian();
        gaussian = state.gaussian();
    }
}
