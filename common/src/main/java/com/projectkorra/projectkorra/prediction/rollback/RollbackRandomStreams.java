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
        world.write(out);
        sound.write(out);
        for (var state : players.values()) state.write(out);
    }

    static RollbackRandomStreams read(DataInput in, Set<UUID> roster) throws IOException {
        if (roster.isEmpty() || roster.size() > RollbackRosterData.MAXIMUM_PLAYERS)
            throw new IllegalArgumentException("Invalid random stream roster");
        var world = RollbackRandom.State.read(in);
        var sound = RollbackRandom.State.read(in);
        var players = new TreeMap<UUID, RollbackRandom.State>();
        for (var id : new java.util.TreeSet<>(roster)) players.put(id, RollbackRandom.State.read(in));
        return new RollbackRandomStreams(world, sound, players);
    }
}
