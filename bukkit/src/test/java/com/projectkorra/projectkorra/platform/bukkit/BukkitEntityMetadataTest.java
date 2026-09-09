package com.projectkorra.projectkorra.platform.bukkit;

import org.bukkit.entity.Entity;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataStoreBase;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BukkitEntityMetadataTest {
    private final Plugin owner = plugin("ProjectKorra");
    private final MetadataStoreBase<UUID> store = new MetadataStoreBase<>() {
        @Override protected String disambiguate(UUID subject, String key) { return subject + ":" + key; }
    };

    @AfterEach void cleanup() { BukkitEntityMetadata.clearAll(owner, ignored -> null); }

    @Test void removingAProjectileReleasesTheRealBukkitMetadataStoreValue() {
        for (int index = 0; index < 1000; index++) {
            final Entity entity = entity();
            // The value deliberately refers back to the entity, as ability and
            // TempFallingBlock metadata do. Bukkit does not remove it itself.
            BukkitEntityMetadata.set(entity, "earthsmash", owner, entity);
            assertSame(entity, store.getMetadata(entity.getUniqueId(), "earthsmash").getFirst().value());
            BukkitEntityMetadata.clear(entity, owner);
            assertTrue(store.getMetadata(entity.getUniqueId(), "earthsmash").isEmpty());
        }
        assertEquals(0, BukkitEntityMetadata.trackedEntities());
    }

    @Test void cleanupPreservesOtherPluginsMetadataOnTheSameKey() {
        final Entity entity = entity();
        final Plugin other = plugin("OtherPlugin");
        entity.setMetadata("projectile", new FixedMetadataValue(other, "other value"));
        BukkitEntityMetadata.set(entity, "projectile", owner, entity);
        BukkitEntityMetadata.clear(entity, owner);
        final var remaining = store.getMetadata(entity.getUniqueId(), "projectile");
        assertEquals(1, remaining.size());
        assertSame(other, remaining.getFirst().getOwningPlugin());
        assertEquals(0, BukkitEntityMetadata.trackedEntities());
    }

    @Test void explicitRemovalAndShutdownAlsoReleaseTheKeyIndex() {
        final Entity entity = entity();
        BukkitEntityMetadata.set(entity, "first", owner, entity);
        BukkitEntityMetadata.set(entity, "second", owner, entity);
        BukkitEntityMetadata.remove(entity, "first", owner);
        assertEquals(1, BukkitEntityMetadata.trackedEntities());
        BukkitEntityMetadata.remove(entity, "second", owner);
        assertEquals(0, BukkitEntityMetadata.trackedEntities());
        BukkitEntityMetadata.set(entity, "third", owner, entity);
        BukkitEntityMetadata.clearAll(owner, id -> entity);
        assertTrue(store.getMetadata(entity.getUniqueId(), "third").isEmpty());
        assertEquals(0, BukkitEntityMetadata.trackedEntities());
    }

    private Entity entity() {
        final UUID id = UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "setMetadata" -> { store.setMetadata(id, (String) args[0], (MetadataValue) args[1]); yield null; }
                    case "removeMetadata" -> { store.removeMetadata(id, (String) args[0], (Plugin) args[1]); yield null; }
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> id.toString();
                    default -> throw new AssertionError(method);
                });
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method);
                });
    }
}
