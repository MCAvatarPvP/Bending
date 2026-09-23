package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;

/** Detached authoritative inputs; state import and native presentation have their own adapters. */
public record RollbackAuthorityUpdate(UUID session, long publication, long revision, long headTick,
        long finalizedTick, long firstTick, List<Map<UUID, RollbackPlayerInput>> frames,
        Map<UUID, List<Long>> receivedTicks) {
    public static final int MAXIMUM_FRAMES = 201;
    public static final int MAXIMUM_RECEIPTS = 221;

    public RollbackAuthorityUpdate {
        Objects.requireNonNull(session, "session");
        if (publication < 1 || revision < 0 || firstTick < 1 || headTick < firstTick || headTick == Long.MAX_VALUE
                || finalizedTick < 0 || finalizedTick > headTick || headTick - firstTick >= MAXIMUM_FRAMES
                || frames.size() != headTick - firstTick + 1) throw new IllegalArgumentException("Authority update bounds");
        Set<UUID> roster = Set.copyOf(frames.getFirst().keySet());
        if (roster.isEmpty() || roster.size() > 128 || !receivedTicks.keySet().equals(roster)) {
            throw new IllegalArgumentException("Authority update roster");
        }
        var copied = new ArrayList<Map<UUID, RollbackPlayerInput>>();
        for (var frame : frames) {
            if (!frame.keySet().equals(roster)) throw new IllegalArgumentException("Incomplete authority frame");
            frame.values().forEach(value -> Objects.requireNonNull(value, "input"));
            copied.add(Collections.unmodifiableMap(new TreeMap<>(frame)));
        }
        frames = List.copyOf(copied);
        var receipts = new TreeMap<UUID, List<Long>>();
        receivedTicks.forEach((player, ticks) -> {
            if (ticks.size() > MAXIMUM_RECEIPTS) throw new IllegalArgumentException("Authority receipt budget");
            long previous = finalizedTick;
            for (long tick : ticks) {
                if (tick <= previous || tick > headTick && tick - headTick > 21) throw new IllegalArgumentException("Authority receipt order/bounds");
                previous = tick;
            }
            receipts.put(player, List.copyOf(ticks));
        });
        receivedTicks = Collections.unmodifiableMap(receipts);
    }
}
