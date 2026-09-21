package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Keeps a server display available when its locally simulated counterpart ends. */
public final class ClientDisplayFallbacks<T> {
    private final Map<Integer, T> pairs = new HashMap<>();
    private final Predicate<T> alive;

    public ClientDisplayFallbacks(final Predicate<T> alive) {
        this.alive = alive;
    }

    public void pair(final int serverId, final T predicted) {
        pairs.put(serverId, predicted);
    }

    public boolean isPaired(final T predicted) {
        return pairs.containsValue(predicted);
    }

    public boolean hide(final int serverId) {
        final T predicted = pairs.get(serverId);
        return predicted != null && alive.test(predicted);
    }

    public void remove(final int serverId) {
        pairs.remove(serverId);
    }

    public void clear() {
        pairs.clear();
    }
}
