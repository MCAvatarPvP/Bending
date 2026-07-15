package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.UUID;

/** A server-observed hit used when no exact client hit claim is available. */
public final class NativeHitResolution {
    private final CoreAbility ability;
    private final UUID target;
    private final long hitTick;
    private final PendingHitResolution pending;

    public NativeHitResolution(final CoreAbility ability, final UUID target,
                               final long hitTick, final long resolveTick,
                               final Vector contact) {
        this.ability = ability;
        this.target = target;
        this.hitTick = hitTick;
        this.pending = new PendingHitResolution(resolveTick, contact);
    }

    /** Damage and velocity from one ability/target/tick share one reaction decision. */
    public boolean matches(final CoreAbility candidate, final UUID targetId, final long tick) {
        return this.ability == candidate && this.hitTick == tick && this.target.equals(targetId)
                && !this.pending.isResolved();
    }

    public boolean add(final Runnable commit) {
        return this.pending.add(commit);
    }

    public PendingHitResolution.Result resolve(final long currentTick, final BoundingBox defenderBox,
                                               final double tolerance) {
        return this.pending.resolve(currentTick, defenderBox, tolerance);
    }

    public UUID target() {
        return this.target;
    }
}
