package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKEventBus;
import java.util.List;

/** Capture this root with abilities so shared listener state retains its identity on import. */
public final class RollbackEventBindings {
    private final List<PKEventBus.Registration> registrations;
    public RollbackEventBindings(List<PKEventBus.Registration> registrations) { this.registrations = List.copyOf(registrations); }
    public List<PKEventBus.Registration> registrations() { return registrations; }
    public static RollbackEventBindings capture(PKEventBus source) {
        return new RollbackEventBindings(source.commonRegistrations());
    }
    public void install(RollbackEventBus target) { target.importRegistrations(registrations); }
}
