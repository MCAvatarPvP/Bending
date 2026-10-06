package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKRunnable;
import com.projectkorra.projectkorra.ability.activation.ActivationContext;
import com.projectkorra.projectkorra.ability.activation.ActivationHandler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Modifier;

/** Portable gameplay lambda: executable code stays in the negotiated local class catalog.
 * Only its compiler descriptor and captured object references cross the gameplay graph.
 * Never uses ObjectInputStream or retains a source lambda/native capture after import.
 */
public final class RollbackCallback implements com.projectkorra.projectkorra.util.TempBlock.RevertTask, ActivationHandler {
    private static final String INTERFACE = PKRunnable.class.getName().replace('.', '/');
    private static final String REVERT_INTERFACE = com.projectkorra.projectkorra.util.TempBlock.RevertTask.class.getName().replace('.', '/');
    private static final String ACTIVATION_INTERFACE = ActivationHandler.class.getName().replace('.', '/');
    private static final String ACTIVATION_SIGNATURE = "(L" + ActivationContext.class.getName().replace('.', '/') + ";)Z";
    private static final ClassValue<MethodHandle> DESCRIBE = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try { return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findVirtual(type, "writeReplace", MethodType.methodType(Object.class))
                    .asType(MethodType.methodType(Object.class, Object.class)); }
            catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Unsupported gameplay callback descriptor", failure); }
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
            } catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Missing gameplay callback factory", failure); }
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
        if (!(value instanceof PKRunnable || value instanceof ActivationHandler) || !value.getClass().isHidden() || !value.getClass().isSynthetic()) return null;
        try {
            Object descriptor = DESCRIBE.get(value.getClass()).invokeExact(value);
            if (!(descriptor instanceof SerializedLambda lambda) || !validContract(lambda))
                throw new IllegalArgumentException("Invalid gameplay callback descriptor");
            Class<?> capturing = Class.forName(lambda.getCapturingClass().replace('/', '.'), false, value.getClass().getClassLoader());
            if (capturing.getNestHost() != value.getClass().getNestHost()) throw new IllegalArgumentException("Foreign callback factory");
            RESTORE.get(capturing);
            return RollbackStateTransfer.Replacement.fromProjection(new RollbackCallback(capturing, lambda));
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalArgumentException("Cannot describe gameplay callback", failure); }
    }

    private static boolean validContract(SerializedLambda lambda) {
        if (ACTIVATION_INTERFACE.equals(lambda.getFunctionalInterfaceClass()))
            return "activate".equals(lambda.getFunctionalInterfaceMethodName())
                    && ACTIVATION_SIGNATURE.equals(lambda.getFunctionalInterfaceMethodSignature());
        return (INTERFACE.equals(lambda.getFunctionalInterfaceClass()) || REVERT_INTERFACE.equals(lambda.getFunctionalInterfaceClass()))
                && "run".equals(lambda.getFunctionalInterfaceMethodName()) && "()V".equals(lambda.getFunctionalInterfaceMethodSignature());
    }

    /** Check compiler-site and captured-argument compatibility before task installation. */
    public void validate() { validate(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())); }
    private void validate(java.util.Set<RollbackCallback> visited) {
        if (!visited.add(this)) return;
        restore();
        for (Object value : captured) if (value instanceof RollbackCallback nested) nested.validate(visited);
    }

    public void validateTask() {
        if (ACTIVATION_INTERFACE.equals(functionalInterface)) throw new IllegalStateException("Activation callback is not a scheduled task");
        validate();
    }

    @Override public void run() {
        if (ACTIVATION_INTERFACE.equals(functionalInterface)) throw new IllegalStateException("Activation callback is not a scheduled task");
        ((PKRunnable) restore()).run();
    }

    @Override public boolean activate(ActivationContext context) {
        if (!ACTIVATION_INTERFACE.equals(functionalInterface)) throw new IllegalStateException("Scheduled callback is not an activation handler");
        return ((ActivationHandler) restore()).activate(context);
    }

    private Object restore() {
        try {
            // Recreate a short-lived implementation from the current copied references.
            // No derived lambda cache can retain references from a discarded branch.
            boolean activation = ACTIVATION_INTERFACE.equals(functionalInterface);
            var descriptor = new SerializedLambda(capturing, functionalInterface, activation ? "activate" : "run",
                    activation ? ACTIVATION_SIGNATURE : "()V", implementationKind,
                    implementationClass, implementationMethod, implementationSignature, instantiatedSignature, captured);
            if (!validContract(descriptor)) throw new IllegalStateException("Unknown gameplay callback contract");
            Object callback = RESTORE.get(capturing).invokeExact(descriptor);
            if (activation ? !(callback instanceof ActivationHandler) : !(callback instanceof PKRunnable)) throw new IllegalStateException("Callback factory returned a foreign contract");
            return callback;
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot restore gameplay callback", failure); }
    }
}
