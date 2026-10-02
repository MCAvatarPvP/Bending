package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/**
 * Portable common gameplay graph, including aliases, cycles and ordinary final fields.
 * Both endpoints explicitly supply the same class/schema and service-binding catalog.
 * Wire data never selects a class loader, class name, constructor or reflection member.
 * Platform bodies, frozen registry definitions and callbacks are explicit local bindings.
 * Native player state and terrain travel separately; this codec does not capture them.
 *
 * <p>Decode validates the entire detached graph before constructing application objects.
 * It uses the same cached layouts and dependency-aware initialization as the in-process
 * importer. Neither operation invokes ability constructors, activation or load hooks.
 * Both replicas must decode the same bootstrap: collection capacity/load-factor tuning
 * is normalized, while collection kind, iteration input and access-order are preserved.</p>
 */
public final class RollbackGraphCodec {
    public static final int VERSION = 1;

    public record Limits(int maximumObjects, int maximumReferences, int maximumWireBytes, int maximumStringBytes) {
        public Limits {
            if (maximumObjects < 1 || maximumObjects > 1_000_000 || maximumReferences < 1 || maximumReferences > 8_000_000
                    || maximumWireBytes < 49 || maximumWireBytes > 134_217_728
                    || maximumStringBytes < 1 || maximumStringBytes > maximumWireBytes) throw invalid("limits");
        }
    }

    /** Type is the shared contract; value can be a different loader-specific implementation. */
    public record Binding(String id, Class<?> type, Object value) {
        public Binding {
            if (id == null || id.isBlank() || id.length() > 512 || type == null || !type.isInstance(value)) throw invalid("binding");
        }
    }

    /** An explicit, immutable allowlist. Symbols allow enums, class values and array component types. */
    public static final class Catalog {
        private final List<Class<?>> types;
        private final Map<Class<?>, Integer> indices;
        private final Map<Class<?>, RollbackStateTransfer.Layout> layouts;
        private final List<Binding> bindings;
        private final IdentityHashMap<Object, Integer> external = new IdentityHashMap<>();
        private final byte[] fingerprint;

        public Catalog(Collection<Class<?>> objects, Collection<Class<?>> symbols, Collection<Binding> bindings) {
            var sorted = new TreeMap<String, Class<?>>();
            var layouts = new HashMap<Class<?>, RollbackStateTransfer.Layout>();
            for (Class<?> type : objects) {
                if (type.isInterface() || type.isEnum() || type.isArray() || Modifier.isAbstract(type.getModifiers())
                        || RollbackStateCell.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
                        || Map.class.isAssignableFrom(type)) throw invalid("application type " + type.getName());
                layouts.put(type, RollbackStateTransfer.LAYOUTS.get(type));
            }
            var all = new ArrayList<Class<?>>(List.of(Object.class, String.class, Class.class, UUID.class,
                    boolean.class, byte.class, short.class, int.class, long.class, float.class, double.class, char.class,
                    Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, Character.class));
            all.addAll(objects);
            all.addAll(symbols);
            for (Binding binding : bindings) all.add(binding.type());
            for (Class<?> type : all) {
                if (type.isHidden() || type == void.class) throw invalid("symbol type");
                Class<?> previous = sorted.putIfAbsent(type.getName(), type);
                if (previous != null && previous != type) throw invalid("ambiguous class name");
            }
            this.types = List.copyOf(sorted.values());
            var indices = new HashMap<Class<?>, Integer>();
            for (int i = 0; i < types.size(); i++) indices.put(types.get(i), i);
            this.indices = Map.copyOf(indices);
            this.layouts = Map.copyOf(layouts);
            this.bindings = bindings.stream().sorted(Comparator.comparing(Binding::id)).toList();
            String previous = null;
            for (int i = 0; i < this.bindings.size(); i++) {
                Binding binding = this.bindings.get(i);
                if (binding.id().equals(previous) || external.put(binding.value(), i) != null) throw invalid("duplicate binding");
                previous = binding.id();
            }
            try {
                var bytes = new ByteArrayOutputStream();
                var out = new DataOutputStream(bytes);
                out.writeInt(VERSION);
                out.writeInt(types.size());
                for (Class<?> type : types) {
                    schemaString(out, type.getName());
                    var layout = layouts.get(type);
                    out.writeInt(layout == null ? -1 : layout.fields.size());
                    if (layout != null) for (var access : layout.fields) {
                        schemaString(out, access.field.getDeclaringClass().getName());
                        schemaString(out, access.field.getName());
                        schemaString(out, access.field.getType().getName());
                        out.writeInt(access.field.getModifiers());
                    }
                    Object[] constants = type.isEnum() ? type.getEnumConstants() : new Object[0];
                    out.writeInt(constants.length);
                    for (Object constant : constants) schemaString(out, ((Enum<?>) constant).name());
                }
                out.writeInt(this.bindings.size());
                for (Binding binding : this.bindings) {
                    schemaString(out, binding.id());
                    schemaString(out, binding.type().getName());
                }
                fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            } catch (IOException | NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }

        public byte[] fingerprint() { return fingerprint.clone(); }
        private int id(Class<?> type) {
            Integer result = indices.get(type);
            if (result == null) throw invalid("unregistered type " + type.getName());
            return result;
        }
        private Class<?> type(int index) {
            if (index < 0 || index >= types.size()) throw invalid("type index");
            return types.get(index);
        }
        private RollbackStateTransfer.Layout layout(int index) {
            var result = layouts.get(type(index));
            if (result == null) throw invalid("unregistered application layout");
            return result;
        }
    }

