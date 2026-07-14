package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Loader-neutral observation point for temporary-block ownership metadata.
 * Every operation identifies one stable layer. This lets clients acknowledge
 * their own predicted layer without mistaking a later overlapping layer for
 * the same block mutation.
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

    public static void publish(final Operation operation, final TempBlock layer, final BlockData effectiveData) {
        publish(operation, layer, effectiveData, false);
    }

    public static void publish(final Operation operation, final TempBlock layer, final BlockData effectiveData,
                               final boolean packetExpected) {
        final Listener current = listener;
        if (current != null && layer != null && effectiveData != null) {
            current.onChange(change(operation, layer, effectiveData, packetExpected));
        }
    }

    /**
     * Arms packet-bound reconciliation before the physical world write. This
     * is deliberately separate from {@link #publish}: metadata-only layer
     * changes and snapshots must never manufacture a vanilla packet receipt.
     */
    public static void beforeWorldChange(final Operation operation, final TempBlock layer,
                                         final BlockData effectiveData) {
        final Listener current = listener;
        if (current != null && layer != null && effectiveData != null) {
            current.beforeWorldChange(change(operation, layer, effectiveData, true));
        }
    }

    private static Change change(final Operation operation, final TempBlock layer,
                                 final BlockData effectiveData, final boolean packetExpected) {
        final UUID ownerId = layer.getOwnerId().orElse(null);
        final BlockData ownerVisible = ownerId == null
                ? effectiveData : TempBlock.getVisibleData(layer.getBlock(), ownerId);
        return new Change(operation, layer.getBlock(), effectiveData.clone(),
                layer.getRevertTime(), layer.getAbility().orElse(null), layer.getLayerId(),
                layer.getRevision(), ownerId,
                ownerVisible == null ? effectiveData.clone() : ownerVisible.clone(),
                TempBlock.getOwnerViews(layer.getBlock(), ownerId), packetExpected);
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

    public record Change(Operation operation, Block block, BlockData data,
                         long revertAtMillis, CoreAbility ability, long layerId,
                         long revision, UUID ownerId, BlockData ownerVisibleData,
                         Map<UUID, BlockData> ownerViews, boolean packetExpected) {
    }

    @FunctionalInterface
    public interface Listener {
        void onChange(Change change);

        default void beforeWorldChange(final Change change) {
        }
    }
}
