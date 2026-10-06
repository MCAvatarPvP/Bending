package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Map;

/** Fixed bootstrap access for native import fields without public accessors. No field discovery during capture/replay. */
final class PaperRollbackPlayerFields {
    private PaperRollbackPlayerFields() { }

    static final class Field<T> {
        private final MethodHandle read, write;
        Field(Class<?> owner, String name, Class<T> type) {
            try {
                var field = owner.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) {
                    throw new IllegalStateException("Native import field changed: " + field);
                }
                var lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                read = lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
                write = Modifier.isFinal(field.getModifiers()) ? null
                        : lookup.unreflectSetter(field).asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native import field changed: " + owner.getName() + "." + name, failure);
            }
        }
        @SuppressWarnings("unchecked") T get(Object instance) {
            try { return (T) read.invokeExact(instance); }
            catch (Throwable failure) { throw failed(failure); }
        }
        void set(Object instance, T value) {
            if (write == null) throw new IllegalStateException("Cannot replace final native import field");
            try { write.invokeExact(instance, (Object) value); }
            catch (Throwable failure) { throw failed(failure); }
        }
    }

    /** Audited scalar/value fields only. World/entity/service references cannot enter a seed through this path. */
    static final class Values {
        private final Field<Object>[] fields;
        private final String[] names;
        private final Class<?>[] types;
        @SuppressWarnings({"unchecked", "rawtypes"}) Values(Class<?> owner, String... names) {
            this.names = names.clone();
            types = new Class<?>[names.length];
            fields = new Field[names.length];
            for (int i = 0; i < names.length; i++) {
                try {
                    var type = owner.getDeclaredField(names[i]).getType();
                    if (!type.isPrimitive() && !type.isEnum() && type != Vec3.class && type != AABB.class
                            && type != BlockPos.class && type != ChunkPos.class && type != EntityDimensions.class && type != Optional.class) {
                        throw new IllegalArgumentException("Non-value native field: " + owner.getName() + "." + names[i]);
                    }
                    fields[i] = new Field(owner, names[i], type);
                    types[i] = type;
                } catch (NoSuchFieldException failure) { throw new IllegalStateException("Native player import schema changed", failure); }
            }
        }
        Object[] capture(Object source) {
            var values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) values[i] = detached(fields[i].get(source));
            return values;
        }
        void apply(Object target, Object[] values) {
            if (values.length != fields.length) throw new IllegalArgumentException("Native import schema mismatch");
            for (int i = 0; i < fields.length; i++) fields[i].set(target, values[i]);
        }
        void export(String group, Object[] values, Map<String, RollbackPlayerValues.Cell> target) {
            if (values.length != fields.length) throw new IllegalArgumentException("Native import schema mismatch");
            for (int i = 0; i < fields.length; i++) target.put(group + "." + names[i], PaperRollbackNativeValues.encode(types[i], values[i]));
        }
        Object[] prepare(String group, Map<String, RollbackPlayerValues.Cell> source) {
            var result = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                String key = group + "." + names[i];
                result[i] = PaperRollbackNativeValues.decode(types[i], RollbackPlayerValues.requireField(key, source.remove(key)));
            }
            return result;
        }
        private static Object detached(Object value) {
            if (value instanceof BlockPos position) return position.immutable();
            if (value instanceof Optional<?> optional) {
                if (optional.isPresent() && !(optional.get() instanceof BlockPos)) throw new IllegalArgumentException("Unsupported optional native import value");
                return optional.map(Values::detached);
            }
            return value;
        }
    }

    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Native player import access failed", failure);
    }
}
