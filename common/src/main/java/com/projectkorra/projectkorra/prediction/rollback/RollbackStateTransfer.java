package com.projectkorra.projectkorra.prediction.rollback;

import org.objenesis.ObjenesisStd;
import org.objenesis.instantiator.ObjectInstantiator;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Imports an in-process gameplay graph into an independently owned simulation.
 * Unlike a checkpoint, this creates new objects, including final child references.
 * Ability constructors, setters, activation and load hooks are never invoked.
 *
 * <p>The caller must run at a quiescent tick boundary and explicitly replace every
 * platform/service handle. Only caller-approved application classes are traversed;
 * this is neither a native object copier nor a network deserializer. Class layouts
 * and access handles are bound once. Unsupported containers, records, hidden callback
 * classes and opaque state cells require an explicit replacement, never a shallow copy.
 * Static registries are not installed by this operation.</p>
 *
 * <p>All roots are copied together, preserving cycles and aliases across players,
 * abilities and services. A failed import exposes no partial result and never writes
 * the source. Time values are preserved exactly: the destination must use the same
 * clock epoch, or explicitly adapt time-bearing services before starting replay.</p>
 */
public final class RollbackStateTransfer {
    /** A direct replacement may be null; a projection is itself copied into the destination graph. */
    public record Replacement(Object value, boolean projection) {
        public Replacement(Object value) { this(value, false); }
        public Replacement { if (projection) Objects.requireNonNull(value, "import projection"); }
        public static Replacement fromProjection(Object value) { return new Replacement(value, true); }
    }

    public record Limits(int maximumObjects, int maximumReferences) {
        public Limits {
            if (maximumObjects < 1 || maximumReferences < 1) throw new IllegalArgumentException("Import limits");
        }
    }

