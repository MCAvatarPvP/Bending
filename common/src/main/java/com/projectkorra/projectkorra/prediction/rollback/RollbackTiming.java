package com.projectkorra.projectkorra.prediction.rollback;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** Opt-in JFR measurements for enrollment and frame costs on the owning thread. */
@Name("projectkorra.RollbackPhase")
@Category({"ProjectKorra", "Rollback"})
@Label("Rollback phase")
@Enabled(false)
@StackTrace(false)
public final class RollbackTiming extends Event {
    private static final EventType TYPE = EventType.getEventType(RollbackTiming.class);

    @Label("Phase") public String phase;
    @Label("Elapsed") @Timespan(Timespan.NANOSECONDS) public long elapsed;
    @Label("Checkpoint objects") public int objects;

    public static long start() { return TYPE.isEnabled() ? System.nanoTime() : 0; }

    public static long finish(long start, String phase) { return finish(start, phase, 0); }

    public static long finish(long start, String phase, int objects) {
        if (start == 0) return 0;
        long end = System.nanoTime();
        var event = new RollbackTiming();
        event.phase = phase;
        event.objects = objects;
        event.elapsed = end - start;
        event.commit();
        return end;
    }
}
