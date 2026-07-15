package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;

/**
 * Loader-neutral ownership boundary for ability-created falling-block entities.
 *
 * <p>The notification is emitted after the platform has assigned an entity id.
 * Prediction transports can therefore identify the caster's matching server
 * entity exactly instead of guessing from entity type and proximity.</p>
 */
public final class TempFallingBlockSync {
    private static volatile Listener listener;

    private TempFallingBlockSync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) listener = null;
    }

    public static void publish(final CoreAbility ability, final FallingBlock fallingBlock) {
        final Listener current = listener;
        if (current == null || ability == null || fallingBlock == null) return;
        try {
            current.onSpawn(ability, fallingBlock);
        } catch (final RuntimeException failure) {
            ProjectKorra.log.warning("TempFallingBlock ownership publication failed: " + failure.getMessage());
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onSpawn(CoreAbility ability, FallingBlock fallingBlock);
    }
}
