package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Builds a schema from installed local gameplay artifacts, never from names supplied over the wire. */
public final class RollbackGameplayCatalog {
    public static final String RESOURCE = "META-INF/projectkorra/rollback-gameplay.types";
    private RollbackGameplayCatalog() { }

    /** Addons may package the same generated inventory resource in their locally installed artifact. */
    public static List<Class<?>> installed(ClassLoader loader) {
        Objects.requireNonNull(loader);
        var names = new TreeSet<String>();
        try {
            var resources = loader.getResources(RESOURCE);
            if (!resources.hasMoreElements()) throw new IllegalStateException("Missing local gameplay type inventory");
            while (resources.hasMoreElements()) {
                try (var input = new BufferedReader(new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    for (String name; (name = input.readLine()) != null;) {
                        if (name.length() > 512 || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                            throw new IllegalArgumentException("Invalid local gameplay type name");
                        names.add(name);
                        if (names.size() > 20_000) throw new IllegalArgumentException("Gameplay type inventory exceeds budget");
                    }
                }
            }
            var types = new ArrayList<Class<?>>();
            for (String name : names) types.add(Class.forName(name, false, loader));
            return List.copyOf(types);
        } catch (IOException | ClassNotFoundException failure) {
            throw new IllegalStateException("Cannot load the installed gameplay type inventory", failure);
        }
    }

    /** Unsupported object forms remain symbols; encoding them still requires an explicit projection/binding. */
    public static RollbackGraphCodec.Catalog create(Collection<Class<?>> installed, Collection<RollbackGraphCodec.Binding> bindings) {
        var symbols = List.copyOf(installed);
        var objects = new ArrayList<Class<?>>();
        for (Class<?> type : symbols) {
            if (type.isInterface() || type.isEnum() || type.isArray() || type.isPrimitive() || Modifier.isAbstract(type.getModifiers())
                    || RollbackStateCell.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) continue;
            boolean supported = true;
            for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
                if (current == null || current.getClassLoader() == null || current.isRecord() || current.isHidden()) { supported = false; break; }
            }
            if (supported) objects.add(type);
        }
        return new RollbackGraphCodec.Catalog(objects, symbols, bindings);
    }
}
