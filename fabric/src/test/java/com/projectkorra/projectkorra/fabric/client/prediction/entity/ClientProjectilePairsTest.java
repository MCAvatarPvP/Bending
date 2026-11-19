package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ProjectileOwner;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.function.LongUnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class ClientProjectilePairsTest {
    private final UUID caster = UUID.randomUUID(), server = UUID.randomUUID(), session = UUID.randomUUID();
    private final LongUnaryOperator mapping = id -> id == 32 ? 7 : 0;
    private final ClientProjectilePairs<Shot> shots = new ClientProjectilePairs<>(shot -> shot.alive);
    private static final class Shot { boolean alive = true; }

    private ProjectileOwner receipt(UUID owner, int ordinal, int id, UUID uuid, String type) {
        return new ProjectileOwner(session, 32, ordinal, owner, id, uuid, "RopeDart", type);
    }

    @Test void delayedInputTagAndLocalLaunchCanFollowBothNativeReceiptAndSpawn() {
        shots.receipt(receipt(caster, 1, 90, server, "ARROW"), 3);
        assertFalse(shots.hide(90, server, "ARROW", mapping));
        Shot shot = new Shot();
        shots.launch(7, 1, caster, "ARROW", "RopeDart", shot);
        assertFalse(shots.hide(90, server, "ARROW", id -> 0));
        assertFalse(shots.hide(91, server, "ARROW", mapping));
        assertTrue(shots.hide(90, server, "ARROW", mapping));
        shots.retire(7);
        assertTrue(shots.hide(90, server, "ARROW", mapping));
        shot.alive = false;
        assertFalse(shots.hide(90, server, "ARROW", mapping));
        assertSame(shot, shots.remove(90, mapping));
    }

    @Test void nearbyStrangerDifferentProjectileAndRecycledIdsNeverHideOrdinaryArrows() {
        Shot first = new Shot(), second = new Shot();
        shots.launch(7, 1, caster, "ARROW", "RopeDart", first);
        shots.launch(7, 2, caster, "SNOWBALL", "RopeDart", second);
        shots.receipt(receipt(UUID.randomUUID(), 1, 80, server, "ARROW"), 4);
        assertFalse(shots.hide(80, server, "ARROW", mapping));
        shots.receipt(receipt(caster, 1, 81, server, "SNOWBALL"), 4);
        assertFalse(shots.hide(81, server, "SNOWBALL", mapping));
        shots.receipt(receipt(caster, 1, 82, server, "ARROW"), 4);
        shots.receipt(receipt(caster, 2, 83, server, "SNOWBALL"), 4);
        assertFalse(shots.hide(82, UUID.randomUUID(), "ARROW", mapping));
        assertTrue(shots.hide(82, server, "ARROW", mapping));
        assertTrue(shots.hide(83, server, "SNOWBALL", mapping));
        shots.rollback(7);
        assertFalse(shots.hide(82, server, "ARROW", mapping));
        assertNull(shots.remove(83, mapping));
    }
}
