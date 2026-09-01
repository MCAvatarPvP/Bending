package com.projectkorra.projectkorra.fabric.client.prediction.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Render-only block overrides used by exact client prediction.
 *
 * <p>The vanilla {@link ClientWorld} is deliberately never changed here. It is
 * the authoritative backing store and is allowed to consume every block and
 * chunk packet from Paper. Prediction writes only to these overlays; chunk
 * rebuilds read the composed state through the renderer mixin. Consequently a
 * stale prediction record can at worst be a removable visual override. It can
 * never corrupt the client chunk or turn a later server update into a ghost
 * block.</p>
 *
 * <p>TempBlock visuals sit above direct moved-earth visuals, matching the
 * common TempBlock stack. Both maps are concurrent because terrain meshes are
 * built on worker threads while their lifecycle is advanced on the client
 * thread.</p>
 */
public final class ClientBlockVisualOverlay {
    /**
     * Briefly keeps the foreground copy alive while the normal terrain mesh
     * accepts the authoritative handoff. This is a visual bridge only: the
     * backing ClientWorld remains authoritative throughout.
     */
    private static final long FOREGROUND_HANDOFF_NANOS = 150_000_000L;
    private static final long FOREGROUND_HANDOFF_FAILSAFE_NANOS = 2_000_000_000L;

    public enum Layer {
        DIRECT,
        TEMP
    }

    private final ConcurrentMap<BlockKey, BlockState> direct = new ConcurrentHashMap<>();
    /** State and lower-layer policy are one atomic worker-visible snapshot. */
    private final ConcurrentMap<BlockKey, TempLayer> temp = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockKey, ForegroundVisual> foregroundDirect =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockKey, ForegroundVisual> foregroundTemp =
            new ConcurrentHashMap<>();

    public BlockState compose(final ClientWorld world, final BlockPos pos,
                              final BlockState authoritativeState) {
        if (world == null || pos == null) return authoritativeState;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final TempLayer tempLayer = this.temp.get(key);
        final BlockState directState = this.direct.get(key);
        if (tempLayer != null && (!tempLayer.delegatesToDirect
                || directState == null)) return tempLayer.state;
        return directState == null ? authoritativeState : directState;
    }

    /**
     * State exposed to the terrain compiler. A locally predicted block rendered
     * in the foreground is cut out of the terrain mesh so the two copies cannot
     * z-fight. Server-only and settled overrides continue down the ordinary
     * chunk-mesh path.
     */
    public BlockState composeTerrain(final ClientWorld world, final BlockPos pos,
                                     final BlockState authoritativeState) {
        if (world == null || pos == null) return authoritativeState;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        while (true) {
            final VisibleForeground selected = visibleForeground(key, authoritativeState);
            if (selected == null) break;
            final ForegroundVisual foreground = selected.visual;
            if (foreground.active()) return Blocks.AIR.getDefaultState();
            if (!foreground.awaitingTerrainRead()) break;
            final ForegroundVisual observed = foreground.terrainRead(
                    System.nanoTime() + FOREGROUND_HANDOFF_NANOS);
            if (foregroundMap(selected.layer).replace(key, foreground, observed)) break;
        }
        return compose(world, pos, authoritativeState);
    }

    public void set(final Layer layer, final ClientWorld world, final BlockPos pos,
                    final BlockState state) {
        set(layer, world, pos, state, ForegroundMode.NONE, false);
    }

    /**
     * Conceals a physically-real owner TempBlock while revealing the current
     * DIRECT prediction beneath it. If no DIRECT state exists, Paper's saved
     * viewer underlay is used instead of the physical TempBlock.
     */
    public void setTempUnderlay(final ClientWorld world, final BlockPos pos,
                                final BlockState fallbackState) {
        set(Layer.TEMP, world, pos, fallbackState, ForegroundMode.NONE, true);
    }

    /**
     * Installs an immediate, per-frame local TempBlock visual. Authoritative
     * and server-only layers must use
     * {@link #set(Layer, ClientWorld, BlockPos, BlockState)}.
     */
    public void setImmediateTemp(final ClientWorld world, final BlockPos pos,
                                 final BlockState state) {
        set(Layer.TEMP, world, pos, state, ForegroundMode.ACTIVE, false);
    }

