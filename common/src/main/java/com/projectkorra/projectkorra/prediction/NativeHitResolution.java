package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.UUID;

/** A server-observed hit used when no exact client hit claim is available. */
public final class NativeHitResolution {
    private final CoreAbility ability;
    private final UUID target;
    private final PendingHitResolution pending;

    public NativeHitResolution(final CoreAbility ability, final UUID target,
                               final long hitTick, final long resolveTick,
                               final Vector contact) {
        this.ability = ability;
        this.target = target;
        this.pending = PendingHitResolution.forAbility(resolveTick, ability, contact);
    }

    /** All streams from one ability/target share one unresolved reaction decision. */
    public boolean matches(final CoreAbility candidate, final UUID targetId) {
        return this.ability == candidate && this.target.equals(targetId)
                && !this.pending.isResolved();
    }

    public boolean add(final Runnable commit) {
        return this.pending.add(commit);
    }

    public boolean add(final HitResolutionSync.Effect effect, final Runnable commit) {
        return this.pending.add(effect, commit);
    }

    public PendingHitResolution.Result resolve(final long currentTick, final BoundingBox defenderBox,
                                               final double tolerance) {
        return this.pending.resolve(currentTick, defenderBox, tolerance);
    }

    public UUID target() {
        return this.target;
    }
}
