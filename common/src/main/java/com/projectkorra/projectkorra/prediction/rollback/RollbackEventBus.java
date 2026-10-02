package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/** Private common-event dispatch. Registrations and listener state rewind with combat. */
public final class RollbackEventBus implements PKEventBus, RollbackStateCell<RollbackEventBus.State> {
    private record Definition(String key, Class<?> event, int priority, boolean ignoreCancelled, MethodHandle target) {
        void invoke(Object listener, Event value) {
            try { target.invokeExact(listener, (Object) value); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Rollback event handler failed: " + key, failure); }
        }
    }

    // Discovery is once per listener class; no reflection or native server event bus during dispatch.
    private static final ClassValue<List<Definition>> DEFINITIONS = new ClassValue<>() {
        @Override protected List<Definition> computeValue(Class<?> type) {
            var definitions = new ArrayList<Definition>();
            for (Method method : type.getMethods()) {
                EventHandler annotation = method.getAnnotation(EventHandler.class);
                if (annotation == null || method.isBridge() || method.isSynthetic()) continue;
                if (method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])
                        || method.getReturnType() != void.class) {
                    throw new IllegalArgumentException("Rollback requires a void common-event handler: " + method);
                }
                try {
                    MethodHandle target = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup()).unreflect(method);
                    if (Modifier.isStatic(method.getModifiers())) target = MethodHandles.dropArguments(target, 0, Object.class);
                    target = target.asType(MethodType.methodType(void.class, Object.class, Object.class));
                    String key = method.getDeclaringClass().getName() + "#" + method.getName() + "(" + method.getParameterTypes()[0].getName() + ")";
                    definitions.add(new Definition(key, method.getParameterTypes()[0], annotation.priority().ordinal(), annotation.ignoreCancelled(), target));
                } catch (IllegalAccessException failure) {
                    throw new IllegalArgumentException("Cannot bind rollback event handler: " + method, failure);
                }
            }
            // Class.getMethods() order is unspecified and can differ between the two loaders.
            definitions.sort(Comparator.comparing(Definition::key));
            return List.copyOf(definitions);
        }
    };

    private record Handler(Object listener, Object owner, Definition definition, long sequence) { }
    private static final Comparator<Handler> ORDER = Comparator.comparingInt((Handler value) -> value.definition.priority)
            .thenComparingLong(Handler::sequence);

    public static final class State {
        private final RollbackEventBus owner;
        private final List<Handler> handlers;
        private final long sequence;
        private State(RollbackEventBus owner) {
            this.owner = owner; handlers = owner.handlers; sequence = owner.sequence;
        }
    }

    private final Thread thread = Thread.currentThread();
    private final int maximumHandlers;
    private final int maximumDepth;
    private List<Handler> handlers = List.of();
    private long sequence;
    private int depth;

    public RollbackEventBus(int maximumHandlers, int maximumDepth) {
        if (maximumHandlers < 1 || maximumHandlers > 65_536 || maximumDepth < 1 || maximumDepth > 256) {
            throw new IllegalArgumentException("Rollback event budgets");
        }
        this.maximumHandlers = maximumHandlers; this.maximumDepth = maximumDepth;
    }

    @Override public void call(Object value) {
        checkThread();
        if (!(value instanceof Event event)) throw new IllegalArgumentException("Rollback requires a common event");
        if (depth >= maximumDepth) throw new IllegalStateException("Rollback event recursion budget exceeded");
        depth++;
        try {
            // A callback can register/unregister listeners. The current dispatch retains its original order.
            List<Handler> dispatch = handlers;
            for (Handler handler : dispatch) {
                Definition definition = handler.definition;
                if (definition.event.isInstance(event)
                        && !(definition.ignoreCancelled && event instanceof Cancellable cancelled && cancelled.isCancelled())) {
                    definition.invoke(handler.listener, event);
                }
            }
        } finally { depth--; }
    }

    @Override public void registerListener(Object listener) { registerListener(listener, listener); }
    @Override public void registerListener(Object listener, Object owner) {
        checkThread(); Objects.requireNonNull(listener); Objects.requireNonNull(owner);
        List<Definition> definitions = DEFINITIONS.get(listener.getClass());
        if (definitions.isEmpty()) return;
        if (definitions.size() > maximumHandlers - handlers.size()) throw new IllegalStateException("Rollback event handler budget exceeded");
        long next = Math.addExact(sequence, definitions.size());
        var registered = new ArrayList<>(handlers);
        long id = sequence;
        for (Definition definition : definitions) registered.add(new Handler(listener, owner, definition, id++));
        registered.sort(ORDER);
        handlers = List.copyOf(registered); sequence = next;
    }
    @Override public List<Registration> commonRegistrations() {
        checkBoundary();
        return handlers.stream().sorted(Comparator.comparingLong(Handler::sequence))
                .map(handler -> new Registration(handler.listener, handler.owner, handler.definition.key,
                        handler.definition.priority, handler.definition.ignoreCancelled)).toList();
    }

    /** Import copied listeners atomically, retaining source ordering even within one priority. */
    public void importRegistrations(List<Registration> registrations) {
        checkBoundary();
        if (!handlers.isEmpty() || sequence != 0) throw new IllegalStateException("Import requires a fresh event bus");
        if (registrations.size() > maximumHandlers) throw new IllegalStateException("Rollback event handler budget exceeded");
        var imported = new ArrayList<Handler>();
        for (Registration registration : registrations) {
            Definition definition = DEFINITIONS.get(registration.listener().getClass()).stream()
                    .filter(value -> value.key.equals(registration.method())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing common event handler: " + registration.method()));
            if (definition.priority != registration.priority() || definition.ignoreCancelled != registration.ignoreCancelled())
                throw new IllegalArgumentException("Event handler definition differs: " + registration.method());
            imported.add(new Handler(registration.listener(), registration.owner(), definition, imported.size()));
        }
        imported.sort(ORDER);
        handlers = List.copyOf(imported); sequence = imported.size();
    }

    @Override public void unregisterAll(Object target) {
        checkThread();
        handlers = handlers.stream().filter(handler -> handler.listener != target && handler.owner != target).toList();
    }

    @Override public State captureRollbackState() { checkBoundary(); return new State(this); }
    @Override public void restoreRollbackState(State state) {
        checkBoundary();
        if (state.owner != this) throw new IllegalArgumentException("Event checkpoint belongs to another runtime");
        handlers = state.handlers; sequence = state.sequence;
    }
    @Override public Collection<?> rollbackReferences() {
        checkThread();
        var references = new ArrayList<Object>();
        Set<Object> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Handler handler : handlers) {
            if (unique.add(handler.listener)) references.add(handler.listener);
        }
        // Owner is only an identity used by unregisterAll, often ProjectKorra.plugin.
        // Do not import the live plugin lifecycle through this tag. A listener's actual
        // mutable dependencies are reached through the listener graph itself.
        return List.copyOf(references);
    }
    private void checkBoundary() {
        checkThread();
        if (depth != 0) throw new IllegalStateException("Cannot checkpoint during event dispatch");
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Rollback event bus crossed threads");
    }
}
