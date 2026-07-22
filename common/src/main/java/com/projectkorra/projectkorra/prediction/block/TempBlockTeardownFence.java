package com.projectkorra.projectkorra.prediction.block;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Durable fence for physical/client writes which arrive after an ability's
 * locally predicted TempBlock teardown has already completed.
 */
public final class TempBlockTeardownFence<K, S> {
    private final Map<K, Entry<S>> entries = new HashMap<>();

    public void arm(final K key, final Collection<S> staleStates,
                    final S retainedState, final long armedTick) {
        if (key == null || staleStates == null || staleStates.isEmpty() || retainedState == null) return;
        final Set<S> states = new HashSet<>();
        for (S state : staleStates) if (state != null) states.add(state);
        if (states.isEmpty()) return;

        final Entry<S> previous = entries.get(key);
        if (previous != null) states.addAll(previous.staleStates);
        entries.put(key, new Entry<>(Set.copyOf(states), retainedState,
                Math.max(armedTick, previous == null ? Long.MIN_VALUE : previous.armedTick)));
    }

    /**
     * Masks an incoming physical state only when it is one of the exact states
     * produced by the completed client lifecycle. Matching a stale state does
     * not consume the fence: one lifecycle can produce several delayed vanilla
     * packets. The retained state also keeps the fence: accepting the normal
     * underlay packet must not let a later client fluid tick recreate the stale
     * state. Any third state is genuinely different authority and releases the
     * coordinate.
     */
    public Optional<S> maskIncoming(final K key, final S incomingState) {
        final Entry<S> entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (entry.staleStates.contains(incomingState)) return Optional.of(entry.retainedState);
        if (entry.retainedState.equals(incomingState)) return Optional.empty();
        entries.remove(key, entry);
        return Optional.empty();
    }

    /**
     * Repairs an already-installed stale state (for example a full chunk or a
     * client-side fluid update). An observation is not authoritative ordering,
     * so a nonmatching state deliberately does not release the fence.
     */
    public Optional<S> audit(final K key, final S currentState) {
        final Entry<S> entry = entries.get(key);
        return entry != null && entry.staleStates.contains(currentState)
                ? Optional.of(entry.retainedState) : Optional.empty();
    }

    public Optional<S> retainedState(final K key) {
        final Entry<S> entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.retainedState);
    }

    public void release(final K key) {
        entries.remove(key);
    }

    public Set<K> keys() {
        return Set.copyOf(entries.keySet());
    }

    public int size() {
        return entries.size();
    }

    public void expireBefore(final long minimumTick) {
        entries.entrySet().removeIf(entry -> entry.getValue().armedTick < minimumTick);
    }

    public void clear() {
        entries.clear();
    }

    private record Entry<S>(Set<S> staleStates, S retainedState, long armedTick) {
    }
}
