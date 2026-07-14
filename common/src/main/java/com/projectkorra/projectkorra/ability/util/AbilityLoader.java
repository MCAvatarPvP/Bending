package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.event.AbilityLoadEvent;
import com.projectkorra.projectkorra.platform.mc.plugin.Plugin;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import sun.reflect.ReflectionFactory;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AbilityLoader<T> {

    private final Plugin plugin;
    private final List<JarFile> jars = new ArrayList<>();
    private final List<String> directoryClasses = new ArrayList<>();
    private ClassLoader loader;
    private String path;

    public AbilityLoader(final JavaPlugin plugin, final String packageBase) {
        this.plugin = plugin;
        this.loader = plugin.getClass().getClassLoader();
        this.path = packageBase.replace('.', '/');

        if (plugin == null || this.loader == null) {
            ProjectKorra.log.severe("Could not find classloader! Will not load abilities from " + packageBase);
            return;
        }

        try {
            final Enumeration<URL> resources = this.loader.getResources(this.path);

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    Path root = Path.of(resource.toURI());
                    try (var files = Files.walk(root)) {
                        files.filter(file -> file.toString().endsWith(".class") && !file.getFileName().toString().contains("$"))
                                .forEach(file -> directoryClasses.add(this.path.replace('/', '.') + "." + root.relativize(file).toString().replace(File.separatorChar, '.').replaceFirst("\\.class$", "")));
                    }
                } else if ("jar".equals(resource.getProtocol())) {
                    JarFile jar = ((JarURLConnection) resource.openConnection()).getJarFile();
                    if (this.jars.stream().noneMatch(existing -> existing.getName().equals(jar.getName())))
                        this.jars.add(jar);
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns a list of loaded objects of the provided classType.
     *
     * @param classType   Type of class to load
     * @param parentClass Type of class that the class must extend. Use
     *                    {@code Object.class} for classes without a type.
     * @return
     */
    public List<T> load(final Class<?> classType, final Class<?> parentClass) {
        final ArrayList<T> loadables = new ArrayList<>();

        if (this.loader == null || (this.jars.isEmpty() && this.directoryClasses.isEmpty())) {
            return loadables;
        }
        for (String className : this.directoryClasses) loadClass(className, classType, parentClass, loadables, null);
        for (JarFile jar : this.jars) {
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {

                final JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().contains("$")) {
                    continue;
                }

                final String className = entry.getName().replace('/', '.').substring(0, entry.getName().length() - 6);
                if (!className.startsWith(this.path.replace('/', '.'))) {
                    continue;
                }

                loadClass(className, classType, parentClass, loadables, jar);
            }
        }

        return loadables;
    }

    private void loadClass(String className, Class<?> classType, Class<?> parentClass, List<T> loadables, JarFile sourceJar) {
        try {
            Class<?> clazz = Class.forName(className, true, this.loader);

            if (!classType.isAssignableFrom(clazz) || clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                return;
            }
            if (loadables.stream().anyMatch(existing -> existing.getClass() == clazz)) return;

            final ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            final Constructor<?> objDef = parentClass.getDeclaredConstructor();
            final Constructor<?> intConstr = rf.newConstructorForSerialization(clazz, objDef);
            final T loadable = (T) clazz.cast(intConstr.newInstance());

            if (loadable == null) {
                return;
            }

            loadables.add(loadable);
            final AbilityLoadEvent<T> event = new AbilityLoadEvent<T>(this.plugin, loadable, sourceJar);
            this.plugin.getServer().getPluginManager().callEvent(event);
        } catch (Exception | Error ignored) {
        }
    }
}