    static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> type) { return new Layout(type); }
    };
    private static final ClassValue<Boolean> IDENTITY_KEYS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("hashCode").getDeclaringClass() == Object.class
                        && type.getMethod("equals", Object.class).getDeclaringClass() == Object.class;
            } catch (NoSuchMethodException exception) { throw new IllegalStateException("Invalid import key type", exception); }
        }
    };

    static final class Access {
        final Field field;
        final MethodHandle get, set;
        Access(Field field) {
            this.field = field;
            try {
                if (!field.trySetAccessible()) throw new IllegalStateException("Inaccessible import field: " + field);
                var lookup = MethodHandles.lookup();
                get = lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
                // Accessible ordinary instance finals can be initialized on an unpublished copy.
                // Record/hidden-class finals cannot; their layouts are rejected before allocation.
                set = lookup.unreflectSetter(field).asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot bind import field: " + field, exception);
            }
        }
        Object read(Object owner) {
            try { return (Object) get.invokeExact(owner); }
            catch (Throwable exception) { throw failure("read", exception); }
        }
        void write(Object owner, Object value) {
            try { set.invokeExact(owner, value); }
            catch (Throwable exception) { throw failure("initialize", exception); }
        }
        IllegalStateException failure(String action, Throwable cause) {
            return new IllegalStateException("Cannot " + action + " imported field: " + field, cause);
        }
    }

    static final class Layout {
        final List<Access> fields;
        final ObjectInstantiator<?> allocator;
        Layout(Class<?> type) {
            var fields = new ArrayList<Access>();
            for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
                if (current == null || current.getClassLoader() == null || current.isRecord() || current.isHidden()) {
                    throw unsupported(type);
                }
                Arrays.stream(current.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                        .sorted(Comparator.comparing(Field::getName)).map(Access::new).forEach(fields::add);
            }
            this.fields = List.copyOf(fields);
            allocator = new ObjenesisStd(false).getInstantiatorOf(type);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final Predicate<Class<?>> gameplayType;
    private final Function<Object, Replacement> replacements;
    private final Limits limits;

    public RollbackStateTransfer(Predicate<Class<?>> gameplayType, Function<Object, Replacement> replacements, Limits limits) {
        this.gameplayType = Objects.requireNonNull(gameplayType, "gameplay types");
        this.replacements = Objects.requireNonNull(replacements, "platform replacements");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Result retains only copied roots; the temporary source-to-copy table is discarded. */
    public List<Object> copy(Collection<?> roots) {
        return copy(roots, ignored -> null);
    }

    /** Service projections take precedence over loader replacements for this import only. */
    public List<Object> copy(Collection<?> roots, Function<Object, Replacement> services) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("State import crossed threads");
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("State import during replay");
        var copy = new Copy(Objects.requireNonNull(services, "service projections"));
        var imported = new ArrayList<Node>();
        for (Object root : roots) imported.add(copy.reference(root));
        while (!copy.pending.isEmpty()) copy.scan(copy.pending.removeFirst());
        finish(copy.ordered, limits);
        var result = new ArrayList<Object>(imported.size());
        for (Node node : imported) result.add(node.target);
        return Collections.unmodifiableList(result);
    }

    static final class Node {
        final Object source;
        Object target;
        boolean ready, populated, indexed;
        List<Node> children = List.of();
        List<Node> construction;
        List<Node> keys = List.of();
        Supplier<Object> create;
        Runnable fill;
        Node(Object source) { this.source = source; }
        void ready(Object target) { this.target = target; ready = true; }
    }

    private final class Copy {
        final Function<Object, Replacement> services;
        final IdentityHashMap<Object, Node> nodes = new IdentityHashMap<>();
        final ArrayDeque<Node> pending = new ArrayDeque<>();
        final List<Node> ordered = new ArrayList<>();
        int references;

        Copy(Function<Object, Replacement> services) { this.services = services; }

        Node reference(Object source) {
            if (++references > limits.maximumReferences()) throw new IllegalStateException("State import exceeds reference budget");
            Node previous = nodes.get(source);
            if (previous != null) return previous;
            if (nodes.size() >= limits.maximumObjects()) throw new IllegalStateException("State import exceeds object budget");
            Replacement replacement = source == null ? null : services.apply(source);
            if (replacement == null && source != null) replacement = replacements.apply(source);
            Object view = replacement != null && replacement.projection() ? replacement.value() : source;
            Node projected = nodes.get(view);
            if (projected != null) { nodes.put(source, projected); return projected; }
            if (view != source && nodes.size() + 1 >= limits.maximumObjects()) throw new IllegalStateException("State import exceeds object budget");
            Node node = new Node(view);
            nodes.put(source, node);
            nodes.put(view, node);
            if ((replacement != null && !replacement.projection()) || view == null || scalar(view) || fixedEmpty(view)) {
                node.ready(replacement != null && !replacement.projection() ? replacement.value() : view);
                node.populated = true;
            } else {
                ordered.add(node);
                pending.add(node);
            }
            return node;
        }

        List<Node> references(Object[] values) {
            var result = new ArrayList<Node>(values.length);
            for (Object value : values) result.add(reference(value));
            return result;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void scan(Node node) {
            Object source = node.source;
            Class<?> type = source.getClass();
            if (source instanceof RollbackStateCell<?>) throw unsupported(type);
            if (type.isArray()) {
                int length = Array.getLength(source);
                if ((long) references + length > limits.maximumReferences()) throw new IllegalStateException("State import array exceeds reference budget");
                node.ready(Array.newInstance(type.getComponentType(), length));
                if (type.getComponentType().isPrimitive()) {
                    references += length;
                    System.arraycopy(source, 0, node.target, 0, length);
                    node.populated = true;
                } else {
                    node.children = references((Object[]) source);
                    node.fill = () -> { for (int i = 0; i < length; i++) Array.set(node.target, i, node.children.get(i).target); };
                }
            } else if (source instanceof Map<?, ?> map) {
                map(node, map);
            } else if (source instanceof Collection<?> collection) {
                collection(node, collection);
            } else if (type == Optional.class || type == WeakReference.class || type == AtomicReference.class
                    || type == AbstractMap.SimpleImmutableEntry.class || type == AbstractMap.SimpleEntry.class) {
                if (source instanceof Map.Entry entry) node.children = references(new Object[]{entry.getKey(), entry.getValue()});
                else node.children = Collections.singletonList(reference(source instanceof Optional optional ? optional.orElse(null)
                        : source instanceof WeakReference weak ? weak.get() : ((AtomicReference) source).get()));
                if (type == AtomicReference.class) {
                    node.ready(new AtomicReference<>());
                    node.fill = () -> ((AtomicReference) node.target).set(node.children.getFirst().target);
                } else {
                    node.create = () -> {
                        Object first = node.children.getFirst().target;
                        if (type == Optional.class) return Optional.ofNullable(first);
                        if (type == WeakReference.class) return new WeakReference<>(first);
                        Object second = node.children.get(1).target;
                        return type == AbstractMap.SimpleEntry.class ? new AbstractMap.SimpleEntry<>(first, second)
                                : new AbstractMap.SimpleImmutableEntry<>(first, second);
                    };
                }
            } else if (type == AtomicInteger.class || type == AtomicLong.class || type == AtomicBoolean.class || type == Date.class) {
                node.ready(source instanceof AtomicInteger value ? new AtomicInteger(value.get())
                        : source instanceof AtomicLong value ? new AtomicLong(value.get())
                        : source instanceof AtomicBoolean value ? new AtomicBoolean(value.get()) : new Date(((Date) source).getTime()));
                node.populated = true;
            } else {
                if (!gameplayType.test(type)) throw unsupported(type);
                Layout layout = LAYOUTS.get(type);
                node.ready(layout.allocator.newInstance());
                var values = new ArrayList<Node>(layout.fields.size());
                for (Access field : layout.fields) values.add(reference(field.read(source)));
                node.children = values;
                node.fill = () -> {
                    for (int i = 0; i < layout.fields.size(); i++) layout.fields.get(i).write(node.target, values.get(i).target);
                };
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void map(Node node, Map<?, ?> source) {
            Class<?> type = source.getClass();
            if ((long) references + 2L * source.size() > limits.maximumReferences()) throw new IllegalStateException("State import map exceeds reference budget");
            var entries = new ArrayList<Node>(source.size() * 2);
            source.forEach((key, value) -> {
                requireKey(key);
                entries.add(reference(key));
                entries.add(reference(value));
            });
            node.children = entries;
            node.indexed = true;
            var keys = new ArrayList<Node>();
            for (int i = 0; i < entries.size(); i += 2) {
                Node key = entries.get(i);
                if (source instanceof SortedMap<?, ?> || (type != IdentityHashMap.class && statefulKey(key))) keys.add(key);
            }
            node.keys = keys;
            if (type == HashMap.class || type == LinkedHashMap.class) node.ready(((HashMap) source).clone());
            else if (type == IdentityHashMap.class) node.ready(((IdentityHashMap) source).clone());
            else if (type == EnumMap.class) node.ready(((EnumMap) source).clone());
            else if (type == ConcurrentHashMap.class) node.ready(new ConcurrentHashMap<>());
            else if (type == WeakHashMap.class) node.ready(new WeakHashMap<>());
            else if (type == TreeMap.class) {
                Node comparator = reference(((TreeMap) source).comparator());
                entries.add(comparator);
                keys.add(comparator);
                node.construction = List.of(comparator);
                node.create = () -> new TreeMap((Comparator) comparator.target);
            } else throw unsupported(type);
            // clone preserves enum type, load factor and LinkedHashMap access-order semantics;
            // discard its shallow entries before any copied root can observe it.
            if (node.ready) ((Map) node.target).clear();
            int size = source.size();
            node.fill = () -> {
                Map target = (Map) node.target;
                for (int i = 0; i < size; i++) target.put(entries.get(2 * i).target, entries.get(2 * i + 1).target);
            };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void collection(Node node, Collection<?> source) {
            Class<?> type = source.getClass();
            if ((long) references + source.size() > limits.maximumReferences()) throw new IllegalStateException("State import collection exceeds reference budget");
            node.children = references(source.toArray());
            if (source instanceof Set<?> || source instanceof PriorityQueue<?>) {
                source.forEach(RollbackStateTransfer::requireKey);
                node.indexed = true;
                node.keys = source instanceof SortedSet<?> || source instanceof PriorityQueue<?> ? node.children
                        : node.children.stream().filter(RollbackStateTransfer::statefulKey).toList();
            }
            if (type == ArrayList.class) node.ready(new ArrayList<>());
            else if (type == LinkedList.class) node.ready(new LinkedList<>());
            else if (type == ArrayDeque.class) node.ready(new ArrayDeque<>());
            else if (type == CopyOnWriteArrayList.class) node.ready(new CopyOnWriteArrayList<>());
            else if (type == CopyOnWriteArraySet.class) node.ready(new CopyOnWriteArraySet<>());
            else if (type == HashSet.class || type == LinkedHashSet.class) {
                Set target = (Set) ((HashSet) source).clone();
                target.clear();
                node.ready(target);
            } else if (source instanceof EnumSet<?> enums) {
                var target = enums.clone();
                target.clear();
                node.ready(target);
            } else if (type == TreeSet.class || type == PriorityQueue.class) {
                Node comparator = reference(source instanceof TreeSet<?> set ? set.comparator() : ((PriorityQueue<?>) source).comparator());
                var children = new ArrayList<>(node.children);
                children.add(comparator);
                node.children = children;
                node.keys = children;
                node.construction = List.of(comparator);
                node.create = () -> type == TreeSet.class ? new TreeSet((Comparator) comparator.target)
                        : new PriorityQueue(Math.max(1, source.size()), (Comparator) comparator.target);
            } else if (fixedList(type)) {
                node.create = () -> {
                    Object[] values = node.children.stream().map(child -> child.target).toArray();
                    if (type.getName().startsWith("java.util.Collections$Singleton")) return Collections.singletonList(values[0]);
                    if (type.getName().startsWith("java.util.Collections$Empty")) return Collections.emptyList();
                    return List.of(values);
                };
                return;
            } else throw unsupported(type);
            int size = source.size();
            node.fill = () -> {
                Collection target = (Collection) node.target;
                for (int i = 0; i < size; i++) target.add(node.children.get(i).target);
            };
        }

    }

    static void finish(List<Node> ordered, Limits limits) {
        // Mutable shells exist before references are installed, so ordinary cycles
        // are legal. Deferred wrappers need targets, not completed child graphs.
        boolean changed;
        long resolutionWork = 0;
        do {
            changed = false;
            // Children are usually discovered after parents; reverse traversal
            // resolves long wrapper chains without quadratic rescans.
            for (int index = ordered.size() - 1; index >= 0; index--) {
                Node node = ordered.get(index);
                resolutionWork += 1L + node.children.size();
                if (resolutionWork > 4L * (limits.maximumReferences() + (long) limits.maximumObjects())) {
                    throw new IllegalStateException("State import resolution budget exceeded");
                }
                if (node.populated) continue;
                List<Node> construction = node.construction == null ? node.children : node.construction;
                if (!node.ready && node.create != null && construction.stream().allMatch(child -> child.ready)) {
                    node.ready(node.create.get());
                    changed = true;
                }
                if (node.ready && !node.indexed && node.children.stream().allMatch(child -> child.ready)) {
                    if (node.fill != null) node.fill.run();
                    node.populated = true;
                    changed = true;
                }
            }
        } while (changed);
        for (Node node : ordered) {
            if (!node.ready || !node.children.stream().allMatch(child -> child.ready) || (!node.indexed && !node.populated)) {
                throw new IllegalStateException("Cyclic immutable import requires an adapter: " + node.source.getClass().getName());
            }
        }
        // Keys' fields, array contents and ordered child collections are now initialized.
        // A key can itself own an indexed container. Build that dependency first;
        // a circular hash/comparator dependency cannot be reconstructed safely.
        var dependencies = new LinkedHashMap<Node, Set<Node>>();
        long work = 0;
        for (Node node : ordered) if (node.indexed) {
            Set<Node> required = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var queue = new ArrayDeque<>(node.keys);
            while (!queue.isEmpty()) {
                if (++work > limits.maximumReferences()) throw new IllegalStateException("State import index dependency budget exceeded");
                Node key = queue.removeFirst();
                if (!seen.add(key)) continue;
                if (key.indexed) required.add(key);
                else queue.addAll(key.children);
            }
            dependencies.put(node, required);
        }
        do {
            changed = false;
            for (var iterator = dependencies.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                if (entry.getValue().stream().allMatch(dependency -> dependency.populated)) {
                    Node node = entry.getKey();
                    node.fill.run();
                    node.populated = true;
                    iterator.remove();
                    changed = true;
                }
            }
        } while (changed);
        if (!dependencies.isEmpty()) throw new IllegalStateException("Cyclic hash/comparator import requires an adapter");
    }

    private static boolean fixedList(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.util.ImmutableCollections$List")
                || name.equals("java.util.Collections$SingletonList") || name.equals("java.util.Collections$EmptyList");
    }

    private static void requireKey(Object key) {
        if (key instanceof Collection<?> || key instanceof Map<?, ?>) {
            throw new IllegalStateException("Collection-valued import key requires an adapter");
        }
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Enum<?> || value instanceof Class<?> || value instanceof UUID
                || value instanceof OptionalInt || value instanceof OptionalLong || value instanceof OptionalDouble
                || value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double || value instanceof Character;
    }

    private static boolean fixedEmpty(Object value) {
        String type = value.getClass().getName();
        return (type.startsWith("java.util.ImmutableCollections$") || type.startsWith("java.util.Collections$Empty"))
                && (value instanceof Map<?, ?> map && map.isEmpty() || value instanceof Collection<?> collection && collection.isEmpty());
    }

    static boolean identityKey(Class<?> type) { return type == null || IDENTITY_KEYS.get(type); }

    private static boolean statefulKey(Node key) {
        return key.source != null && !identityKey(key.source.getClass());
    }

    private static IllegalStateException unsupported(Class<?> type) {
        return new IllegalStateException("State import requires an explicit adapter for " + type.getName());
    }
}
