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

    /** Detached service roots copied with the outgoing ability graph; shared containers are rebound live. */
    protected List<?> projectRollbackRestoration(Set<UUID> participants, Manager live, BiConsumer<Object, Object> bind) {
        throw new UnsupportedOperationException("Manager has no rollback restoration: " + getClass().getName());
    }
    protected RestorationStep prepareRollbackRestoration(Set<UUID> participants, List<?> roots) {
        throw new UnsupportedOperationException("Manager has no rollback restoration: " + getClass().getName());
    }
    public interface RestorationStep {
        void validate();
        void commit();
    }

    public static final class RestorationSources {
        private final Thread owner = Thread.currentThread();
        private final Set<UUID> roster;
        private final List<Manager> targets;
        private final List<List<?>> roots;
        private RestorationSources(Set<UUID> roster, List<Manager> targets, List<List<?>> roots) {
            this.roster = roster; this.targets = List.copyOf(targets); this.roots = List.copyOf(roots);
        }
        public List<List<?>> roots() { return roots; }
        private void requireCurrent() {
            if (Thread.currentThread() != owner || RollbackDomain.active() || RollbackClock.active()
                    || !Platform.scheduler().isPrimaryThread()) throw new IllegalStateException("Restore managers on the live main thread");
            for (var target : targets) if (MANAGERS.get(target.getClass()) != target)
                throw new IllegalStateException("Live manager ownership changed before restoration");
        }
        public RestorationStep prepare(List<?> copiedRoots) {
            requireCurrent();
            if (copiedRoots.size() != targets.size()) throw new IllegalArgumentException("Manager restoration roots");
            var steps = new ArrayList<RestorationStep>();
            for (int i = 0; i < targets.size(); i++) steps.add(targets.get(i).prepareRollbackRestoration(roster, (List<?>) copiedRoots.get(i)));
            return new RestorationStep() {
                private int completed;
                @Override public void validate() { requireCurrent(); steps.forEach(RestorationStep::validate); }
                @Override public void commit() {
                    validate();
                    while (completed < steps.size()) { steps.get(completed).commit(); completed++; }
                }
            };
        }
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

        public RestorationSources restorationSources(Set<UUID> participants, BiConsumer<Object, Object> bind) {
            if (RollbackDomain.active() || RollbackClock.active() || !Platform.scheduler().isPrimaryThread())
                throw new IllegalStateException("Prepare manager restoration on the live main thread");
            var roster = Set.copyOf(participants);
            if (roster.isEmpty() || roster.size() > 128) throw new IllegalArgumentException("Manager restoration roster");
            var targets = new ArrayList<Manager>(); var roots = new ArrayList<List<?>>();
            for (var entry : managers.entrySet()) {
                var live = MANAGERS.get(entry.getKey());
                if (live == null || live.getClass() != entry.getValue().getClass()) throw new IllegalStateException("Live manager definition differs");
                bind.accept(entry.getValue(), live); bind.accept(live, live);
                targets.add(live); roots.add(entry.getValue().projectRollbackRestoration(roster, live, bind));
            }
            return new RestorationSources(roster, targets, roots);
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
