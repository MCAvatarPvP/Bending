package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RollbackCallbackTest {
    static final class State {
        int count;
        PKRunnable callback;
        void increment() { count++; }
        PKRunnable nestedFactory() { return this::increment; }
    }
    private static RollbackGraphCodec codec() {
        return new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(State.class, RollbackCallback.class),
                List.of(RollbackCallbackTest.class), List.of()), new RollbackGraphCodec.Limits(200, 2000, 100_000, 10_000));
    }

    @Test void scheduledLambdaUsesDescribableTargetWithoutChangingExistingRunnableDispatch() {
        List<Runnable> seen = new ArrayList<>();
        PKScheduler scheduler = (PKScheduler) Proxy.newProxyInstance(PKScheduler.class.getClassLoader(), new Class<?>[]{PKScheduler.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    assertEquals(Runnable.class, method.getParameterTypes()[0]);
                    seen.add((Runnable) args[0]); return null;
                });
        var source = new State(); int amount = 3;
        scheduler.runLater(() -> source.count += amount, 4);
        assertInstanceOf(PKRunnable.class, seen.getFirst());
        var copied = codec().decode(codec().encode(List.of(seen.getFirst(), source), RollbackCallback::project));
        ((Runnable) copied.getFirst()).run();
        assertEquals(3, ((State) copied.get(1)).count); assertEquals(0, source.count);
        Runnable original = new Runnable() { @Override public void run() { } };
        scheduler.runNow(original); assertSame(original, seen.get(1));
    }

    @Test void cyclesNestedCallbacksAndMutableCapturesAreCopiedAndRewindTogether() {
        var source = new State();
        PKRunnable inner = source::increment;
        source.callback = () -> { inner.run(); source.count += 2; };
        var codec = codec();
        var imported = codec.decode(codec.encode(List.of(source, source.callback, source.callback), RollbackCallback::project));
        var copy = (State) imported.getFirst();
        assertSame(copy.callback, imported.get(1)); assertSame(imported.get(1), imported.get(2));
        var before = new RollbackStateGraph(value -> false, field -> true, 200).capture(List.of(copy), List.of());
        copy.callback.run(); assertEquals(3, copy.count); assertEquals(0, source.count);
        before.restore(); assertEquals(0, copy.count);
        copy.callback.run(); assertEquals(3, copy.count); assertEquals(0, source.count);
    }

    @Test void temporaryTerrainCallbacksKeepTheirSubtypeWhenNestedInsideScheduledWork() {
        var source = new State();
        com.projectkorra.projectkorra.util.TempBlock.RevertTask revert = source::increment;
        source.callback = () -> { revert.run(); source.count += 4; };
        var codec = codec();
        var copied = (State) codec.decode(codec.encode(List.of(source), RollbackCallback::project)).getFirst();
        copied.callback.run(); assertEquals(5, copied.count); assertEquals(0, source.count);
    }

    @Test void nestedCapturingClassMethodReferencesRebindTheirReceiver() {
        var source = new State(); source.callback = source.nestedFactory();
        var codec = codec();
        var copied = (State) codec.decode(codec.encode(List.of(source), RollbackCallback::project)).getFirst();
        copied.callback.run(); assertEquals(1, copied.count); assertEquals(0, source.count);
    }

    @Test void incompatibleCapturedArgumentsRejectTheWholeTaskBatchBeforeBinding() throws Exception {
        var source = new State(); PKRunnable described = source::increment;
        var codec = codec();
        var callback = (RollbackCallback) codec.decode(codec.encode(List.of(described), RollbackCallback::project)).getFirst();
        var field = RollbackCallback.class.getDeclaredField("captured"); field.setAccessible(true);
        ((Object[]) field.get(callback))[0] = new Object();
        var first = new RollbackTaskBindings.Entry(3, () -> {}, 1, -1, null, 0, 0);
        var bad = new RollbackTaskBindings.Entry(7, callback, 1, -1, null, 0, 0);
        var scheduler = new RollbackScheduler(5, 5);
        assertThrows(ClassCastException.class, () -> new RollbackTaskBindings(List.of(first, bad)).install(scheduler));
        assertEquals(0, scheduler.pendingTasks()); assertThrows(IllegalStateException.class, first.handle()::cancel);
        assertEquals(1, scheduler.runNow(() -> {}).legacyId()); assertEquals(0, source.count);
    }

    @Test void undescribedCallbacksAndMissingCatalogFactoriesFailInsteadOfRetainingLiveState() {
        var source = new State(); Runnable undescribed = () -> source.count++;
        assertNull(RollbackCallback.project(undescribed));
        assertThrows(IllegalArgumentException.class, () -> codec().encode(List.of(undescribed), RollbackCallback::project));
        PKRunnable described = () -> source.count++;
        var missing = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(State.class, RollbackCallback.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(200, 2000, 100_000, 10_000));
        assertThrows(IllegalArgumentException.class, () -> missing.encode(List.of(described), RollbackCallback::project));
        assertEquals(0, source.count);
    }
}
