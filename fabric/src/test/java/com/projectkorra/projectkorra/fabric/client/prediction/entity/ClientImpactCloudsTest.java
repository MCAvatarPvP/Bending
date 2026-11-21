package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ProjectileImpact;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ProjectileOwner;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientImpactCloudsTest {
    private final UUID session = UUID.randomUUID(), caster = UUID.randomUUID(), id = UUID.randomUUID();
    private final Set<Object> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
    private long mapped;
    private final ClientImpactClouds<Object> clouds = new ClientImpactClouds<>(discarded::add, a -> a == 24 ? mapped : 0);

    private ProjectileOwner owner() { return new ProjectileOwner(session, 24, 2, caster, 33, id, "Smokescreen", "SNOWBALL"); }
    private ProjectileImpact outcome(boolean accepted) { return new ProjectileImpact(session, 24, 2, caster, 33, id, accepted); }

    @Test void canceledLobbyHitDiscardsOnlyItsOwnCloudEvenWhenReceiptsAreReordered() {
        Object shot = new Object(), otherShot = new Object(), ours = new Object(), other = new Object();
        clouds.launch(17, 1, caster, otherShot);
        clouds.launch(17, 2, caster, shot);
        clouds.cloud(shot, ours);
        clouds.cloud(otherShot, other);
        clouds.outcome(outcome(false), 8);
        clouds.owner(owner(), 9);
        assertTrue(discarded.isEmpty());
        mapped = 17;
        clouds.expire(0);
        assertEquals(Set.of(ours), discarded);
    }

    @Test void earlierCancellationRetiresLaterPredictionButAcceptedCloudContinues() {
        Object shot = new Object(), cloud = new Object();
        clouds.launch(17, 2, caster, shot);
        mapped = 17;
        clouds.owner(owner(), 1);
        clouds.outcome(outcome(false), 2);
        clouds.cloud(shot, cloud);
        assertTrue(discarded.contains(cloud));
        clouds.clear(); discarded.clear();
        clouds.launch(17, 2, caster, shot);
        clouds.owner(owner(), 3);
        clouds.cloud(shot, cloud);
        clouds.outcome(outcome(true), 4);
        assertTrue(discarded.isEmpty());
    }

    @Test void wrongSourceIdentityCannotRetractACorrectlyPredictedCloud() {
        Object shot = new Object(), cloud = new Object();
        mapped = 17;
        clouds.launch(17, 2, caster, shot);
        clouds.owner(owner(), 4);
        clouds.cloud(shot, cloud);
        clouds.outcome(new ProjectileImpact(session, 24, 2, caster, 34, id, false), 5);
        clouds.outcome(new ProjectileImpact(session, 24, 2, UUID.randomUUID(), 33, id, false), 6);
        clouds.outcome(new ProjectileImpact(session, 24, 1, caster, 33, id, false), 7);
        assertTrue(discarded.isEmpty());
        clouds.outcome(outcome(false), 8);
        assertEquals(Set.of(cloud), discarded);
    }
}
