package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs the existing complete bending tick inside a private replay domain. Native
 * input/world execution and output bindings are mandatory loader adapters; this
 * class does not replace movement or damage with another gameplay implementation.
 */
public final class RollbackCombatRuntime<I, E> implements RollbackReplicaTimeline<RollbackDomain.Checkpoint, I, E> {
    public record Environment(RollbackStateGraph graph, Collection<Field> shared, Collection<?> local,
                              ProjectKorraPlatform platform, RollbackNativeItems<?> items,
                              PredictionServices.Bindings prediction, RollbackConfiguration configuration) {
        public Environment(RollbackStateGraph graph, Collection<Field> shared, Collection<?> local,
                           ProjectKorraPlatform platform, RollbackNativeItems<?> items, PredictionServices.Bindings prediction) {
            this(graph, shared, local, platform, items, prediction, RollbackConfiguration.capture());
        }
        public Environment {
            Objects.requireNonNull(graph, "graph");
            shared = List.copyOf(shared);
            local = List.copyOf(local);
            Objects.requireNonNull(platform, "platform");
            Objects.requireNonNull(prediction, "prediction");
            Objects.requireNonNull(configuration, "configuration");
        }
    }

    /**
     * All referenced mutable adapter state is checkpointed with the runtime. Inputs
     * must be immutable, authenticated upstream and applied to logical entities only.
     * begin/end bind and unbind the tick's output buffer; they must not publish effects.
     * end also runs if begin fails and must tolerate partial initialization.
     * tickWorld runs native entity/world simulation after ordered inputs, before bending.
     */
    public interface Execution<I, E> {
        I predict(UUID participant, I previous);
        void begin(RollbackStep<E> effects);
        void input(UUID participant, I input);
        void tickWorld(long tick);
        void end();
    }

    private final RollbackRound round;
    private boolean delivering, deliveryFailed;
    private final RollbackDomain domain;
    private final RollbackEngine<RollbackDomain.Checkpoint, I, E> engine;

    private RollbackCombatRuntime(RollbackDomain domain, RollbackEngine<RollbackDomain.Checkpoint, I, E> engine, RollbackRound round) {
        this.round = round;
        this.domain = domain;
        this.engine = engine;
    }

    /**
     * Bootstrap supplies all gameplay registries and platform services. The runtime
     * adds its own manager/scheduler/adapter roots, but cannot discover arbitrary addon
     * globals. No engine operation, including initial capture, runs outside the domain.
     */
    public static <I, E> RollbackCombatRuntime<I, E> create(Environment environment, Execution<I, E> execution,
                                                          Runnable bootstrap, Map<UUID, I> initialInputs,
                                                          RollbackEngine.Limits limits, long epochMillis, long epochNanos) {
        return create(environment, execution, bootstrap, initialInputs, limits, epochMillis, epochNanos, false, null);
    }

    /** Same native/ability simulation; confirmation and replacements are owned by the server revision stream. */
    public static <I, E> RollbackCombatRuntime<I, E> createReplica(Environment environment, Execution<I, E> execution,
            Runnable bootstrap, Map<UUID, I> initialInputs, RollbackEngine.Limits limits, long epochMillis, long epochNanos) {
        return create(environment, execution, bootstrap, initialInputs, limits, epochMillis, epochNanos, true, null);
    }

    /** Match state uses the same tick and checkpoint as native movement and ability progression. */
    public static <I, E> RollbackCombatRuntime<I, E> createMatch(Environment environment, Execution<I, E> execution,
            Runnable bootstrap, Map<UUID, I> initialInputs, RollbackEngine.Limits limits,
            long epochMillis, long epochNanos, RollbackRound round) {
        return create(environment, execution, bootstrap, initialInputs, limits, epochMillis, epochNanos, false, Objects.requireNonNull(round));
    }

