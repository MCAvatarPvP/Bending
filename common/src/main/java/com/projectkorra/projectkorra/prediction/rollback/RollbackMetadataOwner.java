package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import java.lang.ref.WeakReference;
import java.util.*;

/** A gameplay plugin's registered name/package pairs identify its metadata owner across loaders. */
public final class RollbackMetadataOwner {
    private static final Map<String, WeakReference<JavaPlugin>> REGISTERED = new TreeMap<>();
    private final List<String> registrations;

    RollbackMetadataOwner(List<String> registrations) { this.registrations = List.copyOf(registrations); }

    public static synchronized void register(String registration, JavaPlugin plugin) {
        REGISTERED.put(registration, new WeakReference<>(plugin));
    }

    public static synchronized void clearRegistrations() { REGISTERED.clear(); }

    public static synchronized Object portable(Object owner) {
        if (!(owner instanceof JavaPlugin plugin) || owner == ProjectKorra.plugin) return owner;
        var names = new ArrayList<String>();
        REGISTERED.forEach((key, reference) -> {
            if (reference.get() == plugin) names.add(key);
        });
        if (names.isEmpty()) throw new IllegalStateException("Unregistered bending metadata owner: " + plugin.getName());
        return new RollbackMetadataOwner(names);
    }

    static synchronized Object local(Object owner) {
        if (!(owner instanceof RollbackMetadataOwner token)) return owner;
        JavaPlugin resolved = null;
        for (String key : token.registrations) {
            var reference = REGISTERED.get(key);
            var candidate = reference == null ? null : reference.get();
            if (candidate == null) continue;
            if (resolved != null && resolved != candidate)
                throw new IllegalStateException("Ambiguous local bending metadata owner: " + token.registrations);
            resolved = candidate;
        }
        if (resolved == null) throw new IllegalStateException("Missing local bending metadata owner: " + token.registrations);
        return resolved;
    }
}
