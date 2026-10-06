package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKServer;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import java.util.*;

/** Server facade with captured metadata and explicit private plugin/block-data bindings. */
public final class RollbackServer implements PKServer, RollbackStateCell<Void> {
    public record Metadata(String version, String minecraftVersion, String name, boolean onlineMode, int viewDistance) {
        public Metadata {
            for (var value : List.of(version, minecraftVersion, name))
                if (value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Server metadata text");
            if (viewDistance < 0 || viewDistance > 1024) throw new IllegalArgumentException("Server view distance");
        }
        public static Metadata capture(PKServer server) {
            if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Capture server metadata before replay");
            return new Metadata(server.version(), server.minecraftVersion(), server.name(), server.onlineMode(), server.viewDistance());
        }
    }
    public interface Blocks<S> extends RollbackStateCell<S> {
        /** A fresh detached common facade over the material's native default block state. */
        BlockData create(Material material);
    }
    private final Thread thread = Thread.currentThread();
    private final Metadata metadata;
    private final List<?> plugins;
    private final Blocks<?> blocks;
    /** Plugin entries must be owned/rebound handles, never the live plugin registry. */
    public RollbackServer(Metadata metadata, Collection<?> plugins, Blocks<?> blocks) {
        this.metadata = Objects.requireNonNull(metadata); this.plugins = List.copyOf(plugins); this.blocks = Objects.requireNonNull(blocks);
    }
    @Override public <S> S handle() { check(); throw new UnsupportedOperationException("Private replay has no raw live server handle"); }
    @Override public String version() { check(); return metadata.version(); }
    @Override public String minecraftVersion() { check(); return metadata.minecraftVersion(); }
    @Override public String name() { check(); return metadata.name(); }
    @Override public boolean onlineMode() { check(); return metadata.onlineMode(); }
    @Override public int viewDistance() { check(); return metadata.viewDistance(); }
    @Override @SuppressWarnings("unchecked") public <P> Collection<P> plugins() { check(); return (Collection<P>) plugins; }
    @Override @SuppressWarnings("unchecked") public <D> D createBlockData(Object material) {
        check();
        if (!(material instanceof Material value)) throw new IllegalArgumentException("Private block data requires a common material");
        return (D) Objects.requireNonNull(blocks.create(value));
    }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() { check(); return List.of(plugins, blocks); }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Private server facade crossed threads"); }
}
