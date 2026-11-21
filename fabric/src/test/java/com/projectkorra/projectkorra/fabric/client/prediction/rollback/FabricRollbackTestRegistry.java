package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.TagGroupLoader;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;

import java.util.List;

/** Initializes native registry/tag fixtures once per test JVM, independently of test order. */
final class FabricRollbackTestRegistry {
    private static boolean initialized;
    private static DynamicRegistryManager.Immutable simulationRegistries;
    private FabricRollbackTestRegistry() { }

    static synchronized void bootstrap() {
        if (initialized) return;
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        Registries.bootstrap();
        try (var resources = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA,
                List.of(VanillaDataPackProvider.createDefaultPack()))) {
            TagGroupLoader.startReload(resources, DynamicRegistryManager.of(Registries.REGISTRIES))
                    .forEach(Registry.PendingTagLoad::apply);
        }
        initialized = true;
    }

    static synchronized DynamicRegistryManager.Immutable simulationRegistries() {
        bootstrap();
        if (simulationRegistries == null) {
            var staticRegistries = DynamicRegistryManager.of(Registries.REGISTRIES);
            try (var resources = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA,
                    List.of(VanillaDataPackProvider.createDefaultPack()))) {
                var wrappers = staticRegistries.streamAllRegistries()
                        .<net.minecraft.registry.RegistryWrapper.Impl<?>>map(entry -> entry.value()).toList();
                var dynamic = net.minecraft.registry.RegistryLoader.loadFromResource(resources, wrappers,
                        net.minecraft.registry.RegistryLoader.DYNAMIC_REGISTRIES);
                var combined = new DynamicRegistryManager.ImmutableImpl(java.util.stream.Stream.concat(
                        staticRegistries.streamAllRegistries(), dynamic.streamAllRegistries())).toImmutable();
                var lootEntries = net.minecraft.loot.LootDataType.stream()
                        .<net.minecraft.registry.RegistryLoader.Entry<?>>map(FabricRollbackTestRegistry::lootEntry).toList();
                var loot = net.minecraft.registry.RegistryLoader.loadFromResource(resources,
                        combined.streamAllRegistries().<net.minecraft.registry.RegistryWrapper.Impl<?>>map(entry -> entry.value()).toList(), lootEntries);
                simulationRegistries = new DynamicRegistryManager.ImmutableImpl(java.util.stream.Stream.concat(
                        combined.streamAllRegistries(), loot.streamAllRegistries())).toImmutable();
            }
        }
        return simulationRegistries;
    }

    private static <T> net.minecraft.registry.RegistryLoader.Entry<T> lootEntry(net.minecraft.loot.LootDataType<T> type) {
        return new net.minecraft.registry.RegistryLoader.Entry<>(type.registryKey(), type.codec(), false);
    }
}