    public static <I, E> RollbackCombatRuntime<I, E> createMatchReplica(Environment environment, Execution<I, E> execution,
            Runnable bootstrap, Map<UUID, I> initialInputs, RollbackEngine.Limits limits,
            long epochMillis, long epochNanos, RollbackRound round) {
        return create(environment, execution, bootstrap, initialInputs, limits, epochMillis, epochNanos, true, Objects.requireNonNull(round));
    }

    /**
     * Assemble a decoded duel seed into an authoritative or replica match. Imported
     * configuration and gameplay roots are mandatory and installed only inside the
     * domain. Loader/addon shared fields and native services remain explicit in base.
     */
    // UUID equality cannot distinguish two replicas with different owned bodies.
    @SuppressWarnings("WrapperReferenceEquality")
    public static <I, E> RollbackCombatRuntime<I, E> createImportedMatch(Environment base,
            RollbackGameplayGraph.Imported imported, Execution<I, E> execution, Runnable afterInstall,
            Map<UUID, I> initialInputs, RollbackEngine.Limits limits, long epochMillis, long epochNanos,
            RollbackRound round, boolean replica) {
        Objects.requireNonNull(base); Objects.requireNonNull(imported); Objects.requireNonNull(afterInstall);
        Objects.requireNonNull(round);
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Import a match before replay");
        if (!(base.platform() instanceof RollbackPlatform platform))
            throw new IllegalArgumentException("Imported match requires the private roster platform");
        var players = imported.bending().players();
        if (!players.keySet().equals(initialInputs.keySet()) || !players.keySet().equals(round.participants())
                || platform.players().onlinePlayers().size() != players.size())
            throw new IllegalArgumentException("Imported match roster differs from runtime");
        for (var entry : players.entrySet()) {
            if (platform.players().getPlayer(entry.getKey()) != entry.getValue().getPlayer())
                throw new IllegalArgumentException("Imported player differs from private platform body");
        }
        var shared = new LinkedHashSet<>(base.shared());
        shared.addAll(RollbackBendingState.sharedFields());
        var local = new ArrayList<Object>(base.local());
        local.addAll(imported.roots());
        var environment = new Environment(base.graph(), shared, local, platform, base.items(), base.prediction(), imported.configuration());
        return create(environment, execution, () -> { imported.bending().install(); afterInstall.run(); },
                initialInputs, limits, epochMillis, epochNanos, replica, round);
    }

    private static <I, E> RollbackCombatRuntime<I, E> create(Environment environment, Execution<I, E> execution,
            Runnable bootstrap, Map<UUID, I> initialInputs, RollbackEngine.Limits limits, long epochMillis, long epochNanos, boolean replica, RollbackRound round) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(limits, "limits");
        // Existing ability progression, tick counters and scheduled delays use Minecraft ticks.
        if (limits.stepNanos() != 50_000_000L) throw new IllegalArgumentException("Combat requires 50ms ticks");
        if (replica && limits.rollbackTicks() < 1) throw new IllegalArgumentException("Client requires unconfirmed history capacity");
        if (!(environment.platform().scheduler() instanceof RollbackScheduler scheduler)) {
            throw new IllegalArgumentException("Combat requires a private rollback scheduler");
        }
        if (scheduler.tick() != 0) throw new IllegalArgumentException("Combat scheduler has already advanced");
        var orderedInputs = Map.copyOf(initialInputs);
        if (orderedInputs.isEmpty() || orderedInputs.size() > 128) throw new IllegalArgumentException("Participant count");

