package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached controls/contact state. Absolute nanosecond clocks never cross machines. */
public record RollbackPlayerContext(Abilities abilities, Input input, Vector clientMovement,
        long lastJumpOffset, OptionalLong eatingOffset, Set<String> tags, Set<UUID> collisionExemptions,
        Map<String, Double> fluidHeights, Set<String> eyeFluids, Vector pistons) {
    public static final int VERSION = 1, MAXIMUM_BYTES = 1_048_576, MAXIMUM_TAGS = 1024, MAXIMUM_TAG_BYTES = 4096;
    public static final int MAXIMUM_FLUIDS = 1024, MAXIMUM_EXEMPTIONS = 4096;
    public record Abilities(boolean invulnerable, boolean flying, boolean mayFly, boolean instantBuild, boolean mayBuild, float flySpeed, float walkSpeed) {
        public Abilities { if (!Float.isFinite(flySpeed) || !Float.isFinite(walkSpeed)) throw invalid("ability speed"); }
    }
    public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) { }
    public record Timers(long lastJump, long eatingStart) { }

    public RollbackPlayerContext {
        Objects.requireNonNull(abilities); Objects.requireNonNull(input); Objects.requireNonNull(clientMovement);
        Objects.requireNonNull(eatingOffset); Objects.requireNonNull(pistons);
        if (tags.size() > MAXIMUM_TAGS || collisionExemptions.size() > MAXIMUM_EXEMPTIONS
                || fluidHeights.size() > MAXIMUM_FLUIDS || eyeFluids.size() > MAXIMUM_FLUIDS) throw invalid("collection budget");
        tags = Set.copyOf(tags); collisionExemptions = Set.copyOf(collisionExemptions);
        fluidHeights = Map.copyOf(fluidHeights); eyeFluids = Set.copyOf(eyeFluids);
        long bytes = 128 + 16L * collisionExemptions.size();
        for (String tag : tags) bytes += utf8(tag).length + 2;
        for (var entry : fluidHeights.entrySet()) {
            key(entry.getKey()); if (!Double.isFinite(entry.getValue())) throw invalid("fluid height");
            bytes += entry.getKey().length() + 10;
        }
        for (String fluid : eyeFluids) { key(fluid); bytes += fluid.length() + 2; }
        if (bytes > MAXIMUM_BYTES) throw invalid("wire budget");
    }
    public Timers rebase(long initialNanos) {
        long jump = Math.addExact(initialNanos, lastJumpOffset);
        long eating = eatingOffset.isPresent() ? Math.addExact(initialNanos, eatingOffset.getAsLong()) : -1;
        if (eatingOffset.isPresent() && eating == -1) throw invalid("active eating clock collides with native sentinel");
        return new Timers(jump, eating);
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(bits(abilities.invulnerable(), abilities.flying(), abilities.mayFly(), abilities.instantBuild(), abilities.mayBuild()));
            out.writeFloat(abilities.flySpeed()); out.writeFloat(abilities.walkSpeed());
            out.writeByte(bits(input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift(), input.sprint()));
            vector(out, clientMovement); out.writeLong(lastJumpOffset); out.writeBoolean(eatingOffset.isPresent());
            if (eatingOffset.isPresent()) out.writeLong(eatingOffset.getAsLong());
            strings(out, tags); out.writeInt(collisionExemptions.size());
            for (UUID id : new TreeSet<>(collisionExemptions)) { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
            out.writeInt(fluidHeights.size());
            for (var entry : new TreeMap<>(fluidHeights).entrySet()) { text(out, entry.getKey()); out.writeDouble(entry.getValue()); }
            strings(out, eyeFluids); vector(out, pistons);
            if (bytes.size() > MAXIMUM_BYTES) throw invalid("wire budget");
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackPlayerContext decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            int flags = bounded(in.readUnsignedByte(), 31);
            var abilities = new Abilities(bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3), bit(flags, 4), in.readFloat(), in.readFloat());
            flags = bounded(in.readUnsignedByte(), 127);
            var input = new Input(bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3), bit(flags, 4), bit(flags, 5), bit(flags, 6));
            var movement = vector(in); long lastJump = in.readLong();
            var eating = bounded(in.readUnsignedByte(), 1) == 1 ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
            var tags = strings(in, MAXIMUM_TAGS); int exemptions = bounded(in.readInt(), MAXIMUM_EXEMPTIONS);
            var ids = new HashSet<UUID>(); UUID previous = null;
            for (int i = 0; i < exemptions; i++) {
                var id = new UUID(in.readLong(), in.readLong());
                if (previous != null && id.compareTo(previous) <= 0) throw invalid("UUID order/duplicate");
                previous = id; ids.add(id);
            }
            int count = bounded(in.readInt(), MAXIMUM_FLUIDS); var heights = new HashMap<String, Double>(); String last = null;
            for (int i = 0; i < count; i++) {
                String key = text(in); if (last != null && key.compareTo(last) <= 0) throw invalid("fluid order/duplicate");
                last = key; heights.put(key, in.readDouble());
            }
            var eyes = strings(in, MAXIMUM_FLUIDS); var pistons = vector(in);
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackPlayerContext(abilities, input, movement, lastJump, eating, tags, ids, heights, eyes, pistons);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid player context: malformed/truncated data", failure); }
    }
    private static int bits(boolean... values) { int result = 0; for (int i = 0; i < values.length; i++) if (values[i]) result |= 1 << i; return result; }
    private static boolean bit(int flags, int bit) { return (flags & (1 << bit)) != 0; }
    private static void vector(DataOutputStream out, Vector v) throws IOException { out.writeDouble(v.x()); out.writeDouble(v.y()); out.writeDouble(v.z()); }
    private static Vector vector(DataInputStream in) throws IOException { return new Vector(in.readDouble(), in.readDouble(), in.readDouble()); }
    private static void strings(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : new TreeSet<>(values)) text(out, value);
    }
    private static Set<String> strings(DataInputStream in, int max) throws IOException {
        int count = bounded(in.readInt(), max); var values = new HashSet<String>(); String previous = null;
        for (int i = 0; i < count; i++) {
            String value = text(in); if (previous != null && value.compareTo(previous) <= 0) throw invalid("string order/duplicate");
            previous = value; values.add(value);
        }
        return values;
    }
    private static void text(DataOutputStream out, String value) throws IOException { byte[] bytes = utf8(value); out.writeShort(bytes.length); out.write(bytes); }
    private static byte[] utf8(String value) {
        if (value.length() > MAXIMUM_TAG_BYTES) throw invalid("text size");
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (buffer.remaining() > MAXIMUM_TAG_BYTES) throw invalid("text size");
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("Invalid player context text", failure); }
    }
    private static String text(DataInputStream in) throws IOException {
        int length = bounded(in.readUnsignedShort(), MAXIMUM_TAG_BYTES); if (length > in.available()) throw invalid("text size");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static void key(String value) { if (value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("fluid key"); }
    private static int bounded(int value, int maximum) { if (value < 0 || value > maximum) throw invalid("count/flags"); return value; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid player context: " + message); }
}
