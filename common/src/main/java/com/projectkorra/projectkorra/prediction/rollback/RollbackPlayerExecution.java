package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Shared input/physics ordering for both native loaders and the existing bending loop. */
public abstract class RollbackPlayerExecution<E> implements RollbackCombatRuntime.Execution<RollbackPlayerInput, E>,
        RollbackStateCell<Map<UUID, Long>> {
    /**
     * Owned session services, captured with the execution. begin installs simulation
     * time and output routing before scheduled work/inputs. action handles any native
     * remainder after the common bending handler, honoring cancellation; sneak/slot
     * transitions have already been applied. Hand swings execute here after bending cancellation.
     * Flight/glide input uses the owned native source's
     * control-event adapter and is not passed to action. tickWorld advances remaining world state,
     * excluding the players ticked here. end unbinds outputs even after partial failure.
     * No method may publish live effects. Unimplemented interactions must fail explicitly.
     */
    public interface Services<E, S> extends RollbackStateCell<S> {
        void begin(RollbackStep<E> effects);
        void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result);
        void tickWorld(long tick);
        void end();
    }

    private final Thread thread = Thread.currentThread();
    private final Map<UUID, RollbackPlayer> players = new TreeMap<>();
    private final Map<UUID, Long> sequences = new TreeMap<>();
    private final Services<E, ?> services;
    private final Set<UUID> applied = new HashSet<>();
    private long tick = -1;
    private boolean worldTicked;
    private RollbackControlEvents controlEvents;
    private final RollbackStepOutput<E> output = new RollbackStepOutput<>();
    private RollbackStepOutput<E>.Binding outputBinding;

    protected RollbackPlayerExecution(Collection<RollbackPlayer> participants, Services<E, ?> services) {
        if (RollbackClock.active()) throw new IllegalStateException("Construct player execution before replay");
        this.services = Objects.requireNonNull(services, "session services");
        if (participants.isEmpty() || participants.size() > 128) throw new IllegalArgumentException("Participant count");
        Object logicalWorld = null;
        for (var player : participants) {
            Objects.requireNonNull(player, "player");
            if (logicalWorld == null) logicalWorld = player.getWorld();
            if (player.getWorld() != logicalWorld) throw new IllegalArgumentException("Combat participants must share their private world");
            UUID id = player.getUniqueId();
            if (players.putIfAbsent(id, player) != null) throw new IllegalArgumentException("Duplicate participant");
            sequences.put(id, 0L);
        }
    }

    /** Stable destination for detached native effects; it rejects emissions outside this execution's tick. */
    public final java.util.function.Consumer<E> output() { requireThread(); return output; }

    /** Loader constructors validate that all logical views share the owned native state. */
    protected abstract void movementInput(RollbackPlayer player, RollbackMovementInput input);
    protected abstract void tickPlayer(RollbackPlayer player);
    protected abstract void swing(RollbackPlayer player, boolean offHand);

    @Override public final RollbackPlayerInput predict(UUID participant, RollbackPlayerInput previous) {
        requireThread(); participant(participant);
        return Objects.requireNonNull(previous, "previous input").predict();
    }

    @Override public final void begin(RollbackStep<E> effects) {
        requireIdle();
        if (!RollbackDomain.active() || !RollbackClock.active()) throw new IllegalStateException("No combat replay tick");
        tick = Objects.requireNonNull(effects, "effects").tick();
        worldTicked = false; applied.clear();
        outputBinding = output.open(effects);
        controlEvents = new RollbackControlEvents(players);
        services.begin(effects);
    }

    @Override public final void input(UUID id, RollbackPlayerInput input) {
        requireTick();
        var participant = participant(id);
        Objects.requireNonNull(input, "input");
        if (worldTicked || !applied.add(id)) throw new IllegalStateException("Player input already applied");
        if (!input.actions().isEmpty() && input.actions().getFirst().action().sequence() <= sequences.get(id)) {
            throw new IllegalArgumentException("Action sequence was already applied or moved backwards");
        }
        participant.setSprinting(input.sprinting());
        var movement = input.movement();
        for (var edge : input.actions()) {
            movementInput(participant, new RollbackMovementInput(movement.strafe(), movement.forward(), movement.jump(), edge.yaw(), edge.pitch()));
            PredictionDeterminism.run(edge.action().sequence(), edge.action().seed(), () -> {
                var result = RollbackInputActions.dispatch(participant, edge.action());
                if (edge.action().kind() == RollbackInputActions.Kind.SWING
                        || edge.action().kind() == RollbackInputActions.Kind.OFF_HAND_SWING) {
                    if (!result.cancelEvent()) swing(participant, edge.action().kind() == RollbackInputActions.Kind.OFF_HAND_SWING);
                    return;
                }
                // Flight/glide have their own native control adapters and no item/melee remainder.
                if (edge.action().kind() != RollbackInputActions.Kind.FLIGHT_START
                        && edge.action().kind() != RollbackInputActions.Kind.FLIGHT_STOP
                        && edge.action().kind() != RollbackInputActions.Kind.GLIDE_START) services.action(participant, edge, result);
            });
            sequences.put(id, edge.action().sequence());
        }
        // Turning after a click affects movement/next tick, not the earlier activation.
        movementInput(participant, movement);
    }

    @Override public final void tickWorld(long expectedTick) {
        requireTick();
        if (expectedTick != tick || worldTicked || applied.size() != players.size()) {
            throw new IllegalStateException("World tick requires exactly one input for every participant");
        }
        worldTicked = true;
        for (var participant : players.values()) {
            tickPlayer(participant);
            if (participant.isOnGround() && participant.isFlying()
                    && participant.getGameMode() != com.projectkorra.projectkorra.platform.mc.GameMode.SPECTATOR) {
                RollbackInputActions.flight(participant, false);
            }
        }
        services.tickWorld(tick);
    }

    @Override public final void end() {
        requireThread();
        if (tick == -1) return;
        try { services.end(); }
        finally {
            try { if (controlEvents != null) controlEvents.close(); }
            finally {
                try { if (outputBinding != null) outputBinding.close(); }
                finally { outputBinding = null; controlEvents = null; tick = -1; applied.clear(); worldTicked = false; }
            }
        }
    }

    @Override public final Map<UUID, Long> captureRollbackState() { requireIdle(); return Map.copyOf(sequences); }
    @Override public final void restoreRollbackState(Map<UUID, Long> state) {
        requireIdle();
        if (!state.keySet().equals(players.keySet()) || state.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Action checkpoint does not match participants");
        }
        sequences.clear(); sequences.putAll(state);
    }
    @Override public final List<?> rollbackReferences() {
        requireIdle();
        var roots = new ArrayList<Object>(); roots.add(services); roots.add(output);
        roots.addAll(players.values());
        return List.copyOf(roots);
    }
    private RollbackPlayer participant(UUID id) {
        var participant = players.get(Objects.requireNonNull(id, "participant"));
        if (participant == null) throw new IllegalArgumentException("Unknown combat participant");
        return participant;
    }
    private void requireThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Player execution crossed threads");
    }
    private void requireIdle() {
        requireThread();
        if (tick != -1) throw new IllegalStateException("Cannot snapshot or begin during player execution");
    }
    private void requireTick() {
        requireThread();
        if (tick == -1 || !RollbackDomain.active() || !RollbackClock.active()) throw new IllegalStateException("No active player execution");
    }
}
