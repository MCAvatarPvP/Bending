package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKTags;
import com.projectkorra.projectkorra.platform.mc.Material;
import java.io.*;
import java.util.*;

/** Complete captured Minecraft block-tag catalog used by the existing platform facade. */
public record RollbackTags(Map<String, List<Material>> blocks) implements PKTags, RollbackStateCell<Void> {
    public static final int MAXIMUM_BYTES = 4_194_304;
    private static final int VERSION = 1, MAXIMUM_TAGS = 8192, MAXIMUM_ENTRIES = 131072;
    public RollbackTags {
        if (blocks.size() > MAXIMUM_TAGS) throw new IllegalArgumentException("Block tag count");
        var copy = new TreeMap<String, List<Material>>(); int entries = 0;
        for (var entry : blocks.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > 256 || !key.matches("[a-z0-9/._-]+")) throw new IllegalArgumentException("Block tag key");
            var members = new TreeSet<Material>(Comparator.comparing(Material::name));
            for (var material : entry.getValue()) if (material != Material.AIR) members.add(Objects.requireNonNull(material));
            entries = Math.addExact(entries, members.size());
            if (entries > MAXIMUM_ENTRIES) throw new IllegalArgumentException("Block tag entries");
            copy.put(key, List.copyOf(members));
        }
        blocks = Collections.unmodifiableMap(copy);
    }
    @Override public <T> Collection<T> values(String registry, String key, Class<T> type) {
        if (!"blocks".equalsIgnoreCase(registry) || type != Material.class) return List.of();
        return blocks.getOrDefault(key, List.of()).stream().map(type::cast).toList();
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeInt(blocks.size());
            for (var entry : blocks.entrySet()) {
                out.writeUTF(entry.getKey()); out.writeInt(entry.getValue().size());
                for (var material : entry.getValue()) out.writeUTF(material.name());
                if (bytes.size() > MAXIMUM_BYTES) throw new IllegalArgumentException("Block tag byte budget");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static RollbackTags decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Block tag byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Block tag version");
            int count = in.readInt(), total = 0; String previous = "";
            if (count < 0 || count > MAXIMUM_TAGS) throw new IllegalArgumentException("Block tag count");
            var tags = new TreeMap<String, List<Material>>();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF(); if (key.compareTo(previous) <= 0) throw new IllegalArgumentException("Block tag order"); previous = key;
                int size = in.readInt(); if (size < 0 || size > MAXIMUM_ENTRIES - total) throw new IllegalArgumentException("Block tag entries"); total += size;
                var values = new ArrayList<Material>(); String prior = "";
                for (int j = 0; j < size; j++) {
                    String name = in.readUTF(); if (name.compareTo(prior) <= 0) throw new IllegalArgumentException("Block tag member order"); prior = name;
                    var material = Material.valueOf(name); if (material == Material.AIR) throw new IllegalArgumentException("Air is excluded by platform tags"); values.add(material);
                }
                tags.put(key, values);
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing block tags");
            return new RollbackTags(tags);
        } catch (IOException failure) { throw new IllegalArgumentException("Malformed block tags", failure); }
    }
}