    private enum Kind {
        NULL, EXTERNAL, STRING, BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR, UUID, ENUM, CLASS,
        OPTIONAL_INT, OPTIONAL_LONG, OPTIONAL_DOUBLE, PRIMITIVE_ARRAY, ARRAY, OBJECT, DATE,
        ATOMIC_INT, ATOMIC_LONG, ATOMIC_BOOLEAN, ATOMIC_REF, OPTIONAL, WEAK, ENTRY, IMMUTABLE_ENTRY,
        ARRAY_LIST, LINKED_LIST, DEQUE, COW_LIST, COW_SET, HASH_SET, LINKED_SET, ENUM_SET, TREE_SET, QUEUE,
        FIXED_LIST, SINGLETON_LIST, EMPTY_LIST, EMPTY_MAP, EMPTY_SET,
        HASH_MAP, LINKED_MAP, IDENTITY_MAP, ENUM_MAP, CONCURRENT_MAP, WEAK_MAP, TREE_MAP
    }

    private record Entry(Kind kind, int type, Object value, int[] references) { }
    private static final int[] NO_REFERENCES = new int[0];
    private final Thread owner = Thread.currentThread();
    private final Catalog catalog;
    private final Limits limits;

    public RollbackGraphCodec(Catalog catalog, Limits limits) {
        this.catalog = Objects.requireNonNull(catalog);
        this.limits = Objects.requireNonNull(limits);
    }

    public byte[] encode(Collection<?> roots) { return encode(roots, ignored -> null); }

    /** Projections select participant-only service state before graph discovery. Direct replacements must be bound. */
    public byte[] encode(Collection<?> roots, Function<Object, RollbackStateTransfer.Replacement> projections) {
        checkThread();
        return encodeGraph(roots, projections);
    }

    /** Outgoing copy only, between simulation ticks; decoding remains a detached bootstrap operation. */
    byte[] encodeExport(Collection<?> roots, Function<Object, RollbackStateTransfer.Replacement> projections) {
        if (Thread.currentThread() != owner || !RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Export graph at a settled owning-domain boundary");
        return encodeGraph(roots, projections);
    }

    private byte[] encodeGraph(Collection<?> roots, Function<Object, RollbackStateTransfer.Replacement> projections) {
        Objects.requireNonNull(projections);
        var capture = new Capture(projections);
        int[] rootIds = new int[bounded(roots.size(), limits.maximumReferences(), "roots")];
        int root = 0;
        for (Object value : roots) rootIds[root++] = capture.reference(value);
        for (int index = 0; index < capture.sources.size(); index++) capture.entries.add(capture.scan(capture.sources.get(index)));
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(new FilterOutputStream(bytes) {
                int written;
                private void reserve(int count) {
                    if ((long) written + count > limits.maximumWireBytes()) throw invalid("wire budget");
                    written += count;
                }
                @Override public void write(int value) { reserve(1); bytes.write(value); }
                @Override public void write(byte[] value, int offset, int count) { reserve(count); bytes.write(value, offset, count); }
            });
            out.writeInt(VERSION);
            out.write(catalog.fingerprint);
            out.writeInt(capture.entries.size());
            writeReferences(out, rootIds);
            for (Entry entry : capture.entries) write(out, entry);
            return bytes.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    /** Recopy a validated detached graph with local shared-service identities. Never writes replacements. */
    List<Object> rebind(Collection<?> roots, Function<Object, RollbackStateTransfer.Replacement> replacements) {
        checkThread();
        var transfer = new RollbackStateTransfer(catalog.layouts::containsKey, value ->
                catalog.external.containsKey(value) ? new RollbackStateTransfer.Replacement(value) : null,
                new RollbackStateTransfer.Limits(limits.maximumObjects(), limits.maximumReferences()));
        return transfer.copy(roots, replacements);
    }

    public List<Object> decode(byte[] bytes) {
        checkThread();
        if (bytes.length > limits.maximumWireBytes()) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION || !MessageDigest.isEqual(in.readNBytes(32), catalog.fingerprint)) throw invalid("catalog/version mismatch");
            int count = bounded(in.readInt(), limits.maximumObjects(), "objects");
            if (count > in.available() / 5) throw invalid("object storage");
            var budget = new Budget();
            int[] roots = readReferences(in, count, budget);
            var entries = new ArrayList<Entry>(count);
            for (int i = 0; i < count; i++) entries.add(read(in, count, budget));
            if (in.available() != 0) throw invalid("trailing data");
            validate(entries, roots);
            return materialize(entries, roots);
        } catch (IOException exception) { throw new IllegalArgumentException("Invalid rollback graph: truncated/malformed data", exception); }
    }

    private final class Budget {
        int references;
        void add(int count) {
            if (count < 0 || (long) references + count > limits.maximumReferences()) throw invalid("reference budget");
            references += count;
        }
    }

    private final class Capture {
        final Function<Object, RollbackStateTransfer.Replacement> projections;
        final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        final ArrayList<Object> sources = new ArrayList<>();
        final ArrayList<Entry> entries = new ArrayList<>();
        final Budget budget = new Budget();