    /** Installs an immediate, per-frame local direct-earth visual. */
    public void setImmediateDirect(final ClientWorld world, final BlockPos pos,
                                   final BlockState state) {
        set(Layer.DIRECT, world, pos, state, ForegroundMode.ACTIVE, false);
    }

    /**
     * Bridges a locally-owned close/restore into the ordinary terrain mesh,
     * including cases where the preceding prediction was air or a fluid and
     * therefore had no drawable foreground model.
     */
    public void beginTempHandoff(final ClientWorld world, final BlockPos pos,
                                 final BlockState state) {
        // A closed TempBlock reveals the current lower layer. Its captured
        // restore is only the fallback when DIRECT has no newer state.
        set(Layer.TEMP, world, pos, state, ForegroundMode.HANDOFF, true);
    }

    private void set(final Layer layer, final ClientWorld world, final BlockPos pos,
                     final BlockState state, final ForegroundMode foregroundMode,
                     final boolean delegatesToDirect) {
        if (layer == null || world == null || pos == null) return;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final BlockState authoritative = world.getBlockState(key.pos);
        final BlockState before = compose(world, key.pos, authoritative);
        final VisibleForeground beforeForeground = visibleForeground(key, authoritative);
        final ConcurrentMap<BlockKey, ForegroundVisual> foreground = foregroundMap(layer);
        if (state == null) {
            removeLayerState(layer, key);
            final ForegroundVisual previous = foreground.get(key);
            final BlockState afterRemoval = compose(world, key.pos, authoritative);
            // An explicit handoff may outlive its logical layer, but only while
            // it is pixel-identical to the newly revealed lower/backing state.
            // ACTIVE projectiles are never detached: moving away must remove
            // their old coordinate immediately.
            if (previous != null && !previous.active()
                    && beforeForeground != null && beforeForeground.layer == layer
                    && previous.state.equals(afterRemoval)) {
                foreground.replace(key, previous, previous.detach());
            } else {
                foreground.remove(key);
            }
        } else {
            putLayerState(layer, key, state, delegatesToDirect);
            if (foregroundMode == ForegroundMode.ACTIVE && supportsForeground(state)) {
                // Equality with ClientWorld is not lifecycle completion. For
                // a moving ability it usually means Paper's delayed duplicate
                // just reached this cell. Keep cutting that real backing block
                // from terrain until the local lifecycle explicitly closes.
                foreground.put(key, ForegroundVisual.active(state));
            } else if (foregroundMode == ForegroundMode.HANDOFF
                    && supportsForeground(state)) {
                foreground.put(key, ForegroundVisual.handoff(
                        state, System.nanoTime()));
            } else {
                // Server/debug states always stay on the ordinary terrain
                // path. Equality with a local BlockState is not ownership.
                foreground.remove(key);
            }
        }
        final BlockState after = compose(world, key.pos, authoritative);
        final VisibleForeground afterForeground = visibleForeground(key, authoritative);
        if (!java.util.Objects.equals(before, after)
                || !java.util.Objects.equals(beforeForeground, afterForeground)) {
            scheduleRebuild(key);
        }
    }

    public void remove(final Layer layer, final ClientWorld world, final BlockPos pos) {
        set(layer, world, pos, null);
    }

    /**
     * Releases a DIRECT visual into a detached handoff only when the real
     * lower state already equals the local visual. Thus an async mesh cannot
     * create a gap, while a divergent server state can never leave a stale
     * predicted block behind.
     */
    public void removeDirectWithHandoff(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final BlockState authoritative = world.getBlockState(key.pos);
        final VisibleForeground visible = visibleForeground(key, authoritative);
        final ForegroundVisual directVisual = foregroundDirect.get(key);
        if (visible != null && visible.layer == Layer.DIRECT
                && directVisual != null && directVisual.active()) {
            final TempLayer tempLayer = temp.get(key);
            final BlockState lower = tempLayer != null ? tempLayer.state : authoritative;
            if (directVisual.state.equals(lower)) {
                foregroundDirect.replace(key, directVisual,
                        ForegroundVisual.handoff(directVisual.state, System.nanoTime()));
            }
        }
        set(Layer.DIRECT, world, pos, null);
    }

