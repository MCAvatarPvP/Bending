package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Detached tracked-data/effect/attribute component of a native player bootstrap.
 * Native blobs use Minecraft's player data serializers and registry-aware effect
 * NBT, so loaders must agree on native protocol and registries before importing.
 * Adapters validate the complete component against their private target before writes.
 */
public final class RollbackPlayerVitals {
    public static final int VERSION = 1, MAXIMUM_BLOB = 1_048_576, MAXIMUM_BYTES = 4_194_304;
    public static final int MAXIMUM_ATTRIBUTES = 1024, MAXIMUM_MODIFIERS = 4096, MAXIMUM_EFFECTS = 1024;
    public static final long MAXIMUM_NBT_ALLOCATION = 16_777_216;
    public static final int MAXIMUM_NBT_DEPTH = 64;
    public enum Operation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

    public record Modifier(String id, double amount, Operation operation, boolean permanent) {
        public Modifier { key(id); finite(amount); Objects.requireNonNull(operation); }
    }
    public record Attribute(String id, double base, List<Modifier> modifiers) {
        public Attribute {
            key(id); finite(base);
            if (modifiers.size() > MAXIMUM_MODIFIERS) throw invalid("modifier count");
            modifiers = modifiers.stream().sorted(Comparator.comparing(Modifier::id)).toList();
            String previous = null;
            for (Modifier modifier : modifiers) {
                if (modifier.id().equals(previous)) throw invalid("duplicate modifier");
                previous = modifier.id();
            }
        }
    }
    private final byte[] tracked, effects;
    private final List<Attribute> attributes;

    public RollbackPlayerVitals(byte[] tracked, byte[] effects, Collection<Attribute> attributes) {
        if (tracked.length < 1 || effects.length < 1 || tracked.length > MAXIMUM_BLOB || effects.length > MAXIMUM_BLOB
                || attributes.size() > MAXIMUM_ATTRIBUTES) throw invalid("component size");
        this.tracked = tracked.clone(); this.effects = effects.clone();
        this.attributes = attributes.stream().sorted(Comparator.comparing(Attribute::id)).toList();
        String previous = null; int modifiers = 0;
        for (Attribute attribute : this.attributes) {
            if (attribute.id().equals(previous)) throw invalid("duplicate attribute");
            previous = attribute.id();
            modifiers += attribute.modifiers().size();
            if (modifiers > MAXIMUM_MODIFIERS) throw invalid("total modifiers");
        }
    }
    public byte[] tracked() { return tracked.clone(); }
    public byte[] effects() { return effects.clone(); }
    public List<Attribute> attributes() { return attributes; }

    /** Bounded output for the native blob encoders as well as the enclosing wire format. */
    public static final class Output extends ByteArrayOutputStream {
        private final int maximum;
        public Output(int maximum) {
            if (maximum < 1 || maximum > MAXIMUM_BYTES) throw invalid("output limit");
            this.maximum = maximum;
        }
        private void reserve(int n) { if (n < 0 || (long) count + n > maximum) throw invalid("wire budget"); }
        @Override public synchronized void write(int b) { reserve(1); super.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) { reserve(len); super.write(b, off, len); }
    }

    public byte[] encode() {
        try {
            var bytes = new Output(MAXIMUM_BYTES); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); blob(out, tracked); blob(out, effects); out.writeInt(attributes.size());
            for (Attribute attribute : attributes) {
                text(out, attribute.id()); out.writeDouble(attribute.base()); out.writeInt(attribute.modifiers().size());
                for (Modifier modifier : attribute.modifiers()) {
                    text(out, modifier.id()); out.writeDouble(modifier.amount()); out.writeByte(modifier.operation().ordinal()); out.writeBoolean(modifier.permanent());
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    public static RollbackPlayerVitals decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            byte[] tracked = blob(in), effects = blob(in);
            int count = bounded(in.readInt(), MAXIMUM_ATTRIBUTES), totalModifiers = 0;
            var attributes = new ArrayList<Attribute>(count);
            String lastAttribute = "";
            for (int i = 0; i < count; i++) {
                String id = text(in); double base = in.readDouble();
                if (id.compareTo(lastAttribute) <= 0) throw invalid("attribute order/duplicate");
                lastAttribute = id;
                int size = bounded(in.readInt(), MAXIMUM_MODIFIERS);
                totalModifiers += size;
                if (totalModifiers > MAXIMUM_MODIFIERS) throw invalid("total modifiers");
                var modifiers = new ArrayList<Modifier>(size);
                String lastModifier = "";
                for (int j = 0; j < size; j++) {
                    String name = text(in); double amount = in.readDouble();
                    if (name.compareTo(lastModifier) <= 0) throw invalid("modifier order/duplicate");
                    lastModifier = name;
                    Operation operation = Operation.values()[bounded(in.readUnsignedByte(), Operation.values().length - 1)];
                    boolean permanent = bounded(in.readUnsignedByte(), 1) == 1;
                    modifiers.add(new Modifier(name, amount, operation, permanent));
                }
                attributes.add(new Attribute(id, base, modifiers));
            }
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackPlayerVitals(tracked, effects, attributes);
        } catch (IOException exception) { throw new IllegalArgumentException("Invalid player vitals: malformed/truncated data", exception); }
    }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in) throws IOException {
        int count = bounded(in.readInt(), MAXIMUM_BLOB);
        if (count == 0 || count > in.available()) throw invalid("blob size");
        return in.readNBytes(count);
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII); out.writeShort(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int count = bounded(in.readUnsignedShort(), 256);
        if (count > in.available()) throw invalid("key size");
        String value = new String(in.readNBytes(count), StandardCharsets.US_ASCII); key(value); return value;
    }
    private static void key(String value) {
        if (value == null || value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("registry key");
    }
    private static void finite(double value) { if (!Double.isFinite(value)) throw invalid("nonfinite number"); }
    private static int bounded(int n, int maximum) { if (n < 0 || n > maximum) throw invalid("count/index"); return n; }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid player vitals: " + detail); }
}
