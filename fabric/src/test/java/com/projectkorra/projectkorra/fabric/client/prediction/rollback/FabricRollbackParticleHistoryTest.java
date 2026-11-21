package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackArenaActions.ParticleOutput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine.Effect;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackParticleHistoryTest {
    final UUID viewer = UUID.randomUUID();
    final List<String> draws = new ArrayList<>();
    final List<String> removed = new ArrayList<>();
    final FabricRollbackParticleHistory<String> history = new FabricRollbackParticleHistory<>(viewer, (tick, ordinal, effect, count) -> {
        String id = tick + "/" + ordinal + "/" + effect.particle() + "/" + count;
        draws.add(id);
        return List.of(id);
    }, removed::add);

    private ParticleOutput particle(String name, UUID target, int count) {
        return new ParticleOutput(target, new Pose(0, 0, 0, 0, 0), name, count, 0, 0, 0, 0, null, false);
    }

    @Test void ordinaryConfirmationKeepsTheOriginalVisualAliveWithoutDrawingAgain() {
        var cloud = particle("CLOUD", null, 1);
        history.frame(1, List.of("sound", cloud));
        history.frame(1, List.of("sound", cloud));
        history.confirm(1, List.of(new Effect<>(1, 0, "sound"), new Effect<>(1, 1, cloud)));
        history.clear();
        assertEquals(List.of("1/1/CLOUD/1"), draws);
        assertTrue(removed.isEmpty());
    }

    @Test void finalizationReplacesChangedEffectsAndRemovesDisappearedTicks() {
        var cloud = particle("CLOUD", null, 1);
        var smoke = particle("SMOKE", viewer, 3);
        history.frame(1, List.of(cloud));
        history.frame(2, List.of(cloud));
        history.frame(3, List.of(smoke));
        history.confirm(2, List.of(new Effect<>(2, 0, smoke)));
        assertEquals(List.of("1/0/CLOUD/1", "2/0/CLOUD/1", "3/0/SMOKE/3", "2/0/SMOKE/3"), draws);
        assertEquals(Set.of("1/0/CLOUD/1","2/0/CLOUD/1"), Set.copyOf(removed));
        history.clear();
        assertEquals(3, removed.size());
        assertEquals("3/0/SMOKE/3", removed.get(2));
    }

    @Test void replayHonorsRecipientAndBudgetAndRestoresPreviouslyMissingParticles() {
        var other = particle("CLOUD", UUID.randomUUID(), 10);
        var dense = particle("SMOKE", null, 20_000);
        var small = particle("CLOUD", viewer, 1);
        history.frame(1, List.of(other, dense, small));
        history.frame(1, List.of(other, small, dense));
        history.confirm(2, List.of(new Effect<>(1, 0, other), new Effect<>(1, 1, small), new Effect<>(1, 2, dense),
                new Effect<>(2, 0, small)));
        assertEquals(List.of("1/1/SMOKE/16384", "1/1/CLOUD/1", "1/2/SMOKE/16383", "2/0/CLOUD/1"), draws);
        assertEquals(List.of("1/1/SMOKE/16384"), removed);
    }
}
