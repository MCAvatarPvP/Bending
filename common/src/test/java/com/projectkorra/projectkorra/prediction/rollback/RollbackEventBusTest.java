package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.event.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackEventBusTest {
    static final class Hit extends Event implements Cancellable {
        boolean cancelled;
        final List<String> visits = new ArrayList<>();
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
    }
    static final class Rules {
        int hits;
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hit(Hit value) { hits++; value.visits.add("hit"); }
        @EventHandler(priority = EventPriority.LOW) public void cancel(Hit value) { value.visits.add("cancel"); value.setCancelled(true); }
        @EventHandler(priority = EventPriority.HIGHEST) public void observe(Event value) { ((Hit) value).visits.add("observe"); }
    }
    @Test void orderedCommonDispatchRespectsCancellationAndInheritedEventTypes() {
        var bus = new RollbackEventBus(100, 10); var rules = new Rules();
        bus.registerListener(rules);
        var hit = new Hit(); bus.call(hit);
        assertTrue(hit.isCancelled()); assertEquals(0, rules.hits);
        assertEquals(List.of("cancel", "observe"), hit.visits);
        assertThrows(IllegalArgumentException.class, () -> bus.call(new Object()));
    }

    static final class Counter {
        int hits;
        @EventHandler public void hit(Hit value) { hits++; }
    }
    @Test void restoringGraphRestoresListenerStateRegistrationRemovalAndFutureOrder() {
        var bus = new RollbackEventBus(100, 10); var first = new Counter(); var second = new Counter();
        // Owners are opaque registration tags, not event dependencies. A live lifecycle
        // token must not make capture traverse that lifecycle's unrelated native state.
        var owner = Thread.currentThread(); bus.registerListener(first, owner);
        var snapshot = new RollbackStateGraph(value -> false, field -> true, 1000).capture(List.of(bus), List.of());
        bus.call(new Hit()); bus.unregisterAll(owner); bus.registerListener(second);
        bus.call(new Hit());
        assertEquals(1, first.hits); assertEquals(1, second.hits);
        snapshot.restore();
        assertEquals(0, first.hits);
        bus.call(new Hit()); assertEquals(1, first.hits); assertEquals(1, second.hits);
        assertThrows(IllegalArgumentException.class, () -> new RollbackEventBus(100, 10).restoreRollbackState(bus.captureRollbackState()));
    }

    static final class Mutating {
        final RollbackEventBus bus; final Counter extra;
        Mutating(RollbackEventBus bus, Counter extra) { this.bus = bus; this.extra = extra; }
        @EventHandler(priority = EventPriority.LOW) public void mutate(Hit value) { bus.unregisterAll(this); bus.registerListener(extra); }
    }
    @Test void callbackRegistrationAffectsNextDispatchAndFailuresUnwindDepth() {
        var bus = new RollbackEventBus(100, 2); var extra = new Counter(); bus.registerListener(new Mutating(bus, extra));
        bus.call(new Hit()); assertEquals(0, extra.hits); bus.call(new Hit()); assertEquals(1, extra.hits);
        Object recursion = new Object() { @EventHandler public void recurse(Hit value) { bus.call(value); } };
        bus.registerListener(recursion);
        assertThrows(IllegalStateException.class, () -> bus.call(new Hit()));
        bus.unregisterAll(recursion); assertDoesNotThrow(bus::captureRollbackState);
        Object failure = new Object() { @EventHandler public void fail(Hit value) { throw new IllegalArgumentException("rule failed"); } };
        bus.registerListener(failure);
        assertEquals("rule failed", assertThrows(IllegalArgumentException.class, () -> bus.call(new Hit())).getMessage());
        bus.unregisterAll(failure); assertDoesNotThrow(bus::captureRollbackState);
    }
    @Test void rejectedRegistrationIsAtomicAndCrossThreadCallsCannotMutateTheBus() throws Exception {
        var bus = new RollbackEventBus(2, 2);
        assertThrows(IllegalStateException.class, () -> bus.registerListener(new Rules()));
        var counter = new Counter(); bus.registerListener(counter); bus.call(new Hit()); assertEquals(1, counter.hits);
        Object malformed = new Object() { @EventHandler public int invalid(Hit event) { return 1; } };
        assertThrows(IllegalArgumentException.class, () -> bus.registerListener(malformed));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> { try { bus.call(new Hit()); } catch (Throwable problem) { failure.set(problem); } });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get()); assertEquals(1, counter.hits);
    }
}
