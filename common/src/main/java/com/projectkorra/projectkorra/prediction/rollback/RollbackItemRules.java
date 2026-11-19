package com.projectkorra.projectkorra.prediction.rollback;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Captured Paper and Spigot item despawn and merge settings. */
public record RollbackItemRules(int despawnTicks, Map<String, Integer> overrides, double mergeRadius,
        boolean horizontalOnly, boolean blockWalls) {
    public static final RollbackItemRules DEFAULT = new RollbackItemRules(6000, Map.of(), 2.5, false, false);
    public RollbackItemRules {
        if (!Double.isFinite(mergeRadius) || mergeRadius < 0 || overrides.size() > 8192)
            throw new IllegalArgumentException("Invalid item merge radius or despawn table");
        overrides = Collections.unmodifiableMap(new TreeMap<>(overrides));
        overrides.forEach((name, ticks) -> {
            if (name.length() > 256 || !name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || ticks == null)
                throw new IllegalArgumentException("Invalid item despawn entry");
        });
    }
    public int despawn(String item) { return overrides.getOrDefault(item, despawnTicks); }
    void write(DataOutput out) throws IOException {
        out.writeInt(despawnTicks);
        out.writeDouble(mergeRadius);
        out.writeBoolean(horizontalOnly);
        out.writeBoolean(blockWalls);
        out.writeShort(overrides.size());
        for (var entry : overrides.entrySet()) {
            out.writeUTF(entry.getKey()); out.writeInt(entry.getValue());
        }
    }
    static RollbackItemRules read(DataInput in) throws IOException {
        int ticks = in.readInt();
        double radius = in.readDouble();
        boolean horizontal = in.readBoolean(), walls = in.readBoolean();
        int count = in.readUnsignedShort();
        if (count > 8192) throw new IllegalArgumentException("Item despawn table exceeds limit");
        var overrides = new TreeMap<String, Integer>();
        for (int i = 0; i < count; i++) {
            if (overrides.putIfAbsent(in.readUTF(), in.readInt()) != null)
                throw new IllegalArgumentException("Duplicate item despawn entry");
        }
        return new RollbackItemRules(ticks, overrides, radius, horizontal, walls);
    }
}