    public void clear(final Layer layer) {
        if (layer == null) return;
        final Set<BlockKey> changed = Set.copyOf(layerKeys(layer));
        clearLayerState(layer);
        foregroundMap(layer).clear();
        changed.forEach(this::scheduleRebuild);
    }

    public void clear() {
        final Set<BlockKey> changed = new HashSet<>(this.direct.keySet());
        changed.addAll(this.temp.keySet());
        this.direct.clear();
        this.temp.clear();
        this.foregroundDirect.clear();
        this.foregroundTemp.clear();
        changed.forEach(this::scheduleRebuild);
    }

    public int size(final Layer layer) {
        return layer == null ? 0 : layerKeys(layer).size();
    }

    /** Lock-free fast path for third-party terrain meshers' hot read loops. */
    public boolean isEmpty() {
        return this.direct.isEmpty() && this.temp.isEmpty()
                && this.foregroundDirect.isEmpty() && this.foregroundTemp.isEmpty();
    }

    /** Immutable, world-local data safe to copy during render extraction. */
    public List<VisualBlock> foregroundBlocks(final ClientWorld world) {
        if (world == null || foregroundDirect.isEmpty() && foregroundTemp.isEmpty()) {
            return List.of();
        }
        final long now = System.nanoTime();
        final List<VisualBlock> result = new ArrayList<>();
        final Set<BlockKey> candidates = new HashSet<>(foregroundDirect.keySet());
        candidates.addAll(foregroundTemp.keySet());
        candidates.forEach(key -> {
            if (key.world != world) return;
            final BlockState authoritative = world.getBlockState(key.pos);
            VisibleForeground selected = visibleForeground(key, authoritative);
            if (selected == null) return;
            ForegroundVisual visual = selected.visual;
            if (visual.expired(now)) {
                if (foregroundMap(selected.layer).remove(key, visual)) scheduleRebuild(key);
                return;
            }
            final BlockState current = layerState(selected.layer, key);
            final boolean attached = !visual.detached && visual.state.equals(current);
            final boolean detached = visual.detached
                    && visual.state.equals(compose(world, key.pos, authoritative));
            if ((attached || detached) && supportsForeground(visual.state)) {
                result.add(new VisualBlock(key.pos, visual.state));
            } else if (visual.detached
                    && foregroundMap(selected.layer).remove(key, visual)) {
                // A newer physical/lower-layer state invalidates the bridge
                // immediately. CAS prevents this cleanup from touching a newer
                // prediction installed concurrently at the same coordinate.
                scheduleRebuild(key);
            }
        });
        return List.copyOf(result);
    }

    private BlockState layerState(final Layer layer, final BlockKey key) {
        if (layer == Layer.DIRECT) return direct.get(key);
        final TempLayer tempLayer = temp.get(key);
        return tempLayer == null ? null : tempLayer.state;
    }

    private Set<BlockKey> layerKeys(final Layer layer) {
        return layer == Layer.TEMP ? temp.keySet() : direct.keySet();
    }

    private void putLayerState(final Layer layer, final BlockKey key,
                               final BlockState state,
                               final boolean delegatesToDirect) {
        if (layer == Layer.TEMP) temp.put(key, new TempLayer(state, delegatesToDirect));
        else direct.put(key, state);
    }

    private void removeLayerState(final Layer layer, final BlockKey key) {
        if (layer == Layer.TEMP) temp.remove(key);
        else direct.remove(key);
    }

    private void clearLayerState(final Layer layer) {
        if (layer == Layer.TEMP) temp.clear();
        else direct.clear();
    }

    private ConcurrentMap<BlockKey, ForegroundVisual> foregroundMap(final Layer layer) {
        return layer == Layer.TEMP ? this.foregroundTemp : this.foregroundDirect;
    }

