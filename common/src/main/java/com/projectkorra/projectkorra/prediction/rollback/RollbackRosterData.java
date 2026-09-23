package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Intrinsic native player bootstrap. World, bending graphs and service seeds accompany this component. */
public record RollbackRosterData(long worldTime, Map<UUID, Player> players) {
    public static final int VERSION = 1, MAXIMUM_PLAYERS = 128, MAXIMUM_BYTES = 16_777_216;
    public static final int MAXIMUM_PROPERTIES = 64, MAXIMUM_PROPERTY_BYTES = 65_536;
    public enum Mode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }
    public enum Chat { FULL, SYSTEM, HIDDEN }
    public enum Hand { LEFT, RIGHT }
    public enum Particles { ALL, DECREASED, MINIMAL }

    /** Keep the multimap key separately from the property name, and preserve duplicate entries. */
    public record Property(String key, String name, String value, String signature) {
        public Property {
            utf8(key, 256); utf8(name, 256); utf8(value, MAXIMUM_PROPERTY_BYTES);
            if (signature != null) utf8(signature, MAXIMUM_PROPERTY_BYTES);
        }
    }
    public record Client(String language, int viewDistance, Chat chat, boolean colors, int modelParts,
                         Hand mainHand, boolean filterText, boolean listable, Particles particles) {
        public Client {
            utf8(language, 64);
            if (language.length() > 16 || viewDistance < -128 || viewDistance > 127 || modelParts < 0 || modelParts > 255) throw invalid("client settings");
            Objects.requireNonNull(chat); Objects.requireNonNull(mainHand); Objects.requireNonNull(particles);
        }
    }
    public record Identity(UUID id, int entityId, String name, List<Property> properties, Mode mode, Client client) {
        public Identity {
            Objects.requireNonNull(id); Objects.requireNonNull(mode); Objects.requireNonNull(client);
            utf8(name, 64);
            if (name.isEmpty() || name.length() > 16 || properties.size() > MAXIMUM_PROPERTIES) throw invalid("player identity");
            properties = List.copyOf(properties);
        }
    }
    public record Player(Identity identity, long randomSeed, RollbackPlayerValues values, RollbackPlayerVitals vitals,
                         RollbackPlayerItems items, RollbackPlayerContext context, RollbackPlayerCombatData combat) {
        public Player {
            Objects.requireNonNull(identity); Objects.requireNonNull(values); Objects.requireNonNull(vitals);
            Objects.requireNonNull(items); Objects.requireNonNull(context); Objects.requireNonNull(combat);
            if (!identity.id().equals(combat.owner())) throw invalid("combat owner differs from identity");
        }
    }
    public RollbackRosterData {
        if (players.isEmpty() || players.size() > MAXIMUM_PLAYERS) throw invalid("roster size");
        players = Collections.unmodifiableMap(new TreeMap<>(players));
        var entityIds = new HashSet<Integer>();
        for (var entry : players.entrySet()) {
            var player = Objects.requireNonNull(entry.getValue());
            if (!entry.getKey().equals(player.identity().id()) || !entityIds.add(player.identity().entityId())) throw invalid("duplicate/mismatched identity");
            player.combat().requireRoster(players.keySet());
        }
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(new FilterOutputStream(bytes) {
                int count;
                private void reserve(int n) { if (n < 0 || n > MAXIMUM_BYTES - count) throw invalid("wire budget"); count += n; }
                @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); out.write(value, offset, length); }
            });
            out.writeInt(VERSION); out.writeLong(worldTime); out.writeInt(players.size());
            for (var player : players.values()) {
                var identity = player.identity();
                out.writeLong(identity.id().getMostSignificantBits()); out.writeLong(identity.id().getLeastSignificantBits());
                out.writeInt(identity.entityId()); text(out, identity.name(), 64); out.writeInt(identity.properties().size());
                for (var property : identity.properties()) {
                    text(out, property.key(), 256); text(out, property.name(), 256); text(out, property.value(), MAXIMUM_PROPERTY_BYTES);
                    out.writeBoolean(property.signature() != null);
                    if (property.signature() != null) text(out, property.signature(), MAXIMUM_PROPERTY_BYTES);
                }
                out.writeByte(identity.mode().ordinal()); var c = identity.client();
                text(out, c.language(), 64); out.writeByte(c.viewDistance()); out.writeByte(c.chat().ordinal()); out.writeBoolean(c.colors());
                out.writeByte(c.modelParts()); out.writeByte(c.mainHand().ordinal()); out.writeBoolean(c.filterText()); out.writeBoolean(c.listable()); out.writeByte(c.particles().ordinal());
                out.writeLong(player.randomSeed());
                blob(out, player.values().encode()); blob(out, player.vitals().encode()); blob(out, player.items().encode());
                blob(out, player.context().encode()); blob(out, player.combat().encode());
            }
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackRosterData decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            long time = in.readLong(); int count = bounded(in.readInt(), MAXIMUM_PLAYERS);
            var players = new TreeMap<UUID, Player>(); UUID previous = null;
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                if (previous != null && previous.compareTo(id) >= 0) throw invalid("roster order/duplicate");
                previous = id;
                int entityId = in.readInt(); String name = text(in, 64); int size = bounded(in.readInt(), MAXIMUM_PROPERTIES);
                var properties = new ArrayList<Property>(size);
                for (int j = 0; j < size; j++) properties.add(new Property(text(in, 256), text(in, 256), text(in, MAXIMUM_PROPERTY_BYTES), bool(in) ? text(in, MAXIMUM_PROPERTY_BYTES) : null));
                Mode mode = choice(in, Mode.values());
                var client = new Client(text(in, 64), in.readByte(), choice(in, Chat.values()), bool(in), in.readUnsignedByte(), choice(in, Hand.values()), bool(in), bool(in), choice(in, Particles.values()));
                var identity = new Identity(id, entityId, name, properties, mode, client); long randomSeed = in.readLong();
                players.put(id, new Player(identity, randomSeed,
                        RollbackPlayerValues.decode(blob(in, RollbackPlayerValues.MAXIMUM_BYTES)), RollbackPlayerVitals.decode(blob(in, RollbackPlayerVitals.MAXIMUM_BYTES)),
                        RollbackPlayerItems.decode(blob(in, RollbackPlayerItems.MAXIMUM_BYTES)), RollbackPlayerContext.decode(blob(in, RollbackPlayerContext.MAXIMUM_BYTES)),
                        RollbackPlayerCombatData.decode(blob(in, RollbackPlayerCombatData.MAXIMUM_BYTES))));
            }
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackRosterData(time, players);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid player roster: malformed/truncated data", failure); }
    }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in, int maximum) throws IOException {
        int size = bounded(in.readInt(), maximum); if (size > in.available()) throw invalid("blob length");
        return in.readNBytes(size);
    }
    private static void text(DataOutputStream out, String value, int maximum) throws IOException { blob(out, utf8(value, maximum)); }
    private static String text(DataInputStream in, int maximum) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(blob(in, maximum))).toString();
    }
    private static byte[] utf8(String value, int maximum) {
        Objects.requireNonNull(value);
        if (value.length() > maximum) throw invalid("text budget");
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (buffer.remaining() > maximum) throw invalid("text budget");
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (java.nio.charset.CharacterCodingException failure) { throw new IllegalArgumentException("Invalid player roster: text encoding", failure); }
    }
    private static <T> T choice(DataInputStream in, T[] choices) throws IOException { return choices[bounded(in.readUnsignedByte(), choices.length - 1)]; }
    private static boolean bool(DataInputStream in) throws IOException { return bounded(in.readUnsignedByte(), 1) == 1; }
    private static int bounded(int value, int max) { if (value < 0 || value > max) throw invalid("count/flag"); return value; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid player roster: " + message); }
}
