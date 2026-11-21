package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPresentationTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    @Test void stopReleasesRenderingBeforeEffectsCleanupEvenWhenThatCleanupFails() throws Exception {
        var roster = roster(); var timeline = timeline(); var sink = new Sink(); sink.failStop = true;
        try (var renderer = renderer(roster)) {
            var presentation = new FabricRollbackPresentation<>(roster, A, timeline, sink, renderer);
            presentation.update(timeline.reconcile()); assertEquals(1, sink.updates); assertRendered(roster, true);
            assertThrows(IllegalStateException.class, () -> presentation.stop(failure())); assertEquals(1, sink.stops); assertRendered(roster, false);
            presentation.stop(failure()); assertEquals(1, sink.stops);
        }
    }
    @Test void staleHeadsAndFailedOutputCannotLeaveAProvisionalViewInstalled() throws Exception {
        for (boolean stale : new boolean[]{false, true}) {
            var roster = roster(); var timeline = timeline(); var sink = new Sink();
            try (var renderer = renderer(roster)) {
                var presentation = new FabricRollbackPresentation<>(roster, A, timeline, sink, renderer);
                var old = timeline.reconcile(); presentation.update(old); assertRendered(roster, true);
                var next = timeline.advance(); sink.failUpdate = !stale;
                assertThrows(IllegalStateException.class, () -> presentation.update(stale ? old : next)); assertRendered(roster, false);
                assertEquals(stale ? 1 : 2, sink.updates);
                presentation.stop(failure()); assertEquals(1, sink.stops);
            }
        }
    }
    @Test void presentationRejectsForeignAndIncompletePlayerSets() throws Exception {
        var roster = roster(); var other = roster(); var timeline = timeline();
        try (var renderer = renderer(roster)) {
            assertThrows(IllegalArgumentException.class, () -> new FabricRollbackPresentation<>(Map.of(A, roster.players().get(A)), A, timeline, new Sink(), renderer));
            assertThrows(IllegalArgumentException.class, () -> new FabricRollbackPresentation<>(Map.of(A, roster.players().get(A), B, other.players().get(B)), A, timeline, new Sink(), renderer));
        }
    }
    private static FabricRollbackPlayerRenderer renderer(FabricRollbackRoster roster) {
        return new FabricRollbackPlayerRenderer(UUID.randomUUID(), roster.world().world(), Set.of(A, B), () -> true, () -> Vec3d.ZERO, position -> 123);
    }
    private static void assertRendered(FabricRollbackRoster roster, boolean expected) {
        var state = new PlayerEntityRenderState(); state.x = 999;
        try (var extraction = FabricRollbackPlayerRenderer.extracting()) { FabricRollbackPlayerRenderer.apply(roster.players().get(B).ownedPlayer(), state, 1); }
        assertEquals(expected ? roster.players().get(B).ownedPlayer().getX() : 999, state.x);
    }
    private static RollbackStartServerEndpoint.Failure failure() { return new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "test stop", null); }
    private static RollbackEngine<Integer, RollbackPlayerInput, String> timeline() {
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        // This fixture tests presentation lifecycle; native movement/collision corrections are covered in FabricRollbackExecutionTest.
        return RollbackEngine.replica(new RollbackSimulation<>() {
            @Override public Integer snapshot() { return 0; }
            @Override public void restore(Integer state) { }
            @Override public RollbackPlayerInput predict(UUID id, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> effects) { }
        }, Map.of(A, idle, B, idle), new RollbackEngine.Limits(10, 1, 10, 50_000_000), 0, 0);
    }
    private static final class Sink implements RollbackClientRuntime.Output<Integer, String> {
        int updates, stops; boolean failUpdate, failStop;
        @Override public void update(RollbackEngine.Update<Integer, RollbackPlayerInput, String> update) { updates++; if (failUpdate) throw new IllegalStateException("output failed"); }
        @Override public void stop(RollbackStartServerEndpoint.Failure failure) { stops++; if (failStop) throw new IllegalStateException("stop failed"); }
    }
}
