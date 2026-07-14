package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;

import java.util.Locale;

/**
 * Loader-neutral observation point for temporary-block visual metadata. The
 * actual block update remains server authoritative and travels over vanilla.
 */
public final class TempBlockSync {
    private static volatile Listener listener;

    private TempBlockSync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) listener = null;
    }

    public static void publish(final Operation operation, final Block block, final BlockData data,
                               final long revertAtMillis, final CoreAbility ability) {
        final Listener current = listener;
        if (current != null && block != null && data != null) {
            current.onChange(operation, block, data.clone(), revertAtMillis, ability);
        }
    }

    public static String encode(final BlockData data) {
        if (data == null) return "minecraft:air";
        StringBuilder encoded = new StringBuilder("minecraft:")
                .append(data.getMaterial().name().toLowerCase(Locale.ROOT));
        if (data instanceof Levelled levelled) {
            encoded.append(";level=").append(levelled.getLevel());
            encoded.append(";waterlogged=").append(levelled.isWaterlogged() ? '1' : '0');
        } else if (data instanceof Fire fire) {
            encoded.append(";faces=");
            boolean first = true;
            for (BlockFace face : fire.getFaces()) {
                if (!first) encoded.append(',');
                encoded.append(face.name());
                first = false;
            }
        }
        return encoded.toString();
    }

    public enum Operation {CREATE, UPDATE_EXPIRY, REVERT}

    @FunctionalInterface
    public interface Listener {
        void onChange(Operation operation, Block block, BlockData data, long revertAtMillis, CoreAbility ability);
    }
}
