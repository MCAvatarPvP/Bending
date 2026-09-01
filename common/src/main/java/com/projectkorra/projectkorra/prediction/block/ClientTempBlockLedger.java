package com.projectkorra.projectkorra.prediction.block;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Loader-neutral ledger for server TempBlock metadata received by a predicting
 * client. The ledger deliberately contains no world mutation API. The Fabric
 * server endpoint emits an owner only for an authenticated exact client that
 * supports the layer's ability. Owner identity gates semantic pairing, while
 * the exact action + effect ordinal proves that a corresponding local layer
 * exists. Until that proof arrives, Paper's physical layer remains visible so
 * a prediction disagreement cannot become ghost air. This ledger supplies
 * ordered physical/underlay states for each coordinate.
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
        return apply(key, operation, 0L, layerId, revision, ownerId, physicalState, viewerState);
    }

    /**
     * Applies one ordered lifecycle operation and retains the input action
     * which produced the layer. Client and server layer ids are process-local,
     * so action + coordinate is the stable cross-process confirmation key.
     */
    public boolean apply(final K key, final TempBlockSync.Operation operation,
                         final long actionSequence, final long layerId, final long revision,
                         final UUID ownerId, final S physicalState, final S viewerState) {
        return applyOrdered(key, operation, actionSequence, layerId, revision,
                ownerId, physicalState, viewerState, false);
    }

    /**
     * Applies an active layer from a completely framed snapshot. Snapshot IDs
     * and stream ordering are validated by the caller, so an equal revision may
     * authoritatively reopen a layer previously pruned only because it left the
     * viewer's snapshot radius. Older revisions are still rejected.
     */
    public boolean applySnapshot(final K key, final long actionSequence,
                                 final long layerId, final long revision,
                                 final UUID ownerId, final S physicalState,
                                 final S viewerState) {
        return applyOrdered(key, TempBlockSync.Operation.CREATE, actionSequence,
                layerId, revision, ownerId, physicalState, viewerState, true);
    }

    private boolean applyOrdered(final K key, final TempBlockSync.Operation operation,
                                 final long actionSequence, final long layerId,
                                 final long revision, final UUID ownerId,
                                 final S physicalState, final S viewerState,
                                 final boolean committedSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");

        final LayerKey<K> revisionKey = new LayerKey<>(key, layerId);
        final long previousRevision = this.revisions.getOrDefault(revisionKey, Long.MIN_VALUE);
        if (revision < previousRevision
                || revision == previousRevision && !committedSnapshot) return false;
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
        final long effectiveAction = actionSequence > 0L
                ? actionSequence : previous == null ? 0L : previous.actionSequence;
        // A committed snapshot is an ordered stack description. Reinsert even
        // an equal-revision existing layer so LinkedHashMap order exactly
        // follows the snapshot rather than the client's partial arrival order.
        if (committedSnapshot) coordinate.layers.remove(layerId);
        coordinate.layers.put(layerId, new Layer<>(ownerId, effectiveAction, state));
        return true;
    }

    /**
     * Returns whether this coordinate contains a physical layer attributed to
     * the viewer. The caller still validates the action/session before hiding it.
     */
    public boolean hidesServerWorld(final K key, final UUID viewerId) {
        if (viewerId == null) return false;
        final Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null) return false;
        for (Layer<S> layer : coordinate.layers.values()) {
            if (viewerId.equals(layer.ownerId)) return true;
        }
        return false;
    }

    /**
     * Returns whether authority confirmed that this viewer's prediction for
     * one input produced a physical layer at this exact coordinate.
     */
    public boolean hasOwnedLayerForAction(final K key, final UUID viewerId,
                                          final long actionSequence) {
        if (viewerId == null || actionSequence <= 0L) return false;
        final Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null) return false;
        for (Layer<S> layer : coordinate.layers.values()) {
            if (viewerId.equals(layer.ownerId) && layer.actionSequence == actionSequence) return true;
        }
        return false;
    }

    /** Last server-computed state visible beneath layers hidden from this client. */
    public Optional<S> viewerState(final K key) {
        final Coordinate<S> coordinate = this.coordinates.get(key);
        return coordinate == null ? Optional.empty() : Optional.ofNullable(coordinate.viewerState);
    }

    /** Physical state of the newest active authoritative layer at a coordinate. */
    public Optional<S> physicalState(final K key) {
        final Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null || coordinate.layers.isEmpty()) return Optional.empty();
        final Layer<S> top = coordinate.layers.values().stream()
                .reduce((first, second) -> second).orElse(null);
        return top == null ? Optional.empty() : Optional.ofNullable(top.state);
    }

    /** Stable id of the stack-top layer, following incremental or snapshot order. */
    public OptionalLong topLayerId(final K key) {
        final Coordinate<S> coordinate = this.coordinates.get(key);
        if (coordinate == null || coordinate.layers.isEmpty()) return OptionalLong.empty();
        long top = 0L;
        for (long layerId : coordinate.layers.keySet()) top = layerId;
        return OptionalLong.of(top);
    }

    public boolean containsLayer(final K key, final long layerId) {
        final Coordinate<S> coordinate = this.coordinates.get(key);
        return coordinate != null && coordinate.layers.containsKey(layerId);
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

    /**
     * Commits the layer membership from a complete authoritative snapshot.
     * Active layers omitted by the snapshot are retired, and every coordinate
     * whose stack changed is returned so a caller can recompute its visual
     * state. Revision entries are deliberately retained as tombstones: a
     * delayed operation at the pruned layer's last revision (or older) cannot
     * resurrect it, while a genuinely newer operation may open it again.
     *
     * @param snapshotLayerIds every layer id present in the committed snapshot
     * @return immutable set of coordinates from which at least one layer was removed
     */
    public Set<K> pruneAbsentFromSnapshot(final Set<Long> snapshotLayerIds) {
        Objects.requireNonNull(snapshotLayerIds, "snapshotLayerIds");
        if (this.coordinates.isEmpty()) return Set.of();

        final Set<K> affected = new LinkedHashSet<>();
        this.coordinates.entrySet().removeIf(entry -> {
            final Coordinate<S> coordinate = entry.getValue();
            final int previousSize = coordinate.layers.size();
            coordinate.layers.keySet().removeIf(layerId -> !snapshotLayerIds.contains(layerId));
            if (coordinate.layers.size() == previousSize) return false;
            affected.add(entry.getKey());
            return coordinate.layers.isEmpty();
        });
        return Set.copyOf(affected);
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

    private record Layer<S>(UUID ownerId, long actionSequence, S state) {
    }
}
