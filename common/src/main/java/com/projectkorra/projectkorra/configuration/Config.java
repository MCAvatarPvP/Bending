package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Platform-neutral ProjectKorra configuration file.
 *
 * <p>This replaces Bukkit's PKConfiguration/YamlConfiguration in common code.
 * The implementation intentionally keeps the Bukkit-style accessors used by the
 * existing ability/config code, but the backing store is plain Java and can be
 * loaded from Bukkit or Fabric data folders.</p>
 */
public class Config implements PKConfiguration {
    private final File file;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, Object> defaults = new LinkedHashMap<>();
    private final PKConfigurationOptions options = new PKConfigurationOptions();
    private BendingPlayer player;
    private boolean loadedWithValues;

    public Config(final File file) {
        if (file.isAbsolute()) {
            this.file = file;
        } else {
            this.file = Platform.dataFolder().resolve(file.getPath()).toFile();
        }
        PredictionConfigSync.registerFile(this.file.toPath(), this);
        reload();
    }

    @SuppressWarnings("unchecked")
    private static void putNested(final Map<String, Object> root, final String path, final Object value) {
        final String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                current.put(parts[i], child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static void writeYaml(final BufferedWriter writer, final Map<String, Object> map, final int indent) throws IOException {
        final String prefix = " ".repeat(indent);
        for (final Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> child) {
                writer.write(prefix + entry.getKey() + ":");
                writer.newLine();
                writeYaml(writer, (Map<String, Object>) child, indent + 2);
            } else if (entry.getValue() instanceof Collection<?> collection) {
                writer.write(prefix + entry.getKey() + ":");
                writer.newLine();
                for (final Object value : collection) {
                    writer.write(" ".repeat(indent + 2) + "- " + formatScalar(value));
                    writer.newLine();
                }
            } else {
                writer.write(prefix + entry.getKey() + ": " + formatScalar(entry.getValue()));
                writer.newLine();
            }
        }
    }

    private static String formatScalar(final Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        final String s = String.valueOf(value).replace("'", "''");
        return "'" + s + "'";
    }

    private static int findMappingColon(final String trimmed) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == ':' && !single && !dbl) return i;
        }
        return -1;
    }

    private static String stripInlineComment(final String raw) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) {
                // YAML treats a # as a comment only when it begins the scalar or is
                // preceded by whitespace.  Keep tag literals such as #ice intact.
                if (i == 0) return raw;
                if (Character.isWhitespace(raw.charAt(i - 1))) {
                    return raw.substring(0, i).trim();
                }
            }
        }
        return raw.trim();
    }

    private static int countLeadingSpaces(final String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private static String join(final List<String> path) {
        return String.join(".", path.stream().filter(s -> !s.isEmpty()).toList());
    }

    private static Object parseScalar(final String rawValue) {
        final String raw = stripInlineComment(rawValue);
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        if (raw.equalsIgnoreCase("null")) return null;
        if (raw.startsWith("[") && raw.endsWith("]")) {
            final String body = raw.substring(1, raw.length() - 1).trim();
            if (body.isEmpty()) return new ArrayList<>();
            final List<String> list = new ArrayList<>();
            for (final String part : body.split(",")) list.add(unquote(stripInlineComment(part.trim())));
            return list;
        }
        try {
            return Integer.parseInt(raw);
        } catch (final NumberFormatException ignored) {
        }
        try {
            return Long.parseLong(raw);
        } catch (final NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(raw);
        } catch (final NumberFormatException ignored) {
        }
        return unquote(raw);
    }

    private static String unquote(final String raw) {
        if (raw.length() >= 2 && ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\"")))) {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        return raw;
    }

    /**
     * Several legacy PK configs use quoted alternatives such as
     * "23.0 # 20.85" or "2.5 # 3.0, 3.5". Bukkit's loose config access
     * commonly treated these as editable comments, but a strict parser sees the
     * entire value as a string. Numeric accessors should use the first concrete
     * value so ability physics stay deterministic on both Bukkit and Fabric.
     */
    private static String firstNumericToken(final Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        final int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        final int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(0, comma);
        return s.trim();
    }

    private static int parseInt(final Object v, final int def) {
        try {
            final String s = firstNumericToken(v);
            return s == null || s.isBlank() ? def : (int) Double.parseDouble(s);
        } catch (final Exception ignored) {
            return def;
        }
    }

    private static long parseLong(final Object v, final long def) {
        try {
            final String s = firstNumericToken(v);
            return s == null || s.isBlank() ? def : (long) Double.parseDouble(s);
        } catch (final Exception ignored) {
            return def;
        }
    }

    private static double parseDouble(final Object v, final double def) {
        try {
            final String s = firstNumericToken(v);
            return s == null || s.isBlank() ? def : Double.parseDouble(s);
        } catch (final Exception ignored) {
            return def;
        }
    }

    public Config get() {
        return this;
    }

    public Config get(final BendingPlayer player) {
        this.player = player;
        return this;
    }

    /**
     * Replaces explicit values with a server-provided prediction snapshot
     * without writing those values to the client's local configuration file.
     * Defaults remain available for forward compatibility.
     */
    public synchronized void applyRemoteValues(final Map<String, Object> remoteValues) {
        this.values.clear();
        if (remoteValues != null) this.values.putAll(remoteValues);
        this.loadedWithValues = !this.values.isEmpty();
    }

    public void create() {
        final File parent = this.file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Platform.logger().warning("Failed to create config directory " + parent);
        }
        if (!this.file.exists()) {
            try {
                if (!this.file.createNewFile()) {
                    Platform.logger().warning("Failed to create config file " + this.file);
                }
            } catch (final IOException e) {
                Platform.logger().warning("Failed to create config file " + this.file + ": " + e.getMessage());
            }
        }
    }

    public void reload() {
        create();
        this.values.clear();
        this.loadedWithValues = false;
        try (BufferedReader reader = Files.newBufferedReader(this.file.toPath(), StandardCharsets.UTF_8)) {
            final List<String> path = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                final int indent = countLeadingSpaces(line) / 2;
                while (path.size() > indent) path.remove(path.size() - 1);
                final String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    final String full = join(path);
                    Object existing = this.values.get(full);
                    List<Object> list;
                    if (existing instanceof List<?> existingList) {
                        @SuppressWarnings("unchecked") List<Object> mutable = (List<Object>) existingList;
                        list = mutable;
                    } else {
                        list = new ArrayList<>();
                        this.values.put(full, list);
                        this.loadedWithValues = true;
                    }
                    list.add(parseScalar(trimmed.substring(2).trim()));
                    continue;
                }
                final int colon = findMappingColon(trimmed);
                if (colon <= 0) continue;
                final String key = unquote(trimmed.substring(0, colon).trim());
                final String rest = trimmed.substring(colon + 1).trim();
                while (path.size() < indent) path.add("");
                if (path.size() == indent) path.add(key);
                else path.set(indent, key);
                final String full = join(path);
                if (rest.isEmpty()) {
                    // Sections are represented by their child paths. Storing an empty
                    // map here would overwrite those children when defaults are merged.
                } else {
                    this.values.put(full, parseScalar(rest));
                    this.loadedWithValues = true;
                }
            }
        } catch (final IOException e) {
            Platform.logger().warning("Failed to load config " + this.file + ": " + e.getMessage());
        }
    }

    public void save() {
        create();
        try (BufferedWriter writer = Files.newBufferedWriter(this.file.toPath(), StandardCharsets.UTF_8)) {
            if (this.options.header() != null && !this.options.header().isBlank()) {
                for (final String line : this.options.header().split("\\R")) {
                    writer.write("# ");
                    writer.write(line);
                    writer.newLine();
                }
            }
            writeYaml(writer, treeForSave(), 0);
        } catch (final IOException e) {
            Platform.logger().warning("Failed to save config " + this.file + ": " + e.getMessage());
        }
    }

    public void save(final File target) throws IOException {
        if (target.equals(this.file)) {
            save();
            return;
        }
        save();
        final File parent = target.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        Files.copy(this.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Bukkit's configuration defaults are read fallbacks, not user values.  The
     * previous neutral writer serialized defaults into existing files every time
     * ConfigManager ran.  That was especially dangerous when AvatarState defaults
     * were registered: boosted AvatarState numbers could be dumped into config.yml
     * and look like normal ability settings.  For an empty/new file we bootstrap
     * from defaults; for an existing file we only write explicit user values unless
     * copyDefaults(true) is deliberately requested.
     */
    private Map<String, Object> treeForSave() {
        final boolean bootstrap = this.values.isEmpty() && !this.defaults.isEmpty();
        final Map<String, Object> flat = new LinkedHashMap<>();
        if (bootstrap || this.options.copyDefaults()) {
            flat.putAll(this.defaults);
        }
        flat.putAll(this.values);
        final Map<String, Object> root = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : flat.entrySet()) {
            if (entry.getValue() == null) continue;
            putNested(root, entry.getKey(), entry.getValue());
        }
        return root;
    }

    public Config getConfig(final String path) {
        if (this.player == null || this.player.getStyle() == null) {
            this.player = null;
            return null;
        }
        final Config c = this.player.getStyle().getConfig();
        return c.contains(path) ? c : null;
    }

    @Override
    public boolean contains(final String path) {
        return raw(path) != null;
    }

    @Override
    public Object get(final String path) {
        return raw(path);
    }

    @Override
    public Object get(final String path, final Object def) {
        final Object v = raw(path);
        return v == null ? def : v;
    }

    @Override
    public void set(final String path, final Object value) {
        if (value == null) this.values.remove(path);
        else this.values.put(path, value);
    }

    @Override
    public void addDefault(final String path, final Object value) {
        this.defaults.putIfAbsent(path, value);
    }

    @Override
    public PKConfigurationOptions options() {
        return this.options;
    }

    public void removeTree(final String prefix) {
        final String p = prefix.endsWith(".") ? prefix : prefix + ".";
        this.values.keySet().removeIf(key -> key.equals(prefix) || key.startsWith(p));
    }

    @Override
    public String getString(final String path) {
        final Object v = raw(path);
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public String getString(final String path, final String def) {
        final String v = getString(path);
        return v == null ? def : v;
    }

    @Override
    public boolean getBoolean(final String path) {
        return getBoolean(path, false);
    }

    @Override
    public boolean getBoolean(final String path, final boolean def) {
        final Object v = raw(path);
        return v instanceof Boolean b ? b : v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    @Override
    public int getInt(final String path) {
        return getInt(path, 0);
    }

    @Override
    public int getInt(final String path, final int def) {
        final Object v = raw(path);
        return v instanceof Number n ? n.intValue() : parseInt(v, def);
    }

    @Override
    public long getLong(final String path) {
        return getLong(path, 0L);
    }

    @Override
    public long getLong(final String path, final long def) {
        final Object v = raw(path);
        return v instanceof Number n ? n.longValue() : parseLong(v, def);
    }

    @Override
    public double getDouble(final String path) {
        return getDouble(path, 0.0D);
    }

    @Override
    public double getDouble(final String path, final double def) {
        final Object v = raw(path);
        return v instanceof Number n ? n.doubleValue() : parseDouble(v, def);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> getStringList(final String path) {
        final Object v = raw(path);
        if (v instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return new ArrayList<>();
    }

    @Override
    public PKConfigurationSection getConfigurationSection(final String path) {
        if (!hasSection(path)) return null;
        return new SimpleSection(this, path);
    }

    @Override
    public Set<String> getKeys(final boolean deep) {
        if (deep) return new LinkedHashSet<>(mergedFlat().keySet());
        final Set<String> keys = new LinkedHashSet<>();
        for (final String key : mergedFlat().keySet()) keys.add(key.split("\\.")[0]);
        return keys;
    }

    private Object raw(final String path) {
        final Config c = getConfig(path);
        if (c != null && c != this) return c.rawLocal(path);
        return rawLocal(path);
    }

    private Object rawLocal(final String path) {
        return this.values.containsKey(path) ? this.values.get(path) : this.defaults.get(path);
    }

    private Map<String, Object> mergedFlat() {
        final Map<String, Object> merged = new LinkedHashMap<>(this.defaults);
        merged.putAll(this.values);
        return merged;
    }

    private boolean hasSection(final String path) {
        final String prefix = path.endsWith(".") ? path : path + ".";
        for (final String key : mergedFlat().keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return rawLocal(path) instanceof Map<?, ?>;
    }

    private record SimpleSection(Config root, String prefix) implements PKConfigurationSection {
        private String child(final String path) {
            return path == null || path.isBlank() ? prefix : prefix + "." + path;
        }

        @Override
        public boolean contains(final String path) {
            return root.contains(child(path));
        }

        @Override
        public Object get(final String path) {
            return root.get(child(path));
        }

        @Override
        public Object get(final String path, final Object def) {
            return root.get(child(path), def);
        }

        @Override
        public String getString(final String path) {
            return root.getString(child(path));
        }

        @Override
        public String getString(final String path, final String def) {
            return root.getString(child(path), def);
        }

        @Override
        public boolean getBoolean(final String path) {
            return root.getBoolean(child(path));
        }

        @Override
        public boolean getBoolean(final String path, final boolean def) {
            return root.getBoolean(child(path), def);
        }

        @Override
        public int getInt(final String path) {
            return root.getInt(child(path));
        }

        @Override
        public int getInt(final String path, final int def) {
            return root.getInt(child(path), def);
        }

        @Override
        public long getLong(final String path) {
            return root.getLong(child(path));
        }

        @Override
        public long getLong(final String path, final long def) {
            return root.getLong(child(path), def);
        }

        @Override
        public double getDouble(final String path) {
            return root.getDouble(child(path));
        }

        @Override
        public double getDouble(final String path, final double def) {
            return root.getDouble(child(path), def);
        }

        @Override
        public List<String> getStringList(final String path) {
            return root.getStringList(child(path));
        }

        @Override
        public PKConfigurationSection getConfigurationSection(final String path) {
            return root.getConfigurationSection(child(path));
        }

        @Override
        public Set<String> getKeys(final boolean deep) {
            final String p = prefix + ".";
            final Set<String> out = new LinkedHashSet<>();
            for (final String key : root.mergedFlat().keySet()) {
                if (!key.startsWith(p)) continue;
                final String tail = key.substring(p.length());
                out.add(deep ? tail : tail.split("\\.")[0]);
            }
            return out;
        }
    }
}
