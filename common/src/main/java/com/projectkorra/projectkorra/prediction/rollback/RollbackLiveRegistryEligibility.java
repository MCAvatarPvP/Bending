package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.util.*;
import java.lang.reflect.Field;
import java.util.*;

/** Read-only compatibility check for live data still consumed by a global manager tick. */
public final class RollbackLiveRegistryEligibility {
    private RollbackLiveRegistryEligibility() { }

    private static final List<Field> REGISTRIES = List.of(
            registry(RevertChecker.class, "airRevertQueue"));

    private static Field registry(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()
                    || !Map.class.isAssignableFrom(field.getType()) && !Collection.class.isAssignableFrom(field.getType()))
                throw new IllegalStateException("Unrecognized live ability registry " + field);
            return field;
        } catch (NoSuchFieldException failure) { throw new IllegalStateException("Missing live ability registry " + type.getName() + "." + name, failure); }
    }

    /** Checks emptiness only. Reading an outsider's actor graph or altering entries is unnecessary. */
    public static Optional<String> activeRegistry() {
        for (var field : REGISTRIES) {
            try {
                var state = field.get(null);
                boolean occupied = state instanceof Map<?, ?> map ? !map.isEmpty() : !((Collection<?>)state).isEmpty();
                if (occupied) return Optional.of(field.getDeclaringClass().getSimpleName() + "." + field.getName());
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
        return Optional.empty();
    }

    public static void requireAvailable() {
        activeRegistry().ifPresent(name -> { throw new IllegalStateException("Live bending state requires normal server simulation: " + name); });
    }
}
