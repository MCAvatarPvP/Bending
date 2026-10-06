package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Captures name/alias resolution through the actual manager, without retaining live plugins. */
public final class PaperRollbackPlugins {
    private PaperRollbackPlugins() { }
    public static RollbackPlugins.Seed capture() {
        if (!Bukkit.isPrimaryThread() || RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Capture plugins at live bootstrap boundary");
        return capture(Bukkit.getPluginManager());
    }
    static RollbackPlugins.Seed capture(PluginManager manager) {
        var entries = new ArrayList<RollbackPlugins.Entry>(); var identities = new IdentityHashMap<Plugin, String>();
        var keys = new TreeSet<String>();
        for (var plugin : manager.getPlugins()) {
            entries.add(new RollbackPlugins.Entry(plugin.getName(), plugin.isEnabled())); identities.put(plugin, plugin.getName());
            keys.add(RollbackPlugins.key(plugin.getName()));
            for (var alias : plugin.getPluginMeta().getProvidedPlugins()) keys.add(RollbackPlugins.key(alias));
        }
        var aliases = new TreeMap<String, String>();
        for (var key : keys) {
            var plugin = manager.getPlugin(key);
            if (plugin == null) continue;
            var name = identities.get(plugin); if (name == null) throw new IllegalStateException("Plugin registry changed during capture");
            aliases.put(key, name);
        }
        return new RollbackPlugins.Seed(entries, aliases);
    }
}
