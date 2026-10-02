package com.projectkorra.projectkorra.prediction.rollback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Bounded, single-threaded input prediction, replay and finalization. The runtime adapter
 * owns the complete simulation; the transport owns session authentication and clock mapping.
 * Neither ability-specific state nor live platform callbacks belong in this engine.
 */
public final class RollbackEngine<S, I, E> implements RollbackReplicaTimeline<S, I, E> {
    public record Limits(int rollbackTicks, int futureTicks, int effectsPerTick, long stepNanos) {
        public Limits {
            if (rollbackTicks < 0 || rollbackTicks > 200 || futureTicks < 0 || futureTicks > 20
                    || effectsPerTick < 1 || effectsPerTick > 16_384
                    || stepNanos < 1_000_000L || stepNanos > 1_000_000_000L) {
                throw new IllegalArgumentException("Invalid rollback limits");
            }
        }
    }

    public enum Submission {
        ACCEPTED, DUPLICATE, UNKNOWN_PARTICIPANT, FINALIZED, TOO_FAR_AHEAD, CONFLICTING_INPUT
    }

    /** Stable within a finalized session. The bridge supplies the session ID for delivery. */
    public record Effect<E>(long tick, int ordinal, E value) {}

    public record Frame<S, I, E>(long tick, S state, Map<UUID, I> inputs, List<E> effects) {
        public Frame {
            Objects.requireNonNull(state, "state");
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            effects = List.copyOf(effects);
        }
    }

    /** Provisional head and irreversible frontier are separate, even when no effects occurred. */
    public record Update<S, I, E>(Frame<S, I, E> head, Frame<S, I, E> confirmed,
                                   long revision, long replayedFrom, List<Effect<E>> finalizedEffects) {
        public Update {
            finalizedEffects = List.copyOf(finalizedEffects);
        }
    }

    public record Diagnostics(long tick, long confirmedTick, long revision, long replayedSteps,
                              int snapshots, int queuedInputTicks, boolean failed) {}

    private final RollbackSimulation<S, I, E> simulation;
    private final Limits limits;
    private final boolean replica;
    private final long epochMillis;
    private final long epochNanos;
    private final Thread owner = Thread.currentThread();
    private final List<UUID> participants;
    private final NavigableMap<Long, Frame<S, I, E>> frames = new TreeMap<>();
    private final NavigableMap<Long, Map<UUID, I>> actualInputs = new TreeMap<>();
    private Frame<S, I, E> head;
    private Frame<S, I, E> confirmed;
    private long dirtyFrom = Long.MAX_VALUE;
    private long revision;
    private long replayedSteps;
    private boolean failed;

    public RollbackEngine(final RollbackSimulation<S, I, E> simulation, final Map<UUID, I> initialInputs,
                          final Limits limits, final long epochMillis) {
        this(simulation, initialInputs, limits, epochMillis, System.nanoTime());
    }

    /** Supply both clock anchors when importing runtime timers created before the session. */
    public RollbackEngine(final RollbackSimulation<S, I, E> simulation, final Map<UUID, I> initialInputs,
                          final Limits limits, final long epochMillis, final long epochNanos) {
        this(simulation, initialInputs, limits, epochMillis, epochNanos, false);
    }

    /** Client history is retained until explicitly confirmed; rollbackTicks is its unconfirmed capacity. */
    public static <S, I, E> RollbackEngine<S, I, E> replica(RollbackSimulation<S, I, E> simulation,
            Map<UUID, I> initialInputs, Limits limits, long epochMillis, long epochNanos) {
        if (limits.rollbackTicks() < 1) throw new IllegalArgumentException("Client prediction requires retained history");
        return new RollbackEngine<>(simulation, initialInputs, limits, epochMillis, epochNanos, true);
    }

    private RollbackEngine(final RollbackSimulation<S, I, E> simulation, final Map<UUID, I> initialInputs,
                           final Limits limits, final long epochMillis, final long epochNanos, boolean replica) {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.replica = replica;
        this.epochMillis = epochMillis;
        this.epochNanos = epochNanos;
        if (initialInputs.isEmpty() || initialInputs.size() > 128) throw new IllegalArgumentException("Participant count");
        final Map<UUID, I> ordered = new TreeMap<>(initialInputs);
        ordered.forEach((id, input) -> Objects.requireNonNull(input, "initial input"));
        participants = List.copyOf(ordered.keySet());
        head = new Frame<>(0, simulation.snapshot(), ordered, List.of());
        confirmed = head;
        frames.put(0L, head);
    }

    /**
     * A participant gets exactly one immutable input per tick. Out-of-order delivery can
     * fill a missing tick, but may never rewrite an already accepted input. Transport must
     * derive participant identity from the authenticated connection, not from payload data.
     */
    public Submission submit(final UUID participant, final long tick, final I input) {
        return putInput(participant, tick, input, false);
    }

    @Override public boolean replica() { checkThread(); return replica; }

    /** Trusted client owner may replace speculation; this is never allowed on the authority engine. */
    @Override public Submission correct(UUID participant, long tick, I input) {
        requireReplica();
        return putInput(participant, tick, input, true);
    }

