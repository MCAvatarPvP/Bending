package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Loader-neutral observation point for ability-owned velocity writes.  The
 * velocity itself remains server authoritative and is still sent by vanilla;
 * this metadata lets an exact-prediction client identify its own echo without
 * mistaking unrelated knockback for it.
 */
public final class VelocitySync {
    private static final long DIRECT_CONTACT_CONTINUITY_MILLIS = 150L;
    private static volatile Listener listener;
    private static final ThreadLocal<Integer> COMMIT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Map<Ability, Map<UUID, Long>> CONFIRMED_DIRECT_CONTACTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VelocitySync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) {
            listener = null;
            CONFIRMED_DIRECT_CONTACTS.clear();
            COMMIT_DEPTH.remove();
        }
    }

    public static void publish(final Ability ability, final Entity target, final Vector velocity) {
        final Listener current = listener;
        if (current != null && ability != null && target != null && velocity != null) {
            current.onVelocity(ability, target, velocity.clone());
        }
    }

    /** Prevents platform interception from deferring a standard velocity twice. */
    public static void commit(final Runnable write) {
        if (write == null) return;
        final int previous = COMMIT_DEPTH.get();
        COMMIT_DEPTH.set(previous + 1);
        try {
            write.run();
        } finally {
            if (previous == 0) COMMIT_DEPTH.remove();
            else COMMIT_DEPTH.set(previous);
        }
    }

    /**
     * Captures legacy/addon velocity writes which bypass GeneralMethods. The
     * first contact shares the reaction decision; sustained writes stay smooth
     * while they continue at least once every three ticks.
     */
    public static void applyDirect(final Ability ability, final Entity target,
                                   final Vector velocity, final Runnable write) {
        if (write == null) return;
        if (COMMIT_DEPTH.get() > 0 || ability == null || target == null || velocity == null
                || ability.getPlayer() == null
                || ability.getPlayer().getUniqueId().equals(target.getUniqueId())) {
            write.run();
            return;
        }

        final long now = System.currentTimeMillis();
        final Map<UUID, Long> targets = CONFIRMED_DIRECT_CONTACTS.computeIfAbsent(
                ability, ignored -> new HashMap<>());
        final Long confirmedUntil = targets.get(target.getUniqueId());
        final Runnable commit = () -> {
            targets.put(target.getUniqueId(), System.currentTimeMillis() + DIRECT_CONTACT_CONTINUITY_MILLIS);
            publish(ability, target, velocity);
            commit(write);
        };
        if (confirmedUntil != null && confirmedUntil >= now) {
            commit.run();
            return;
        }
        if (!HitResolutionSync.defer(HitResolutionSync.Effect.VELOCITY, ability, target, commit)) {
            commit.run();
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onVelocity(Ability ability, Entity target, Vector velocity);
    }
}
