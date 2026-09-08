package com.projectkorra.projectkorra.fabric.client.prediction.action;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Binds an authoritative creation to one local instance across input transitions. */
public final class ClientAbilityIdentity<T> {
    private final Map<Long, T> instances = new HashMap<>();

    public T find(final long serverCreation, final long localCreation,
                  final Collection<T> live, final ToLongFunction<T> creationOf) {
        if (serverCreation <= 0L) return null;
        final T established = instances.get(serverCreation);
        for (T candidate : live) {
            if (candidate == established) return candidate;
        }
        if (localCreation > 0L) {
            for (T candidate : live) {
                if (creationOf.applyAsLong(candidate) == localCreation) return candidate;
            }
        }
        return null;
    }

    public void bind(final long serverCreation, final T instance) {
        if (serverCreation > 0L && instance != null) instances.put(serverCreation, instance);
    }

    public void retain(final Collection<?> live) {
        instances.values().removeIf(instance -> live.stream().noneMatch(candidate -> candidate == instance));
    }

    public void clear() {
        instances.clear();
    }
}
