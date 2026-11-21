package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPluginsTest {
    private static RollbackPlugins.Seed seed() {
        return new RollbackPlugins.Seed(List.of(new RollbackPlugins.Entry("Core_Plugin", true), new RollbackPlugins.Entry("Disabled", false)),
                Map.of("core_plugin", "Core_Plugin", "legacy", "Core_Plugin", "disabled", "Disabled"));
    }
    @Test void aliasesAndEnumerationResolveOnlyPrivateHandlesAndRewindTheirState() {
        var first = new Handle(); var second = new Handle();
        var registry = new RollbackPlugins(seed(), Map.of("Core_Plugin", first, "Disabled", second));
        assertSame(first, registry.getPlugin("CORE PLUGIN")); assertSame(first, registry.getPlugin("Legacy"));
        assertSame(second, registry.getPlugin("DISABLED")); assertFalse(registry.isPluginEnabled("disabled"));
        assertTrue(registry.isPluginPresent("Disabled")); assertTrue(registry.isPluginEnabled("legacy"));
        assertNull(registry.getPlugin("missing")); assertFalse(registry.isPluginPresent("missing")); assertFalse(registry.isPluginEnabled("missing"));
        assertEquals("Core_Plugin", registry.pluginName(first)); assertEquals("", registry.pluginName(null));
        assertThrows(IllegalArgumentException.class, () -> registry.pluginName(new Handle()));
        assertEquals(List.of(first, second), registry.entries());
        assertThrows(UnsupportedOperationException.class, () -> registry.entries().clear());
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 1000).capture(List.of(registry), List.of());
        first.value = 9; second.value = 12; checkpoint.restore(); assertEquals(0, first.value); assertEquals(0, second.value);
        assertSame(first, registry.getPlugin("legacy"));
    }
    @Test void invalidBindingAndCatalogDataCannotMasqueradeAsAnAbsentPlugin() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlugins(seed(), Map.of()));
        var handle = new Handle();
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlugins(seed(), Map.of("Core_Plugin", handle, "Disabled", handle)));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlugins.Seed(seed().entries(), Map.of("core_plugin", "Core_Plugin")));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlugins.Seed(seed().entries(), Map.of("core_plugin", "Core_Plugin", "disabled", "unknown")));
        var bytes = seed().encode(); assertEquals(seed(), RollbackPlugins.Seed.decode(bytes));
        for (int i = 0; i < bytes.length; i++) {
            var truncated = Arrays.copyOf(bytes, i); assertThrows(IllegalArgumentException.class, () -> RollbackPlugins.Seed.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlugins.Seed.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> RollbackPlugins.Seed.decode(new byte[RollbackPlugins.MAXIMUM_BYTES + 1]));
    }
    private static final class Handle { int value; }
}
