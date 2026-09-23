package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.activation.ActivationContext;
import com.projectkorra.projectkorra.ability.activation.ActivationHandler;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ClickType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RollbackActivationTest {
    private static final UUID PARTICIPANT = new UUID(0, 1);
    private static final ClickType INPUT = ClickType.LEFT_CLICK;

    @Test void lateOneShotInputReplaysExistingRegisteredHandlersWithoutRepeatingTheAction() throws Exception {
        try (var registry = new Registry()) {
            List<Long> activations = new ArrayList<>();
            AbilityActivationManager.registerGlobal(INPUT, context -> {
                activations.add(RollbackClock.millis());
                return true;
            });
            var graph = new RollbackStateGraph(value -> false, field -> true, 100);
            var engine = new RollbackEngine<>(new RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, String>() {
                @Override public RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(activations), List.of()); }
                @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
                @Override public Boolean predict(UUID participant, Boolean previous) { return false; }
                @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> effects) {
                    if (inputs.get(PARTICIPANT)) {
                        assertTrue(AbilityActivationManager.dispatchGlobal(context()));
                        effects.emit("activated");
                    }
                }
            }, Map.of(PARTICIPANT, false), new RollbackEngine.Limits(2, 1, 10, 50_000_000L), 1_000);
            engine.advance();
            engine.advance();
            assertTrue(activations.isEmpty());
            assertEquals(RollbackEngine.Submission.ACCEPTED, engine.submit(PARTICIPANT, 1, true));
            engine.reconcile();
            assertEquals(List.of(1_050L), activations);
            var finalized = engine.advance().finalizedEffects();
            assertEquals(1, finalized.size());
            assertEquals(1, finalized.getFirst().tick());
            assertEquals("activated", finalized.getFirst().value());
            assertTrue(engine.advance().finalizedEffects().isEmpty());
            assertEquals(List.of(1_050L), activations);
        }
    }

    @Test void failedRegisteredHandlerCannotFinalizeAPartlyAppliedAction() throws Exception {
        try (var registry = new Registry()) {
            IllegalArgumentException failure = new IllegalArgumentException("provisional mutation failed");
            AbilityActivationManager.registerGlobal(INPUT, context -> { throw failure; });
            AbilityActivationManager.registerGlobal(INPUT, context -> { fail("later handlers must not run"); return true; });
            var engine = new RollbackEngine<>(new RollbackSimulation<Integer, Boolean, String>() {
                @Override public Integer snapshot() { return 0; }
                @Override public void restore(Integer state) { }
                @Override public Boolean predict(UUID participant, Boolean previous) { return false; }
                @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> effects) {
                    effects.emit("provisional effect must never finalize");
                    AbilityActivationManager.dispatchGlobal(context());
                }
            }, Map.of(PARTICIPANT, false), new RollbackEngine.Limits(0, 0, 10, 50_000_000L), 1_000);
            IllegalStateException thrown = assertThrows(IllegalStateException.class, engine::advance);
            assertSame(failure, thrown.getCause());
            assertTrue(engine.diagnostics().failed());
            assertEquals(0, engine.diagnostics().confirmedTick());
            assertThrows(IllegalStateException.class, engine::advance);
        }
    }

    @Test void reflectedComboConstructorFailuresPropagateIntoReplay() {
        String name = "RollbackConstructorFixture";
        var combos = ComboManager.getComboAbilities();
        var previous = combos.put(name, new ComboManager.ComboAbilityInfo(name, new ArrayList<>(), ExplodingCombo.class));
        try (var clock = RollbackClock.at(1_000, 1, 50_000_000)) {
            var failure = assertThrows(IllegalStateException.class, () -> ComboManager.createComboAbility(new Player(), name));
            assertTrue(failure.getMessage().contains(name));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause().getCause());
        } finally {
            if (previous == null) combos.remove(name); else combos.put(name, previous);
        }
    }

    public static final class ExplodingCombo {
        public ExplodingCombo(Player player) { throw new IllegalArgumentException("fixture constructor failure"); }
    }

    private static ActivationContext context() {
        return new ActivationContext(new Player(), null, INPUT).withAbilityName("RegisteredRollbackFixture");
    }

    private static final class Registry implements AutoCloseable {
        private final Map<ClickType, List<ActivationHandler>> handlers;
        private final List<ActivationHandler> previous;
        @SuppressWarnings("unchecked") Registry() throws Exception {
            Field field = AbilityActivationManager.class.getDeclaredField("GLOBAL_HANDLERS");
            field.setAccessible(true);
            handlers = (Map<ClickType, List<ActivationHandler>>) field.get(null);
            previous = handlers.remove(INPUT);
        }
        @Override public void close() {
            if (previous == null) handlers.remove(INPUT); else handlers.put(INPUT, previous);
        }
    }
}
