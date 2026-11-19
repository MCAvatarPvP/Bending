package com.projectkorra.projectkorra.platform.mc.metadata;

public interface MetadataValue {
    Object value();
    default Object owner() { return null; }
}
