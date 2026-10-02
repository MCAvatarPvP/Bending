package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RollbackStateGraphTest {
    private final RollbackStateGraph graph = new RollbackStateGraph(ignored -> false, ignored -> true, 10_000);

    private static class BaseValues { private int hidden = 7; }
    private static final class Values extends BaseValues {
        private boolean flag = true;
        private byte tiny = Byte.MIN_VALUE;
        private short small = Short.MAX_VALUE;
        private char letter = '\uffff';
        private int hidden = Integer.MIN_VALUE;
        private volatile long large = Long.MAX_VALUE;
        private float decimal = -0.0F;
        private double precise = Double.NaN;
        private final Node reference = new Node();
    }

    @Test void cachedAccessorsRestoreEveryPrimitiveAndShadowedPrivateFields() {
        Values values = new Values();
        var snapshot = graph.capture(List.of(values), List.of());
        values.flag = false;
        values.tiny = 0;
        values.small = 0;
        values.letter = 0;
        values.hidden = 0;
        ((BaseValues) values).hidden = 0;
        values.large = 0;
        values.decimal = 1;
        values.precise = 1;
        values.reference.position = 9;
        snapshot.restore();
        assertTrue(values.flag);
        assertEquals(Byte.MIN_VALUE, values.tiny);
        assertEquals(Short.MAX_VALUE, values.small);
        assertEquals('\uffff', values.letter);
        assertEquals(Integer.MIN_VALUE, values.hidden);
        assertEquals(7, ((BaseValues) values).hidden);
        assertEquals(Long.MAX_VALUE, values.large);
        assertEquals(Float.floatToRawIntBits(-0.0F), Float.floatToRawIntBits(values.decimal));
        assertTrue(Double.isNaN(values.precise));
        assertEquals(0, values.reference.position);
    }

    @Test void cachedLayoutsRespectEachGraphsFieldSelection() {
        Values values = new Values();
        graph.capture(List.of(values), List.of()); // Warm the shared class layout.
        var selective = new RollbackStateGraph(ignored -> false,
                field -> field.getName().equals("large"), 100);
        var snapshot = selective.capture(List.of(values), List.of());
        values.large = 4;
        values.hidden = 5;
        snapshot.restore();
        assertEquals(Long.MAX_VALUE, values.large);
        assertEquals(5, values.hidden);
    }

    @Test void excludedFieldsNeverNeedAccessToClosedPlatformModules() {
        var excluded = new RollbackStateGraph(ignored -> false, ignored -> false, 100);
        var privateRoots = RollbackStateGraph.staticFields(String.class, ignored -> true);
        assertFalse(privateRoots.isEmpty());
        assertDoesNotThrow(() -> excluded.capture(List.of(), privateRoots).restore());
    }

    private static class Node {
        int position;
        Node next;
        Node shared;
        final List<Node> children = new ArrayList<>();
        final int[] samples = {1, 2, 3};
        final AtomicLong sequence = new AtomicLong(5);
    }

    @Test void restoresDynamicFieldsCyclesAliasesAndRemovedObjectsWithoutReconstructingThem() {
        Node parent = new Node();
        Node child = new Node();
        parent.next = child;
        parent.shared = child;
        child.next = parent;
        parent.children.add(child);
        var first = graph.capture(List.of(parent), List.of());
        parent.next = new Node();
        parent.shared = null;
        parent.children.clear();
        parent.position = 90;
        child.position = 70;
        parent.samples[0] = 99;
        parent.sequence.incrementAndGet();
        var second = graph.capture(List.of(parent, child), List.of());

        first.restore();
        assertSame(child, parent.next);
        assertSame(parent.next, parent.shared);
        assertSame(parent, child.next);
        assertSame(child, parent.children.getFirst());
        assertEquals(0, child.position);
        assertArrayEquals(new int[]{1, 2, 3}, parent.samples);
        assertEquals(5, parent.sequence.get());

        second.restore();
        assertNotSame(child, parent.next);
        assertNull(parent.shared);
        assertTrue(parent.children.isEmpty());
        assertEquals(90, parent.position);
        assertEquals(70, child.position);
        first.restore();
        assertSame(child, parent.next); // Checkpoints remain reusable after another restore.
    }

    private static class Key implements Comparable<Key> {
        int coordinate;
        Key(int coordinate) { this.coordinate = coordinate; }
        @Override public int hashCode() { return coordinate; }
        @Override public boolean equals(Object other) { return other instanceof Key key && key.coordinate == coordinate; }
        @Override public int compareTo(Key other) { return Integer.compare(coordinate, other.coordinate); }
    }

    @Test void restoresMutableKeysBeforeRebuildingHashMapsAndPriorityQueues() {
        Key first = new Key(1), second = new Key(2);
        Map<Key, String> map = new HashMap<>();
        map.put(first, "first");
        map.put(second, "second");
        PriorityQueue<Key> queue = new PriorityQueue<>(List.of(first, second));
        var snapshot = graph.capture(List.of(map, queue), List.of());
        first.coordinate = 8;
        second.coordinate = -3;
        queue.clear();
        map.clear();
        snapshot.restore();
        assertEquals("first", map.get(new Key(1)));
        assertEquals("second", map.get(new Key(2)));
        assertSame(first, queue.poll());
        assertSame(second, queue.poll());
    }

    private record Holder(List<Node> immutableList, WeakReference<Node> reference) {}

    @Test void immutableContainersStillCaptureTheirMutableChildren() {
        Node child = new Node();
        Holder holder = new Holder(List.of(child), new WeakReference<>(child));
        var snapshot = graph.capture(List.of(holder), List.of());
        child.position = 20;
        snapshot.restore();
        assertEquals(0, child.position);
        assertSame(child, holder.reference().get());
    }

    private static final class Entry extends java.util.AbstractMap.SimpleImmutableEntry<Node, Node> {
        int extra;
        Entry(Node key, Node value) { super(key, value); }
    }

    @Test void nativeImmutableContainersAndPrimitiveOptionalsRetainMutableChildrenAndSubclassState() {
        Node key = new Node(), value = new Node();
        Entry entry = new Entry(key, value);
        var immutableMap = com.google.common.collect.ImmutableMap.of("entry", entry);
        var immutableList = com.google.common.collect.ImmutableList.of(value);
        var snapshot = graph.capture(List.of(immutableMap, immutableList,
                java.util.OptionalInt.of(2), java.util.OptionalLong.of(3), java.util.OptionalDouble.of(4)), List.of());
        key.position = 4;
        value.position = 5;
        entry.extra = 6;
        snapshot.restore();
        assertSame(entry, immutableMap.get("entry"));
        assertSame(value, immutableList.getFirst());
        assertEquals(0, key.position);
        assertEquals(0, value.position);
        assertEquals(0, entry.extra);
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(key));
    }

    private static class Registry {
        static int sequence;
        static Node instance;
        static final Map<Integer, Node> active = new HashMap<>();
    }

    @Test void registeredStaticRootsRestoreMembershipAndReplacedSingletons() {
        var fields = RollbackStateGraph.staticFields(Registry.class, ignored -> true);
        var before = graph.capture(List.of(), fields);
        try {
            Node original = new Node();
            Registry.instance = original;
            Registry.sequence = 3;
            Registry.active.put(3, original);
            var checkpoint = graph.capture(List.of(), fields);
            Registry.instance = new Node();
            Registry.sequence = 4;
            Registry.active.clear();
            Registry.active.put(4, Registry.instance);
            checkpoint.restore();
            assertSame(original, Registry.instance);
            assertEquals(3, Registry.sequence);
            assertEquals(Map.of(3, original), Registry.active);
        } finally {
            before.restore();
        }
    }

    @Test void unsupportedMutablePlatformStateIsRejectedInsteadOfSilentlySkipped() {
        var failure = assertThrows(IllegalStateException.class,
                () -> graph.capture(List.of(new Random(1)), List.of()));
        assertTrue(failure.getMessage().contains("java.util.Random"));
    }

    @Test void explicitExternalHandlesAreNotTraversedAndObjectBudgetIsEnforced() {
        Random external = new Random(1);
        var adapterGraph = new RollbackStateGraph(value -> value instanceof Random, ignored -> true, 2);
        assertEquals(0, adapterGraph.capture(List.of(external), List.of()).objectCount());
        Node root = new Node();
        root.next = new Node();
        assertThrows(IllegalStateException.class, () -> adapterGraph.capture(List.of(root), List.of()));
    }
}
