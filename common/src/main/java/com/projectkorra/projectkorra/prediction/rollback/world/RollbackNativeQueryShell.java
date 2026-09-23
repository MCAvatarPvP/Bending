package com.projectkorra.projectkorra.prediction.rollback.world;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Shell for audited private native queries and explicitly bound native steps.
 * Unconfigured virtual calls fail; final methods, fields and static calls still
 * require an explicit platform audit. Never attach this object to a live world/server.
 * No native constructors, live world references or instrumentation agents are used.
 */
public final class RollbackNativeQueryShell<T> {
    private static final String HANDLER = "rollback$queryHandler";
    private record Layout(Class<?> type, MethodHandle handler, Map<Method, Method> methods) { }
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> base) {
            try {
                Class<?> generated = new ByteBuddy()
                        // Remapped native classes can retain synthetic covariant
                        // methods after losing ACC_BRIDGE. Match JVM descriptors so
                        // those calls cannot silently bypass the query boundary.
                        .with(MethodGraph.Compiler.Default.forJVMHierarchy())
                        .ignore(none())
                        .subclass(base, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                        .defineField(HANDLER, InvocationHandler.class, Visibility.PUBLIC)
                        .method(isVirtual().and(not(isFinal())).and(not(isDeclaredBy(Object.class))))
                        .intercept(InvocationHandlerAdapter.toField(HANDLER))
                        .make().load(base.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded();
                MethodHandle handler = MethodHandles.publicLookup().findSetter(generated, HANDLER, InvocationHandler.class)
                        .asType(MethodType.methodType(void.class, Object.class, InvocationHandler.class));
                return new Layout(generated, handler, new ConcurrentHashMap<>());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot construct native query shell for " + base, failure);
            }
        }
    };

    private final Thread thread = Thread.currentThread();
    private final T instance;
    private final Layout layout;
    private final Map<Method, Function<Object[], ?>> queries = new HashMap<>();
    private boolean configuring;
    private boolean sealed;
    private Method probed;
    private Object probeValue;
    private boolean constantProbe;
    private boolean voidProbe;

    private RollbackNativeQueryShell(Class<T> type) {
        layout = LAYOUTS.get(type);
        instance = type.cast(new ObjenesisStd(false).newInstance(layout.type));
        try { layout.handler.invokeExact((Object) instance, (InvocationHandler) this::invoke); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Native query shell handler", failure); }
    }

    public static <T> RollbackNativeQueryShell<T> create(Class<T> type) {
        return new RollbackNativeQueryShell<>(Objects.requireNonNull(type, "type"));
    }

    /** A compiled method reference also works after Fabric remaps the native method. */
    public RollbackNativeQueryShell<T> constant(Function<T, ?> getter, Object value) {
        return configure(getter, value, arguments -> value, true, false, false);
    }

    /**
     * Binds one compiled native call to a query over private logical state. The probe
     * only identifies the remapped method; its arguments are not retained. The query
     * receives the actual invocation arguments and must not publish live side effects.
     * Unbound calls still fail, including calls inherited from native world interfaces.
     */
    public RollbackNativeQueryShell<T> query(Function<T, ?> probe, Object probeResult, Function<Object[], ?> query) {
        return configure(probe, probeResult, Objects.requireNonNull(query, "query"), false, false, false);
    }

    /**
     * Runs one explicitly audited native read body against this private receiver.
     * Its virtual dependencies still pass through the boundary. Direct fields and
     * static calls in that body must be audited by the platform adapter. The guard
     * runs before native iteration and must reject work outside the captured bounds.
     */
    public RollbackNativeQueryShell<T> nativeQuery(Function<T, ?> probe, Object probeResult, Consumer<Object[]> guard) {
        Objects.requireNonNull(guard, "guard");
        return configure(probe, probeResult, arguments -> { guard.accept(arguments); return null; }, false, false, true);
    }

    /**
     * Runs an audited void native step over exclusively owned simulation objects.
     * Validate all targets before entry. Native writes/events must resolve private
     * state or provisional outputs; this must never authorize a live-world action.
     */
    public RollbackNativeQueryShell<T> nativeAction(Consumer<T> probe, Consumer<Object[]> guard) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(guard, "guard");
        return configure(value -> { probe.accept(value); return null; }, null,
                arguments -> { guard.accept(arguments); return null; }, false, true, true);
    }

    /** Native read APIs which fill a caller-owned result collection instead of returning it. */
    public RollbackNativeQueryShell<T> outputQuery(Consumer<T> probe, Consumer<Object[]> query) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(query, "query");
        return configure(value -> { probe.accept(value); return null; }, null,
                arguments -> { query.accept(arguments); return null; }, false, true, false);
    }

    private RollbackNativeQueryShell<T> configure(Function<T, ?> getter, Object value, Function<Object[], ?> query, boolean constant, boolean allowVoid, boolean nativeBody) {
        checkThread();
        if (sealed || configuring) throw new IllegalStateException("Native query shell configuration is closed");
        Objects.requireNonNull(getter, "getter");
        configuring = true;
        probed = null;
        probeValue = value;
        constantProbe = constant;
        voidProbe = allowVoid;
        try {
            getter.apply(instance);
            if (probed == null) throw new IllegalArgumentException("Expected one intercepted native getter");
            Function<Object[], ?> implementation = nativeBody ? nativeBody(probed, query) : query;
            if (queries.putIfAbsent(probed, implementation) != null) throw new IllegalArgumentException("Native query already bound: " + probed);
        } finally {
            configuring = false;
            probeValue = null;
            probed = null;
        }
        return this;
    }

    private Function<Object[], ?> nativeBody(Method method, Function<Object[], ?> guard) {
        if (Modifier.isAbstract(method.getModifiers())) throw new IllegalArgumentException("Native query has no body: " + method);
        try {
            var generated = instance.getClass();
            var lookup = MethodHandles.privateLookupIn(generated, MethodHandles.lookup());
            var handle = lookup.findSpecial(generated.getSuperclass(), method.getName(),
                    MethodType.methodType(method.getReturnType(), method.getParameterTypes()), generated).bindTo(instance)
                    .asSpreader(Object[].class, method.getParameterCount())
                    .asType(MethodType.methodType(Object.class, Object[].class));
            return arguments -> {
                guard.apply(arguments.clone());
                try { return (Object) handle.invokeExact(arguments); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new IllegalStateException("Native query failed: " + method, failure); }
            };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Native query body is inaccessible: " + method, failure);
        }
    }

    /** Returning the shell permanently closes its method configuration. */
    public T instance() { checkThread(); sealed = true; return instance; }

    private Object invoke(Object receiver, Method method, Object[] arguments) {
        checkThread();
        method = layout.methods.computeIfAbsent(method, original -> {
            try {
                Method resolved = layout.type.getSuperclass().getMethod(original.getName(), original.getParameterTypes());
                // A superclass/interface descriptor must share the binding of its
                // actual covariant override. Unrelated return types stay distinct.
                return original.getReturnType().isAssignableFrom(resolved.getReturnType()) ? resolved : original;
            } catch (NoSuchMethodException ignored) { return original; } // Protected native method.
        });
        if (configuring) {
            if (probed != null || constantProbe && method.getParameterCount() != 0 || (method.getReturnType() == void.class) != voidProbe) {
                throw new IllegalArgumentException("Expected one native query method: " + method);
            }
            probed = method;
            return probeValue;
        }
        Function<Object[], ?> query = queries.get(method);
        if (query != null) return query.apply(arguments == null ? new Object[0] : arguments.clone());
        throw new IllegalStateException("Unsupported world call from native rollback query entity: " + method);
    }

    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native query shell used from another thread");
    }
}
