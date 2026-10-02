package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKRunnable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Modifier;

/** Portable scheduler lambda: executable code stays in the negotiated local class catalog.
 * Only its compiler descriptor and captured object references cross the gameplay graph.
 * Never uses ObjectInputStream or retains a source lambda/native capture after import.
 */
public final class RollbackCallback implements com.projectkorra.projectkorra.util.TempBlock.RevertTask {
    private static final String INTERFACE = PKRunnable.class.getName().replace('.', '/');
    private static final String REVERT_INTERFACE = com.projectkorra.projectkorra.util.TempBlock.RevertTask.class.getName().replace('.', '/');
    private static final ClassValue<MethodHandle> DESCRIBE = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try { return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findVirtual(type, "writeReplace", MethodType.methodType(Object.class))
                    .asType(MethodType.methodType(Object.class, Object.class)); }
            catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Unsupported scheduler callback descriptor", failure); }
        }
    };
    private static final ClassValue<MethodHandle> RESTORE = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                var method = type.getDeclaredMethod("$deserializeLambda$", SerializedLambda.class);
                if (!method.isSynthetic() || !Modifier.isStatic(method.getModifiers()))
                    throw new IllegalArgumentException("Callback requires a compiler-generated factory");
                return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflect(method)
                        .asType(MethodType.methodType(Object.class, SerializedLambda.class));
            } catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Missing scheduler callback factory", failure); }
        }
    };
    private final Class<?> capturing;
    private final String functionalInterface;
    private final String implementationClass, implementationMethod, implementationSignature, instantiatedSignature;
    private final int implementationKind;
    private final Object[] captured;

    private RollbackCallback(Class<?> capturing, SerializedLambda source) {
        this.capturing = capturing;
        functionalInterface = source.getFunctionalInterfaceClass();
        implementationClass = source.getImplClass(); implementationMethod = source.getImplMethodName();
        implementationSignature = source.getImplMethodSignature(); instantiatedSignature = source.getInstantiatedMethodType();
        implementationKind = source.getImplMethodKind();
        captured = new Object[source.getCapturedArgCount()];
        for (int i = 0; i < captured.length; i++) captured[i] = source.getCapturedArg(i);
    }

    /** Source-only projection; ordinary named callbacks continue through normal graph copying. */
    public static RollbackStateTransfer.Replacement project(Object value) {
        if (!(value instanceof PKRunnable) || !value.getClass().isHidden() || !value.getClass().isSynthetic()) return null;
        try {
            Object descriptor = DESCRIBE.get(value.getClass()).invokeExact(value);
            if (!(descriptor instanceof SerializedLambda lambda) || !(INTERFACE.equals(lambda.getFunctionalInterfaceClass()) || REVERT_INTERFACE.equals(lambda.getFunctionalInterfaceClass()))
                    || !"run".equals(lambda.getFunctionalInterfaceMethodName()) || !"()V".equals(lambda.getFunctionalInterfaceMethodSignature()))
                throw new IllegalArgumentException("Invalid scheduler callback descriptor");
            Class<?> capturing = Class.forName(lambda.getCapturingClass().replace('/', '.'), false, value.getClass().getClassLoader());
            if (capturing.getNestHost() != value.getClass().getNestHost()) throw new IllegalArgumentException("Foreign callback factory");
            RESTORE.get(capturing);
            return RollbackStateTransfer.Replacement.fromProjection(new RollbackCallback(capturing, lambda));
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalArgumentException("Cannot describe scheduler callback", failure); }
    }

    /** Check compiler-site and captured-argument compatibility before task installation. */
    public void validate() { validate(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())); }
    private void validate(java.util.Set<RollbackCallback> visited) {
        if (!visited.add(this)) return;
        restore();
        for (Object value : captured) if (value instanceof RollbackCallback nested) nested.validate(visited);
    }

    @Override public void run() { restore().run(); }

    private PKRunnable restore() {
        try {
            // Recreate a short-lived implementation from the current copied references.
            // No derived lambda cache can retain references from a discarded branch.
            var descriptor = new SerializedLambda(capturing, functionalInterface, "run", "()V", implementationKind,
                    implementationClass, implementationMethod, implementationSignature, instantiatedSignature, captured);
            Object callback = RESTORE.get(capturing).invokeExact(descriptor);
            if (!(callback instanceof PKRunnable runnable)) throw new IllegalStateException("Callback factory returned a foreign contract");
            return runnable;
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot restore scheduler callback", failure); }
    }
}