        if (round != null && (round.tick() != 0 || !round.participants().equals(orderedInputs.keySet()))) {
            throw new IllegalArgumentException("Combat requires a fresh round with the same roster");
        }
        var state = new RuntimeState<>(scheduler, execution);
        var local = new ArrayList<Object>(environment.local());
        local.add(state);
        if (round != null) local.add(round);
        var shared = new LinkedHashSet<>(environment.shared());
        shared.addAll(RollbackStateGraph.staticFields(BendingManager.class, field -> true));
        shared.addAll(RollbackStateGraph.staticFields(ProjectKorra.class,
                field -> field.getName().equals("time_step") || field.getName().equals("collisionManager")));
        final RollbackDomain domain;
        // The manager's previous-tick time must start at the same epoch as the engine.
        try (var clock = RollbackClock.at(epochMillis, epochNanos, 0, limits.stepNanos())) {
            domain = RollbackDomain.create(environment.graph(), shared, local, environment.platform(),
                    environment.items(), environment.prediction(), environment.configuration(), () -> {
                        bootstrap.run();
                        state.manager = new BendingManager();
                    });
        }
        var engine = domain.call(() -> {
            var simulation = new RollbackSimulation<RollbackDomain.Checkpoint, I, E>() {
                @Override public RollbackDomain.Checkpoint snapshot() { return domain.capture(); }
                @Override public void restore(RollbackDomain.Checkpoint snapshot) { domain.restore(snapshot); }
                @Override public I predict(UUID participant, I previous) { return execution.predict(participant, previous); }
                @Override public void step(long tick, Map<UUID, I> inputs, RollbackStep<E> effects) {
                    Throwable failure = null;
                    try {
                        if (round != null) round.beginTick(tick);
                        execution.begin(effects);
                        scheduler.advance(tick);
                        // The engine supplies UUID order, independent of network arrival order.
                        inputs.forEach(execution::input);
                        execution.tickWorld(tick);
                        state.manager.run();
                    } catch (RuntimeException | Error exception) {
                        failure = exception;
                        throw exception;
                    } finally {
                        try { execution.end(); }
                        catch (RuntimeException | Error cleanupFailure) {
                            if (failure == null) throw cleanupFailure;
                            if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
            };
            return replica ? RollbackEngine.replica(simulation, orderedInputs, limits, epochMillis, epochNanos)
                    : new RollbackEngine<>(simulation, orderedInputs, limits, epochMillis, epochNanos);
        });
        return new RollbackCombatRuntime<>(domain, engine, round);
    }

    public RollbackEngine.Submission submit(UUID participant, long tick, I input) {
        requireUsable();
        return domain.call(() -> engine.submit(participant, tick, input));
    }

    public RollbackEngine.Update<RollbackDomain.Checkpoint, I, E> advance() {
        requireUsable();
        return domain.call(engine::advance);
    }

    public RollbackEngine.Update<RollbackDomain.Checkpoint, I, E> reconcile() {
        requireUsable();
        return domain.call(engine::reconcile);
    }

    @Override public RollbackEngine.Update<RollbackDomain.Checkpoint, I, E> correctState(long tick, RollbackDomain.Checkpoint state) {
        requireUsable(); return domain.call(() -> engine.correctState(tick, state));
    }
    /**
     * Install validated authoritative data into the existing private objects at the
     * confirmed tick, then replay retained input. Importers must bind all decoded
     * state to this domain, never install live handles or publish outputs.
     */
    public RollbackEngine.Update<RollbackDomain.Checkpoint, I, E> importConfirmedState(long tick, Runnable importer) {
        requireUsable(); Objects.requireNonNull(importer, "importer");
        if (!engine.replica()) throw new IllegalStateException("Only replicas import repair state");
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Import requires a live tick boundary");
        if (tick != engine.confirmed().tick()) throw new IllegalArgumentException("State import must match the confirmed frontier");
        return domain.call(() -> {
            domain.restore(engine.confirmed().state());
            importer.run();
            return engine.correctState(tick, domain.capture());
        });
    }

    @Override public boolean replica() { return engine.replica(); }
    @Override public RollbackEngine.Submission correct(UUID participant, long tick, I input) {
        requireUsable();
        return domain.call(() -> engine.correct(participant, tick, input));
    }
    @Override public RollbackEngine.Update<RollbackDomain.Checkpoint, I, E> confirm(long tick) {
        requireUsable();
        return domain.call(() -> engine.confirm(tick));
    }
    @Override public List<RollbackEngine.Frame<RollbackDomain.Checkpoint, I, E>> frames(long from, long through) {
        requireUsable();
        return domain.call(() -> engine.frames(from, through));
    }

    /** Detached loader payload paired with the exact replay frontier it describes. */
    public record Export(long tick, long confirmedTick, long revision, byte[] bytes) {
        public Export {
            if (confirmedTick < 0 || tick < confirmedTick || revision < 0)
                throw new IllegalArgumentException("Export frontier");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /**
     * Read current settled state with this runtime's private registries and services installed.
     * The loader encodes native bodies, terrain and bending state together in this callback;
     * it must only read state and return detached bytes, without publishing live effects.
     * Pending corrections must first be reconciled and published by the authority owner.
     * This method never silently replays input or advances the confirmation frontier.
     */
    public Export exportState(java.util.function.Supplier<byte[]> encoder) {
        requireUsable();
        Objects.requireNonNull(encoder, "encoder");
        if (RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Export requires a live tick boundary");
        var frontier = engine.diagnostics();
        // Validate before entering the domain: an unreconciled input is recoverable,
        // and must not poison the private domain or export the superseded branch.
        engine.frames(frontier.tick(), frontier.tick());
        return domain.call(() -> new Export(frontier.tick(), frontier.confirmedTick(), frontier.revision(), encoder.get()));
    }

    /**
     * Export the authoritative confirmed checkpoint without replacing the current head.
     * The encoder has the same detached/read-only contract as exportState. Both private
     * head state and outside registries are restored even if encoding fails.
     */
    public Export exportConfirmedState(java.util.function.Supplier<byte[]> encoder) {
        requireUsable(); Objects.requireNonNull(encoder, "encoder");
        if (engine.replica()) throw new IllegalStateException("Only authority exports confirmed repair state");
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Export requires a live tick boundary");
        var frontier = engine.diagnostics();
        engine.frames(frontier.tick(), frontier.tick());
        var confirmed = engine.confirmed();
        return domain.call(() -> {
            var current = domain.capture();
            Throwable failure = null;
            try {
                domain.restore(confirmed.state());
                return new Export(confirmed.tick(), confirmed.tick(), frontier.revision(), encoder.get());
            } catch (RuntimeException | Error problem) { failure = problem; throw problem; }
            finally {
                try { domain.restore(current); }
                catch (RuntimeException | Error restoreFailure) {
                    if (failure != null) failure.addSuppressed(restoreFailure); else throw restoreFailure;
                }
            }
        });
    }

    public RollbackEngine.Diagnostics diagnostics() { return engine.diagnostics(); }
    @Override public List<UUID> participants() { return engine.participants(); }
    @Override public RollbackEngine.Limits limits() { return engine.limits(); }
    public boolean failed() { return deliveryFailed || domain.failed() || engine.diagnostics().failed(); }

    /** Called by the authority transport after publishing its confirmed frontier, outside replay. */
    public void deliverConfirmedDefeats(java.util.function.Consumer<RollbackRound.Defeat> delivery) {
        requireUsable(); Objects.requireNonNull(delivery);
        if (round == null || engine.replica()) throw new IllegalStateException("Only an authoritative match delivers live defeats");
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Live results cannot be delivered during replay");
        long confirmed = engine.diagnostics().confirmedTick();
        delivering = true;
        try { round.finalizeThrough(confirmed, delivery); }
        catch (RuntimeException | Error failure) { deliveryFailed = true; throw failure; }
        finally { delivering = false; }
    }

    private void requireUsable() {
        if (delivering || failed()) throw new IllegalStateException("Combat runtime is delivering results or has failed");
    }

    private static final class RuntimeState<I, E> implements RollbackStateCell<Void> {
        private final RollbackScheduler scheduler;
        private final Execution<I, E> execution;
        private BendingManager manager;
        private RuntimeState(RollbackScheduler scheduler, Execution<I, E> execution) {
            this.scheduler = scheduler;
            this.execution = execution;
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
        @Override public Collection<?> rollbackReferences() {
            return manager == null ? List.of(scheduler, execution) : List.of(scheduler, execution, manager);
        }
    }
}