        Capture(Function<Object, RollbackStateTransfer.Replacement> projections) { this.projections = projections; }
        int reference(Object source) {
            budget.add(1);
            Integer old = ids.get(source);
            if (old != null) return old;
            var replacement = source == null ? null : projections.apply(source);
            Object view = replacement == null ? source : replacement.value();
            if (replacement != null && !replacement.projection() && view != null && !catalog.external.containsKey(view)) {
                throw invalid("unbound direct replacement " + view.getClass().getName());
            }
            old = ids.get(view);
            if (old == null) {
                if (sources.size() >= limits.maximumObjects()) throw invalid("object budget");
                old = sources.size();
                ids.put(view, old);
                sources.add(view);
            }
            ids.put(source, old);
            return old;
        }
        int[] references(Collection<?> values) {
            if ((long) budget.references + values.size() > limits.maximumReferences()) throw invalid("reference budget");
            int[] result = new int[values.size()];
            int i = 0;
            for (Object value : values) result[i++] = reference(value);
            return result;
        }
        Entry leaf(Kind kind, Object value) { return new Entry(kind, -1, value, NO_REFERENCES); }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Entry scan(Object source) {
            if (source == null) return leaf(Kind.NULL, null);
            Integer external = catalog.external.get(source);
            if (external != null) return new Entry(Kind.EXTERNAL, external, null, NO_REFERENCES);
            Class<?> type = source.getClass();
            if (source instanceof String) return leaf(Kind.STRING, source);
            if (source instanceof Boolean) return leaf(Kind.BOOLEAN, source);
            if (source instanceof Byte) return leaf(Kind.BYTE, source);
            if (source instanceof Short) return leaf(Kind.SHORT, source);
            if (source instanceof Integer) return leaf(Kind.INT, source);
            if (source instanceof Long) return leaf(Kind.LONG, source);
            if (source instanceof Float) return leaf(Kind.FLOAT, source);
            if (source instanceof Double) return leaf(Kind.DOUBLE, source);
            if (source instanceof Character) return leaf(Kind.CHAR, source);
            if (source instanceof UUID) return leaf(Kind.UUID, source);
            if (source instanceof Class<?> value) return new Entry(Kind.CLASS, catalog.id(value), null, NO_REFERENCES);
            if (source instanceof Enum<?> value) return new Entry(Kind.ENUM, catalog.id(value.getDeclaringClass()), value.ordinal(), NO_REFERENCES);
            if (source instanceof OptionalInt) return leaf(Kind.OPTIONAL_INT, source);
            if (source instanceof OptionalLong) return leaf(Kind.OPTIONAL_LONG, source);
            if (source instanceof OptionalDouble) return leaf(Kind.OPTIONAL_DOUBLE, source);
            if (type == AtomicInteger.class) return leaf(Kind.ATOMIC_INT, ((AtomicInteger) source).get());
            if (type == AtomicLong.class) return leaf(Kind.ATOMIC_LONG, ((AtomicLong) source).get());
            if (type == AtomicBoolean.class) return leaf(Kind.ATOMIC_BOOLEAN, ((AtomicBoolean) source).get());
            if (type == Date.class) return leaf(Kind.DATE, ((Date) source).getTime());
            if (type.isArray()) {
                int id = catalog.id(type.getComponentType());
                int length = Array.getLength(source);
                if (!type.getComponentType().isPrimitive()) return new Entry(Kind.ARRAY, id, null, references(Arrays.asList((Object[]) source)));
                budget.add(length);
                Object copy = Array.newInstance(type.getComponentType(), length);
                System.arraycopy(source, 0, copy, 0, length);
                return new Entry(Kind.PRIMITIVE_ARRAY, id, copy, NO_REFERENCES);
            }
            if (type == AtomicReference.class || type == WeakReference.class || type == Optional.class) {
                Object child = source instanceof AtomicReference<?> ref ? ref.get() : source instanceof WeakReference<?> ref ? ref.get() : ((Optional<?>) source).orElse(null);
                return new Entry(type == AtomicReference.class ? Kind.ATOMIC_REF : type == WeakReference.class ? Kind.WEAK : Kind.OPTIONAL,
                        -1, null, new int[]{reference(child)});
            }
            if (type == AbstractMap.SimpleEntry.class || type == AbstractMap.SimpleImmutableEntry.class) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) source;
                return new Entry(type == AbstractMap.SimpleEntry.class ? Kind.ENTRY : Kind.IMMUTABLE_ENTRY, -1, null,
                        new int[]{reference(entry.getKey()), reference(entry.getValue())});
            }
            if (source instanceof Map<?, ?> map) {
                if (2L * map.size() + budget.references > limits.maximumReferences()) throw invalid("map budget");
                Kind kind;
                int symbol = -1;
                Object value = null;
                if (type == HashMap.class) kind = Kind.HASH_MAP;
                else if (type == LinkedHashMap.class) {
                    kind = Kind.LINKED_MAP;
                    var probe = (LinkedHashMap) ((LinkedHashMap) map).clone();
                    probe.clear();
                    Object first = new Object(), second = new Object();
                    probe.put(first, first); probe.put(second, second); probe.get(first);
                    value = probe.keySet().iterator().next() != first;
                } else if (type == IdentityHashMap.class) kind = Kind.IDENTITY_MAP;
                else if (type == ConcurrentHashMap.class) kind = Kind.CONCURRENT_MAP;
                else if (type == WeakHashMap.class) kind = Kind.WEAK_MAP;
                else if (type == TreeMap.class) kind = Kind.TREE_MAP;
                else if (type == EnumMap.class) { kind = Kind.ENUM_MAP; symbol = enumType((EnumMap<?, ?>) map); }
                else if (map.isEmpty() && fixed(type)) return leaf(Kind.EMPTY_MAP, null);
                else throw unsupported(type);
                if (2L * map.size() + budget.references > limits.maximumReferences()) throw invalid("map budget");
                var children = new ArrayList<Object>(map.size() * 2 + 1);
                map.forEach((key, item) -> { requireKey(key); children.add(key); children.add(item); });
                if (kind == Kind.TREE_MAP) children.add(((TreeMap<?, ?>) map).comparator());
                return new Entry(kind, symbol, value, references(children));
            }
            if (source instanceof Collection<?> collection) {
                if ((long) collection.size() + budget.references > limits.maximumReferences()) throw invalid("collection budget");
                Kind kind;
                int symbol = -1;
                if (type == ArrayList.class) kind = Kind.ARRAY_LIST;
                else if (type == LinkedList.class) kind = Kind.LINKED_LIST;
                else if (type == ArrayDeque.class) kind = Kind.DEQUE;
                else if (type == CopyOnWriteArrayList.class) kind = Kind.COW_LIST;
                else if (type == CopyOnWriteArraySet.class) kind = Kind.COW_SET;
                else if (type == HashSet.class) kind = Kind.HASH_SET;
                else if (type == LinkedHashSet.class) kind = Kind.LINKED_SET;
                else if (collection instanceof EnumSet enums) {
                    kind = Kind.ENUM_SET;
                    Set<?> probe = enums.isEmpty() ? EnumSet.complementOf(enums) : enums;
                    if (probe.isEmpty()) throw unsupported(type);
                    symbol = catalog.id(((Enum<?>) probe.iterator().next()).getDeclaringClass());
                } else if (type == TreeSet.class) kind = Kind.TREE_SET;
                else if (type == PriorityQueue.class) kind = Kind.QUEUE;
                else if (type.getName().equals("java.util.Collections$SingletonList")) kind = Kind.SINGLETON_LIST;
                else if (type.getName().equals("java.util.Collections$EmptyList")) kind = Kind.EMPTY_LIST;
                else if (type.getName().startsWith("java.util.ImmutableCollections$List")) kind = Kind.FIXED_LIST;
                else if (collection.isEmpty() && fixed(type)) return leaf(Kind.EMPTY_SET, null);
                else throw unsupported(type);
                if (collection instanceof Set<?> || collection instanceof PriorityQueue<?>) collection.forEach(RollbackGraphCodec::requireKey);
                var children = new ArrayList<Object>(collection);
                if (kind == Kind.TREE_SET) children.add(((TreeSet<?>) collection).comparator());
                if (kind == Kind.QUEUE) children.add(((PriorityQueue<?>) collection).comparator());
                return new Entry(kind, symbol, null, references(children));
            }
            Integer id = catalog.indices.get(type);
            if (id == null || !catalog.layouts.containsKey(type)) throw unsupported(type);
            var children = new ArrayList<Object>();
            for (var field : catalog.layout(id).fields) children.add(field.read(source));
            return new Entry(Kind.OBJECT, id, null, references(children));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        int enumType(EnumMap<?, ?> map) {
            if (!map.isEmpty()) return catalog.id(((Enum<?>) map.keySet().iterator().next()).getDeclaringClass());
            // Public EnumMap operations expose the empty map's key contract without JDK reflection.
            EnumMap probe = map.clone();
            for (Class<?> type : catalog.types) if (type.isEnum() && type.getEnumConstants().length > 0) {
                try { probe.put(type.getEnumConstants()[0], null); return catalog.id(type); }
                catch (ClassCastException ignored) { }
            }
            throw invalid("empty enum map requires a registered nonempty enum");
        }
    }

