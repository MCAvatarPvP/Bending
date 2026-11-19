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

    @Test void bothOwnerPayloadsMatchThePaperWireFieldsExactly() {
        var buffer = new net.minecraft.network.RegistryByteBuf(io.netty.buffer.Unpooled.buffer(),
                net.minecraft.registry.DynamicRegistryManager.EMPTY);
        try {
            UUID zero = new UUID(0, 0), owner = new UUID(0, 1), actor = new UUID(0, 2);
            var claim = new ProjectileOwner(zero, 300, 2, owner, 400, actor, "Smokescreen", "AREA_EFFECT_CLOUD");
            ProjectileOwner.CODEC.encode(buffer, claim);
            assertEquals("00".repeat(16) + "ac0202" + "00".repeat(15) + "019003" + "00".repeat(15)
                    + "020b536d6f6b6573637265656e11415245415f4546464543545f434c4f5544", io.netty.buffer.ByteBufUtil.hexDump(buffer));
            assertEquals(claim, ProjectileOwner.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            var melee = new com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.MeleeAction(
                    zero, 300, 4, "QuickStrike", 400, actor);
            com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.MeleeAction.CODEC.encode(buffer, melee);
            assertEquals("00".repeat(16) + "ac02040b517569636b537472696b659003" + "00".repeat(15) + "02",
                    io.netty.buffer.ByteBufUtil.hexDump(buffer));
            assertEquals(melee, com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.MeleeAction.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
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
        assertSame(shot, shots.remove(90, server, "ARROW", mapping));
    }

    @Test void smokeCloudParticlesFollowTheConfirmedImpactAndRetainServerFallback() {
        Shot cloud = new Shot();
        shots.launch(7, 2, caster, "AREA_EFFECT_CLOUD", "Smokescreen", cloud);
        shots.receipt(new ProjectileOwner(session, 32, 2, caster, 125, server, "Smokescreen", "AREA_EFFECT_CLOUD"), 12);
        assertTrue(shots.hide(125, server, "AREA_EFFECT_CLOUD", mapping));
        cloud.alive = false;
        assertFalse(shots.hide(125, server, "AREA_EFFECT_CLOUD", mapping));
        assertNull(shots.remove(125, UUID.randomUUID(), "AREA_EFFECT_CLOUD", mapping));
        assertFalse(shots.tracked(125));
    }

    @Test void firstRenderRequiresTheShootersUniqueMatchingFlightAndYieldsToTheReceipt() {
        var shot = new Shot();
        shots.launch(7, 1, caster, "ARROW", "RopeDart", shot);
        shots.nativeSpawn(8, server, UUID.randomUUID(), "ARROW", 40, local -> true);
        assertFalse(shots.hide(8, server, "ARROW", mapping));
        shots.nativeSpawn(9, server, caster, "ARROW", 40, local -> false);
        assertFalse(shots.hide(9, server, "ARROW", mapping));
        shots.nativeSpawn(9, server, caster, "ARROW", 40, local -> true);
        assertTrue(shots.hide(9, server, "ARROW", mapping));
        assertFalse(shots.hide(9, UUID.randomUUID(), "ARROW", mapping));
        shots.receipt(receipt(caster, 1, 9, server, "ARROW"), 41);
        assertTrue(shots.hide(9, server, "ARROW", id -> 0));
        shots.expirePreviews(43);
        assertFalse(shots.hide(9, server, "ARROW", id -> 0));
        assertTrue(shots.hide(9, server, "ARROW", mapping));
        shots.clear();
        shots.launch(7, 1, caster, "ARROW", "RopeDart", shot);
        shots.launch(8, 1, caster, "ARROW", "RopeDart", new Shot());
        shots.nativeSpawn(9, server, caster, "ARROW", 40, local -> true);
        assertFalse(shots.hide(9, server, "ARROW", mapping));
    }

    @Test void simultaneousStickyBombsPairOnlyWithTheirOwnFlyingBullets() {
        Shot bomb = new Shot();
        shots.launch(7, 1, caster, "SHULKER_BULLET", "StickyBomb", bomb);
        shots.receipt(new ProjectileOwner(session, 32, 1, UUID.randomUUID(), 7, server, "StickyBomb", "SHULKER_BULLET"), 1);
        assertFalse(shots.hide(7, server, "SHULKER_BULLET", mapping));
        shots.receipt(new ProjectileOwner(session, 32, 1, caster, 8, server, "StickyBomb", "SHULKER_BULLET"), 1);
        assertTrue(shots.hide(8, server, "SHULKER_BULLET", mapping));
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
        assertNull(shots.remove(83, server, "SNOWBALL", mapping));
    }
}
