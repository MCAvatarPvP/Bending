package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;

/**
 * Loader-neutral ownership boundary for ability-created falling-block entities.
 *
 * <p>A semantic ordinal is reserved before spawn, then completed after the
 * platform assigns an entity id. This lets the owner suppress either packet
 * order without ever proximity-matching another player's falling block.</p>
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

    public static int prepare(final CoreAbility ability, final Location location,
                              final BlockData blockData) {
        final Listener current = listener;
        if (current == null || ability == null || location == null || blockData == null) return 0;
        try {
            return current.beforeSpawn(ability, location, blockData);
        } catch (final RuntimeException failure) {
            ProjectKorra.log.warning("TempFallingBlock prepare publication failed: " + failure.getMessage());
            return 0;
        }
    }

    public static void publish(final CoreAbility ability, final FallingBlock fallingBlock,
                               final int spawnOrdinal) {
        final Listener current = listener;
        if (current == null || ability == null || fallingBlock == null || spawnOrdinal <= 0) return;
        try {
            current.onSpawn(ability, fallingBlock, spawnOrdinal);
        } catch (final RuntimeException failure) {
            ProjectKorra.log.warning("TempFallingBlock ownership publication failed: " + failure.getMessage());
        }
    }

    public interface Listener {
        int beforeSpawn(CoreAbility ability, Location location, BlockData blockData);

        void onSpawn(CoreAbility ability, FallingBlock fallingBlock, int spawnOrdinal);
    }
}
