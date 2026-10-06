package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * One consistent duel seed for server and client construction. Decode does not create a
 * simulation or authorize a start: the loader must bind and validate all native services,
 * import the bending graph with its local catalog, and only then acknowledge readiness.
 */
public record RollbackBootstrapData(UUID session, UUID challenge, UUID match, int round,
                                    long epochMillis, long epochNanos, String definitions,
                                    Map<UUID, UUID> sides, RollbackWorldSeed world, RollbackRosterData roster,
                                    RollbackConfiguration.Data configuration, RollbackPlayerAccess access, RollbackMaterials materials, RollbackTags tags, RollbackServer.Metadata server, RollbackPlugins.Seed plugins, byte[] bending) {
    public static final int VERSION = 6, MAXIMUM_BYTES = 268_435_456, MAXIMUM_GRAPH_BYTES = 134_217_728;

    public RollbackBootstrapData {
        Objects.requireNonNull(session); Objects.requireNonNull(challenge); Objects.requireNonNull(match);
        Objects.requireNonNull(world); Objects.requireNonNull(roster); Objects.requireNonNull(configuration);
        Objects.requireNonNull(access); Objects.requireNonNull(materials); Objects.requireNonNull(tags); Objects.requireNonNull(server); Objects.requireNonNull(plugins);
        requireHash(definitions);
        sides = Collections.unmodifiableMap(new TreeMap<>(sides));
        if (round < 0 || sides.size() < 2 || sides.size() > RollbackRosterData.MAXIMUM_PLAYERS || sides.containsValue(null)
                || new HashSet<>(sides.values()).size() < 2 || !sides.keySet().equals(roster.players().keySet())
                || !sides.keySet().equals(access.players().keySet())) throw invalid("round/roster");
        for (var entry : roster.players().entrySet()) {
            var identity = entry.getValue().identity(); var profile = access.players().get(entry.getKey()).profile();
            if (!identity.mode().name().equals(profile.gameMode()) || !identity.client().mainHand().name().equals(profile.mainHand())) throw invalid("native/common player profile differs");
        }
        if (roster.worldTime() != world.settings().gameTime()) throw invalid("roster and world were captured at different ticks");
        if (bending.length < 49 || bending.length > MAXIMUM_GRAPH_BYTES) throw invalid("bending graph budget");
        bending = bending.clone();
    }
    @Override public byte[] bending() { return bending.clone(); }

    public byte[] encode(RollbackTerrainCodec.BlockStates states, RollbackTerrainCodec.Limits limits) {
        var worldBytes = world.encode(states, limits); var players = roster.encode(); var config = configuration.encode(); var accessBytes = access.encode(); var materialBytes = materials.encode(); var tagBytes = tags.encode(); var serverBytes = server.encode(); var pluginBytes = plugins.encode();
        long size = 144L + 32L * sides.size() + worldBytes.length + players.length + config.length + accessBytes.length + materialBytes.length + tagBytes.length + serverBytes.length + pluginBytes.length + bending.length;
        if (size > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var bytes = new ByteArrayOutputStream((int) size); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); uuid(out, session); uuid(out, challenge); uuid(out, match); out.writeInt(round);
            out.writeLong(epochMillis); out.writeLong(epochNanos); out.write(HexFormat.of().parseHex(definitions));
            out.writeInt(sides.size());
            for (var entry : sides.entrySet()) { uuid(out, entry.getKey()); uuid(out, entry.getValue()); }
            blob(out, worldBytes); blob(out, players); blob(out, config); blob(out, accessBytes); blob(out, materialBytes); blob(out, tagBytes); blob(out, serverBytes); blob(out, pluginBytes); blob(out, bending);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    /** Reject incompatible locally installed gameplay/registry definitions before decoding native terrain. */
    public static RollbackBootstrapData decode(byte[] bytes, String expectedDefinitions,
                                               RollbackTerrainCodec.BlockStates states, RollbackTerrainCodec.Limits limits) {
        Objects.requireNonNull(bytes); requireHash(expectedDefinitions);
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            var session = uuid(in); var challenge = uuid(in); var match = uuid(in); int round = in.readInt();
            long millis = in.readLong(), nanos = in.readLong();
            byte[] definitions = in.readNBytes(32);
            if (!MessageDigest.isEqual(definitions, HexFormat.of().parseHex(expectedDefinitions))) throw invalid("incompatible gameplay definitions");
            int count = in.readInt();
            if (count < 2 || count > RollbackRosterData.MAXIMUM_PLAYERS) throw invalid("roster size");
            var sides = new TreeMap<UUID, UUID>(); UUID previous = null;
            for (int i = 0; i < count; i++) {
                UUID id = uuid(in);
                if (previous != null && previous.compareTo(id) >= 0) throw invalid("roster order/duplicate");
                previous = id; sides.put(id, uuid(in));
            }
            var world = RollbackWorldSeed.decode(blob(in, RollbackWorldSeed.MAXIMUM_BYTES), states, limits);
            var roster = RollbackRosterData.decode(blob(in, RollbackRosterData.MAXIMUM_BYTES));
            var config = RollbackConfiguration.Data.decode(blob(in, RollbackConfiguration.MAXIMUM_BYTES));
            var access = RollbackPlayerAccess.decode(blob(in, RollbackPlayerAccess.MAXIMUM_BYTES));
            var materials = RollbackMaterials.decode(blob(in, RollbackMaterials.MAXIMUM_BYTES));
            var tags = RollbackTags.decode(blob(in, RollbackTags.MAXIMUM_BYTES));
            var server = RollbackServer.Metadata.decode(blob(in, RollbackServer.Metadata.MAXIMUM_BYTES));
            var plugins = RollbackPlugins.Seed.decode(blob(in, RollbackPlugins.MAXIMUM_BYTES));
            var graph = blob(in, MAXIMUM_GRAPH_BYTES);
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackBootstrapData(session, challenge, match, round, millis, nanos, expectedDefinitions, sides, world, roster, config, access, materials, tags, server, plugins, graph);
        } catch (IOException malformed) { throw new IllegalArgumentException("Invalid duel bootstrap: truncated/malformed data", malformed); }
    }

    /** Agreement covers the actual seed and metadata, not just an advertised build/version. */
    public static String fingerprint(byte[] encoded) {
        if (encoded.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("definition hash");
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in, int maximum) throws IOException {
        int size = in.readInt(); if (size < 0 || size > maximum || size > in.available()) throw invalid("component length");
        return in.readNBytes(size);
    }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid duel bootstrap: " + detail); }
}
