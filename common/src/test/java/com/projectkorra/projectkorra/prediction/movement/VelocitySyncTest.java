package com.projectkorra.projectkorra.prediction.movement;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySyncTest {

    @Test
    void predictedRemotePermitIsTargetScopedAndAlwaysRestored() {
        final UUID targetId = new UUID(1L, 2L);
        final Entity target = new FakeEntity(targetId);
        final Entity targetView = new FakeEntity(targetId);
        final Entity other = new FakeEntity(new UUID(3L, 4L));

        assertFalse(VelocitySync.isPredictedRemoteTarget(target));
        assertThrows(IllegalStateException.class, () ->
                VelocitySync.commitPredictedRemote(target, () -> {
                    assertTrue(VelocitySync.isPredictedRemoteTarget(target));
                    assertTrue(VelocitySync.isPredictedRemoteTarget(targetView),
                            "platform wrappers of the same native target must share the permit");
                    assertFalse(VelocitySync.isPredictedRemoteTarget(other),
                            "the permit must not grant velocity authority over another entity");
                    throw new IllegalStateException("test");
                }));
        assertFalse(VelocitySync.isPredictedRemoteTarget(target),
                "the velocity-only permit must not leak beyond the synchronous write");
    }

    private static final class FakeEntity extends Entity {
        private final UUID id;

        private FakeEntity(final UUID id) {
            this.id = id;
        }

        @Override
        public UUID getUniqueId() {
            return id;
        }
    }
}
