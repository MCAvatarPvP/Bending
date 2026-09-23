package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import org.junit.jupiter.api.Test;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.implementation.FixedValue;
import java.lang.reflect.InvocationTargetException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

public class RollbackNativeQueryShellTest {
    public interface Provider { Number value(); }
    public static class CovariantNative implements Provider {
        @Override public Integer value() { throw new AssertionError("Native method must not execute"); }
    }
    public abstract static class World {
        public World() { throw new AssertionError("Native world constructor must not run"); }
        public abstract boolean isClient();
        public int block() { throw new AssertionError("Unconfigured method must be intercepted"); }
        public int block(int x, int y) { throw new AssertionError("Unconfigured method must be intercepted"); }
        public void collect(int x, java.util.List<Integer> output) { throw new AssertionError("Unconfigured method must be intercepted"); }
        public int twiceBlock(int x, int y) { return block(x, y) * 2; }
    }

    @Test void constructorlessShellAllowsOnlyConfiguredGettersOnItsOwningThread() {
        var shell = RollbackNativeQueryShell.create(World.class).constant(World::isClient, false);
        World world = shell.instance();
        assertFalse(world.isClient());
        assertThrows(IllegalStateException.class, world::block);
        assertThrows(IllegalStateException.class, () -> shell.constant(World::block, 1));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(world::isClient).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        var second = RollbackNativeQueryShell.create(World.class).constant(World::isClient, true).instance();
        assertTrue(second.isClient());
        assertFalse(world.isClient());
    }

    @Test void invalidConfigurationCannotSilentlyAuthorizeAnUnknownNativeCall() {
        var shell = RollbackNativeQueryShell.create(World.class);
        assertThrows(IllegalArgumentException.class, () -> shell.constant(world -> 1, 1));
        assertThrows(IllegalArgumentException.class, () -> shell.constant(world -> {
            world.isClient(); return world.isClient();
        }, false));
        assertThrows(IllegalStateException.class, shell.instance()::isClient);
    }

    @Test void queryBindingsUseActualArgumentsAndRestoredStateWithoutCallingDuringConfiguration() {
        int[] state = {3};
        int[] calls = {0};
        var shell = RollbackNativeQueryShell.create(World.class).query(world -> world.block(0, 0), 0, args -> {
            calls[0]++;
            return state[0] + (int) args[0] * 10 + (int) args[1];
        });
        assertEquals(0, calls[0]);
        assertThrows(IllegalArgumentException.class, () -> shell.query(world -> world.block(1, 2), 0, args -> -1));
        var world = shell.instance();
        assertEquals(25, world.block(2, 2));
        state[0] = 8;
        assertEquals(20, world.block(1, 2));
        state[0] = 3;
        assertEquals(25, world.block(2, 2));
        assertEquals(3, calls[0]);
        assertThrows(IllegalStateException.class, world::block);
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(() -> world.block(0, 0)).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(3, calls[0]);
    }

    @Test void outputQueriesOnlyAcceptVoidMethodsAndFillTheActualCallerCollection() {
        int[] state = {3};
        var probeOutput = new java.util.ArrayList<Integer>();
        var shell = RollbackNativeQueryShell.create(World.class);
        assertThrows(IllegalArgumentException.class, () -> shell.outputQuery(World::block, args -> fail()));
        assertThrows(IllegalArgumentException.class, () -> shell.query(world -> {
            world.collect(0, probeOutput); return null;
        }, null, args -> fail()));
        shell.outputQuery(world -> world.collect(0, probeOutput), args -> {
            @SuppressWarnings("unchecked") var output = (java.util.List<Integer>) args[1];
            output.add((int) args[0] + state[0]);
        });
        assertTrue(probeOutput.isEmpty(), "configuration must not execute the output query");
        assertThrows(IllegalArgumentException.class, () -> shell.outputQuery(world -> world.collect(0, probeOutput), args -> fail()));
        var world = shell.instance();
        var output = new java.util.ArrayList<Integer>();
        world.collect(5, output);
        state[0] = 10;
        world.collect(1, output);
        state[0] = 3;
        world.collect(5, output);
        assertEquals(java.util.List.of(8, 11, 8), output);
        assertTrue(probeOutput.isEmpty());
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> world.collect(0, output)).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(3, output.size());
    }

    @Test void syntheticCovariantMethodsWithoutBridgeFlagsCannotBypassTheBoundary() throws Exception {
        // Fabric's remapped ServerWorld retains this JVM-legal method pair with
        // ACC_SYNTHETIC but no ACC_BRIDGE on the wider descriptor.
        Class<?> nativeType = new ByteBuddy().with(MethodGraph.Compiler.Default.forJVMHierarchy())
                .subclass(Object.class)
                .defineMethod("value", Number.class, Visibility.PUBLIC, SyntheticState.SYNTHETIC).intercept(FixedValue.value(42))
                .defineMethod("value", Integer.class, Visibility.PUBLIC).intercept(FixedValue.value(41))
                .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded();
        var narrow = java.util.Arrays.stream(nativeType.getDeclaredMethods()).filter(method -> method.getReturnType() == Integer.class).findFirst().orElseThrow();
        var wide = java.util.Arrays.stream(nativeType.getDeclaredMethods()).filter(method -> method.getReturnType() == Number.class).findFirst().orElseThrow();
        assertTrue(wide.isSynthetic()); assertFalse(wide.isBridge());
        var unknown = RollbackNativeQueryShell.create(nativeType).instance();
        assertInstanceOf(IllegalStateException.class, assertThrows(InvocationTargetException.class, () -> narrow.invoke(unknown)).getCause());
        assertInstanceOf(IllegalStateException.class, assertThrows(InvocationTargetException.class, () -> wide.invoke(unknown)).getCause());
        var shell = RollbackNativeQueryShell.create(nativeType).constant(value -> {
            try { return narrow.invoke(value); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }, 7);
        var bound = shell.instance();
        assertEquals(7, narrow.invoke(bound));
        assertEquals(7, wide.invoke(bound), "covariant descriptors share the configured native getter");
    }

    @Test void auditedNativeBodiesKeepTheirVirtualDependenciesGuardedAndUseCurrentState() {
        int[] state = {3};
        var shell = RollbackNativeQueryShell.create(World.class);
        assertThrows(IllegalArgumentException.class, () -> shell.nativeQuery(World::isClient, false, args -> { }));
        var world = shell.query(value -> value.block(0, 0), 0, args -> state[0] + (int) args[0] + (int) args[1])
                .nativeQuery(value -> value.twiceBlock(0, 0), 0, args -> {
                    if ((int) args[0] < 0) throw new IllegalArgumentException("outside captured bounds");
                }).instance();
        assertEquals(12, world.twiceBlock(1, 2));
        state[0] = 7; assertEquals(20, world.twiceBlock(1, 2));
        state[0] = 3; assertEquals(12, world.twiceBlock(1, 2));
        assertThrows(IllegalArgumentException.class, () -> world.twiceBlock(-1, 0));
        var unbound = RollbackNativeQueryShell.create(World.class)
                .nativeQuery(value -> value.twiceBlock(0, 0), 0, args -> { }).instance();
        assertThrows(IllegalStateException.class, () -> unbound.twiceBlock(0, 0));
    }

    @Test void interfaceDescriptorsShareTheirActualCovariantOverrideBinding() {
        var value = RollbackNativeQueryShell.create(CovariantNative.class).constant(CovariantNative::value, 7).instance();
        assertEquals(7, value.value());
        assertEquals(7, ((Provider) value).value());
    }
}
