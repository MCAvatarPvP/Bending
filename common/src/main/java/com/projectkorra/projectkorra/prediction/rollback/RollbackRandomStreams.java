package com.projectkorra.projectkorra.prediction.rollback;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Settled RNG positions for one native world, its sound stream, and every enrolled body. */
public record RollbackRandomStreams(RollbackRandom.State world, RollbackRandom.State sound,
                                    Map<UUID, RollbackRandom.State> players) {
    public RollbackRandomStreams {
        Objects.requireNonNull(world);
        Objects.requireNonNull(sound);
        if (players.isEmpty() || players.size() > RollbackRosterData.MAXIMUM_PLAYERS)
            throw new IllegalArgumentException("Invalid random stream roster");
        players = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(players)));
    }

    /** The authenticated roster determines both stream order and the exact number of reads. */
    void write(DataOutput out) throws IOException {
        write(out, world);
        write(out, sound);
        for (var state : players.values()) write(out, state);
    }

    static RollbackRandomStreams read(DataInput in, Set<UUID> roster) throws IOException {
        if (roster.isEmpty() || roster.size() > RollbackRosterData.MAXIMUM_PLAYERS)
            throw new IllegalArgumentException("Invalid random stream roster");
        var world = read(in);
        var sound = read(in);
        var players = new TreeMap<UUID, RollbackRandom.State>();
        for (var id : new java.util.TreeSet<>(roster)) players.put(id, read(in));
        return new RollbackRandomStreams(world, sound, players);
    }

    private static void write(DataOutput out, RollbackRandom.State state) throws IOException {
        out.writeLong(state.bits());
        out.writeBoolean(state.hasGaussian());
        out.writeDouble(state.gaussian());
    }

    private static RollbackRandom.State read(DataInput in) throws IOException {
        long bits = in.readLong();
        int cached = in.readUnsignedByte();
        if (cached > 1) throw new IllegalArgumentException("Invalid Gaussian cache flag");
        return new RollbackRandom.State(bits, cached != 0, in.readDouble());
    }
}
