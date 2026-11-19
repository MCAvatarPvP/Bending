package com.projectkorra.projectkorra.platform.mc;

import com.projectkorra.projectkorra.platform.mc.plugin.Plugin;

public class NamespacedKey {
    private final String namespace;
    private final String key;

    public NamespacedKey(Plugin plugin, String key) {
        this(plugin.getName().toLowerCase(), key);
    }

    public NamespacedKey(String namespace, String key) {
        this.namespace = namespace;
        this.key = key.toLowerCase();
    }

    public static NamespacedKey fromString(String value) {
        if (value == null || value.isBlank()) return null;
        String[] split = value.split(":", 2);
        return split.length == 2 ? new NamespacedKey(split[0], split[1]) : new NamespacedKey("minecraft", value);
    }

    public String getNamespace() {
        return namespace;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof NamespacedKey that
                && namespace.equals(that.namespace) && key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return 31 * namespace.hashCode() + key.hashCode();
    }

    public Object handle() {
        return this;
    }
}
