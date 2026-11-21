package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-captured player policy for one duel. Contains no scoreboard or permission-provider internals. */
public record RollbackPlayerAccess(Map<UUID, Entry> players) {
    public static final int VERSION = 1, MAXIMUM_BYTES = 16_777_216, MAXIMUM_PERMISSIONS = 65_536;
    public static final int MAXIMUM_DECISIONS = 1_048_576;

    public record Entry(UUID player, RollbackPlayerState.Profile profile, Set<UUID> hidden, Map<String, Boolean> permissions) {
        public Entry {
            Objects.requireNonNull(player); Objects.requireNonNull(profile); utf8(profile.displayName(), 65_536);
            RollbackRosterData.Mode.valueOf(profile.gameMode());
            if (!profile.online() || profile.ping() < 0) throw invalid("profile");
            hidden = Collections.unmodifiableSet(new TreeSet<>(hidden));
            if (hidden.size() > RollbackRosterData.MAXIMUM_PLAYERS || permissions.size() > MAXIMUM_PERMISSIONS) throw invalid("player budget");
            var copy = new TreeMap<String, Boolean>();
            permissions.forEach((node, allowed) -> {
                if (!node.equals(permissionName(node))) throw invalid("noncanonical permission");
                copy.put(node, Objects.requireNonNull(allowed));
            });
            permissions = Collections.unmodifiableMap(copy);
        }
        /** Unknown decisions cannot safely be inferred from operator status, wildcards or the local client. */
        public boolean hasPermission(String name) {
            Boolean allowed = permissions.get(permissionName(name));
            if (allowed == null) throw new IllegalStateException("Permission was not captured for rollback: " + name);
            return allowed;
        }
    }

    public RollbackPlayerAccess {
        if (players.size() < 2 || players.size() > RollbackRosterData.MAXIMUM_PLAYERS) throw invalid("roster size");
        players = Collections.unmodifiableMap(new TreeMap<>(players)); int decisions = 0;
        for (var mapping : players.entrySet()) {
            var entry = Objects.requireNonNull(mapping.getValue());
            if (!mapping.getKey().equals(entry.player())) throw invalid("player identity");
            if (!players.keySet().containsAll(entry.hidden())) throw invalid("visibility outside roster");
            decisions = Math.addExact(decisions, entry.permissions().size());
            if (decisions > MAXIMUM_DECISIONS) throw invalid("decision budget");
        }
    }

    public static String permissionName(String value) {
        Objects.requireNonNull(value); String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) throw invalid("blank permission");
        utf8(normalized, 1_024); return normalized;
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(new FilterOutputStream(bytes) {
                int count;
                private void reserve(int size) { if (size < 0 || size > MAXIMUM_BYTES - count) throw invalid("wire budget"); count += size; }
                @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); out.write(value, offset, length); }
            });
            out.writeInt(VERSION); out.writeInt(players.size());
            for (var entry : players.entrySet()) {
                uuid(out, entry.getKey()); var value = entry.getValue(); var profile = value.profile();
                text(out, profile.displayName(), 65_536); text(out, profile.gameMode(), 32); text(out, profile.mainHand(), 16);
                out.writeBoolean(profile.online()); out.writeBoolean(profile.operator()); out.writeBoolean(profile.playedBefore()); out.writeInt(profile.ping());
                out.writeInt(value.hidden().size()); for (var id : value.hidden()) uuid(out, id);
                out.writeInt(value.permissions().size());
                for (var permission : value.permissions().entrySet()) { text(out, permission.getKey(), 1_024); out.writeBoolean(permission.getValue()); }
            }
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    public static RollbackPlayerAccess decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            int count = bounded(in.readInt(), RollbackRosterData.MAXIMUM_PLAYERS), decisions = 0;
            var players = new TreeMap<UUID, Entry>(); UUID previous = null;
            for (int i = 0; i < count; i++) {
                UUID id = uuid(in); ordered(previous, id); previous = id;
                var profile = new RollbackPlayerState.Profile(text(in, 65_536), text(in, 32), text(in, 16), bool(in), bool(in), bool(in), in.readInt());
                int size = bounded(in.readInt(), count); var hidden = new TreeSet<UUID>(); UUID last = null;
                for (int j = 0; j < size; j++) { var hiddenId = uuid(in); ordered(last, hiddenId); last = hiddenId; hidden.add(hiddenId); }
                int nodes = bounded(in.readInt(), MAXIMUM_PERMISSIONS); decisions += nodes;
                if (decisions > MAXIMUM_DECISIONS) throw invalid("decision budget");
                var permissions = new TreeMap<String, Boolean>(); String lastNode = null;
                for (int j = 0; j < nodes; j++) {
                    String node = text(in, 1_024);
                    if (lastNode != null && lastNode.compareTo(node) >= 0) throw invalid("permission order/duplicate");
                    lastNode = node; permissions.put(node, bool(in));
                }
                players.put(id, new Entry(id, profile, hidden, permissions));
            }
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackPlayerAccess(players);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid player access: malformed/truncated data", failure); }
    }

    private static void ordered(UUID previous, UUID next) { if (previous != null && previous.compareTo(next) >= 0) throw invalid("UUID order/duplicate"); }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void text(DataOutputStream out, String value, int maximum) throws IOException { byte[] bytes = utf8(value, maximum); out.writeInt(bytes.length); out.write(bytes); }
    private static String text(DataInputStream in, int maximum) throws IOException {
        int size = bounded(in.readInt(), maximum); if (size > in.available()) throw invalid("text length");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(in.readNBytes(size))).toString();
    }
    private static byte[] utf8(String value, int maximum) {
        Objects.requireNonNull(value); if (value.length() > maximum) throw invalid("text budget");
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (encoded.remaining() > maximum) throw invalid("text budget"); byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (java.nio.charset.CharacterCodingException failure) { throw new IllegalArgumentException("Invalid player access: text encoding", failure); }
    }
    private static int bounded(int value, int maximum) { if (value < 0 || value > maximum) throw invalid("count/flag"); return value; }
    private static boolean bool(DataInputStream in) throws IOException { return bounded(in.readUnsignedByte(), 1) == 1; }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid player access: " + detail); }
}
