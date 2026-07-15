package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.Locale;
import java.util.HashMap;
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
    private static final ThreadLocal<WorldMutation> WORLD_MUTATION = new ThreadLocal<>();

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
            try {
                current.onChange(change(operation, layer, effectiveData, packetExpected));
            } catch (RuntimeException failure) {
                ProjectKorra.log.warning("TempBlock lifecycle publication failed: " + failure.getMessage());
            }
        }
    }

    /**
     * Announces the coordinate fence before the physical world write. This is
     * deliberately separate from {@link #publish}: metadata-only layer changes
     * and snapshots do not imply that a vanilla packet will exist.
     */
    public static void beforeWorldChange(final Operation operation, final TempBlock layer,
                                         final BlockData effectiveData) {
        final Listener current = listener;
        if (current != null && layer != null && effectiveData != null) {
            try {
                current.beforeWorldChange(change(operation, layer, effectiveData, true));
            } catch (RuntimeException failure) {
                ProjectKorra.log.warning("TempBlock pre-mutation publication failed: " + failure.getMessage());
            }
        }
    }

    /**
     * Marks a physical world write as belonging to a TempBlock lifecycle. The
     * predicting Fabric adapter uses this boundary to write directly to its
     * client TempBlock layer instead of enrolling the change in the generic
     * authoritative rollback ledger.
     */
    public static void runWorldMutation(final Operation operation, final TempBlock layer,
                                        final BlockData effectiveData, final Runnable mutation) {
        if (mutation == null) return;
        final WorldMutation previous = WORLD_MUTATION.get();
        WORLD_MUTATION.set(new WorldMutation(operation, layer == null ? 0L : layer.getLayerId(),
                layer == null ? 0L : layer.getRevision(), effectiveData == null ? null : effectiveData.clone()));
        try {
            mutation.run();
        } finally {
            if (previous == null) WORLD_MUTATION.remove();
            else WORLD_MUTATION.set(previous);
        }
    }

    public static WorldMutation currentWorldMutation() {
        return WORLD_MUTATION.get();
    }

    private static Change change(final Operation operation, final TempBlock layer,
                                 final BlockData effectiveData, final boolean packetExpected) {
        final UUID ownerId = layer.getOwnerId().orElse(null);
        final BlockData ownerVisible = ownerId == null
                ? effectiveData : TempBlock.getVisibleData(layer.getBlock(), ownerId);
        final Map<UUID, BlockData> ownerViews = new HashMap<>(
                TempBlock.getOwnerViews(layer.getBlock(), ownerId));
        if (ownerId != null) {
            ownerViews.put(ownerId, ownerVisible == null ? effectiveData.clone() : ownerVisible.clone());
        }
        return new Change(operation, layer.getBlock(), effectiveData.clone(),
                layer.getRevertTime(), layer.getAbility().orElse(null), layer.getLayerId(),
                layer.getRevision(), ownerId,
                ownerVisible == null ? effectiveData.clone() : ownerVisible.clone(),
                Map.copyOf(ownerViews), packetExpected);
    }

    public static String encode(final BlockData data) {
        if (data == null) return "minecraft:air";
        if (data.getClass() == BlockData.class && data.getExactState() != null) {
            return data.getExactState();
        }
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
        } else if (data instanceof Snow snow) {
            encoded.append(";layers=").append(snow.getLayers());
        }
        return encoded.toString();
    }

    public enum Operation {CREATE, UPDATE_EXPIRY, REVERT, DISCARD}

    public record Change(Operation operation, Block block, BlockData data,
                         long revertAtMillis, CoreAbility ability, long layerId,
                         long revision, UUID ownerId, BlockData ownerVisibleData,
                         Map<UUID, BlockData> ownerViews, boolean packetExpected) {
    }

    public record WorldMutation(Operation operation, long layerId, long revision, BlockData data) {
    }

    @FunctionalInterface
    public interface Listener {
        void onChange(Change change);

        default void beforeWorldChange(final Change change) {
        }
    }
}
