package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemScope;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Keeps a simulation's instance graph and selected shared runtime registries separate
 * from normal gameplay. Existing ability code continues to use its registries and
 * platform services during a call; the outside state is restored before returning.
 *
 * <p>Every mutable shared service must be registered before initialization. Platform
 * adapters must supply isolated logical world/entity state, never live mutation handles.
 * This scope is main-thread-only: it cannot isolate unsynchronized asynchronous readers
 * of legacy static state. Such services must be adapted before they can participate.</p>
 */
public final class RollbackDomain {
    private static final ThreadLocal<RollbackDomain> ACTIVE = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final RollbackStateGraph graph;
    private final List<Field> shared;
    private final List<?> local;
    private final ProjectKorraPlatform platform;
    private final RollbackNativeItems<?> items;
    private final PredictionServices.Bindings prediction;
    private final RollbackConfiguration configuration;
    private Checkpoint state;
    private boolean failed;

    private RollbackDomain(RollbackStateGraph graph, Collection<Field> shared, Collection<?> local,
                           ProjectKorraPlatform platform, RollbackNativeItems<?> items, PredictionServices.Bindings prediction,
                           RollbackConfiguration configuration) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.shared = List.copyOf(shared);
        this.prediction = Objects.requireNonNull(prediction, "prediction services");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        var roots = new ArrayList<Object>(local);
        roots.add(prediction);
        roots.add(configuration);
        // The owned platform includes dynamic event registrations and other mutable services.
        // Capturing only the execution adapter would leave those on the discarded branch.
        if (platform instanceof RollbackPlatform) roots.add(platform);
        this.local = List.copyOf(roots);
        this.platform = Objects.requireNonNull(platform, "platform");
        this.items = items;
        for (Field field : this.shared) {
            if (!Modifier.isStatic(field.getModifiers())) throw new IllegalArgumentException("Expected shared static root: " + field);
        }
    }

    /**
     * Bootstrap installs the session's initial registry contents and logical participants.
     * Initialization runs under isolated services, with the outside registries restored
     * even if initialization or the first checkpoint fails.
     */
    public static RollbackDomain create(RollbackStateGraph graph, Collection<Field> shared,
                                        Collection<?> local, ProjectKorraPlatform platform, Runnable bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        RollbackDomain domain = new RollbackDomain(graph, shared, local, platform, null, PredictionServices.empty(), RollbackConfiguration.capture());
        domain.call(() -> { bootstrap.run(); return null; });
        return domain;
    }

    /** Installs native logical item construction for bootstrap and every subsequent domain call. */
    public static RollbackDomain create(RollbackStateGraph graph, Collection<Field> shared,
                                        Collection<?> local, ProjectKorraPlatform platform,
                                        RollbackNativeItems<?> items, Runnable bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        RollbackDomain domain = new RollbackDomain(graph, shared, local, platform, Objects.requireNonNull(items, "items"), PredictionServices.empty(), RollbackConfiguration.capture());
        domain.call(() -> { bootstrap.run(); return null; });
        return domain;
    }

    /**
     * Session hooks must consume private state and buffer detached outputs. They are
     * snapshotted with the domain; they may not delegate to installed live listeners.
     * The simpler overloads reject any prediction hook until explicitly configured.
     */
    public static RollbackDomain create(RollbackStateGraph graph, Collection<Field> shared,
                                        Collection<?> local, ProjectKorraPlatform platform,
                                        RollbackNativeItems<?> items, PredictionServices.Bindings prediction,
                                        Runnable bootstrap) {
        return create(graph, shared, local, platform, items, prediction, RollbackConfiguration.capture(), bootstrap);
    }

    /** Both replicas supply views prepared from the same authoritative configuration bytes. */
    public static RollbackDomain create(RollbackStateGraph graph, Collection<Field> shared,
                                        Collection<?> local, ProjectKorraPlatform platform,
                                        RollbackNativeItems<?> items, PredictionServices.Bindings prediction,
                                        RollbackConfiguration configuration, Runnable bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        RollbackDomain domain = new RollbackDomain(graph, shared, local, platform, items, prediction, configuration);
        domain.call(() -> { bootstrap.run(); return null; });
        return domain;
    }

    /** Executes one bounded engine operation and retains its resulting private state. */
    public <T> T call(Supplier<T> operation) {
        checkThread();
        Objects.requireNonNull(operation, "operation");
        if (failed) throw new IllegalStateException("Rollback domain failed; discard the session");
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested rollback domains are not supported");
        final RollbackStateGraph.Snapshot outside;
        try { outside = graph.capture(List.of(), shared); }
        catch (RuntimeException | Error exception) { failed = true; throw exception; }
        Throwable failure = null;
        ACTIVE.set(this);
        try (var scope = Platform.using(platform);
             var configurationScope = configuration.using();
             var itemScope = items == null ? null : RollbackItemScope.using(items);
             var predictionScope = PredictionServices.using(prediction)) {
            if (state != null) state.snapshot.restore();
            T result = operation.get();
            state = capture();
            return result;
        } catch (RuntimeException | Error exception) {
            failed = true;
            failure = exception;
            throw exception;
        } finally {
            try {
                outside.restore();
            } catch (RuntimeException | Error restoreFailure) {
                failed = true;
                if (failure != null) failure.addSuppressed(restoreFailure);
                else throw restoreFailure;
            } finally {
                ACTIVE.remove();
            }
        }
    }

    /** Snapshot/restore methods are used by RollbackSimulation while this domain is installed. */
    public Checkpoint capture() {
        checkActive();
        return new Checkpoint(this, graph.capture(local, shared));
    }

    public void restore(Checkpoint checkpoint) {
        checkActive();
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.owner != this) throw new IllegalArgumentException("Checkpoint belongs to another rollback domain");
        checkpoint.snapshot.restore();
    }

    public static final class Checkpoint {
        private final RollbackDomain owner;
        private final RollbackStateGraph.Snapshot snapshot;
        private Checkpoint(RollbackDomain owner, RollbackStateGraph.Snapshot snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }
        public int objectCount() { return snapshot.objectCount(); }
    }

    public boolean failed() { checkThread(); return failed; }

    /** True only while private session state and services are installed on this thread. */
    public static boolean active() { return ACTIVE.get() != null; }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Rollback domain crossed threads");
    }
    private void checkActive() {
        checkThread();
        if (ACTIVE.get() != this) throw new IllegalStateException("Rollback domain is not installed");
    }
}
