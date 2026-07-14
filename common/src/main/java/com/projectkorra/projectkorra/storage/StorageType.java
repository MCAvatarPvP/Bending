package com.projectkorra.projectkorra.storage;

import java.util.Locale;

public enum StorageType {
    SQLITE,
    MYSQL,
    MEMORY;

    public static StorageType fromConfig(final String engine) {
        if (engine == null) {
            return SQLITE;
        }
        final String normalized = engine.trim().toUpperCase(Locale.ROOT);
        for (final StorageType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return SQLITE;
    }
}

