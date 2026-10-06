package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.random.RandomSplitter;

/** Matches the installed Paper 1.21.11 LegacyRandomSource, including its float-rounded double draws. */
final class FabricRollbackPaperRandom extends CheckedRandom {
    FabricRollbackPaperRandom(long seed) { super(seed); }
    @Override public double nextDouble() {
        long bits = ((long) next(26) << 27) + next(27);
        // Paper's compiled BitRandomSource multiplies as float, then widens to double.
        return bits * 1.110223E-16F;
    }
    @Override public Random split() { return new FabricRollbackPaperRandom(nextLong()); }
    @Override public RandomSplitter nextSplitter() { return new Splitter(nextLong()); }
    private record Splitter(long seed) implements RandomSplitter {
        @Override public Random split(int x, int y, int z) { return new FabricRollbackPaperRandom(MathHelper.hashCode(x, y, z) ^ seed); }
        @Override public Random split(String name) { return new FabricRollbackPaperRandom(name.hashCode() ^ seed); }
        @Override public Random split(long value) { return new FabricRollbackPaperRandom(value); }
        @Override public void addDebugInfo(StringBuilder info) { info.append("LegacyPositionalRandomFactory{").append(seed).append('}'); }
    }
}
