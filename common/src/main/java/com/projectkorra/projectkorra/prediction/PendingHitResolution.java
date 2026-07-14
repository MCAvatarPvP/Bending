package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** A single ordered bundle of damage/velocity effects awaiting defender input. */
public final class PendingHitResolution {
    private final long resolveTick;
    private final Vector contact;
    private final List<Runnable> effects = new ArrayList<>();
    private boolean resolved;

    public PendingHitResolution(final long resolveTick, final Vector contact) {
        this.resolveTick = resolveTick;
        this.contact = contact == null ? null : contact.clone();
    }

    public boolean add(final Runnable commit) {
        if (resolved || commit == null) return false;
        effects.add(commit);
        return true;
    }

    public Result resolve(final long currentTick, final BoundingBox defenderBox, final double tolerance) {
        if (resolved) return Result.ALREADY_RESOLVED;
        if (currentTick < resolveTick) return Result.WAITING;
        resolved = true;
        if (!ReactionWindow.containsContact(defenderBox, contact, tolerance)) {
            effects.clear();
            return Result.DODGED;
        }
        List<Runnable> commits = List.copyOf(effects);
        effects.clear();
        commits.forEach(Runnable::run);
        return Result.COMMITTED;
    }

    public boolean isResolved() {
        return resolved;
    }

    public enum Result {
        WAITING,
        COMMITTED,
        DODGED,
        ALREADY_RESOLVED
    }
}
