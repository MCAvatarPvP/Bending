package com.projectkorra.projectkorra.prediction.rollback.world;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RollbackNativeMethodsTest {
    @Test void ownedEntryPhaseRunsOnceThroughVirtualAndSuperRoutesWithoutChangingOrdinaryNativeCode() throws Throwable {
        var entry = PhaseBase.class.getDeclaredMethod("tick", long.class);
        var base = PhaseBase.class.getDeclaredMethod("move", long.class);
        var child = PhaseChild.class.getDeclaredMethod("move", long.class);
        var phase = MethodHandles.lookup().findStatic(RollbackNativeMethodsTest.class, "movementPhase", MethodType.methodType(void.class, PhaseBase.class, long.class));
        var builder = new RollbackNativeMethods().copy(entry).copy(base).copy(child).before(base, phase);
        assertThrows(IllegalArgumentException.class, () -> builder.before(base, phase));
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().before(base, phase));
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copy(base).before(base, MethodHandles.empty(MethodType.methodType(void.class))));
        var copied = builder.build(); var tick = copied.get(entry); PhaseBase owned = new PhaseChild();
        assertEquals(4, (int) tick.invokeExact(owned, 3L));
        assertEquals(java.util.List.of("child", "phase", "body"), owned.calls);
        owned.calls.clear();
        assertThrows(IllegalArgumentException.class, () -> { int ignored = (int) tick.invokeExact(owned, -1L); });
        assertEquals(java.util.List.of("child", "phase"), owned.calls); assertEquals(1, owned.steps);
        owned.calls.clear(); assertEquals(5, (int) copied.get(base).invokeExact(owned, 5L));
        assertEquals(java.util.List.of("phase", "body"), owned.calls);
        var ordinary = new PhaseChild(); assertEquals(4, ordinary.tick(3));
        assertEquals(java.util.List.of("child", "body"), ordinary.calls);
    }
    private static void movementPhase(PhaseBase player, long input) {
        player.calls.add("phase"); if (input < 0) throw new IllegalArgumentException("Invalid movement");
    }
    static class PhaseBase {
        final ArrayList<String> calls = new ArrayList<>(); int steps;
        final int tick(long input) { return move(input); }
        int move(long input) { calls.add("body"); steps++; return Math.toIntExact(input); }
    }
    static final class PhaseChild extends PhaseBase {
        @Override int move(long input) { calls.add("child"); return super.move(input) + 1; }
    }

    @Test void abstractNativeCallsSelectAuditedOverridesAndRejectUnboundReceivers() throws Throwable {
        var entry = AbstractNative.class.getDeclaredMethod("tick", int.class);
        var declaration = AbstractNative.class.getDeclaredMethod("damage", int.class);
        var implementation = BoundNative.class.getDeclaredMethod("damage", int.class);
        var event = AbstractNative.class.getDeclaredMethod("publish", int.class);
        var events = new ArrayList<Integer>();
        var route = MethodHandles.lookup().findVirtual(ArrayList.class, "add", MethodType.methodType(boolean.class, Object.class))
                .bindTo(events).asType(MethodType.methodType(void.class, int.class));
        var builder = new RollbackNativeMethods().copy(entry).dispatch(declaration).copy(implementation).replace(event, route);
        assertThrows(IllegalArgumentException.class, () -> builder.dispatch(declaration));
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().dispatch(entry));
        var tick = builder.build().get(entry);
        AbstractNative owned = new BoundNative();
        assertEquals(7, (int) tick.invokeExact(owned, 3));
        assertEquals(java.util.List.of(3), events);
        AbstractNative unbound = new UnboundNative();
        assertThrows(IllegalArgumentException.class, () -> { int ignored = (int) tick.invokeExact(unbound, 3); });
        assertEquals(java.util.List.of(3), events);
    }

    static abstract class AbstractNative {
        int health = 10;
        final int tick(int amount) { return damage(amount); }
        abstract int damage(int amount);
        static void publish(int amount) { throw new AssertionError("Live event reached"); }
    }
    static final class BoundNative extends AbstractNative {
        @Override int damage(int amount) { publish(amount); return health -= amount; }
    }
    static final class UnboundNative extends AbstractNative {
        @Override int damage(int amount) { throw new AssertionError("Unaudited native override reached"); }
    }

    @Test void dispatchDeclarationDoesNotAuthorizeAnUncopiedBaseBody() throws Throwable {
        var entry = NativeDefault.class.getDeclaredMethod("tick");
        var declaration = NativeDefault.class.getDeclaredMethod("value");
        var implementation = NativeOverride.class.getDeclaredMethod("value");
        var tick = new RollbackNativeMethods().copy(entry).dispatch(declaration).copy(implementation).build().get(entry);
        NativeDefault owned = new NativeOverride();
        assertEquals(42, (int) tick.invokeExact(owned));
        NativeDefault unbound = new NativeDefault();
        assertThrows(IllegalArgumentException.class, () -> { int ignored = (int) tick.invokeExact(unbound); });
    }
    static class NativeDefault {
        final int tick() { return value(); }
        int value() { throw new AssertionError("Uncopied default reached"); }
    }
    static final class NativeOverride extends NativeDefault {
        @Override int value() { return 42; }
    }

    @Test void auditedLambdaBodiesPreserveCapturesWideArgumentsAndExceptionsWithoutLiveEvents() throws Throwable {
        var instance = Native.class.getDeclaredMethod("capturedLambda", long.class, double.class);
        var statik = Native.class.getDeclaredMethod("staticLambda", long.class, long.class);
        var event = Native.class.getDeclaredMethod("event", int.class);
        var events = new ArrayList<Integer>();
        var route = MethodHandles.lookup().findVirtual(ArrayList.class, "add", MethodType.methodType(boolean.class, Object.class))
                .bindTo(events).asType(MethodType.methodType(void.class, int.class));
        var builder = new RollbackNativeMethods().copy(instance).copy(statik).replace(event, route);
        for (var method : Native.class.getDeclaredMethods()) {
            if (method.getName().startsWith("lambda$capturedLambda$") || method.getName().startsWith("lambda$staticLambda$")) builder.copyLambda(method);
        }
        var built = builder.build();
        var copied = built.get(instance);
        var receiver = new Native();
        Native.globalEvents = 0;
        assertEquals(12.5, (double) copied.invokeExact(receiver, 9L, 2.5));
        assertEquals(13.5, (double) copied.invokeExact(receiver, 9L, 2.5));
        assertEquals(29L, (long) built.get(statik).invokeExact(9L, 10L));
        assertEquals(java.util.List.of(2, 2, 10), events);
        assertEquals(0, Native.globalEvents);
        assertThrows(IllegalArgumentException.class, () -> { double ignored = (double) copied.invokeExact(receiver, 9L, -1.0); });
        assertEquals(2, receiver.count);
        assertEquals(14.5, receiver.capturedLambda(9L, 2.5));
        assertEquals(1, Native.globalEvents);
    }

    @Test void lambdaSubstitutionRequiresExplicitAuditAndRejectsOrdinaryMethodReferences() throws Exception {
        var entry = Native.class.getDeclaredMethod("capturedLambda", long.class, double.class);
        var callback = java.util.Arrays.stream(Native.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("lambda$capturedLambda$")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copy(entry).copy(callback).build());
        var event = Native.class.getDeclaredMethod("event", int.class);
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copyLambda(event));
    }

    @Test void privateFieldReadsRemainDynamicWithoutChangingNativeConfiguration() throws Throwable {
        var entry = FieldChild.class.getDeclaredMethod("calculate", long.class);
        var global = Fields.class.getDeclaredField("factor");
        var local = Fields.class.getDeclaredField("offset");
        var settings = new long[]{7};
        var getter = MethodHandles.arrayElementGetter(long[].class).bindTo(settings);
        getter = MethodHandles.insertArguments(getter, 0, 0);
        var offset = MethodHandles.lookup().findStatic(RollbackNativeMethodsTest.class, "privateOffset", MethodType.methodType(double.class, Fields.class));
        var copied = new RollbackNativeMethods().copy(entry).read(global, getter).read(local, offset).build().get(entry);
        var player = new FieldChild();
        assertEquals(21, (double) copied.invokeExact(player, 2L));
        settings[0] = 9;
        assertEquals(25, (double) copied.invokeExact(player, 2L));
        assertEquals(9.5, player.calculate(2));
        assertEquals(3, Fields.factor);
        assertEquals(3.5, player.offset);
    }

    @Test void substitutedFieldsRejectWrongTypesDuplicatesAndNativeWrites() throws Throwable {
        var field = Fields.class.getDeclaredField("factor");
        var getter = MethodHandles.constant(long.class, 7L);
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().read(field, MethodHandles.constant(int.class, 7)));
        var builder = new RollbackNativeMethods().read(field, getter);
        assertThrows(IllegalArgumentException.class, () -> builder.read(field, getter));
        var write = Fields.class.getDeclaredMethod("change");
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copy(write).read(field, getter).build());
        assertEquals(3, Fields.factor);
    }

    private static double privateOffset(Fields receiver) { return receiver.offset * 2; }
    static class Fields {
        static long factor = 3;
        double offset = 3.5;
        static void change() { factor++; }
    }
    static final class FieldChild extends Fields {
        double calculate(long input) { return input * factor + offset; }
    }

    @Test void copiedPrivateNativeBodiesRetainStateBranchesLambdasAndExceptionsWithoutChangingOriginals() throws Throwable {
        Native.globalEvents = 0;
        var entry = Native.class.getDeclaredMethod("step", int.class);
        var inner = Native.class.getDeclaredMethod("calculate", int.class);
        var event = Native.class.getDeclaredMethod("event", int.class);
        var values = new ArrayList<Integer>();
        MethodHandle route = MethodHandles.lookup().findVirtual(ArrayList.class, "add", MethodType.methodType(boolean.class, Object.class))
                .bindTo(values).asType(MethodType.methodType(void.class, int.class));
        var built = new RollbackNativeMethods().copy(entry).copy(inner).replace(event, route).build();
        var replica = new Native();
        var handle = built.get(entry);
        assertEquals(7, (int) handle.invokeExact(replica, 3));
        assertEquals(1, replica.count);
        assertEquals(java.util.List.of(6), values);
        assertEquals(0, Native.globalEvents);
        assertThrows(IllegalArgumentException.class, () -> { int ignored = (int) handle.invokeExact(replica, -1); });
        assertEquals(2, replica.count); // Native finally block still runs.
        assertEquals(9, replica.step(3));
        assertEquals(1, Native.globalEvents);
        assertEquals(java.util.List.of(6), values);
    }

    @Test void nativeSuperInvocationKeepsItsOriginalDispatchAndWideArgumentSlots() throws Throwable {
        var entry = Child.class.getDeclaredMethod("sum", long.class, double.class);
        var parent = Parent.class.getDeclaredMethod("sum", long.class, double.class);
        var child = new Child();
        var copied = new RollbackNativeMethods().copy(entry).build().get(entry);
        assertEquals(child.sum(9, 2.5), (double) copied.invokeExact(child, 9L, 2.5));
        var together = new RollbackNativeMethods().copy(entry).copy(parent).build();
        assertEquals(child.sum(9, 2.5), (double) together.get(entry).invokeExact(child, 9L, 2.5));
    }

    @Test void configurationCannotSilentlyBypassOrChangeUnsupportedBindings() throws Throwable {
        var event = Native.class.getDeclaredMethod("event", int.class);
        var entry = Native.class.getDeclaredMethod("methodReference", int.class);
        var replacement = MethodHandles.empty(MethodType.methodType(void.class, int.class));
        var builder = new RollbackNativeMethods().copy(entry).replace(event, replacement);
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(IllegalStateException.class, () -> builder.copy(entry));
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().replace(event, MethodHandles.constant(int.class, 1)));
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copy(Native.class.getDeclaredMethod("locked")));
    }

    @Test void copiedVirtualCallsSelectConfiguredOverridesAndKeepSuperCallsExact() throws Throwable {
        var entry = Parent.class.getDeclaredMethod("dispatch", long.class, double.class);
        var parent = Parent.class.getDeclaredMethod("sum", long.class, double.class);
        var child = Child.class.getDeclaredMethod("sum", long.class, double.class);
        var copied = new RollbackNativeMethods().copy(entry).copy(parent).copy(child).build().get(entry);
        Parent receiver = new Child();
        assertEquals(receiver.dispatch(9, 2.5), (double) copied.invokeExact(receiver, 9L, 2.5));
        Parent base = new Parent();
        assertEquals(base.dispatch(9, 2.5), (double) copied.invokeExact(base, 9L, 2.5));
        Parent unknown = new Child() { @Override double sum(long a, double b) { fail("Unbound native override ran"); return 0; } };
        assertThrows(IllegalArgumentException.class, () -> { double ignored = (double) copied.invokeExact(unknown, 9L, 2.5); });
        var missing = new RollbackNativeMethods().copy(entry).copy(parent).build().get(entry);
        assertThrows(IllegalArgumentException.class, () -> { double ignored = (double) missing.invokeExact(receiver, 9L, 2.5); });
    }

    @Test void nativeLoaderDoesNotNeedVisibilityOfThePluginOrItsPrivateReplacement() throws Throwable {
        String name = "nativefixture/PrivateOwner";
        var writer = new net.bytebuddy.jar.asm.ClassWriter(net.bytebuddy.jar.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(net.bytebuddy.jar.asm.Opcodes.V17, net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd();
        var constructor = writer.visitMethod(net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(net.bytebuddy.jar.asm.Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        var body = writer.visitMethod(net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, "bump", "(I)I", null, null);
        body.visitCode(); body.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ALOAD, 0); body.visitInsn(net.bytebuddy.jar.asm.Opcodes.DUP);
        body.visitFieldInsn(net.bytebuddy.jar.asm.Opcodes.GETFIELD, name, "value", "I"); body.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 1);
        body.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
        body.visitInsn(net.bytebuddy.jar.asm.Opcodes.IADD); body.visitInsn(net.bytebuddy.jar.asm.Opcodes.DUP_X1);
        body.visitFieldInsn(net.bytebuddy.jar.asm.Opcodes.PUTFIELD, name, "value", "I"); body.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN);
        body.visitMaxs(0, 0); body.visitEnd();
        var callback = writer.visitMethod(net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE | net.bytebuddy.jar.asm.Opcodes.ACC_STATIC
                        | net.bytebuddy.jar.asm.Opcodes.ACC_SYNTHETIC, "lambda$callback$0", "(I)I", null, null);
        callback.visitCode(); callback.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 0);
        callback.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
        callback.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN); callback.visitMaxs(0, 0); callback.visitEnd();
        var caller = writer.visitMethod(net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC | net.bytebuddy.jar.asm.Opcodes.ACC_STATIC,
                "callback", "(I)I", null, null);
        caller.visitCode();
        caller.visitInvokeDynamicInsn("applyAsInt", "()Ljava/util/function/IntUnaryOperator;", new net.bytebuddy.jar.asm.Handle(
                        net.bytebuddy.jar.asm.Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                net.bytebuddy.jar.asm.Type.getMethodType("(I)I"), new net.bytebuddy.jar.asm.Handle(
                        net.bytebuddy.jar.asm.Opcodes.H_INVOKESTATIC, name, "lambda$callback$0", "(I)I", false),
                net.bytebuddy.jar.asm.Type.getMethodType("(I)I"));
        caller.visitVarInsn(net.bytebuddy.jar.asm.Opcodes.ILOAD, 0);
        caller.visitMethodInsn(net.bytebuddy.jar.asm.Opcodes.INVOKEINTERFACE, "java/util/function/IntUnaryOperator", "applyAsInt", "(I)I", true);
        caller.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN); caller.visitMaxs(0, 0); caller.visitEnd(); writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        var loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
            @Override public java.io.InputStream getResourceAsStream(String path) {
                return path.equals(name + ".class") ? new java.io.ByteArrayInputStream(bytes) : super.getResourceAsStream(path);
            }
        };
        Class<?> owner = loader.define();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(RollbackNativeMethods.class.getName()));
        var method = owner.getMethod("bump", int.class);
        var replacement = MethodHandles.lookup().findStatic(RollbackNativeMethodsTest.class, "seven", MethodType.methodType(int.class, int.class));
        var copied = new RollbackNativeMethods().copy(method).replace(Math.class.getMethod("abs", int.class), replacement).build().get(method);
        Object replica = owner.getConstructor().newInstance();
        assertEquals(7, copied.invokeWithArguments(replica, -2));
        assertEquals(9, method.invoke(replica, -2));
        var entry = owner.getMethod("callback", int.class);
        var privateLambda = owner.getDeclaredMethod("lambda$callback$0", int.class);
        var lambda = new RollbackNativeMethods().copy(entry).copyLambda(privateLambda)
                .replace(Math.class.getMethod("abs", int.class), replacement).build().get(entry);
        assertEquals(7, (int) lambda.invokeExact(-2));
        assertEquals(2, entry.invoke(null, -2));
    }

    @Test void copiedInterfaceCallsHonorInheritedDefaultsAndClassImplementations() throws Throwable {
        var entry = BaseEffect.class.getDeclaredMethod("dispatch", int.class);
        var base = BaseEffect.class.getDeclaredMethod("apply", int.class);
        var child = DerivedEffect.class.getDeclaredMethod("apply", int.class);
        var concrete = ClassEffect.class.getDeclaredMethod("apply", int.class);
        var handle = new RollbackNativeMethods().copy(entry).copy(base).copy(child).copy(concrete).build().get(entry);
        BaseEffect plain = new BaseEffect() { };
        BaseEffect inherited = new DerivedEffect() { };
        BaseEffect classOverride = new ClassEffect();
        assertEquals(plain.dispatch(3), (int) handle.invokeExact(plain, 3));
        assertEquals(inherited.dispatch(3), (int) handle.invokeExact(inherited, 3));
        assertEquals(classOverride.dispatch(3), (int) handle.invokeExact(classOverride, 3));
    }

    @Test void unboundInheritedInterfaceDefaultsCannotSilentlyUseTheBaseBody() throws Throwable {
        var entry = BaseEffect.class.getDeclaredMethod("dispatch", int.class);
        var base = BaseEffect.class.getDeclaredMethod("apply", int.class);
        var handle = new RollbackNativeMethods().copy(entry).copy(base).build().get(entry);
        BaseEffect inherited = new DerivedEffect() { };
        var failure = assertThrows(IllegalArgumentException.class, () -> { int ignored = (int) handle.invokeExact(inherited, 3); });
        assertTrue(failure.getMessage().contains("Unbound native override"));
    }

    interface BaseEffect {
        default int apply(int amount) { return amount + 1; }
        default int dispatch(int amount) { return apply(amount); }
    }
    interface DerivedEffect extends BaseEffect {
        @Override default int apply(int amount) { return BaseEffect.super.apply(amount) * 2; }
    }
    static final class ClassEffect implements DerivedEffect {
        @Override public int apply(int amount) { return amount + 10; }
    }

    private static int seven(int ignored) { return 7; }

    @Test void privateFactoriesReplaceCanonicalAllocationsAndRejectComplexOrIndirectOnes() throws Throwable {
        Allocation.originalCalls = 0;
        var constructor = Allocation.class.getDeclaredConstructor(long.class, double.class);
        var factory = MethodHandles.lookup().findStatic(RollbackNativeMethodsTest.class, "allocate", MethodType.methodType(Allocation.class, long.class, double.class));
        var entry = Native.class.getDeclaredMethod("allocate", long.class, double.class);
        var copied = new RollbackNativeMethods().copy(entry).construct(constructor, factory).build().get(entry);
        assertEquals(9, ((Allocation) copied.invokeExact(3L, 1.5D)).value);
        assertEquals(0, Allocation.originalCalls);
        assertEquals(4.5, Native.allocate(3, 1.5).value);
        assertEquals(1, Allocation.originalCalls);
        var complex = Native.class.getDeclaredMethod("allocateComplex", long.class, double.class);
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copy(complex).construct(constructor, factory).build());
        var reference = Native.class.getDeclaredMethod("allocateReference", long.class, double.class);
        assertThrows(IllegalArgumentException.class, () -> new RollbackNativeMethods().copy(reference).construct(constructor, factory).build());
        assertEquals(1, Allocation.originalCalls);
    }

    private static Allocation allocate(long first, double second) {
        var result = new Allocation(); result.value = (first + second) * 2; return result;
    }
    static final class Allocation {
        static int originalCalls;
        double value;
        private Allocation() { }
        Allocation(long first, double second) { value = first + second; originalCalls++; }
    }

    static class Native {
        static int globalEvents;
        private int count;
        int step(int value) {
            try { return calculate(value) + count; }
            finally { count++; }
        }
        private int calculate(int value) {
            if (value < 0) throw new IllegalArgumentException("negative");
            java.util.function.IntUnaryOperator nativeLambda = input -> input * 2;
            int result = nativeLambda.applyAsInt(value);
            event(result);
            return result + 1;
        }
        private static void event(int value) { globalEvents++; }
        double capturedLambda(long base, double input) {
            java.util.function.DoubleUnaryOperator callback = value -> {
                if (value < 0) throw new IllegalArgumentException("negative lambda input");
                event((int) value);
                return base + value + ++count;
            };
            return callback.applyAsDouble(input);
        }
        static long staticLambda(long base, long input) {
            java.util.function.LongUnaryOperator callback = value -> { event((int) value); return base + value * 2; };
            return callback.applyAsLong(input);
        }
        int methodReference(int value) {
            java.util.function.IntConsumer callback = Native::event;
            callback.accept(value);
            return value;
        }
        synchronized void locked() { }
        static Allocation allocate(long first, double second) { return new Allocation(first, second); }
        static Allocation allocateComplex(long first, double second) { return new Allocation(Math.abs(first), second); }
        static Allocation allocateReference(long first, double second) {
            java.util.function.BiFunction<Long, Double, Allocation> factory = Allocation::new;
            return factory.apply(first, second);
        }
    }
    static class Parent {
        double sum(long a, double b) { return a + b; }
        double dispatch(long a, double b) { return sum(a, b); }
    }
    static class Child extends Parent { @Override double sum(long a, double b) { return super.sum(a, b) * 2; } }
}
