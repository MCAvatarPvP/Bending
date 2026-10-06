package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Server-owned configuration seed and per-domain views of the existing registered configs. */
public final class RollbackConfiguration implements RollbackStateCell<RollbackConfiguration.Snapshot> {
    public static final int VERSION = 1, MAXIMUM_BYTES = 16_777_216;
    private static final int MAXIMUM_FILES = 4096, MAXIMUM_VALUES = 1_000_000, MAXIMUM_DEPTH = 32;
    private static final ThreadLocal<RollbackConfiguration> ACTIVE = new ThreadLocal<>();

    /** Preserve defaults separately: containsExplicit and style fallback depend on that distinction. */
    public static final class Settings {
        private final Map<String, Object> values, defaults;
        private final boolean loaded;
        private final String implementation;
        public Settings(String implementation, Map<String, Object> values, Map<String, Object> defaults, boolean loaded) {
            name(implementation); this.implementation = implementation;
            var budget = new Budget();
            this.values = copyMap(values, budget, 0); this.defaults = copyMap(defaults, budget, 0); this.loaded = loaded;
        }
        public Map<String, Object> values() { return copyMap(values, new Budget(), 0); }
        public Map<String, Object> defaults() { return copyMap(defaults, new Budget(), 0); }
        public boolean loaded() { return loaded; }
    }

