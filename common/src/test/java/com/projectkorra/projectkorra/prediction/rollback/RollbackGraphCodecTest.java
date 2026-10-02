package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RollbackGraphCodecTest {
    private static final RollbackGraphCodec.Limits LIMITS = new RollbackGraphCodec.Limits(10_000, 100_000, 1_048_576, 65_536);
    private enum Axis { X, Y, Z }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void preservesCyclesFinalFieldsHashDependenciesComparatorsAndRemapsExternalHandles() {
        var sourceService = new Service("source");
        var targetService = new Service("replica");
        var source = codec(sourceService);
        var target = codec(targetService);
        var key = new Key(sourceService, "one");
        var child = new Key(sourceService, "two");
        key.other = child;
        child.other = key;
        var order = new Order(-1);
        var tree = new TreeMap<Key, Object>(order);
        tree.put(key, tree);
        tree.put(child, key);
        var hash = new HashMap<Key, Object>();
        hash.put(key, tree);
        var refs = new AtomicReference<>();
        refs.set(refs);
        Object[] array = {key, child, null, refs, List.of(key), new WeakReference<>(child)};
        array[2] = array;
        key.extra = Optional.of(array);
        int constructions = Key.constructions;
        byte[] wire = source.encode(List.of(array, hash, key, order, sourceService));
        List<Object> imported = target.decode(wire);
        assertEquals(constructions, Key.constructions);
        Object[] copy = (Object[]) imported.getFirst();
        Key copiedKey = (Key) copy[0], copiedChild = (Key) copy[1];
        assertSame(copiedKey, imported.get(2));
        assertSame(copiedChild, copiedKey.other);
        assertSame(copiedKey, copiedChild.other);
        assertSame(targetService, copiedKey.service);
        assertSame(targetService, imported.get(4));
        assertSame(copy, copy[2]);
        assertSame(copy, copiedKey.extra.orElseThrow());
        assertSame(copy[3], ((AtomicReference<?>) copy[3]).get());
        assertSame(copiedChild, ((WeakReference<?>) copy[5]).get());
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) copy[4]).clear());
        var copiedTree = (TreeMap) ((Map) imported.get(1)).get(copiedKey);
        assertSame(copiedTree, copiedTree.get(copiedKey));
        assertSame(copiedKey, copiedTree.get(copiedChild));
        assertSame(imported.get(3), copiedTree.comparator());
        assertNotSame(key.parts, copiedKey.parts);
        copiedKey.parts.add("change");
        assertEquals(Set.of("one"), key.parts);
        assertSame(sourceService, key.service);
        assertSame(key, child.other);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes", "removal"})
    void preservesDistinctBoxedIdentityEmptyEnumTypesAccessOrderAndPrimitiveBits() {
        var codec = codec(new Service("local"));
        Integer a = new Integer(7), b = new Integer(7);
        var identity = new IdentityHashMap<>();
        identity.put(a, "a"); identity.put(b, "b");
        var access = new LinkedHashMap<String, Integer>(16, .25f, true);
        access.put("first", a); access.put("second", b);
        var emptyMap = new EnumMap<Axis, Integer>(Axis.class);
        EnumSet<Axis> emptySet = EnumSet.noneOf(Axis.class);
        float rawFloat = Float.intBitsToFloat(0x7fc00041);
        double rawDouble = Double.longBitsToDouble(0x7ff8000000000042L);
        Object[] primitives = {new boolean[]{true, false}, new byte[]{-1, 2}, new short[]{-1000}, new char[]{'\uFFFF'},
                new int[]{Integer.MIN_VALUE}, new long[]{Long.MAX_VALUE}, new float[]{rawFloat, -0f}, new double[]{rawDouble, -0d}};
        var output = codec.decode(codec.encode(Arrays.asList(identity, a, b, emptyMap, emptySet, access, primitives, null)));
        Map copiedIdentity = (Map) output.getFirst();
        assertNotSame(output.get(1), output.get(2));
        assertEquals("a", copiedIdentity.get(output.get(1)));
        assertEquals("b", copiedIdentity.get(output.get(2)));
        ((EnumMap) output.get(3)).put(Axis.Y, 1);
        ((EnumSet) output.get(4)).add(Axis.Z);
        var copiedAccess = (LinkedHashMap) output.get(5);
        copiedAccess.get("first");
        assertEquals(List.of("second", "first"), new ArrayList<>(copiedAccess.keySet()));
        assertEquals(List.of("first", "second"), new ArrayList<>(access.keySet()));
        Object[] copy = (Object[]) output.get(6);
        assertArrayEquals((boolean[]) primitives[0], (boolean[]) copy[0]);
        assertArrayEquals((byte[]) primitives[1], (byte[]) copy[1]);
        assertArrayEquals((short[]) primitives[2], (short[]) copy[2]);
        assertArrayEquals((char[]) primitives[3], (char[]) copy[3]);
        assertArrayEquals((int[]) primitives[4], (int[]) copy[4]);
        assertArrayEquals((long[]) primitives[5], (long[]) copy[5]);
        assertEquals(0x7fc00041, Float.floatToRawIntBits(((float[]) copy[6])[0]));
        assertEquals(0x7ff8000000000042L, Double.doubleToRawLongBits(((double[]) copy[7])[0]));
        assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits(((float[]) copy[6])[1]));
        assertEquals(Double.doubleToRawLongBits(-0d), Double.doubleToRawLongBits(((double[]) copy[7])[1]));
        assertNull(output.getLast());
        assertTrue(emptyMap.isEmpty());
        assertTrue(emptySet.isEmpty());
    }

    @Test void projectionsPreserveAliasesAndRequireBoundDirectValues() {
        var codec = codec(new Service("local"));
        var source = new HashMap<String, Object>();
        source.put("inside", "value"); source.put("outside", new Thread());
        var projection = new HashMap<>(source);
        projection.remove("outside");
        byte[] wire = codec.encode(List.of(source, source), object -> object == source
                ? RollbackStateTransfer.Replacement.fromProjection(projection) : null);
        var copy = codec.decode(wire);
        assertSame(copy.getFirst(), copy.getLast());
        assertEquals(Map.of("inside", "value"), copy.getFirst());
        assertEquals(2, source.size());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(source)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(source),
                value -> new RollbackStateTransfer.Replacement(new Object())));
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void remainingContainersAndValueWrappersRetainBehaviorAndOwnership() {
        var service = new Service("local");
        var codec = codec(service);
        var key = new Key(service, "key");
        var order = new Order(1);
        var queue = new PriorityQueue<Key>(order);
        queue.add(key); queue.add(key);
        var tree = new TreeSet<Key>(order); tree.add(key);
        List<Object> roots = Arrays.asList(new LinkedList<>(Arrays.asList(key, null)), new ArrayDeque<>(List.of(key)),
                new CopyOnWriteArrayList<>(Arrays.asList(null, key)), new CopyOnWriteArraySet<>(List.of(key)),
                new LinkedHashSet<>(List.of(key)), tree, queue, new ConcurrentHashMap<>(Map.of("key", key)),
                new WeakHashMap<>(Map.of(key, "weak")), new AbstractMap.SimpleEntry<>(key, null),
                new AbstractMap.SimpleImmutableEntry<>(null, key), Collections.singletonList(null),
                Collections.emptyList(), Set.of(), Map.of(), OptionalInt.of(42), OptionalLong.empty(), OptionalDouble.of(-0d),
                new AtomicInteger(3), new AtomicLong(4), new AtomicBoolean(true), new Date(12345), key, order, "\uD83D\uDD25");
        var copy = codec.decode(codec.encode(roots));
        Object copiedKey = copy.get(22);
        assertEquals(Arrays.asList(copiedKey, null), copy.get(0));
        assertSame(copiedKey, ((ArrayDeque) copy.get(1)).removeFirst());
        assertEquals(Arrays.asList(null, copiedKey), copy.get(2));
        assertEquals(Set.of(copiedKey), copy.get(3));
        assertEquals(Set.of(copiedKey), copy.get(4));
        assertSame(copy.get(23), ((TreeSet) copy.get(5)).comparator());
        assertEquals(2, ((PriorityQueue) copy.get(6)).size());
        assertSame(copiedKey, ((PriorityQueue) copy.get(6)).remove());
        assertSame(copiedKey, ((Map) copy.get(7)).get("key"));
        assertEquals("weak", ((Map) copy.get(8)).get(copiedKey));
        assertSame(copiedKey, ((Map.Entry) copy.get(9)).getKey());
        assertSame(copiedKey, ((Map.Entry) copy.get(10)).getValue());
        assertThrows(UnsupportedOperationException.class, () -> ((Map.Entry) copy.get(10)).setValue(null));
        assertEquals(Collections.singletonList(null), copy.get(11));
        for (int i = 12; i < 15; i++) assertEquals(roots.get(i), copy.get(i));
        assertEquals(OptionalInt.of(42), copy.get(15));
        assertEquals(OptionalLong.empty(), copy.get(16));
        assertEquals(OptionalDouble.of(-0d), copy.get(17));
        assertEquals(3, ((AtomicInteger) copy.get(18)).getAndIncrement());
        assertEquals(3, ((AtomicInteger) roots.get(18)).get());
        assertEquals(4, ((AtomicLong) copy.get(19)).get());
        assertTrue(((AtomicBoolean) copy.get(20)).get());
        assertEquals(new Date(12345), copy.get(21));
        assertNotSame(roots.get(21), copy.get(21));
        assertEquals("\uD83D\uDD25", copy.get(24));
        assertEquals(1, ((ArrayDeque) roots.get(1)).size());
    }

    @Test void catalogsAreOrderIndependentButSchemaBindingAndEnumContractsMustMatch() {
        var service = new Service("a");
        var a = codec(service);
        var catalog = new RollbackGraphCodec.Catalog(List.of(Order.class, Key.class), List.of(Axis.class),
                List.of(new RollbackGraphCodec.Binding("service", Service.class, new Service("b"))));
        var b = new RollbackGraphCodec(catalog, LIMITS);
        byte[] bytes = a.encode(List.of(Axis.Z, Key.class, new Key(service, "x")));
        assertEquals(Axis.Z, b.decode(bytes).getFirst());
        var wrong = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(Key.class, Order.class), List.of(Axis.class),
                List.of(new RollbackGraphCodec.Binding("other-service", Service.class, service))), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> wrong.decode(bytes));
        var onlySymbol = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(Order.class), List.of(Axis.class, Key.class),
                List.of(new RollbackGraphCodec.Binding("service", Service.class, service))), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> onlySymbol.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> onlySymbol.encode(List.of(new Key(service, "x"))));
        byte[] fingerprint = catalog.fingerprint();
        fingerprint[0] ^= 1;
        assertNotEquals(fingerprint[0], catalog.fingerprint()[0]);
    }

    @Test void truncationsInvalidReferencesAndTrailingDataNeverReachApplicationHashing() {
        var service = new Service("local");
        var codec = codec(service);
        var key = new Key(service, "value");
        byte[] valid = codec.encode(List.of(new HashMap<>(Map.of(key, key))));
        int hashes = Key.hashes;
        for (int end = 0; end < valid.length; end++) {
            byte[] truncated = Arrays.copyOf(valid, end);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(truncated), "length=" + end);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(valid, valid.length + 1)));
        byte[] badRoot = valid.clone(); ByteBuffer.wrap(badRoot).putInt(44, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badRoot));
        byte[] badCount = valid.clone(); ByteBuffer.wrap(badCount).putInt(36, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badCount));
        byte[] badVersion = valid.clone(); ByteBuffer.wrap(badVersion).putInt(0, 0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badVersion));
        assertEquals(hashes, Key.hashes);
    }

    @Test void invalidBooleanUtf8FieldTypesAndUnreachableNodesAreRejectedBeforeInitialization() {
        var service = new Service("local");
        var codec = codec(service);
        byte[] booleanBytes = codec.encode(List.of(true));
        booleanBytes[49] = 2;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(booleanBytes));
        byte[] text = codec.encode(List.of("xx"));
        text[53] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(text));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of("\uD800")));
        byte[] unused = codec.encode(List.of("one", "two"));
        ByteBuffer.wrap(unused).putInt(48, 0); // Two roots now reference the first string; second node is hidden.
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unused));
        var key = new Key(service, "part");
        byte[] badField = codec.encode(List.of(key, "wrong-service"));
        // The first object starts after two root references. Its final field is the bound service.
        int fieldCount = ByteBuffer.wrap(badField).getInt(57);
        ByteBuffer.wrap(badField).putInt(61 + 4 * (fieldCount - 1), 1);
        int hashes = Key.hashes;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badField));
        assertEquals(hashes, Key.hashes);
    }

    @Test void budgetsRejectLargeArraysStringsGraphsAndResolutionCycles() {
        var service = new Service("local");
        var catalog = catalog(service);
        var small = new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(8, 16, 256, 8));
        assertThrows(IllegalArgumentException.class, () -> small.encode(List.of(new int[17])));
        assertThrows(IllegalArgumentException.class, () -> small.encode(List.of("123456789")));
        assertThrows(IllegalArgumentException.class, () -> small.decode(codec(service).encode(List.of(new int[17]))));
        assertThrows(IllegalArgumentException.class, () -> small.encode(Collections.singletonList(new Object[]{"a", "b", "c", "d", "e", "f", "g", "h"})));
        var tightWire = new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(10, 100, 60, 60));
        assertThrows(IllegalArgumentException.class, () -> tightWire.encode(List.of(new long[8])));
        var key = new Key(service, "part");
        var map = new HashMap<Key, Object>();
        map.put(key, "entry");
        key.extra = Optional.of(map);
        var codec = codec(service);
        assertThrows(IllegalStateException.class, () -> codec.decode(codec.encode(List.of(map))));
        assertEquals("entry", map.get(key));
    }

    private static RollbackGraphCodec codec(Service service) { return new RollbackGraphCodec(catalog(service), LIMITS); }
    private static RollbackGraphCodec.Catalog catalog(Service service) {
        return new RollbackGraphCodec.Catalog(List.of(Key.class, Order.class), List.of(Axis.class),
                List.of(new RollbackGraphCodec.Binding("service", Service.class, service)));
    }
    private record Service(String label) { }
    private static final class Key {
        static int constructions, hashes;
        final Service service;
        final HashSet<String> parts = new HashSet<>();
        Optional<Object> extra = Optional.empty();
        Key other;
        Key(Service service, String part) { constructions++; this.service = service; parts.add(part); }
        @Override public int hashCode() { hashes++; return parts.hashCode(); }
        @Override public boolean equals(Object other) { return other instanceof Key key && parts.equals(key.parts); }
    }
    private static final class Order implements Comparator<Key> {
        final int direction;
        Order(int direction) { this.direction = direction; }
        @Override public int compare(Key a, Key b) { return direction * a.parts.iterator().next().compareTo(b.parts.iterator().next()); }
    }
}
