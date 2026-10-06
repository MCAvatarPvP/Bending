package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKPlugins;
import java.io.*;
import java.util.*;

/** Captured plugin lookup semantics with explicitly rebound, checkpointed private handles. */
public final class RollbackPlugins implements PKPlugins, RollbackStateCell<Void> {
    public static final int MAXIMUM_BYTES = 4_194_304;
    public record Entry(String name, boolean enabled) {
        public Entry { RollbackPlugins.name(name); }
    }
    public record Seed(List<Entry> entries, Map<String, String> lookups) {
        public Seed {
            entries = List.copyOf(entries); lookups = Collections.unmodifiableMap(new TreeMap<>(lookups));
            if (entries.size() > 4096 || lookups.size() > 16384) throw new IllegalArgumentException("Plugin catalog budget");
            var names = new HashSet<String>(); var normalized = new HashSet<String>();
            for (var entry : entries) {
                if (!names.add(entry.name()) || !normalized.add(key(entry.name()))) throw new IllegalArgumentException("Duplicate plugin identity");
                if (!entry.name().equals(lookups.get(key(entry.name())))) throw new IllegalArgumentException("Missing canonical plugin lookup");
            }
            lookups.forEach((alias, target) -> {
                name(alias);
                if (!alias.equals(key(alias)) || !names.contains(target)) throw new IllegalArgumentException("Invalid plugin lookup");
            });
        }
        public byte[] encode() {
            try {
                var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
                out.writeInt(1); out.writeInt(entries.size());
                for (var entry : entries) { out.writeUTF(entry.name()); out.writeBoolean(entry.enabled()); }
                out.writeInt(lookups.size());
                for (var lookup : lookups.entrySet()) { out.writeUTF(lookup.getKey()); out.writeUTF(lookup.getValue()); }
                if (bytes.size() > MAXIMUM_BYTES) throw new IllegalArgumentException("Plugin catalog byte budget");
                return bytes.toByteArray();
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        }
        public static Seed decode(byte[] bytes) {
            if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Plugin catalog byte budget");
            try {
                var in = new DataInputStream(new ByteArrayInputStream(bytes));
                if (in.readInt() != 1) throw new IllegalArgumentException("Plugin catalog version");
                int count = count(in, 4096); var entries = new ArrayList<Entry>();
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF(); int flag = in.readUnsignedByte();
                    if (flag > 1) throw new IllegalArgumentException("Plugin enabled flag"); entries.add(new Entry(name, flag == 1));
                }
                count = count(in, 16384); var aliases = new TreeMap<String, String>(); String previous = "";
                for (int i = 0; i < count; i++) {
                    String alias = in.readUTF(); if (alias.compareTo(previous) <= 0) throw new IllegalArgumentException("Plugin lookup order");
                    previous = alias; aliases.put(alias, in.readUTF());
                }
                if (in.available() != 0) throw new IllegalArgumentException("Trailing plugin catalog");
                return new Seed(entries, aliases);
            } catch (IOException failure) { throw new IllegalArgumentException("Malformed plugin catalog", failure); }
        }
        private static int count(DataInputStream in, int maximum) throws IOException {
            int value = in.readInt(); if (value < 0 || value > maximum) throw new IllegalArgumentException("Plugin catalog count"); return value;
        }
    }
    private final Thread thread = Thread.currentThread();
    private final Seed seed;
    private final Map<String, Object> handles;
    private final Map<Object, String> identities = new IdentityHashMap<>();
    private final List<Object> entries;
    private final Map<String, Boolean> enabled = new HashMap<>();
    /** Every handle must belong to this private domain; capture never supplies live handles to this constructor. */
    public RollbackPlugins(Seed seed, Map<String, ?> handles) {
        this.seed = Objects.requireNonNull(seed); this.handles = Map.copyOf(handles);
        if (!this.handles.keySet().equals(seed.entries().stream().map(Entry::name).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Private plugin roster differs from capture");
        var expected = new HashSet<String>(); var ordered = new ArrayList<Object>();
        for (var entry : seed.entries()) {
            expected.add(entry.name()); Object handle = Objects.requireNonNull(this.handles.get(entry.name()), "Missing private plugin " + entry.name());
            if (identities.put(handle, entry.name()) != null) throw new IllegalArgumentException("Shared private plugin identity");
            ordered.add(handle); enabled.put(entry.name(), entry.enabled());
        }
        if (!expected.equals(handles.keySet())) throw new IllegalArgumentException("Private plugin roster differs from capture");
        entries = List.copyOf(ordered);
    }
    @Override @SuppressWarnings("unchecked") public <P> P getPlugin(String name) { check(); String resolved = seed.lookups().get(key(name)); return resolved == null ? null : (P) handles.get(resolved); }
    @Override public boolean isPluginPresent(String name) { check(); return seed.lookups().containsKey(key(name)); }
    @Override public boolean isPluginEnabled(String name) { check(); return Boolean.TRUE.equals(enabled.get(seed.lookups().get(key(name)))); }
    @Override public String pluginName(Object plugin) {
        check(); if (plugin == null) return ""; String name = identities.get(plugin);
        if (name == null) throw new IllegalArgumentException("Foreign plugin handle"); return name;
    }
    public List<Object> entries() { check(); return entries; }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() { check(); return entries; }
    public static String key(String name) { return Objects.requireNonNull(name).replace(' ', '_').toLowerCase(Locale.ENGLISH); }
    private static void name(String name) { if (name == null || name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Plugin name"); }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private plugin registry crossed threads"); }
}
