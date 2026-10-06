package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKMaterials;
import com.projectkorra.projectkorra.platform.mc.Material;
import java.io.*;
import java.util.*;

/** Immutable server material properties, shared by every replica without live registry reads. */
public record RollbackMaterials(Set<Material> solid) implements PKMaterials, RollbackStateCell<Void> {
    public static final int VERSION = 1, MAXIMUM_BYTES = 65_536;
    public RollbackMaterials { solid = Set.copyOf(solid); }
    public static RollbackMaterials capture(PKMaterials source) {
        if (RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Capture material properties before replay");
        Objects.requireNonNull(source);
        var solid = EnumSet.noneOf(Material.class);
        for (var material : Material.values()) if (source.isSolid(material)) solid.add(material);
        return new RollbackMaterials(solid);
    }
    @Override public boolean isSolid(Material material) { return solid.contains(Objects.requireNonNull(material)); }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeInt(Material.values().length);
            for (var material : Material.values()) { out.writeUTF(material.name()); out.writeBoolean(isSolid(material)); }
            if (bytes.size() > MAXIMUM_BYTES) throw new IllegalArgumentException("Material property budget");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static RollbackMaterials decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Material property budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION || in.readInt() != Material.values().length)
                throw new IllegalArgumentException("Incompatible material properties");
            var solid = EnumSet.noneOf(Material.class);
            for (var material : Material.values()) {
                if (!in.readUTF().equals(material.name())) throw new IllegalArgumentException("Material property names/order");
                int flag = in.readUnsignedByte();
                if (flag > 1) throw new IllegalArgumentException("Material property flag");
                if (flag == 1) solid.add(material);
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing material properties");
            return new RollbackMaterials(solid);
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated material properties", malformed); }
    }
}
