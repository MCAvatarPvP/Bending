package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKServer;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import java.util.*;
import java.io.*;

/** Server facade with captured metadata and explicit private plugin/block-data bindings. */
public final class RollbackServer implements PKServer, RollbackStateCell<Void> {
    public record Metadata(String version, String minecraftVersion, String name, boolean onlineMode, int viewDistance) {
        public static final int MAXIMUM_BYTES = 16384;
        private static final int VERSION = 1;
        public Metadata {
            for (var value : List.of(version, minecraftVersion, name))
                if (value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Server metadata text");
            if (viewDistance < 0 || viewDistance > 1024) throw new IllegalArgumentException("Server view distance");
        }
        public byte[] encode() {
            try {
                var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
                out.writeInt(VERSION); out.writeUTF(version); out.writeUTF(minecraftVersion); out.writeUTF(name);
                out.writeBoolean(onlineMode); out.writeInt(viewDistance);
                if (bytes.size() > MAXIMUM_BYTES) throw new IllegalArgumentException("Server metadata byte budget");
                return bytes.toByteArray();
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        }
        public static Metadata decode(byte[] bytes) {
            if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Server metadata byte budget");
            try {
                var in = new DataInputStream(new ByteArrayInputStream(bytes));
                if (in.readInt() != VERSION) throw new IllegalArgumentException("Server metadata version");
                String version = in.readUTF(), minecraft = in.readUTF(), name = in.readUTF();
                int flag = in.readUnsignedByte(); if (flag > 1) throw new IllegalArgumentException("Server metadata boolean");
                var value = new Metadata(version, minecraft, name, flag == 1, in.readInt());
                if (in.available() != 0) throw new IllegalArgumentException("Trailing server metadata");
                return value;
            } catch (IOException failure) { throw new IllegalArgumentException("Malformed server metadata", failure); }
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
