package com.projectkorra.projectkorra.prediction.rollback;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackPluginsNativeTest {
    @Test void nativeManagerResolutionWinsOverProvidedAliasCollisionsAndCaptureKeepsNoLiveHandles() {
        Plugin first = plugin("First", true, List.of("shared", "old_name"));
        Plugin second = plugin("Second", false, List.of("shared"));
        var lookup = Map.of("first", first, "second", second, "shared", first, "old_name", first);
        var manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("getPlugins")) return new Plugin[]{first, second};
            if (method.getName().equals("getPlugin")) return lookup.get(RollbackPlugins.key((String) args[0]));
            throw new AssertionError("Unexpected manager operation " + method);
        });
        var seed = PaperRollbackPlugins.capture(manager);
        assertEquals(List.of(new RollbackPlugins.Entry("First", true), new RollbackPlugins.Entry("Second", false)), seed.entries());
        assertEquals("First", seed.lookups().get("shared")); assertEquals(seed, RollbackPlugins.Seed.decode(seed.encode()));
        Object ownedFirst = new Object(), ownedSecond = new Object();
        var privateRegistry = new RollbackPlugins(seed, Map.of("First", ownedFirst, "Second", ownedSecond));
        assertSame(ownedFirst, privateRegistry.getPlugin("OLD NAME")); assertSame(ownedFirst, privateRegistry.getPlugin("SHARED"));
        assertSame(ownedSecond, privateRegistry.getPlugin("Second")); assertFalse(privateRegistry.isPluginEnabled("Second"));
        assertThrows(IllegalArgumentException.class, () -> privateRegistry.pluginName(first));
    }
    private static Plugin plugin(String name, boolean enabled, List<String> provided) {
        var meta = (PluginMeta) Proxy.newProxyInstance(PluginMeta.class.getClassLoader(), new Class<?>[]{PluginMeta.class}, (proxy, method, args) -> {
            if (method.getName().equals("getProvidedPlugins")) return provided;
            throw new AssertionError("Unexpected metadata read " + method);
        });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled;
            case "getPluginMeta" -> meta;
            default -> throw new AssertionError("Unexpected live plugin read " + method);
        });
    }
}
