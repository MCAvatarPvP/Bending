package com.projectkorra.projectkorra.prediction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Loader-neutral ledger for server TempBlock metadata received by a predicting
 * client. The ledger deliberately contains no world mutation API: metadata can
 * decide whether vanilla server block traffic is hidden, but the client's real
 * {@code TempBlock} instances remain the only source of visual block changes.
 *
 * @param <K> coordinate key type
 * @param <S> client block-state type
 */
public final class ClientTempBlockLedger<K, S> {
    private static final int MAX_REVISION_TOMBSTONES = 16_384;
    private final Map<K, Coordinate<S>> coordinates = new LinkedHashMap<>();
    private final LinkedHashMap<LayerKey<K>, Long> revisions = new LinkedHashMap<>();

    /**
     * Applies one ordered lifecycle operation.
     *
     * @return {@code true} when the operation advanced the ledger
     */
    public boolean apply(final K key, final TempBlockSync.Operation operation,
                         final long layerId, final long revision, final UUID ownerId,
                         final S physicalState, final S viewerState) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");

        final LayerKey<K> revisionKey = new LayerKey<>(key, layerId);
        final long previousRevision = this.revisions.getOrDefault(revisionKey, Long.MIN_VALUE);
        if (revision <= previousRevision) return false;
        this.revisions.put(revisionKey, revision);
        while (this.revisions.size() > MAX_REVISION_TOMBSTONES) {
            this.revisions.remove(this.revisions.keySet().iterator().next());
        }

        Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null) {
            // A pre-mutation REVERT can legitimately be repeated by the normal
            // post-mutation publication. Unknown reverts have no state to close.
            if (closesLayer(operation)) return true;
            coordinate = new Coordinate<>();
            this.coordinates.put(key, coordinate);
        }
        if (viewerState != null) coordinate.viewerState = viewerState;

        if (closesLayer(operation)) {
            coordinate.layers.remove(layerId);
            if (coordinate.layers.isEmpty()) this.coordinates.remove(key);
            return true;
        }

        final Layer<S> previous = coordinate.layers.get(layerId);
        final S state = physicalState != null ? physicalState : previous == null ? null : previous.state;
        coordinate.layers.put(layerId, new Layer<>(ownerId, state));
        return true;
    }

    /** Returns whether this coordinate contains any physical layer owned by the viewer. */
    public boolean hidesServerWorld(final K key, final UUID viewerId) {
        if (viewerId == null) return false;
        final Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null) return false;
        for (Layer<S> layer : coordinate.layers.values()) {
            if (viewerId.equals(layer.ownerId)) return true;
        }
        return false;
    }

    /** Last server-computed state visible beneath layers hidden from this client. */
    public Optional<S> viewerState(final K key) {
        final Coordinate<S> coordinate = this.coordinates.get(key);
        return coordinate == null ? Optional.empty() : Optional.ofNullable(coordinate.viewerState);
    }

    /**
     * Returns the server-computed state that must render above a viewer's
     * locally predicted layer. This occurs when a newer server-only layer (or
     * another player's predicted layer) occupies the same coordinate.
     */
    public Optional<S> overlayState(final K key, final UUID viewerId) {
        if (viewerId == null) return Optional.empty();
        final Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null || coordinate.layers.isEmpty() || coordinate.viewerState == null) {
            return Optional.empty();
        }
        boolean ownsLayer = false;
        for (Layer<S> layer : coordinate.layers.values()) {
            if (viewerId.equals(layer.ownerId)) ownsLayer = true;
        }
        final Layer<S> top = coordinate.layers.values().stream().reduce((first, second) -> second).orElse(null);
        return ownsLayer && top != null && !viewerId.equals(top.ownerId)
                ? Optional.of(coordinate.viewerState) : Optional.empty();
    }

    public Map<K, S> hiddenViewerStates(final UUID viewerId) {
        if (viewerId == null || this.coordinates.isEmpty()) return Map.of();
        final Map<K, S> result = new LinkedHashMap<>();
        this.coordinates.forEach((key, coordinate) -> {
            if (coordinate.viewerState != null && hidesServerWorld(key, viewerId)) {
                result.put(key, coordinate.viewerState);
            }
        });
        return Map.copyOf(result);
    }

    public int coordinateCount() {
        return this.coordinates.size();
    }

    public void clear() {
        this.coordinates.clear();
        this.revisions.clear();
    }

    private static boolean closesLayer(final TempBlockSync.Operation operation) {
        return operation == TempBlockSync.Operation.REVERT
                || operation == TempBlockSync.Operation.DISCARD;
    }

    private static final class Coordinate<S> {
        private final LinkedHashMap<Long, Layer<S>> layers = new LinkedHashMap<>();
        private S viewerState;
    }

    private record LayerKey<K>(K coordinate, long layerId) {
    }

    private record Layer<S>(UUID ownerId, S state) {
    }
}
