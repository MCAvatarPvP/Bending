package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Change;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainDelta;

import java.io.*;
import java.util.*;

/** Detached components of the confirmed duel; construction metadata comes from the authenticated bootstrap. */
public record RollbackConfirmedData(RollbackRound.Data round, RollbackRosterData roster, RollbackRandomStreams random,
        Map<UUID, Long> actionSequences, List<Change> terrain, RollbackActorData actors, byte[] bending,
        RollbackScheduledTicks scheduledTicks) {
    public RollbackConfirmedData(RollbackRound.Data round, RollbackRosterData roster, RollbackRandomStreams random,
            Map<UUID, Long> actionSequences, List<Change> terrain, RollbackActorData actors, byte[] bending) {
        this(round, roster, random, actionSequences, terrain, actors, bending, null);
    }
    public static final int VERSION = 2;

    public RollbackConfirmedData {
        Objects.requireNonNull(round); Objects.requireNonNull(roster); Objects.requireNonNull(random); Objects.requireNonNull(actors);
        actionSequences = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(actionSequences)));
        var participants = roster.players().keySet();
        var history = roster.combatActors();
        if (history != null) history.requireHistoryFor(actors);
        if (!random.players().keySet().equals(participants) || !actionSequences.keySet().equals(participants)
                || actionSequences.values().stream().anyMatch(sequence -> sequence < 0)
                || !participants.containsAll(round.attackers().keySet()) || !participants.containsAll(round.attackers().values()))
            throw new IllegalArgumentException("Confirmed components disagree on roster");
        if (bending.length < 49 || bending.length > RollbackBootstrapData.MAXIMUM_GRAPH_BYTES)
            throw new IllegalArgumentException("Confirmed gameplay graph size");
        terrain = List.copyOf(terrain);
        bending = bending.clone();
    }
    @Override public byte[] bending() { return bending.clone(); }

    private void requireMatch(RollbackBootstrapData initial) {
        if (!initial.session().equals(actors.session()) || !initial.sides().keySet().equals(roster.players().keySet())
                || roster.worldTime() != Math.addExact(initial.roster().worldTime(), round.tick()))
            throw new IllegalArgumentException("Confirmed checkpoint differs from duel");
        roster.players().forEach((id, player) -> {
            var start = initial.roster().players().get(id);
            if (player.identity().entityId() != start.identity().entityId() || player.randomSeed() != start.randomSeed())
                throw new IllegalArgumentException("Confirmed body differs from enrolled identity");
        });
        if (initial.world().scheduledTicks() != null && scheduledTicks == null)
            throw new IllegalArgumentException("Confirmed checkpoint is missing native terrain queues");
        if (scheduledTicks != null) {
            var original = initial.world().ticks();
            if (!scheduledTicks.bounds().equals(original.bounds()) || scheduledTicks.maximumBlockTicks() != original.maximumBlockTicks()
                    || scheduledTicks.maximumFluidTicks() != original.maximumFluidTicks())
                throw new IllegalArgumentException("Confirmed terrain queue region or policy changed");
        }
        var policy = new RollbackRound(initial.session(), initial.sides());
        policy.validateData(round);
    }

    public byte[] encode(RollbackBootstrapData initial, RollbackTerrainCodec.BlockStates states,
            RollbackTerrainCodec.Limits terrainLimits, int maximumBytes) {
        budget(maximumBytes);
        requireMatch(initial);
        var players = roster.encode();
        var changes = RollbackTerrainDelta.encode(terrain, initial.world().terrain().bounds(), states, terrainLimits);
        var spawned = actors.encode();
        byte[] ticks = scheduledTicks == null ? null : scheduledTicks.encode();
        var order = List.copyOf(roster.players().keySet());
        // Format, simulation tick, two fixed streams, one stream and two scalars per player,
        // defeat count and entries, and four length-prefixed sections.
        long size = 4L + 8 + 34 + (17L + 8 + 4) * order.size() + 4 + 16L * round.defeats().size()
                + 16 + players.length + changes.length + spawned.length + bending.length + (ticks == null ? 0 : 4L + ticks.length);
        if (size > maximumBytes) throw new IllegalArgumentException("Confirmed checkpoint byte budget");
        try {
            var bytes = new ByteArrayOutputStream((int) size);
            var out = new DataOutputStream(bytes);
            out.writeInt(ticks == null ? 1 : VERSION); out.writeLong(round.tick()); random.write(out);
            for (var id : order) {
                out.writeLong(actionSequences.get(id));
                var attacker = round.attackers().get(id);
                out.writeInt(attacker == null ? -1 : order.indexOf(attacker));
            }
            out.writeInt(round.defeats().size());
            for (var defeat : round.defeats()) {
                out.writeLong(defeat.tick());
                out.writeInt(order.indexOf(defeat.player()));
                out.writeInt(defeat.lastAttacker() == null ? -1 : order.indexOf(defeat.lastAttacker()));
            }
            blob(out, players); blob(out, changes); blob(out, spawned); blob(out, bending);
            if (ticks != null) blob(out, ticks);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static RollbackConfirmedData decode(byte[] bytes, long expectedTick, RollbackBootstrapData initial,
            RollbackTerrainCodec.BlockStates states, RollbackTerrainCodec.Limits terrainLimits, int maximumBytes) {
        budget(maximumBytes);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("Confirmed checkpoint byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version < 1 || version > VERSION || in.readLong() != expectedTick || expectedTick < 0)
                throw new IllegalArgumentException("Confirmed checkpoint version or frontier");
            var order = List.copyOf(initial.roster().players().keySet());
            var random = RollbackRandomStreams.read(in, initial.sides().keySet());
            var sequences = new TreeMap<UUID, Long>();
            var attackers = new TreeMap<UUID, UUID>();
            for (var id : order) {
                sequences.put(id, in.readLong());
                UUID attacker = member(order, in.readInt(), true);
                if (attacker != null) attackers.put(id, attacker);
            }
            int count = in.readInt();
            if (count < 0 || count > order.size()) throw new IllegalArgumentException("Confirmed defeat count");
            var defeats = new ArrayList<RollbackRound.Defeat>(count);
            for (int i = 0; i < count; i++)
                defeats.add(new RollbackRound.Defeat(initial.session(), in.readLong(), member(order, in.readInt(), false), member(order, in.readInt(), true)));
            var players = blob(in, RollbackRosterData.MAXIMUM_BYTES);
            var changes = blob(in, terrainLimits.maximumWireBytes());
            var actors = blob(in, RollbackActorData.MAXIMUM_BYTES);
            var bending = blob(in, RollbackBootstrapData.MAXIMUM_GRAPH_BYTES);
            var ticks = version >= 2 ? RollbackScheduledTicks.decode(blob(in, RollbackScheduledTicks.MAXIMUM_BYTES)) : null;
            if (in.available() != 0) throw new IllegalArgumentException("Trailing confirmed state");
            var value = new RollbackConfirmedData(new RollbackRound.Data(expectedTick, attackers, defeats), RollbackRosterData.decode(players),
                    random, sequences, RollbackTerrainDelta.decode(changes, initial.world().terrain().bounds(), states, terrainLimits),
                    RollbackActorData.decode(actors), bending, ticks);
            value.requireMatch(initial);
            return value;
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated confirmed checkpoint", malformed); }
    }

    private static UUID member(List<UUID> roster, int index, boolean optional) {
        if (optional && index == -1) return null;
        if (index < 0 || index >= roster.size()) throw new IllegalArgumentException("Confirmed participant index");
        return roster.get(index);
    }
    private static void budget(int maximum) {
        if (maximum < 1 || maximum > RollbackStateCorrection.MAXIMUM_BYTES - RollbackStateCorrection.HEADER_BYTES)
            throw new IllegalArgumentException("Confirmed checkpoint byte budget");
    }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > maximum || length > in.available()) throw new IllegalArgumentException("Confirmed component length");
        return in.readNBytes(length);
    }
}
