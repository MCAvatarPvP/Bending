package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.*;

/** Each victim's own scoreboard determines which attackers share a team with friendly fire disabled. */
public record RollbackCombatTeams(Map<UUID, Set<UUID>> protectedTeams) {
    public static final RollbackCombatTeams EMPTY = new RollbackCombatTeams(Map.of());
    public RollbackCombatTeams {
        if (protectedTeams.size() > 128) throw new IllegalArgumentException("Combat team roster size");
        var rows = new TreeMap<UUID, Set<UUID>>();
        protectedTeams.forEach((defender, members) -> {
            if (!members.contains(defender) || members.size() > 128) throw new IllegalArgumentException("Combat team members");
            rows.put(defender, Collections.unmodifiableSet(new TreeSet<>(members)));
        });
        protectedTeams = Collections.unmodifiableMap(rows);
    }
    public boolean canHarm(UUID shooter, UUID victim) {
        return !protectedTeams.getOrDefault(victim, Set.of()).contains(shooter);
    }
    void write(DataOutput out) throws IOException {
        out.writeByte(protectedTeams.size());
        for (var row : protectedTeams.entrySet()) {
            writeId(out, row.getKey()); out.writeByte(row.getValue().size());
            for (UUID member : row.getValue()) writeId(out, member);
        }
    }
    static RollbackCombatTeams read(DataInput in) throws IOException {
        int count = in.readUnsignedByte();
        if (count > 128) throw new IllegalArgumentException("Combat team roster size");
        var teams = new TreeMap<UUID, Set<UUID>>();
        for (int i = 0; i < count; i++) {
            UUID victim = readId(in); int size = in.readUnsignedByte();
            if (size > 128) throw new IllegalArgumentException("Combat team members");
            var members = new TreeSet<UUID>();
            for (int j = 0; j < size; j++) if (!members.add(readId(in))) throw new IllegalArgumentException("Repeated teammate");
            if (teams.putIfAbsent(victim, members) != null) throw new IllegalArgumentException("Repeated combat team");
        }
        return new RollbackCombatTeams(teams);
    }
    private static UUID readId(DataInput in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeId(DataOutput out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
}
