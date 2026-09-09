package com.projectkorra.projectkorra.platform.bukkit;

import org.bukkit.entity.Entity;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Tracks only keys and UUIDs; Bukkit's metadata store retains the actual values. */
public final class BukkitEntityMetadata {
    private static final Map<UUID, Set<String>> KEYS = new ConcurrentHashMap<>();

    private BukkitEntityMetadata() { }

    public static void set(final Entity entity, final String key, final Plugin owner, final Object value) {
        entity.setMetadata(key, new FixedMetadataValue(owner, value));
        KEYS.computeIfAbsent(entity.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }

    public static void remove(final Entity entity, final String key, final Plugin owner) {
        entity.removeMetadata(key, owner);
        KEYS.computeIfPresent(entity.getUniqueId(), (ignored, keys) -> {
            keys.remove(key);
            return keys.isEmpty() ? null : keys;
        });
    }

    /** Entity removal alone does not release values from Bukkit's metadata store. */
    public static void clear(final Entity entity, final Plugin owner) {
        final Set<String> keys = KEYS.remove(entity.getUniqueId());
        if (keys != null) for (String key : keys) entity.removeMetadata(key, owner);
    }

    public static void clearAll(final Plugin owner, final Function<UUID, Entity> entities) {
        for (UUID id : List.copyOf(KEYS.keySet())) {
            final Entity entity = entities.apply(id);
            if (entity != null) clear(entity, owner);
            else KEYS.remove(id);
        }
    }

    static int trackedEntities() {
        return KEYS.size();
    }
}