    private void write(DataOutputStream out, Entry entry) throws IOException {
        out.writeByte(entry.kind.ordinal());
        if (typed(entry.kind)) out.writeInt(entry.type);
        Object value = entry.value;
        switch (entry.kind) {
            case STRING -> writeString(out, (String) value);
            case BOOLEAN, ATOMIC_BOOLEAN, LINKED_MAP -> out.writeBoolean((Boolean) value);
            case BYTE -> out.writeByte((Byte) value);
            case SHORT -> out.writeShort((Short) value);
            case CHAR -> out.writeChar((Character) value);
            case INT, ATOMIC_INT, ENUM -> out.writeInt((Integer) value);
            case LONG, ATOMIC_LONG, DATE -> out.writeLong((Long) value);
            case FLOAT -> out.writeInt(Float.floatToRawIntBits((Float) value));
            case DOUBLE -> out.writeLong(Double.doubleToRawLongBits((Double) value));
            case UUID -> { UUID id = (UUID) value; out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
            case OPTIONAL_INT -> { var optional = (OptionalInt) value; out.writeBoolean(optional.isPresent()); if (optional.isPresent()) out.writeInt(optional.getAsInt()); }
            case OPTIONAL_LONG -> { var optional = (OptionalLong) value; out.writeBoolean(optional.isPresent()); if (optional.isPresent()) out.writeLong(optional.getAsLong()); }
            case OPTIONAL_DOUBLE -> { var optional = (OptionalDouble) value; out.writeBoolean(optional.isPresent()); if (optional.isPresent()) out.writeLong(Double.doubleToRawLongBits(optional.getAsDouble())); }
            case PRIMITIVE_ARRAY -> {
                int length = Array.getLength(value);
                out.writeInt(length);
                Class<?> type = catalog.type(entry.type);
                for (int i = 0; i < length; i++) writePrimitive(out, type, value, i);
            }
            default -> { }
        }
        writeReferences(out, entry.references);
    }

    private Entry read(DataInputStream in, int count, Budget budget) throws IOException {
        int ordinal = in.readUnsignedByte();
        if (ordinal >= Kind.values().length) throw invalid("node kind");
        Kind kind = Kind.values()[ordinal];
        int type = typed(kind) ? in.readInt() : -1;
        if (kind == Kind.EXTERNAL) bounded(type, catalog.bindings.size() - 1, "binding index");
        else if (typed(kind)) catalog.type(type);
        Object value = switch (kind) {
            case STRING -> readString(in);
            case BOOLEAN, ATOMIC_BOOLEAN, LINKED_MAP -> readBoolean(in);
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case CHAR -> in.readChar();
            case INT, ATOMIC_INT, ENUM -> in.readInt();
            case LONG, ATOMIC_LONG, DATE -> in.readLong();
            case FLOAT -> Float.intBitsToFloat(in.readInt());
            case DOUBLE -> Double.longBitsToDouble(in.readLong());
            case UUID -> new UUID(in.readLong(), in.readLong());
            case OPTIONAL_INT -> readBoolean(in) ? OptionalInt.of(in.readInt()) : OptionalInt.empty();
            case OPTIONAL_LONG -> readBoolean(in) ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
            case OPTIONAL_DOUBLE -> readBoolean(in) ? OptionalDouble.of(Double.longBitsToDouble(in.readLong())) : OptionalDouble.empty();
            case PRIMITIVE_ARRAY -> {
                Class<?> component = catalog.type(type);
                if (!component.isPrimitive()) throw invalid("primitive array type");
                int length = in.readInt();
                budget.add(length);
                int width = component == byte.class || component == boolean.class ? 1 : component == short.class || component == char.class ? 2
                        : component == int.class || component == float.class ? 4 : 8;
                if ((long) length * width > in.available()) throw invalid("array storage");
                Object array = Array.newInstance(component, length);
                for (int i = 0; i < length; i++) readPrimitive(in, component, array, i);
                yield array;
            }
            default -> null;
        };
        return new Entry(kind, type, value, readReferences(in, count, budget));
    }

    private void validate(List<Entry> entries, int[] roots) {
        // Only reachable nodes may be allocated; reject concealed, unused object graphs.
        boolean[] seen = new boolean[entries.size()];
        var pending = new ArrayDeque<Integer>();
        for (int root : roots) if (!seen[root]) { seen[root] = true; pending.add(root); }
        while (!pending.isEmpty()) for (int id : entries.get(pending.removeFirst()).references) if (!seen[id]) { seen[id] = true; pending.add(id); }
        for (int i = 0; i < entries.size(); i++) {
            if (!seen[i]) throw invalid("unreachable node");
            Entry entry = entries.get(i);
            int n = entry.references.length;
            switch (entry.kind) {
                case OBJECT -> {
                    var fields = catalog.layout(entry.type).fields;
                    if (n != fields.size()) throw invalid("field count");
                    for (int f = 0; f < n; f++) requireAssignable(fields.get(f).field.getType(), entries.get(entry.references[f]));
                }
                case ARRAY -> {
                    Class<?> component = catalog.type(entry.type);
                    if (component.isPrimitive()) throw invalid("reference array type");
                    for (int ref : entry.references) requireAssignable(component, entries.get(ref));
                }
                case ATOMIC_REF, WEAK, OPTIONAL -> { if (n != 1) throw invalid("wrapper length"); }
                case ENTRY, IMMUTABLE_ENTRY -> { if (n != 2) throw invalid("entry length"); }
                case SINGLETON_LIST -> { if (n != 1) throw invalid("singleton length"); }
                case TREE_SET, QUEUE -> { if (n < 1) throw invalid("comparator missing"); }
                case TREE_MAP -> { if (n % 2 != 1) throw invalid("sorted map length"); }
                case HASH_MAP, LINKED_MAP, IDENTITY_MAP, ENUM_MAP, CONCURRENT_MAP, WEAK_MAP -> { if (n % 2 != 0) throw invalid("map length"); }
                case ARRAY_LIST, LINKED_LIST, DEQUE, COW_LIST, COW_SET, HASH_SET, LINKED_SET, ENUM_SET, FIXED_LIST -> { }
                default -> { if (n != 0) throw invalid("leaf references"); }
            }
            if (entry.kind == Kind.ENUM || entry.kind == Kind.ENUM_MAP || entry.kind == Kind.ENUM_SET) {
                Class<?> type = catalog.type(entry.type);
                if (!type.isEnum()) throw invalid("enum type");
                if (entry.kind == Kind.ENUM) bounded((Integer) entry.value, type.getEnumConstants().length - 1, "enum constant");
            }
            if (sorted(entry.kind)) requireAssignable(Comparator.class, entries.get(entry.references[n - 1]));
            int size = n - (sorted(entry.kind) ? 1 : 0);
            if (indexed(entry.kind)) {
                var keys = new HashSet<Integer>();
                for (int k = 0; k < size; k += map(entry.kind) ? 2 : 1) {
                    int id = entry.references[k];
                    Entry key = entries.get(id);
                    Class<?> keyType = valueType(key);
                    if (keyType != null && (Map.class.isAssignableFrom(keyType) || Collection.class.isAssignableFrom(keyType))) throw invalid("container key");
                    if (entry.kind != Kind.QUEUE && !keys.add(id)) throw invalid("duplicate key reference");
                    if (entry.kind == Kind.ENUM_MAP || entry.kind == Kind.ENUM_SET) requireAssignable(catalog.type(entry.type), key);
                }
            }
            if (entry.kind == Kind.DEQUE || entry.kind == Kind.QUEUE || entry.kind == Kind.FIXED_LIST || entry.kind == Kind.CONCURRENT_MAP
                    || entry.kind == Kind.ENUM_SET || entry.kind == Kind.ENUM_MAP) {
                for (int k = 0; k < size; k++) {
                    if (entry.kind == Kind.ENUM_MAP && k % 2 == 1) continue;
                    if (valueType(entries.get(entry.references[k])) == null) throw invalid("null container element");
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes", "removal"})
    private List<Object> materialize(List<Entry> entries, int[] roots) {
        var nodes = new ArrayList<RollbackStateTransfer.Node>(entries.size());
        for (Entry entry : entries) nodes.add(new RollbackStateTransfer.Node(entry));
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            var node = nodes.get(index);
            node.children = Arrays.stream(entry.references).mapToObj(nodes::get).toList();
            int n = node.children.size(), size = n - (sorted(entry.kind) ? 1 : 0);
            Object target = switch (entry.kind) {
                case NULL -> null;
                case EXTERNAL -> catalog.bindings.get(entry.type).value();
                // Keep separate equal-valued nodes distinct for IdentityHashMap and identity fields.
                case BOOLEAN -> new Boolean((Boolean) entry.value);
                case BYTE -> new Byte((Byte) entry.value);
                case SHORT -> new Short((Short) entry.value);
                case INT -> new Integer((Integer) entry.value);
                case LONG -> new Long((Long) entry.value);
                case FLOAT -> new Float((Float) entry.value);
                case DOUBLE -> new Double((Double) entry.value);
                case CHAR -> new Character((Character) entry.value);
                case STRING, UUID, OPTIONAL_INT, OPTIONAL_LONG, OPTIONAL_DOUBLE, PRIMITIVE_ARRAY -> entry.value;
                case ENUM -> catalog.type(entry.type).getEnumConstants()[(Integer) entry.value];
                case CLASS -> catalog.type(entry.type);
                case ARRAY -> Array.newInstance(catalog.type(entry.type), n);
                case OBJECT -> catalog.layout(entry.type).allocator.newInstance();
                case DATE -> new Date((Long) entry.value);
                case ATOMIC_INT -> new AtomicInteger((Integer) entry.value);
                case ATOMIC_LONG -> new AtomicLong((Long) entry.value);
                case ATOMIC_BOOLEAN -> new AtomicBoolean((Boolean) entry.value);
                case ATOMIC_REF -> new AtomicReference<>();
                case ARRAY_LIST -> new ArrayList<>();
                case LINKED_LIST -> new LinkedList<>();
                case DEQUE -> new ArrayDeque<>();
                case COW_LIST -> new CopyOnWriteArrayList<>();
                case COW_SET -> new CopyOnWriteArraySet<>();
                case HASH_SET -> new HashSet<>();
                case LINKED_SET -> new LinkedHashSet<>();
                case ENUM_SET -> EnumSet.noneOf((Class) catalog.type(entry.type));
                case HASH_MAP -> new HashMap<>();
                case LINKED_MAP -> new LinkedHashMap<>(16, .75f, (Boolean) entry.value);
                case IDENTITY_MAP -> new IdentityHashMap<>();
                case ENUM_MAP -> new EnumMap((Class) catalog.type(entry.type));
                case CONCURRENT_MAP -> new ConcurrentHashMap<>();
                case WEAK_MAP -> new WeakHashMap<>();
                case EMPTY_LIST -> Collections.emptyList();
                case EMPTY_MAP -> Map.of();
                case EMPTY_SET -> Set.of();
                default -> null;
            };
            if (target != null || entry.kind == Kind.NULL) node.ready(target);
            switch (entry.kind) {
                case OBJECT -> node.fill = () -> {
                    var fields = catalog.layout(entry.type).fields;
                    for (int i = 0; i < n; i++) fields.get(i).write(node.target, node.children.get(i).target);
                };
                case ARRAY -> node.fill = () -> { for (int i = 0; i < n; i++) Array.set(node.target, i, node.children.get(i).target); };
                case ATOMIC_REF -> node.fill = () -> ((AtomicReference) node.target).set(node.children.getFirst().target);
                case OPTIONAL, WEAK, ENTRY, IMMUTABLE_ENTRY, FIXED_LIST, SINGLETON_LIST -> node.create = () -> {
                    Object first = n == 0 ? null : node.children.getFirst().target;
                    return switch (entry.kind) {
                        case OPTIONAL -> Optional.ofNullable(first);
                        case WEAK -> new WeakReference<>(first);
                        case ENTRY -> new AbstractMap.SimpleEntry<>(first, node.children.get(1).target);
                        case IMMUTABLE_ENTRY -> new AbstractMap.SimpleImmutableEntry<>(first, node.children.get(1).target);
                        case SINGLETON_LIST -> Collections.singletonList(first);
                        default -> List.of(node.children.stream().map(child -> child.target).toArray());
                    };
                };
                default -> { }
            }
            if (map(entry.kind) || collection(entry.kind)) {
                node.indexed = indexed(entry.kind);
                if (sorted(entry.kind)) {
                    var comparator = node.children.getLast();
                    node.construction = List.of(comparator);
                    node.create = () -> entry.kind == Kind.TREE_MAP ? new TreeMap((Comparator) comparator.target)
                            : entry.kind == Kind.TREE_SET ? new TreeSet((Comparator) comparator.target) : new PriorityQueue(Math.max(1, size), (Comparator) comparator.target);
                }
                var keys = new ArrayList<RollbackStateTransfer.Node>();
                if (node.indexed && entry.kind != Kind.IDENTITY_MAP) {
                    for (int i = 0; i < size; i += map(entry.kind) ? 2 : 1) {
                        Entry key = entries.get(entry.references[i]);
                        if (sorted(entry.kind) || (key.kind != Kind.EXTERNAL && !RollbackStateTransfer.identityKey(valueType(key)))) keys.add(node.children.get(i));
                    }
                    if (sorted(entry.kind)) keys.add(node.children.getLast());
                }
                node.keys = keys;
                node.fill = () -> {
                    if (map(entry.kind)) {
                        Map result = (Map) node.target;
                        for (int i = 0; i < size; i += 2) result.put(node.children.get(i).target, node.children.get(i + 1).target);
                        if (result.size() != size / 2) throw invalid("duplicate map keys");
                    } else {
                        Collection result = (Collection) node.target;
                        for (int i = 0; i < size; i++) result.add(node.children.get(i).target);
                        if (result.size() != size) throw invalid("duplicate set elements");
                    }
                };
            }
        }
        RollbackStateTransfer.finish(nodes, new RollbackStateTransfer.Limits(limits.maximumObjects(), limits.maximumReferences()));
        var result = new ArrayList<Object>(roots.length);
        for (int id : roots) result.add(nodes.get(id).target);
        return Collections.unmodifiableList(result);
    }

    private Class<?> valueType(Entry entry) {
        return switch (entry.kind) {
            case NULL -> null;
            case EXTERNAL -> catalog.bindings.get(entry.type).type();
            case OBJECT, ENUM -> catalog.type(entry.type);
            case ARRAY, PRIMITIVE_ARRAY -> catalog.type(entry.type).arrayType();
            case CLASS -> Class.class;
            case STRING -> String.class;
            case BOOLEAN -> Boolean.class;
            case BYTE -> Byte.class;
            case SHORT -> Short.class;
            case INT -> Integer.class;
            case LONG -> Long.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            case CHAR -> Character.class;
            case UUID -> UUID.class;
            case OPTIONAL_INT -> OptionalInt.class;
            case OPTIONAL_LONG -> OptionalLong.class;
            case OPTIONAL_DOUBLE -> OptionalDouble.class;
            case DATE -> Date.class;
            case ATOMIC_INT -> AtomicInteger.class;
            case ATOMIC_LONG -> AtomicLong.class;
            case ATOMIC_BOOLEAN -> AtomicBoolean.class;
            case ATOMIC_REF -> AtomicReference.class;
            case OPTIONAL -> Optional.class;
            case WEAK -> WeakReference.class;
            case ENTRY -> AbstractMap.SimpleEntry.class;
            case IMMUTABLE_ENTRY -> AbstractMap.SimpleImmutableEntry.class;
            case ARRAY_LIST -> ArrayList.class;
            case LINKED_LIST -> LinkedList.class;
            case DEQUE -> ArrayDeque.class;
            case COW_LIST -> CopyOnWriteArrayList.class;
            case COW_SET -> CopyOnWriteArraySet.class;
            case HASH_SET -> HashSet.class;
            case LINKED_SET -> LinkedHashSet.class;
            case ENUM_SET -> EnumSet.class;
            case TREE_SET -> TreeSet.class;
            case QUEUE -> PriorityQueue.class;
            case FIXED_LIST, SINGLETON_LIST, EMPTY_LIST -> List.class;
            case EMPTY_MAP -> Map.class;
            case EMPTY_SET -> Set.class;
            case HASH_MAP -> HashMap.class;
            case LINKED_MAP -> LinkedHashMap.class;
            case IDENTITY_MAP -> IdentityHashMap.class;
            case ENUM_MAP -> EnumMap.class;
            case CONCURRENT_MAP -> ConcurrentHashMap.class;
            case WEAK_MAP -> WeakHashMap.class;
            case TREE_MAP -> TreeMap.class;
        };
    }

    private void requireAssignable(Class<?> expected, Entry entry) {
        Class<?> actual = valueType(entry);
        if (actual == null) { if (expected.isPrimitive()) throw invalid("null primitive field"); return; }
        if (expected.isPrimitive()) expected = expected == boolean.class ? Boolean.class : expected == byte.class ? Byte.class
                : expected == short.class ? Short.class : expected == int.class ? Integer.class : expected == long.class ? Long.class
                : expected == float.class ? Float.class : expected == double.class ? Double.class : Character.class;
        if (!expected.isAssignableFrom(actual)) throw invalid("reference type " + actual.getName() + " for " + expected.getName());
    }

    private static boolean typed(Kind kind) { return kind == Kind.EXTERNAL || kind == Kind.ENUM || kind == Kind.CLASS || kind == Kind.OBJECT
            || kind == Kind.ARRAY || kind == Kind.PRIMITIVE_ARRAY || kind == Kind.ENUM_SET || kind == Kind.ENUM_MAP; }
    private static boolean map(Kind kind) { return kind.ordinal() >= Kind.HASH_MAP.ordinal(); }
    private static boolean collection(Kind kind) { return kind.ordinal() >= Kind.ARRAY_LIST.ordinal() && kind.ordinal() <= Kind.QUEUE.ordinal(); }
    private static boolean indexed(Kind kind) { return map(kind) || kind == Kind.COW_SET || kind == Kind.HASH_SET || kind == Kind.LINKED_SET
            || kind == Kind.ENUM_SET || kind == Kind.TREE_SET || kind == Kind.QUEUE; }
    private static boolean sorted(Kind kind) { return kind == Kind.TREE_MAP || kind == Kind.TREE_SET || kind == Kind.QUEUE; }
    private static boolean fixed(Class<?> type) { return type.getName().startsWith("java.util.ImmutableCollections$") || type.getName().startsWith("java.util.Collections$Empty"); }
    private static void requireKey(Object value) { if (value instanceof Map<?, ?> || value instanceof Collection<?>) throw invalid("container key requires a binding"); }
    private void checkThread() {
        if (Thread.currentThread() != owner || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Graph transfer requires an owning-thread bootstrap boundary");
    }
    private static int bounded(int value, int maximum, String label) { if (value < 0 || value > maximum) throw invalid(label); return value; }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid rollback graph: " + detail); }
    private static IllegalArgumentException unsupported(Class<?> type) { return invalid("explicit binding required for " + type.getName()); }
    private static void writeReferences(DataOutputStream out, int[] references) throws IOException {
        out.writeInt(references.length);
        for (int id : references) out.writeInt(id);
    }
    private int[] readReferences(DataInputStream in, int count, Budget budget) throws IOException {
        int length = in.readInt();
        budget.add(length);
        if (4L * length > in.available()) throw invalid("reference storage");
        int[] result = new int[length];
        for (int i = 0; i < length; i++) result[i] = bounded(in.readInt(), count - 1, "reference index");
        return result;
    }
    private void writeString(DataOutputStream out, String value) throws IOException {
        if (value.length() > limits.maximumStringBytes()) throw invalid("string budget");
        byte[] bytes;
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        } catch (CharacterCodingException exception) { throw invalid("UTF-8"); }
        bounded(bytes.length, limits.maximumStringBytes(), "string budget");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private String readString(DataInputStream in) throws IOException {
        int length = bounded(in.readInt(), limits.maximumStringBytes(), "string budget");
        if (length > in.available()) throw invalid("string storage");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static void schemaString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw invalid("boolean");
        return value == 1;
    }
    private static void writePrimitive(DataOutputStream out, Class<?> type, Object array, int i) throws IOException {
        if (type == boolean.class) out.writeBoolean(Array.getBoolean(array, i));
        else if (type == byte.class) out.writeByte(Array.getByte(array, i));
        else if (type == short.class) out.writeShort(Array.getShort(array, i));
        else if (type == char.class) out.writeChar(Array.getChar(array, i));
        else if (type == int.class) out.writeInt(Array.getInt(array, i));
        else if (type == long.class) out.writeLong(Array.getLong(array, i));
        else if (type == float.class) out.writeInt(Float.floatToRawIntBits(Array.getFloat(array, i)));
        else out.writeLong(Double.doubleToRawLongBits(Array.getDouble(array, i)));
    }
    private static void readPrimitive(DataInputStream in, Class<?> type, Object array, int i) throws IOException {
        if (type == boolean.class) Array.setBoolean(array, i, readBoolean(in));
        else if (type == byte.class) Array.setByte(array, i, in.readByte());
        else if (type == short.class) Array.setShort(array, i, in.readShort());
        else if (type == char.class) Array.setChar(array, i, in.readChar());
        else if (type == int.class) Array.setInt(array, i, in.readInt());
        else if (type == long.class) Array.setLong(array, i, in.readLong());
        else if (type == float.class) Array.setFloat(array, i, Float.intBitsToFloat(in.readInt()));
        else Array.setDouble(array, i, Double.longBitsToDouble(in.readLong()));
    }
}
