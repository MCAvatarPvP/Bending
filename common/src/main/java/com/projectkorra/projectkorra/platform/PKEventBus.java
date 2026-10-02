package com.projectkorra.projectkorra.platform;

/**
 * Platform event dispatcher. Accepts opaque event objects to keep core free of platform event imports.
 */
public interface PKEventBus {
    void call(Object event);

    void registerListener(Object listener);

    default void registerListener(final Object listener, final Object owner) {
        registerListener(listener);
    }

    void unregisterAll(Object listener);

    /** Common handlers in registration order. Native events require their own adapter. */
    default java.util.List<Registration> commonRegistrations() {
        throw new UnsupportedOperationException("Event registration capture is unavailable");
    }

    /** Portable metadata; listener and owner must be copied in the same gameplay graph. */
    final class Registration {
        private final Object listener, owner;
        private final String method;
        private final int priority;
        private final boolean ignoreCancelled;
        public Registration(Object listener, Object owner, String method, int priority, boolean ignoreCancelled) {
            this.listener = listener; this.owner = owner; this.method = method;
            this.priority = priority; this.ignoreCancelled = ignoreCancelled;
            java.util.Objects.requireNonNull(listener);
            java.util.Objects.requireNonNull(owner);
            java.util.Objects.requireNonNull(method);
            if (method.isBlank() || priority < 0 || priority >= com.projectkorra.projectkorra.platform.mc.event.EventPriority.values().length)
                throw new IllegalArgumentException("Event registration metadata");
        }
        public Object listener() { return listener; }
        public Object owner() { return owner; }
        public String method() { return method; }
        public int priority() { return priority; }
        public boolean ignoreCancelled() { return ignoreCancelled; }
        public static String key(java.lang.reflect.Method method) {
            return method.getDeclaringClass().getName() + "#" + method.getName() + "(" + method.getParameterTypes()[0].getName() + ")";
        }
    }
}