    /** Aliases preserve identity when a config is registered under both a file and an addon namespace. */
    public static final class Data {
        private final SortedMap<String, Settings> configurations;
        private final SortedMap<String, String> aliases;
        private Data(Map<String, Settings> configurations, Map<String, String> aliases) {
            if (configurations.size() > MAXIMUM_FILES || aliases.size() > MAXIMUM_FILES) throw invalid("file count");
            this.configurations = Collections.unmodifiableSortedMap(new TreeMap<>(configurations));
            this.aliases = Collections.unmodifiableSortedMap(new TreeMap<>(aliases));
            var first = new HashMap<String, String>();
            this.aliases.forEach((name, canonical) -> {
                name(name); name(canonical);
                if (!this.configurations.containsKey(canonical)) throw invalid("unknown alias target");
                first.putIfAbsent(canonical, name);
            });
            this.configurations.forEach((name, settings) -> {
                if (!name.equals(first.get(name)) || settings == null) throw invalid("noncanonical alias group");
            });
        }
        public Set<String> names() { return aliases.keySet(); }
        public byte[] encode() {
            try {
                var bytes = new ByteArrayOutputStream();
                var out = new DataOutputStream(new FilterOutputStream(bytes) {
                    private int count;
                    private void reserve(int amount) { if (amount < 0 || amount > MAXIMUM_BYTES - count) throw invalid("wire budget"); count += amount; }
                    @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                    @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); out.write(value, offset, length); }
                });
                var budget = new Budget();
                out.writeInt(VERSION); out.writeInt(aliases.size());
                for (var entry : aliases.entrySet()) { text(out, entry.getKey()); text(out, entry.getValue()); }
                out.writeInt(configurations.size());
                for (var entry : configurations.entrySet()) {
                    text(out, entry.getKey()); var config = entry.getValue(); text(out, config.implementation); out.writeBoolean(config.loaded);
                    write(out, config.values, budget, 0); write(out, config.defaults, budget, 0);
                }
                return bytes.toByteArray();
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        /** Includes actual typed values, defaults and their iteration order, not just config filenames. */
        public String fingerprint() {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode())); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        public static Data decode(byte[] bytes) {
            if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
            try {
                var in = new DataInputStream(new ByteArrayInputStream(bytes));
                if (in.readInt() != VERSION) throw invalid("version");
                var aliases = new TreeMap<String, String>(); String previous = null;
                int count = bounded(in.readInt(), MAXIMUM_FILES);
                for (int i = 0; i < count; i++) {
                    String id = text(in); previous = ordered(previous, id); aliases.put(id, text(in));
                }
                var configurations = new TreeMap<String, Settings>(); previous = null;
                count = bounded(in.readInt(), MAXIMUM_FILES); var budget = new Budget();
                for (int i = 0; i < count; i++) {
                    String id = text(in); previous = ordered(previous, id); String implementation = text(in); boolean loaded = bool(in);
                    configurations.put(id, new Settings(implementation, stringMap(read(in, budget, 0)), stringMap(read(in, budget, 0)), loaded));
                }
                if (in.available() != 0) throw invalid("trailing data");
                return new Data(configurations, aliases);
            } catch (IOException failure) { throw new IllegalArgumentException("Invalid rollback configuration: malformed data", failure); }
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Data data;
    private final IdentityHashMap<Config, Config> views = new IdentityHashMap<>();
    private final List<Config> configs;
    private final List<RollbackGraphCodec.Binding> sourceBindings, bindings;

    public static final class Snapshot {
        private final RollbackConfiguration owner;
        private final List<List<BendingPlayer>> contexts;
        private Snapshot(RollbackConfiguration owner) {
            this.owner = owner;
            contexts = owner.configs.stream().map(Config::captureRollbackContext).toList();
        }
    }
    @Override public Snapshot captureRollbackState() { checkThread(); return new Snapshot(this); }
    @Override public void restoreRollbackState(Snapshot snapshot) {
        checkThread(); if (snapshot.owner != this) throw new IllegalArgumentException("Configuration snapshot belongs to another session");
        for (int i = 0; i < configs.size(); i++) configs.get(i).restoreRollbackContext(snapshot.contexts.get(i));
    }
    @Override public Collection<?> rollbackReferences() {
        checkThread(); var references = new ArrayList<>();
        configs.forEach(config -> config.captureRollbackContext().stream().filter(Objects::nonNull).forEach(references::add));
        return references;
    }

    public static Data captureData(Map<String, Config> sources) {
        if (RollbackDomain.active()) throw new IllegalStateException("Capture configuration before private simulation");
        var aliases = aliases(sources); var settings = new TreeMap<String, Settings>();
        aliases.forEach((name, canonical) -> {
            if (name.equals(canonical)) settings.put(name, sources.get(name).captureRollbackSettings());
        });
        var result = new Data(settings, aliases); result.encode(); return result;
    }
    public static RollbackConfiguration capture() {
        var sources = PredictionConfigSync.sources(); return prepare(captureData(sources), sources);
    }
    public static RollbackConfiguration prepare(Data data, Map<String, Config> sources) {
        if (RollbackDomain.active() || ACTIVE.get() != null) throw new IllegalStateException("Prepare configuration before private simulation");
        if (!data.aliases.equals(aliases(sources))) throw invalid("registered config identities differ from the server");
        data.configurations.forEach((name, settings) -> {
            if (!sources.get(name).getClass().getName().equals(settings.implementation)) throw invalid("configuration implementation differs: " + name);
        });
        return new RollbackConfiguration(data, sources);
    }
    private RollbackConfiguration(Data data, Map<String, Config> sources) {
        this.data = data;
        var configs = new ArrayList<Config>(); var sourceBindings = new ArrayList<RollbackGraphCodec.Binding>();
        var bindings = new ArrayList<RollbackGraphCodec.Binding>();
        data.configurations.forEach((name, settings) -> {
            Config source = sources.get(name), view = source.createRollbackView(settings);
            if (view == source || !source.getClass().isInstance(view)) throw invalid("configuration adapter retained the source or changed its type");
            configs.add(view); views.put(source, view); views.put(view, view);
            sourceBindings.add(new RollbackGraphCodec.Binding("config/" + name, source.getClass(), source));
            bindings.add(new RollbackGraphCodec.Binding("config/" + name, source.getClass(), view));
        });
        this.configs = List.copyOf(configs); this.sourceBindings = List.copyOf(sourceBindings); this.bindings = List.copyOf(bindings);
    }
    public Data data() { checkThread(); return data; }
    public List<Config> references() { checkThread(); return configs; }
    public List<RollbackGraphCodec.Binding> sourceBindings() { checkThread(); return sourceBindings; }
    public List<RollbackGraphCodec.Binding> bindings() { checkThread(); return bindings; }
    public RollbackStateTransfer.Replacement replacement(Object source) {
        checkThread();
        if (!(source instanceof Config config)) return null;
        Config view = views.get(config); if (view == null) throw invalid("unregistered config in gameplay graph");
        return new RollbackStateTransfer.Replacement(view);
    }
    /** Existing ability-held and static Config references both resolve to the same private view. */
    public static Config resolve(Config config) {
        var active = ACTIVE.get(); if (active == null) return config;
        Config view = active.views.get(config); if (view == null) throw invalid("config was not included in session bootstrap");
        return view;
    }
    Scope using() {
        checkThread();
        if (!RollbackDomain.active() || ACTIVE.get() != null) throw new IllegalStateException("Configuration requires its private domain");
        ACTIVE.set(this); return new Scope();
    }
    final class Scope implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            checkThread(); if (closed) return;
            if (ACTIVE.get() != RollbackConfiguration.this) throw new IllegalStateException("Configuration scope ownership changed");
            ACTIVE.remove(); closed = true;
        }
    }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Configuration crossed threads"); }
    private static SortedMap<String, String> aliases(Map<String, Config> sources) {
        if (sources.size() > MAXIMUM_FILES) throw invalid("file count");
        var aliases = new TreeMap<String, String>(); var identities = new IdentityHashMap<Config, String>();
        new TreeMap<>(sources).forEach((name, config) -> {
            name(name); Objects.requireNonNull(config);
            aliases.put(name, identities.computeIfAbsent(config, ignored -> name));
        });
        return aliases;
    }

    /** Defensive tree copy: even a YAML timestamp or nested collection returned by get() cannot mutate the seed. */
    public static Object copyValue(Object value) { return copy(value, new Budget(), 0); }
    private static Map<String, Object> copyMap(Map<String, Object> source, Budget budget, int depth) {
        return stringMap(copy(source, budget, depth));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw invalid("config paths");
        return (Map<String, Object>) map;
    }
    private static final class Budget {
        int values;
        void add(int depth) { if (depth > MAXIMUM_DEPTH || ++values > MAXIMUM_VALUES) throw invalid("value/depth budget"); }
    }
    private static Object copy(Object value, Budget budget, int depth) {
        budget.add(depth);
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value.getClass() == Date.class) return new Date(((Date) value).getTime());
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>();
            map.forEach((key, child) -> result.put(copy(key, budget, depth + 1), copy(child, budget, depth + 1)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<>(); list.forEach(child -> result.add(copy(child, budget, depth + 1)));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Set<?> set) {
            var result = new LinkedHashSet<>(); set.forEach(child -> result.add(copy(child, budget, depth + 1)));
            return Collections.unmodifiableSet(result);
        }
        throw invalid("unsupported YAML value " + value.getClass().getName());
    }
    private static void write(DataOutputStream out, Object value, Budget budget, int depth) throws IOException {
        budget.add(depth);
        if (value == null) out.writeByte(0);
        else if (value instanceof String s) { out.writeByte(1); text(out, s); }
        else if (value instanceof Boolean b) { out.writeByte(2); out.writeBoolean(b); }
        else if (value instanceof Byte n) { out.writeByte(3); out.writeByte(n); }
        else if (value instanceof Short n) { out.writeByte(4); out.writeShort(n); }
        else if (value instanceof Integer n) { out.writeByte(5); out.writeInt(n); }
        else if (value instanceof Long n) { out.writeByte(6); out.writeLong(n); }
        else if (value instanceof Float n) { out.writeByte(7); out.writeInt(Float.floatToRawIntBits(n)); }
        else if (value instanceof Double n) { out.writeByte(8); out.writeLong(Double.doubleToRawLongBits(n)); }
        else if (value instanceof BigInteger n) { out.writeByte(9); text(out, n.toString()); }
        else if (value instanceof BigDecimal n) { out.writeByte(10); text(out, n.toString()); }
        else if (value instanceof Date n) { out.writeByte(11); out.writeLong(n.getTime()); }
        else if (value instanceof List<?> list) {
            out.writeByte(12); out.writeInt(list.size()); for (Object child : list) write(out, child, budget, depth + 1);
        } else if (value instanceof Map<?, ?> map) {
            out.writeByte(13); out.writeInt(map.size());
            for (var entry : map.entrySet()) { write(out, entry.getKey(), budget, depth + 1); write(out, entry.getValue(), budget, depth + 1); }
        } else if (value instanceof Set<?> set) {
            out.writeByte(14); out.writeInt(set.size()); for (Object child : set) write(out, child, budget, depth + 1);
        } else throw invalid("value type");
    }
    private static Object read(DataInputStream in, Budget budget, int depth) throws IOException {
        budget.add(depth);
        return switch (in.readUnsignedByte()) {
            case 0 -> null; case 1 -> text(in); case 2 -> bool(in); case 3 -> in.readByte(); case 4 -> in.readShort();
            case 5 -> in.readInt(); case 6 -> in.readLong(); case 7 -> Float.intBitsToFloat(in.readInt()); case 8 -> Double.longBitsToDouble(in.readLong());
            case 9 -> new BigInteger(text(in)); case 10 -> new BigDecimal(text(in)); case 11 -> new Date(in.readLong());
            case 12 -> { int size = count(in); var list = new ArrayList<>(); for (int i = 0; i < size; i++) list.add(read(in, budget, depth + 1)); yield list; }
            case 13 -> {
                int size = count(in); var map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    Object key = read(in, budget, depth + 1), value = read(in, budget, depth + 1);
                    if (map.containsKey(key)) throw invalid("duplicate map key"); map.put(key, value);
                }
                yield map;
            }
            case 14 -> {
                int size = count(in); var set = new LinkedHashSet<>();
                for (int i = 0; i < size; i++) if (!set.add(read(in, budget, depth + 1))) throw invalid("duplicate set entry"); yield set;
            }
            default -> throw invalid("value tag");
        };
    }
    private static int count(DataInputStream in) throws IOException { return bounded(in.readInt(), Math.min(MAXIMUM_VALUES, in.available())); }
    private static boolean bool(DataInputStream in) throws IOException { int value = in.readUnsignedByte(); if (value > 1) throw invalid("boolean"); return value == 1; }
    private static int bounded(int value, int max) { if (value < 0 || value > max) throw invalid("size"); return value; }
    private static String ordered(String previous, String next) { if (previous != null && previous.compareTo(next) >= 0) throw invalid("ordering/duplicate"); return next; }
    private static void name(String name) { if (name == null || name.isBlank() || name.length() > 480) throw invalid("config identity"); }
    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAXIMUM_BYTES) throw invalid("string size");
        if (!new String(bytes, StandardCharsets.UTF_8).equals(text)) throw invalid("invalid Unicode");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = bounded(in.readInt(), Math.min(MAXIMUM_BYTES, in.available()));
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid rollback configuration: " + detail); }
}
