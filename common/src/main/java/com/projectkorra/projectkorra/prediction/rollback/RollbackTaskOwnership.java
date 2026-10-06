package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Resolves live callback ownership from captured references, without ability-name filters. */
public final class RollbackTaskOwnership implements Predicate<RollbackLiveScheduler.Work> {
    private final Set<UUID> participants;
    private final Set<Object> owned;
    private final Predicate<Object> external;
    private final int maximumObjects;
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            var fields = new ArrayList<Field>();
            for (Class<?> current = type; current != null && current != Object.class && current != Record.class; current = current.getSuperclass()) {
                if (current.getClassLoader() == null) throw new IllegalArgumentException("Opaque callback state requires a boundary: " + current.getName());
                for (var field : current.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    if (!field.trySetAccessible()) throw new IllegalArgumentException("Inaccessible callback reference: " + field);
                    fields.add(field);
                }
            }
            return List.copyOf(fields);
        }
    };
    /**
     * ownedState identifies additional participant-only service roots. external stops at
     * shared loader infrastructure; it must not hide player/ability references. Global
     * service tickers remain live and must independently honor roster ownership gates.
     */
    public RollbackTaskOwnership(Set<UUID> participants, Collection<?> ownedState,
            Predicate<Object> external, int maximumObjects) {
        this.participants = Set.copyOf(participants);
        if (participants.isEmpty() || participants.size() > 128 || maximumObjects < 1) throw new IllegalArgumentException("Task ownership limits");
        owned = Collections.newSetFromMap(new IdentityHashMap<>()); owned.addAll(ownedState);
        if (owned.contains(null)) throw new IllegalArgumentException("Null owned state");
        this.external = Objects.requireNonNull(external); this.maximumObjects = maximumObjects;
    }
    @Override public boolean test(RollbackLiveScheduler.Work work) {
        Objects.requireNonNull(work);
        return ownsReferences(work.ability(), work.callback());
    }
    /** Shared by activation teardown; recomputes ownership from current captured references. */
    public boolean ownsCallback(Object callback) { return ownsReferences(Objects.requireNonNull(callback)); }
    private boolean ownsReferences(Object... roots) {
        var walk = new Walk();
        for (var root : roots) walk.add(root);
        while (!walk.pending.isEmpty()) walk.scan(walk.pending.removeFirst());
        if (walk.member && walk.outsider) throw new IllegalStateException("Callback spans rollback and outside players");
        return walk.member;
    }
    private final class Walk {
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<Object> pending = new ArrayDeque<>();
        boolean member, outsider;
        void add(Object value) {
            if (value == null || !seen.add(value)) return;
            if (seen.size() > maximumObjects) throw new IllegalStateException("Callback ownership exceeds object budget");
            pending.addLast(value);
        }
        void player(UUID id) { if (participants.contains(id)) member = true; else outsider = true; }
        void scan(Object value) {
            if (value instanceof Player player) { player(player.getUniqueId()); return; }
            if (value instanceof OfflineBendingPlayer player) { player(player.getUUID()); return; }
            if (value instanceof CoreAbility ability && ability.getPlayer() != null) { player(ability.getPlayer().getUniqueId()); return; }
            if (owned.contains(value)) { member = true; return; }
            if (value instanceof UUID id) { if (participants.contains(id)) member = true; return; }
            if (value instanceof String || value instanceof Enum<?> || value instanceof Class<?>
                    || value instanceof Boolean || value instanceof Character || value instanceof Byte
                    || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof PKTask
                    || value instanceof World || external.test(value)) return;
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) for (Object item : (Object[]) value) add(item);
            } else if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> { add(key); add(item); });
                if (map instanceof SortedMap<?, ?> sorted) add(sorted.comparator());
                customContainerFields(value);
            } else if (value instanceof Collection<?> collection) {
                collection.forEach(this::add);
                if (collection instanceof SortedSet<?> sorted) add(sorted.comparator());
                if (collection instanceof PriorityQueue<?> queue) add(queue.comparator());
                customContainerFields(value);
            } else if (value instanceof Optional<?> optional) optional.ifPresent(this::add);
            else if (value instanceof java.util.concurrent.atomic.AtomicReference<?> reference) add(reference.get());
            else if (value.getClass() == java.util.concurrent.atomic.AtomicInteger.class
                    || value.getClass() == java.util.concurrent.atomic.AtomicLong.class
                    || value.getClass() == java.util.concurrent.atomic.AtomicBoolean.class) { }
            else for (Field field : FIELDS.get(type)) read(value, field);
        }
        void customContainerFields(Object value) {
            for (Class<?> type = value.getClass(); type.getClassLoader() != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    if (!field.trySetAccessible()) throw new IllegalArgumentException("Inaccessible callback reference: " + field);
                    read(value, field);
                }
            }
        }
        void read(Object value, Field field) {
            try { add(field.get(value)); }
            catch (IllegalAccessException failure) { throw new IllegalArgumentException("Cannot inspect callback ownership", failure); }
        }
    }
}
