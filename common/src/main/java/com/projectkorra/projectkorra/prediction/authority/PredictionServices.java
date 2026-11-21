package com.projectkorra.projectkorra.prediction.authority;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.*;

/**
 * Thread-scoped replacements for legacy prediction hooks. A private simulation must
 * never fall through to a live transport/authority listener. Every hook it uses must
 * be explicitly bound or explicitly disabled. Normal gameplay keeps its installed
 * listeners. Mutable bound service state participates in the domain's state graph.
 */
public final class PredictionServices {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private PredictionServices() { }

    public static boolean active() { return CURRENT.get() != null; }

    /** Private execution cannot replace or clear the process-wide live hooks. */
    public static void requireGlobalMutation() {
        if (active()) throw new IllegalStateException("Cannot change live prediction services from a private scope");
    }

    public static <T> T current(Class<T> type, T live) {
        Objects.requireNonNull(type, "service type");
        Scope scope = CURRENT.get();
        if (scope == null) return live;
        if (!scope.bindings.services.containsKey(type)) {
            throw new IllegalStateException("Missing private prediction service: " + type.getName());
        }
        return type.cast(scope.bindings.services.get(type));
    }

    public static Scope using(Bindings bindings) {
        Scope scope = new Scope(Objects.requireNonNull(bindings, "bindings"), CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static Builder builder() { return new Builder(); }
    public static Bindings empty() { return builder().build(); }

    public static final class Builder {
        private final Map<Class<?>, Object> services = new LinkedHashMap<>();
        private Builder() { }
        public <T> Builder bind(Class<T> type, T service) {
            Objects.requireNonNull(type, "service type");
            services.put(type, type.cast(Objects.requireNonNull(service, "service")));
            return this;
        }
        /** Disable only when the session handles this output/state through another path. */
        public Builder disable(Class<?> type) {
            services.put(Objects.requireNonNull(type, "service type"), null);
            return this;
        }
        public Bindings build() { return new Bindings(services); }
    }

    public static final class Bindings implements RollbackStateCell<Void> {
        private final Map<Class<?>, Object> services;
        private final List<?> references;
        private Bindings(Map<Class<?>, Object> source) {
            services = Collections.unmodifiableMap(new LinkedHashMap<>(source));
            references = services.values().stream().filter(Objects::nonNull).toList();
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
        @Override public List<?> rollbackReferences() { return references; }
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Bindings bindings;
        private final Scope previous;
        private boolean closed;
        private Scope(Bindings bindings, Scope previous) { this.bindings = bindings; this.previous = previous; }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Prediction service scope crossed threads");
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Prediction service scopes closed out of order");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            closed = true;
        }
    }
}