    private Submission putInput(UUID participant, long tick, I input, boolean replace) {
        checkUsable();
        Objects.requireNonNull(input, "input");
        if (!head.inputs().containsKey(participant)) return Submission.UNKNOWN_PARTICIPANT;
        if (tick <= confirmed.tick()) return Submission.FINALIZED;
        // The next scheduled tick is always admissible; futureTicks is additional lead.
        if (tick > head.tick() && tick - head.tick() - 1 > limits.futureTicks()) return Submission.TOO_FAR_AHEAD;
        final Map<UUID, I> inputs = actualInputs.computeIfAbsent(tick, ignored -> new LinkedHashMap<>());
        final I previous = inputs.get(participant);
        if (previous != null && previous.equals(input)) return Submission.DUPLICATE;
        if (previous != null && !replace) return Submission.CONFLICTING_INPUT;
        inputs.put(participant, input);
        if (tick <= head.tick() && !input.equals(frames.get(tick).inputs().get(participant))) {
            dirtyFrom = Math.min(dirtyFrom, tick);
        }
        return Submission.ACCEPTED;
    }

    /**
     * Batches every pending correction into at most one replay before advancing. Returned
     * finalized effects are delivered only once by this engine; the platform must commit
     * them idempotently using session/tick/ordinal and stop the session on commit failure.
     */
    public Update<S, I, E> advance() {
        checkUsable();
        if (replica && head.tick() - confirmed.tick() >= limits.rollbackTicks()) {
            throw new IllegalStateException("Client prediction history is full; wait for authority confirmation");
        }
        final long replayedFrom = reconcileInternal();
        head = execute(Math.incrementExact(head.tick()), head);
        frames.put(head.tick(), head);
        final long frontier = replica ? confirmed.tick() : Math.max(0, head.tick() - limits.rollbackTicks());
        var effects = finalizeThrough(frontier);
        return new Update<>(head, confirmed, revision, replayedFrom, effects);
    }

    /** Confirm only after applying the complete authoritative inputs through this tick. */
    @Override public Update<S, I, E> confirm(long tick) {
        requireReplica(); checkUsable();
        if (tick < confirmed.tick() || tick > head.tick()) throw new IllegalArgumentException("Invalid authority frontier");
        long replayedFrom = reconcileInternal();
        var effects = finalizeThrough(tick);
        return new Update<>(head, confirmed, revision, replayedFrom, effects);
    }

    private List<Effect<E>> finalizeThrough(long frontier) {
        final List<Effect<E>> effects = new ArrayList<>();
        if (frontier > confirmed.tick()) {
            for (Frame<S, I, E> frame : frames.subMap(confirmed.tick(), false, frontier, true).values()) {
                for (int ordinal = 0; ordinal < frame.effects().size(); ordinal++) {
                    effects.add(new Effect<>(frame.tick(), ordinal, frame.effects().get(ordinal)));
                }
            }
            confirmed = frames.get(frontier);
            frames.headMap(frontier, false).clear();
            actualInputs.headMap(frontier, true).clear();
        }
        return List.copyOf(effects);
    }

    /** Reconcile without advancing time or finalizing additional effects. */
    public Update<S, I, E> reconcile() {
        checkUsable();
        final long replayedFrom = reconcileInternal();
        return new Update<>(head, confirmed, revision, replayedFrom, List.of());
    }

    public Frame<S, I, E> head() {
        checkThread();
        return head;
    }

    public Frame<S, I, E> confirmed() {
        checkThread();
        return confirmed;
    }

    public Diagnostics diagnostics() {
        checkThread();
        return new Diagnostics(head.tick(), confirmed.tick(), revision, replayedSteps,
                frames.size(), actualInputs.size(), failed);
    }

    @Override public List<UUID> participants() { checkThread(); return participants; }
    @Override public Limits limits() { checkThread(); return limits; }

    @Override public List<Frame<S, I, E>> frames(long from, long through) {
        checkUsable();
        if (dirtyFrom <= through) throw new IllegalStateException("Reconcile before reading simulation history");
        if (from < confirmed.tick() || through > head.tick() || from > through) throw new IllegalArgumentException("History is not retained");
        return List.copyOf(frames.subMap(from, true, through, true).values());
    }

    private void requireReplica() {
        checkThread();
        if (!replica) throw new IllegalStateException("Authority inputs and finalization cannot be overridden");
    }

    private long reconcileInternal() {
        if (dirtyFrom == Long.MAX_VALUE) return -1;
        final long from = dirtyFrom;
        final long through = head.tick();
        final Frame<S, I, E> start = frames.get(from - 1);
        try {
            simulation.restore(start.state());
            Frame<S, I, E> previous = start;
            for (long tick = from; tick <= through; tick++) {
                previous = execute(tick, previous);
                frames.put(tick, previous);
                replayedSteps++;
            }
            head = previous;
            revision++;
            dirtyFrom = Long.MAX_VALUE;
            return from;
        } catch (RuntimeException | Error exception) {
            failed = true;
            throw exception;
        }
    }

    private Frame<S, I, E> execute(final long tick, final Frame<S, I, E> previous) {
        final Map<UUID, I> actual = actualInputs.getOrDefault(tick, Map.of());
        final Map<UUID, I> inputs = new LinkedHashMap<>();
        final RollbackStep<E> context = new RollbackStep<>(tick, limits.effectsPerTick());
        try (var ignored = RollbackClock.at(epochMillis, epochNanos, tick, limits.stepNanos())) {
            for (UUID participant : participants) {
                final I input = actual.get(participant);
                inputs.put(participant, input == null
                        ? Objects.requireNonNull(simulation.predict(participant, previous.inputs().get(participant)), "predicted input")
                        : input);
            }
            simulation.step(tick, Collections.unmodifiableMap(inputs), context);
            return new Frame<>(tick, simulation.snapshot(), inputs, context.finish());
        } catch (RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            context.finish();
        }
    }

    private void checkUsable() {
        checkThread();
        if (failed) throw new IllegalStateException("Rollback simulation failed; this session cannot continue");
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Rollback engine crossed threads");
    }
}
