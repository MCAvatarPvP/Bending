package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable native combat history. Entity links are roster UUIDs; source indices preserve identity. */
public record RollbackPlayerCombatData(UUID owner, UUID lastHurtByPlayer, UUID lastHurtByMob, UUID lastHurtMob, UUID explosionCause,
        boolean hasKinetic, Map<UUID, Long> kinetic, Tracker tracker, List<Source> sources, int lastDamage, List<Entry> entries) {
    public static final int VERSION = 1, MAXIMUM_ENTRIES = 4096, MAXIMUM_SOURCES = MAXIMUM_ENTRIES + 1, MAXIMUM_ROSTER = 128;
    public static final int MAXIMUM_BYTES = 4_194_304;
    public record Tracker(int lastDamageTime, int combatStartTime, int combatEndTime, boolean inCombat, boolean takingDamage) { }
    public record Source(String type, UUID direct, UUID causing, UUID eventDamager, Vector position, String knownCause, boolean critical) {
        public Source {
            if (type == null || type.length() > 256 || !type.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("damage type key");
            if (knownCause != null && !knownCause.matches("[A-Z_]{1,64}")) throw invalid("damage cause name");
            if (direct != null && eventDamager != null) throw invalid("event damager conflicts with direct entity");
        }
    }
    public record Entry(int source, float damage, String fallLocation, float fallDistance) {
        public Entry {
            if (source < 0 || !Float.isFinite(damage) || !Float.isFinite(fallDistance)) throw invalid("combat entry");
            if (fallLocation != null && !fallLocation.matches("[a-z0-9_./:-]{1,128}")) throw invalid("fall location");
        }
    }
    public RollbackPlayerCombatData {
        Objects.requireNonNull(owner); Objects.requireNonNull(tracker);
        if (kinetic.size() > MAXIMUM_ROSTER || sources.size() > MAXIMUM_SOURCES || entries.size() > MAXIMUM_ENTRIES) throw invalid("history budget");
        kinetic = Map.copyOf(kinetic); sources = List.copyOf(sources); entries = List.copyOf(entries);
        if (!hasKinetic && !kinetic.isEmpty()) throw invalid("absent kinetic map has entries");
        if (lastDamage < -1 || lastDamage >= sources.size()) throw invalid("last damage reference");
        var used = new BitSet(sources.size()); if (lastDamage >= 0) used.set(lastDamage);
        for (Entry entry : entries) {
            if (entry.source() >= sources.size()) throw invalid("entry source reference");
            used.set(entry.source());
        }
        if (used.cardinality() != sources.size()) throw invalid("unreferenced source");
    }
    public Set<UUID> references() {
        var ids = new HashSet<UUID>(kinetic.keySet());
        add(ids, lastHurtByPlayer); add(ids, lastHurtByMob); add(ids, lastHurtMob); add(ids, explosionCause);
        for (Source source : sources) { add(ids, source.direct()); add(ids, source.causing()); add(ids, source.eventDamager()); }
        return Set.copyOf(ids);
    }
    public void requireRoster(Set<UUID> roster) {
        if (!roster.contains(owner) || !roster.containsAll(references())) throw invalid("entity outside imported roster");
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); uuid(out, owner); nullable(out, lastHurtByPlayer); nullable(out, lastHurtByMob); nullable(out, lastHurtMob); nullable(out, explosionCause);
            out.writeBoolean(hasKinetic); out.writeInt(kinetic.size());
            for (var hit : new TreeMap<>(kinetic).entrySet()) { uuid(out, hit.getKey()); out.writeLong(hit.getValue()); }
            out.writeInt(tracker.lastDamageTime()); out.writeInt(tracker.combatStartTime()); out.writeInt(tracker.combatEndTime()); out.writeBoolean(tracker.inCombat()); out.writeBoolean(tracker.takingDamage());
            out.writeInt(sources.size());
            for (Source source : sources) {
                text(out, source.type()); nullable(out, source.direct()); nullable(out, source.causing()); nullable(out, source.eventDamager());
                out.writeBoolean(source.position() != null);
                if (source.position() != null) { out.writeDouble(source.position().x()); out.writeDouble(source.position().y()); out.writeDouble(source.position().z()); }
                nullableText(out, source.knownCause()); out.writeBoolean(source.critical());
            }
            out.writeInt(lastDamage); out.writeInt(entries.size());
            for (Entry entry : entries) { out.writeInt(entry.source()); out.writeFloat(entry.damage()); nullableText(out, entry.fallLocation()); out.writeFloat(entry.fallDistance()); }
            if (bytes.size() > MAXIMUM_BYTES) throw invalid("wire budget");
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackPlayerCombatData decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            UUID owner = uuid(in), player = nullable(in), attacker = nullable(in), target = nullable(in), explosion = nullable(in);
            boolean hasKinetic = bool(in); int count = bounded(in.readInt(), MAXIMUM_ROSTER); var kinetic = new HashMap<UUID, Long>(); UUID previous = null;
            for (int i = 0; i < count; i++) {
                UUID id = uuid(in); if (previous != null && id.compareTo(previous) <= 0) throw invalid("kinetic order/duplicate"); previous = id;
                kinetic.put(id, in.readLong());
            }
            var tracker = new Tracker(in.readInt(), in.readInt(), in.readInt(), bool(in), bool(in));
            count = bounded(in.readInt(), MAXIMUM_SOURCES); var sources = new ArrayList<Source>(count);
            for (int i = 0; i < count; i++) {
                String type = text(in); UUID direct = nullable(in), causing = nullable(in), event = nullable(in);
                Vector position = bool(in) ? new Vector(in.readDouble(), in.readDouble(), in.readDouble()) : null;
                sources.add(new Source(type, direct, causing, event, position, nullableText(in), bool(in)));
            }
            int last = in.readInt(); count = bounded(in.readInt(), MAXIMUM_ENTRIES); var entries = new ArrayList<Entry>(count);
            for (int i = 0; i < count; i++) entries.add(new Entry(in.readInt(), in.readFloat(), nullableText(in), in.readFloat()));
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackPlayerCombatData(owner, player, attacker, target, explosion, hasKinetic, kinetic, tracker, sources, last, entries);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid player combat: malformed/truncated data", failure); }
    }
    private static void add(Set<UUID> values, UUID id) { if (id != null) values.add(id); }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void nullable(DataOutputStream out, UUID id) throws IOException { out.writeBoolean(id != null); if (id != null) uuid(out, id); }
    private static UUID nullable(DataInputStream in) throws IOException { return bool(in) ? uuid(in) : null; }
    private static void nullableText(DataOutputStream out, String value) throws IOException { out.writeBoolean(value != null); if (value != null) text(out, value); }
    private static String nullableText(DataInputStream in) throws IOException { return bool(in) ? text(in) : null; }
    private static void text(DataOutputStream out, String value) throws IOException { var bytes = value.getBytes(StandardCharsets.US_ASCII); out.writeShort(bytes.length); out.write(bytes); }
    private static String text(DataInputStream in) throws IOException {
        int count = bounded(in.readUnsignedShort(), 256); if (count > in.available()) throw invalid("text length");
        return new String(in.readNBytes(count), StandardCharsets.US_ASCII);
    }
    private static boolean bool(DataInputStream in) throws IOException { return bounded(in.readUnsignedByte(), 1) == 1; }
    private static int bounded(int value, int max) { if (value < 0 || value > max) throw invalid("count/flag"); return value; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid player combat: " + message); }
}
