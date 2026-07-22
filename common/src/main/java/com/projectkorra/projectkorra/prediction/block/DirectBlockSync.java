package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.util.Information;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Causal metadata for ordinary block writes which are part of an earth
 * movement animation. TempBlocks have their own layered lifecycle and never
 * enter this channel; permanent writes from other elements remain wholly
 * server authoritative.
 */
public final class DirectBlockSync {
    public interface Listener {
        /**
         * Records one semantic common-code write attempt. {@code packetExpected}
         * is false when the platform's complete state already equals the
         * replacement and Minecraft therefore will not emit a block update.
         */
        void beforeChange(CoreAbility ability, Block block, BlockData replacement,
                          boolean packetExpected);
    }

    private static volatile Listener listener;
    private static final ThreadLocal<EarthLifecycle> EARTH_LIFECYCLE = new ThreadLocal<>();

    private DirectBlockSync() {
    }

    public static void install(final Listener next) {
        listener = next;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) listener = null;
    }

    public static void beforeWorldChange(final Block block, final BlockData replacement) {
        if (block == null || replacement == null || TempBlockSync.currentWorldMutation() != null) return;
        final Listener current = listener;
        if (current == null) return;
        // The semantic ordinal must advance for every common-code call on both
        // loaders, even if only one side currently sees the call as a no-op.
        // Otherwise the first Fabric/Paper state difference permanently shifts
        // all later Earth writes onto the wrong receipts. A no-op still emits
        // no receipt because no vanilla block packet will follow it.
        final boolean packetExpected = !TempBlockSync.encode(block.getBlockData())
                .equals(TempBlockSync.encode(replacement));
        current.beforeChange(AbilityExecutionContext.current(), block, replacement, packetExpected);
    }

    /**
     * Restores the causal identity which a moved-earth Information entry keeps
     * after its creating CoreAbility has ended. RevertChecker and temp-air
     * drains run outside AbilityExecutionContext on both loaders.
     */
    public static void runEarthLifecycle(final Information information, final Runnable task) {
        callEarthLifecycle(information, () -> {
            task.run();
            return null;
        });
    }

    public static <T> T callEarthLifecycle(final Information information, final Supplier<T> task) {
        if (task == null) return null;
        final EarthLifecycle previous = EARTH_LIFECYCLE.get();
        final EarthLifecycle next = information == null ? null : new EarthLifecycle(
                information.getPredictionOwner(), information.getPredictionActionSequence(),
                information.getPredictionAbility());
        if (next != null && next.valid()) EARTH_LIFECYCLE.set(next);
        try {
            return task.get();
        } finally {
            if (previous == null) EARTH_LIFECYCLE.remove();
            else EARTH_LIFECYCLE.set(previous);
        }
    }

    public static EarthLifecycle currentEarthLifecycle() {
        return EARTH_LIFECYCLE.get();
    }

    /** All EarthAbility subclasses, including addons, share the same policy. */
    public static boolean isPredictable(final CoreAbility ability, final String fallbackAbilityName) {
        if (ability instanceof EarthAbility) return true;
        if (fallbackAbilityName == null || fallbackAbilityName.isBlank()) return false;
        return CoreAbility.getAbility(fallbackAbilityName) instanceof EarthAbility;
    }

    public record EarthLifecycle(UUID ownerId, long actionSequence, String ability) {
        public boolean valid() {
            return ownerId != null && actionSequence > 0L && ability != null && !ability.isBlank();
        }
    }
}
