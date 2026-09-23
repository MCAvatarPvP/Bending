package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BukkitEventBusTest {
    @Test void boundCallbacksRetainOrderingCancellationOwnershipAndFailure() {
        var bus = new BukkitProjectKorraPlatform(null).events();
        var calls = new ArrayList<String>();
        Object owner = new Object();
        bus.registerListener(new Listener(calls), owner);
        bus.call(new TestEvent());
        assertEquals(List.of("first", "last"), calls);
        bus.unregisterAll(owner);
        bus.call(new TestEvent());
        assertEquals(List.of("first", "last"), calls);
        bus.registerListener(new Object() {
            @EventHandler public void failed(TestEvent event) { throw new IllegalStateException("listener failure"); }
        });
        RuntimeException failure = assertThrows(RuntimeException.class, () -> bus.call(new TestEvent()));
        assertEquals("listener failure", failure.getCause().getMessage());
    }

    private static final class TestEvent extends Event implements Cancellable {
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
    }
    private record Listener(List<String> calls) {
        @EventHandler(priority = EventPriority.LOWEST)
        public void first(TestEvent event) { calls.add("first"); event.setCancelled(true); }
        @EventHandler(ignoreCancelled = true)
        public void skipped(TestEvent event) { fail("Cancelled event reached an ignoring listener"); }
        @EventHandler(priority = EventPriority.HIGHEST)
        public int last(TestEvent event) { calls.add("last"); return 1; }
    }
}
