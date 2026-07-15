package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.ability.CoreAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A single ordered bundle of damage/velocity effects awaiting defender input. */
public final class PendingHitResolution {
    private final long resolveTick;
    private final HitGeometry initialGeometry;
    private final HitGeometry collisionContact;
    private final Supplier<HitGeometry> liveGeometry;
    private final List<Runnable> effects = new ArrayList<>();
    private final Map<HitResolutionSync.Effect, Runnable> streamedEffects = new LinkedHashMap<>();
    private boolean resolved;

    public PendingHitResolution(final long resolveTick, final Vector contact) {
        this(resolveTick, HitGeometry.point(contact), null);
    }

    public PendingHitResolution(final long resolveTick, final HitGeometry initialGeometry,
                                final Supplier<HitGeometry> liveGeometry) {
        this(resolveTick, initialGeometry, HitGeometry.point(null), liveGeometry);
    }

    PendingHitResolution(final long resolveTick, final HitGeometry initialGeometry,
                         final HitGeometry collisionContact,
                         final Supplier<HitGeometry> liveGeometry) {
        this.resolveTick = resolveTick;
        this.initialGeometry = initialGeometry == null
                ? HitGeometry.point(null) : initialGeometry;
        this.collisionContact = collisionContact == null
                ? HitGeometry.point(null) : collisionContact;
        this.liveGeometry = liveGeometry;
    }

    public static PendingHitResolution forAbility(final long resolveTick, final CoreAbility ability,
                                                  final Vector fallbackContact) {
        final HitGeometry initial = HitGeometry.capture(ability, fallbackContact);
        return new PendingHitResolution(resolveTick, initial, HitGeometry.point(fallbackContact),
                () -> HitGeometry.capture(ability, fallbackContact));
    }

    public boolean add(final Runnable commit) {
        if (resolved || commit == null) return false;
        effects.add(commit);
        return true;
    }

    /** One pending slot per effect prevents streamed contacts stacking bursts. */
    public boolean add(final HitResolutionSync.Effect effect, final Runnable commit) {
        if (resolved || effect == null || commit == null) return false;
        // One collision may legitimately apply several distinct potion/fire
        // changes. Unlike streamed damage or velocity, these must not replace
        // one another inside the pending bundle.
        if (effect == HitResolutionSync.Effect.STATUS) {
            effects.add(commit);
            return true;
        }
        streamedEffects.put(effect, commit);
        return true;
    }

    public Result resolve(final long currentTick, final BoundingBox defenderBox, final double tolerance) {
        if (resolved) return Result.ALREADY_RESOLVED;
        if (currentTick < resolveTick) return Result.WAITING;
        resolved = true;
        HitGeometry currentGeometry = null;
        if (liveGeometry != null) {
            try {
                currentGeometry = liveGeometry.get();
            } catch (final RuntimeException ignored) {
                // The collision-time geometry remains authoritative below.
            }
        }
        // A projectile can move beyond the defender during the reaction wait.
        // Never replace the shape which actually produced the hit. Live
        // geometry is an additional acceptance path for moving/multi-stream
        // abilities, not a reason to erase a valid point-blank collision.
        final boolean intersectsInitial = ReactionWindow.intersects(defenderBox, initialGeometry, tolerance);
        // The exact collision-time defender center is retained independently
        // at zero radius. Broken/transient ability geometry can no longer turn
        // a stationary authoritative contact into a ghost miss.
        final boolean intersectsContact = ReactionWindow.intersects(defenderBox, collisionContact, tolerance);
        final boolean intersectsCurrent = currentGeometry != null && !currentGeometry.isEmpty()
                && ReactionWindow.intersects(defenderBox, currentGeometry, tolerance);
        if (!intersectsInitial && !intersectsContact && !intersectsCurrent) {
            effects.clear();
            streamedEffects.clear();
            return Result.DODGED;
        }
        List<Runnable> commits = List.copyOf(effects);
        List<Runnable> streamedCommits = List.copyOf(streamedEffects.values());
        effects.clear();
        streamedEffects.clear();
        commits.forEach(Runnable::run);
        streamedCommits.forEach(Runnable::run);
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