    /**
     * Chooses the drawable foreground with exactly the same TEMP-over-DIRECT
     * priority as {@link #compose(ClientWorld, BlockPos, BlockState)}. A TEMP
     * entry without local foreground provenance intentionally suppresses a
     * DIRECT foreground beneath it; server masks must never leak into this
     * renderer merely because their state equals a local block.
     */
    private VisibleForeground visibleForeground(final BlockKey key,
                                                final BlockState authoritativeState) {
        if (key == null) return null;
        if (temp.containsKey(key)) {
            final TempLayer tempLayer = temp.get(key);
            if (tempLayer == null) return null;
            final ForegroundVisual visual = foregroundTemp.get(key);
            final boolean directIsVisible = tempLayer.delegatesToDirect
                    && direct.containsKey(key);
            if (!directIsVisible && visual != null && !visual.detached
                    && visual.state.equals(tempLayer.state)) {
                return new VisibleForeground(Layer.TEMP, visual);
            }
            if (!tempLayer.delegatesToDirect) return null;
        }
        final ForegroundVisual directVisual = foregroundDirect.get(key);
        if (directVisual != null && !directVisual.detached
                && directVisual.state.equals(direct.get(key))) {
            return new VisibleForeground(Layer.DIRECT, directVisual);
        }

        // Detached bridges no longer own logical state. Select one only if it
        // still exactly matches the state now exposed by the live composition.
        final BlockState composed = compose(key.world, key.pos, authoritativeState);
        final ForegroundVisual tempVisual = foregroundTemp.get(key);
        if (tempVisual != null && tempVisual.detached
                && tempVisual.state.equals(composed)) {
            return new VisibleForeground(Layer.TEMP, tempVisual);
        }
        if (directVisual != null && directVisual.detached
                && directVisual.state.equals(composed)) {
            return new VisibleForeground(Layer.DIRECT, directVisual);
        }
        return null;
    }

    private static boolean supportsForeground(final BlockState state) {
        return state != null && !state.isAir()
                && (state.getRenderType() == BlockRenderType.MODEL
                || !state.getFluidState().isEmpty());
    }

    private enum ForegroundMode {
        NONE,
        ACTIVE,
        HANDOFF
    }

    private void scheduleRebuild(final BlockKey key) {
        if (key == null || key.world == null || key.pos == null) return;
        final MinecraftClient client = MinecraftClient.getInstance();
        final Runnable rebuild = () -> {
            if (client.world != key.world || client.worldRenderer == null) return;
            // Adjacent faces and fluids are part of the same visual transaction.
            client.worldRenderer.scheduleBlockRenders(
                    key.pos.getX() - 1, key.pos.getY() - 1, key.pos.getZ() - 1,
                    key.pos.getX() + 1, key.pos.getY() + 1, key.pos.getZ() + 1);
        };
        if (client.isOnThread()) rebuild.run();
        else client.execute(rebuild);
    }

    private record BlockKey(ClientWorld world, BlockPos pos) {
    }

    /**
     * A concealed Paper TempBlock delegates to DIRECT when present, otherwise
     * its server-supplied viewer state remains the logical fallback.
     */
    private record TempLayer(BlockState state, boolean delegatesToDirect) {
    }

    private record VisibleForeground(Layer layer, ForegroundVisual visual) {
    }

    public record VisualBlock(BlockPos pos, BlockState state) {
        public VisualBlock {
            pos = pos == null ? null : pos.toImmutable();
        }
    }

    private record ForegroundVisual(BlockState state, long handoffDeadlineNanos,
                                    long failsafeDeadlineNanos, boolean detached) {
        private static ForegroundVisual active(final BlockState state) {
            return new ForegroundVisual(state, 0L, 0L, false);
        }

        private static ForegroundVisual handoff(final BlockState state,
                                                final long nowNanos) {
            return new ForegroundVisual(state, Long.MAX_VALUE,
                    nowNanos + FOREGROUND_HANDOFF_FAILSAFE_NANOS, false);
        }

        private boolean active() {
            return handoffDeadlineNanos == 0L;
        }

        private boolean awaitingTerrainRead() {
            return handoffDeadlineNanos == Long.MAX_VALUE;
        }

        private ForegroundVisual terrainRead(final long deadlineNanos) {
            return new ForegroundVisual(state, deadlineNanos,
                    failsafeDeadlineNanos, detached);
        }

        private ForegroundVisual detach() {
            return detached ? this : new ForegroundVisual(state,
                    handoffDeadlineNanos, failsafeDeadlineNanos, true);
        }

        private boolean expired(final long nowNanos) {
            return !active() && (nowNanos >= handoffDeadlineNanos
                    || nowNanos >= failsafeDeadlineNanos);
        }
    }
}
