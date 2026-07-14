package com.projectkorra.projectkorra.platform.mc.metadata;

public class FixedMetadataValue implements MetadataValue {
    private final Object owner;
    private final Object value;

    public FixedMetadataValue(Object plugin, Object value) {
        this.owner = plugin;
        this.value = value;
    }

    public Object owner() {
        return owner;
    }

    public Object value() {
        return value;
    }
}
