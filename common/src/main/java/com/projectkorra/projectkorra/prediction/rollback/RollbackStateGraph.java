package com.projectkorra.projectkorra.prediction.rollback;

import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * In-process mementos of the existing mutable runtime. Ability fields are discovered
 * dynamically, so their progress/collision methods continue to own gameplay rules.
 *
 * <p>This is not a network deserializer. Identity is preserved, including cycles,
 * shared child objects and removed instances retained by a checkpoint. Platform handles
 * must be declared external and have their logical state captured by the platform
 * adapter separately. Unsupported JDK state fails capture instead of silently omitting it.</p>
 */
public final class RollbackStateGraph {
    // ClassValue lets addon classes unload. Bind only included fields: an excluded
    // platform field must never require reflective access to its declaring module.
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> type) { return new Layout(type); }
    };

    private static final class Layout {
        final List<Field> declared;
        final List<Field> sorted;
        final Map<Field, FieldAccess> accessors = new ConcurrentHashMap<>();

        Layout(Class<?> type) {
            declared = List.of(type.getDeclaredFields());
            sorted = declared.stream().sorted(Comparator.comparing(Field::getName)).toList();
        }
    }

    private static final class FieldAccess {
        final Field field;
        final MethodHandle getter;
        final MethodHandle setter;

        FieldAccess(Field field) {
            this.field = field;
            try {
                if (!field.trySetAccessible()) throw new IllegalStateException("Simulation field is inaccessible: " + field);
                MethodHandle get = MethodHandles.lookup().unreflectGetter(field);
                MethodHandle set = Modifier.isFinal(field.getModifiers()) ? null : MethodHandles.lookup().unreflectSetter(field);
                if (Modifier.isStatic(field.getModifiers())) {
                    get = MethodHandles.dropArguments(get, 0, Object.class);
                    if (set != null) set = MethodHandles.dropArguments(set, 0, Object.class);
                }
                getter = get.asType(MethodType.methodType(Object.class, Object.class));
                setter = set == null ? null : set.asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not bind simulation field " + field, exception);
            }
        }

        Object read(Object owner) {
            try { return (Object) getter.invokeExact(owner); }
            catch (RuntimeException | Error exception) { throw exception; }
            catch (Throwable exception) { throw new IllegalStateException("Could not snapshot " + field, exception); }
        }

        void restore(Object owner, Object value) {
            if (setter == null) {
                Object current = read(owner);
                if (field.getType().isPrimitive() ? !Objects.equals(current, value) : current != value) {
                    throw new IllegalStateException("Final simulation reference changed: " + field);
                }
                return;
            }
            try { setter.invokeExact(owner, value); }
            catch (RuntimeException | Error exception) { throw exception; }
            catch (Throwable exception) { throw new IllegalStateException("Could not restore " + field, exception); }
        }
    }

    private final Predicate<Object> external;
    private final Predicate<Field> included;
    private final int maximumObjects;

    public RollbackStateGraph(final Predicate<Object> external, final Predicate<Field> included,
                              final int maximumObjects) {
        this.external = Objects.requireNonNull(external, "external");
        this.included = Objects.requireNonNull(included, "included");
        if (maximumObjects < 1) throw new IllegalArgumentException("maximumObjects");
        this.maximumObjects = maximumObjects;
    }

    public Snapshot capture(final Collection<?> roots, final Collection<Field> staticRoots) {
        final Capture capture = new Capture();
        for (Object root : roots) capture.visit(root);
        for (Field field : staticRoots) {
            if (!Modifier.isStatic(field.getModifiers())) throw new IllegalArgumentException("Expected static root: " + field);
            capture.field(null, field);
        }
        while (!capture.pending.isEmpty()) capture.scan(capture.pending.removeFirst());
        return new Snapshot(capture.fields, capture.values, capture.indices, capture.guards, capture.seen.size());
    }

    /** Returns an explicit service's static roots without enumerating or initializing unrelated classes. */
    public static List<Field> staticFields(final Class<?> type, final Predicate<Field> select) {
        final List<Field> fields = new ArrayList<>();
        for (Field field : LAYOUTS.get(type).sorted) {
            if (Modifier.isStatic(field.getModifiers()) && select.test(field)) fields.add(field);
        }
        return List.copyOf(fields);
    }

    public static final class Snapshot {
        private final List<FieldValue> fields;
        private final List<Restore> values;
        private final List<Restore> indices;
        private final List<Restore> guards;
        private final int objects;
        private final Thread owner = Thread.currentThread();

        private Snapshot(List<FieldValue> fields, List<Restore> values, List<Restore> indices,
                         List<Restore> guards, int objects) {
            this.fields = List.copyOf(fields);
            this.values = List.copyOf(values);
            this.indices = List.copyOf(indices);
            this.guards = List.copyOf(guards);
            this.objects = objects;
        }

        public int objectCount() { return objects; }

        public void restore() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Runtime snapshot crossed threads");
            // Restore keys' mutable coordinates before rebuilding hash/ordering indices.
            guards.forEach(Restore::restore);
            fields.forEach(FieldValue::restore);
            values.forEach(Restore::restore);
            indices.forEach(Restore::restore);
        }
    }

    @FunctionalInterface
    private interface Restore { void restore(); }

    private record FieldValue(Object owner, FieldAccess access, Object value) {
        void restore() { access.restore(owner, value); }
    }

    private final class Capture {
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        final ArrayDeque<Object> pending = new ArrayDeque<>();
        final List<FieldValue> fields = new ArrayList<>();
        final List<Restore> values = new ArrayList<>();
        final List<Restore> indices = new ArrayList<>();
        final List<Restore> guards = new ArrayList<>();

        void visit(final Object value) {
            if (value == null || scalar(value) || external.test(value) || seen.containsKey(value)) return;
            if (seen.size() >= maximumObjects) throw new IllegalStateException("Rollback state exceeds object budget");
            seen.put(value, Boolean.TRUE);
            pending.addLast(value);
        }

        void field(final Object owner, final Field field) {
            if (!included.test(field)) return;
            final FieldAccess access = LAYOUTS.get(field.getDeclaringClass()).accessors.computeIfAbsent(field, FieldAccess::new);
            final Object value = access.read(owner);
            fields.add(new FieldValue(owner, access, value));
            visit(value);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void scan(final Object value) {
            final Class<?> type = value.getClass();
            if (value instanceof RollbackStateCell cell) {
                final Object state = cell.captureRollbackState();
                values.add(() -> cell.restoreRollbackState(state));
                for (Object reference : cell.rollbackReferences()) visit(reference);
                return;
            }
            if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
                // Addon collection subclasses can carry gameplay fields of their own.
                for (Class<?> current = type; current.getClassLoader() != null; current = current.getSuperclass()) {
                    for (Field field : LAYOUTS.get(current).declared) {
                        if (!Modifier.isStatic(field.getModifiers())) field(value, field);
                    }
                }
            }
            if (type.isArray()) {
                final int length = Array.getLength(value);
                final Object copy = Array.newInstance(type.getComponentType(), length);
                System.arraycopy(value, 0, copy, 0, length);
                values.add(() -> System.arraycopy(copy, 0, value, 0, length));
                if (!type.getComponentType().isPrimitive()) for (Object item : (Object[]) copy) visit(item);
            } else if (value instanceof Map map) {
                final List<Object[]> entries = new ArrayList<>();
                map.forEach((key, item) -> {
                    if (key instanceof Collection<?> || key instanceof Map<?, ?>) {
                        throw new IllegalStateException("Collection-valued hash keys require a state adapter: " + type.getName());
                    }
                    entries.add(new Object[]{key, item});
                    visit(key);
                    visit(item);
                });
                if (map instanceof SortedMap sorted) visit(sorted.comparator());
                if (fixedCollection(type)) return;
                requireMutableCollection(type);
                indices.add(() -> {
                    map.clear();
                    for (Object[] entry : entries) map.put(entry[0], entry[1]);
                });
            } else if (value instanceof Collection collection) {
                final Object[] items = collection.toArray();
                for (Object item : items) visit(item);
                if (collection instanceof SortedSet sorted) visit(sorted.comparator());
                if (collection instanceof PriorityQueue queue) visit(queue.comparator());
                if (fixedCollection(type)) return;
                requireMutableCollection(type);
                Restore restore = () -> {
                    if (collection instanceof List list && list.size() == items.length) {
                        for (int i = 0; i < items.length; i++) list.set(i, items[i]);
                    } else {
                        collection.clear();
                        for (Object item : items) collection.add(item);
                    }
                };
                if (collection instanceof Set || collection instanceof PriorityQueue) indices.add(restore);
                else values.add(restore);
            } else if (value instanceof AtomicLong atomic) {
                final long saved = atomic.get();
                values.add(() -> atomic.set(saved));
            } else if (value instanceof AtomicInteger atomic) {
                final int saved = atomic.get();
                values.add(() -> atomic.set(saved));
            } else if (value instanceof AtomicBoolean atomic) {
                final boolean saved = atomic.get();
                values.add(() -> atomic.set(saved));
            } else if (value instanceof AtomicReference atomic) {
                final Object saved = atomic.get();
                visit(saved);
                values.add(() -> atomic.set(saved));
            } else if (value instanceof Date date) {
                final long saved = date.getTime();
                values.add(() -> date.setTime(saved));
            } else if (value instanceof Optional<?> optional) {
                optional.ifPresent(this::visit);
            } else if (value instanceof java.util.AbstractMap.SimpleImmutableEntry<?, ?> entry) {
                // The entry cannot change, but its key/value can own mutable state.
                // Native/addon subclasses may add their own mutable fields too.
                for (Class<?> current = type; current != java.util.AbstractMap.SimpleImmutableEntry.class; current = current.getSuperclass()) {
                    for (Field field : LAYOUTS.get(current).declared) if (!Modifier.isStatic(field.getModifiers())) field(value, field);
                }
                visit(entry.getKey());
                visit(entry.getValue());
            } else if (value instanceof WeakReference<?> reference) {
                final Object saved = reference.get(); // Pin the referent for the bounded lifetime of this snapshot.
                visit(saved);
                guards.add(() -> {
                    if (reference.get() != saved) throw new IllegalStateException("A simulation weak reference was explicitly cleared");
                });
            } else {
                for (Class<?> current = type; current != Object.class && current != Record.class; current = current.getSuperclass()) {
                    if (current.getClassLoader() == null) {
                        throw new IllegalStateException("Mutable platform type requires a state adapter: " + current.getName()
                                + "; referenced by " + fields.stream().filter(saved -> saved.value == value).map(saved -> saved.access.field.toString()).toList());
                    }
                    for (Field field : LAYOUTS.get(current).sorted) if (!Modifier.isStatic(field.getModifiers())) field(value, field);
                }
            }
        }
    }

    private static boolean scalar(final Object value) {
        return value instanceof String || value instanceof Enum<?> || value instanceof Class<?> || value instanceof UUID
                || value instanceof java.util.OptionalInt || value instanceof java.util.OptionalLong || value instanceof java.util.OptionalDouble
                || value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double || value instanceof Character;
    }

    private static boolean fixedCollection(final Class<?> type) {
        final String name = type.getName();
        return name.startsWith("java.util.ImmutableCollections$") || name.startsWith("java.util.Collections$Empty")
                || name.startsWith("java.util.Collections$Singleton")
                || com.google.common.collect.ImmutableMap.class.isAssignableFrom(type)
                || com.google.common.collect.ImmutableCollection.class.isAssignableFrom(type);
    }

    private static void requireMutableCollection(final Class<?> type) {
        final String name = type.getName();
        if (name.contains("$Unmodifiable") || name.contains("$SubList") || name.contains("$KeySetView")
                || name.contains("$Values") || name.contains("$EntrySet")) {
            throw new IllegalStateException("Backed collection view requires a state adapter: " + name);
        }
    }
}
