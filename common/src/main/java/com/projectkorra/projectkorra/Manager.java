package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import com.projectkorra.projectkorra.platform.mc.event.Listener;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.StatisticsManager;
import org.apache.commons.lang3.Validate;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.BiConsumer;

public abstract class Manager implements Listener {

    /**
     * {@link Map} containing all {@link Manager} instances by their
     * {@link Class} as key
     */
    private static final Map<Class<? extends Manager>, Manager> MANAGERS = new HashMap<>();

    /** Source references only; transfer this registry before installing it in a domain. */
    public static RollbackRegistry captureRollbackRegistry() {
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Manager import during replay");
        return new RollbackRegistry(MANAGERS);
    }

    public static RollbackRegistry exportRollbackRegistry() {
        if (!RollbackDomain.active()) throw new IllegalStateException("Export managers inside their replay domain");
        return new RollbackRegistry(MANAGERS);
    }

    /**
     * Select the source containers to import. Values remain source references until
     * the whole bending graph is transferred, preserving aliases held by abilities.
     * A manager must explicitly support import; unknown services cannot use live state.
     */
    protected void projectRollbackState(Set<UUID> participants, BiConsumer<Object, Object> project) {
        throw new UnsupportedOperationException("Manager has no rollback import: " + getClass().getName());
    }

    /** Recreate private tasks only. Never call normal activation or register live listeners. */
    protected void onRollbackInstall() { }

    public static final class RollbackRegistry {
        private final Map<Class<? extends Manager>, Manager> managers;

        private RollbackRegistry(Map<Class<? extends Manager>, Manager> source) {
            managers = new LinkedHashMap<>();
            source.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                    .forEach(entry -> managers.put(entry.getKey(), entry.getValue()));
        }

        public List<Manager> instances() { return List.copyOf(managers.values()); }

        public void projectSources(Set<UUID> participants, BiConsumer<Object, Object> project) {
            if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Manager import during replay");
            projectState(participants, project);
        }

        public void projectCurrentSources(Set<UUID> participants, BiConsumer<Object, Object> project) {
            if (!RollbackDomain.active()) throw new IllegalStateException("Export managers inside their replay domain");
            projectState(participants, project);
        }

        private void projectState(Set<UUID> participants, BiConsumer<Object, Object> project) {
            Set<UUID> roster = Set.copyOf(participants);
            for (Manager manager : managers.values()) {
                project.accept(manager, manager);
                manager.projectRollbackState(roster, project);
            }
        }

        public void install() {
            if (!RollbackDomain.active()) throw new IllegalStateException("Manager install requires a rollback domain");
            MANAGERS.clear();
            MANAGERS.putAll(managers);
            managers.values().forEach(Manager::onRollbackInstall);
        }
    }

    /**
     * Register a new {@link Manager} instance.
     *
     * @param managerClass {@link Class} of the {@link Manager} to be registered
     * @throws NullPointerException     if managerClass is null
     * @throws IllegalArgumentException if managerClass has already been
     *                                  registered
     */
    public static void registerManager(final Class<? extends Manager> managerClass) {
        Validate.notNull(managerClass, "Manager class cannot be null");
        Validate.isTrue(!MANAGERS.containsKey(managerClass), "Manager has already been registered");
        try {
            final Constructor<? extends Manager> constructor = managerClass.getDeclaredConstructor();
            final boolean accessible = constructor.isAccessible();
            constructor.setAccessible(true);
            final Manager manager = constructor.newInstance();
            constructor.setAccessible(accessible);
            manager.activate();
            MANAGERS.put(managerClass, manager);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | SecurityException |
                 IllegalArgumentException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    /**
     * Get a registered {@link Manager} by its {@link Class}.
     *
     * @param managerClass {@link Class} of the registered {@link Manager}
     * @return instance of the {@link Manager} class
     * @throws NullPointerException     if managerClass is null
     * @throws IllegalArgumentException if managerClass has not yet been
     *                                  registered
     */
    public static <T extends Manager> T getManager(final Class<T> managerClass) {
        Validate.notNull(managerClass, "Manager class cannot be null");
        Validate.isTrue(MANAGERS.containsKey(managerClass), "Manager has not yet been registered");
        final Manager registered = MANAGERS.get(managerClass);
        return managerClass.cast(registered);
    }

    /**
     * Activates core {@link Manager} instances
     */
    public static void startup() {
        registerManager(StatisticsManager.class);
        registerManager(FlightHandler.class);
    }

    /**
     * Deactivates and clears all {@link Manager} instances
     */
    public static void shutdown() {
        MANAGERS.values().forEach(Manager::deactivate);
        MANAGERS.clear();
    }

    /**
     * Get this plugin instance
     *
     * @return {@link ProjectKorra} plugin instance
     */
    protected ProjectKorra getPlugin() {
        return JavaPlugin.getPlugin(ProjectKorra.class);
    }

    /**
     * Activate this {@link Manager}
     */
    public final void activate() {
        Platform.events().registerListener(this, ProjectKorra.plugin);
        this.onActivate();
    }

    /**
     * Overridable method to execute code when this {@link Manager} is activated
     */
    public void onActivate() {

    }

    /**
     * Deactivate this {@link Manager}
     */
    public final void deactivate() {
        HandlerList.unregisterAll(this);
        this.onDeactivate();
    }

    /**
     * Overridable method to execute code when this {@link Manager} is
     * deactivated
     */
    public void onDeactivate() {

    }
}
