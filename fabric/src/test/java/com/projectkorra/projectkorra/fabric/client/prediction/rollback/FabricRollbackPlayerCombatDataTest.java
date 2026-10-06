package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.fabric.mixin.client.DamageTrackerRollbackAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerCombatDataTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void actualPaperCohortRebindsAttackersAliasesMetadataAndCooldownsThenRewindsNativeDamage() throws Exception {
        var data = fixtures(); var scene = new Scene();
        FabricRollbackPlayerCombatData.apply(scene.states, data);
        assertData(data, scene);
        var a = scene.states.get(A).ownedPlayer(); var b = scene.states.get(B).ownedPlayer();
        assertSame(a, b.getPrimeAdversary()); assertSame(b, a.getPrimeAdversary());
        assertSame(b, ((LivingEntityRollbackHistoryAccess) a).rollback$lastHurtMob());
        assertSame(a, ((LivingEntityRollbackHistoryAccess) b).rollback$lastHurtMob());
        assertTrue(a.isInPiercingCooldown(b, 5));
        assertEquals(A, scene.states.get(B).importedExplosionCause());
        var entries = ((DamageTrackerRollbackAccess) b.getDamageTracker()).rollback$entries();
        assertSame(entries.get(0).damageSource(), ((LivingEntityRollbackHistoryAccess) b).rollback$lastDamage());
        assertSame(entries.get(1).damageSource(), entries.get(2).damageSource()); assertNotSame(entries.get(1).damageSource(), entries.get(3).damageSource());
        assertEquals("MAGIC", FabricRollbackPlayerCombatData.knownCause(entries.get(0).damageSource()));
        assertTrue(FabricRollbackPlayerCombatData.critical(entries.get(0).damageSource()));
        var historical = entries.get(1).damageSource();
        assertEquals(new Vec3d(1.2, 3.4, 5.6), historical.getStoredPosition()); assertEquals(historical.getStoredPosition(), historical.getPosition());
        assertNull(historical.getSource()); assertNull(historical.getAttacker()); assertSame(b, FabricRollbackPlayerCombatData.eventDamager(historical));
        assertEquals("CUSTOM", FabricRollbackPlayerCombatData.knownCause(historical));
        assertEquals("generic", entries.get(2).fallLocation().id()); assertEquals(7, entries.get(2).fallDistance());
        assertTrue(scene.queries.outputs.isEmpty(), "Import must not emit damage/status/sound output");
        // An uncached UUID link resolves through the private world, not a live client world.
        ((LivingEntityRollbackHistoryAccess) b).rollback$lastHurtByPlayer(LazyEntityReference.ofUUID(A));
        assertSame(a, b.getPrimeAdversary());
        var another = new Scene(); FabricRollbackPlayerCombatData.apply(another.states, data);
        assertNotSame(historical, ((DamageTrackerRollbackAccess) another.states.get(B).ownedPlayer().getDamageTracker()).rollback$entries().get(1).damageSource());
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 500_000).capture(List.copyOf(scene.states.values()), List.of());
        assertTrue(scene.states.get(B).damage(scene.world.world().getDamageSources().playerAttack(a), 8));
        assertEquals(12, b.getHealth()); assertEquals(5, entries.size());
        scene.states.get(B).importedExplosionCause(null); ((LivingEntityRollbackHistoryAccess) a).rollback$kinetic().clear();
        checkpoint.restore();
        assertEquals(20, b.getHealth()); assertEquals(4, entries.size()); assertSame(historical, entries.get(1).damageSource());
        assertData(data, scene); assertData(data, another);
        b.age = 400; b.getDamageTracker().update(); assertEquals(4, entries.size());
        b.age = 401; b.getDamageTracker().update(); assertTrue(entries.isEmpty());
    }

    @Test void invalidLateSourceMissingRosterForeignWorldAndReplayDoNotPartlyMutateTheCohort() throws Exception {
        var data = fixtures(); var scene = new Scene();
        byte[] beforeA = FabricRollbackPlayerCombatData.capture(scene.states.get(A)).encode(), beforeB = FabricRollbackPlayerCombatData.capture(scene.states.get(B)).encode();
        var b = data.get(B); var sources = new ArrayList<>(b.sources()); var source = sources.getFirst();
        sources.set(0, new RollbackPlayerCombatData.Source("test:missing", source.direct(), source.causing(), source.eventDamager(), source.position(), source.knownCause(), source.critical()));
        var bad = new RollbackPlayerCombatData(B, b.lastHurtByPlayer(), b.lastHurtByMob(), b.lastHurtMob(), b.explosionCause(), b.hasKinetic(), b.kinetic(), b.tracker(), sources, b.lastDamage(), b.entries());
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerCombatData.apply(scene.states, Map.of(A, data.get(A), B, bad)));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerCombatData.apply(Map.of(B, scene.states.get(B)), Map.of(B, b)));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerCombatData.apply(Map.of(A, scene.states.get(A), B, new Scene().states.get(B)), data));
        var simulation = new RollbackSimulation<Boolean, Boolean, Boolean>() {
            @Override public Boolean snapshot() { return true; }
            @Override public void restore(Boolean ignored) { }
            @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Boolean> effects) {
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerCombatData.apply(scene.states, data));
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerCombatData.capture(scene.states.get(A)));
            }
        };
        new RollbackEngine<>(simulation, Map.of(A, false), new RollbackEngine.Limits(1, 1, 4, 50_000_000), 0).advance();
        assertArrayEquals(beforeA, FabricRollbackPlayerCombatData.capture(scene.states.get(A)).encode());
        assertArrayEquals(beforeB, FabricRollbackPlayerCombatData.capture(scene.states.get(B)).encode());
        assertTrue(scene.queries.outputs.isEmpty());
    }
    private static void assertData(Map<UUID, RollbackPlayerCombatData> data, Scene scene) {
        data.forEach((id, value) -> assertArrayEquals(value.encode(), FabricRollbackPlayerCombatData.capture(scene.states.get(id)).encode()));
    }
    private Map<UUID, RollbackPlayerCombatData> fixtures() throws Exception {
        var result = new HashMap<UUID, RollbackPlayerCombatData>();
        for (String name : List.of("a", "b")) {
            try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-combat-" + name + ".base64"))) {
                var data = RollbackPlayerCombatData.decode(Base64.getMimeDecoder().decode(input.readAllBytes())); result.put(data.owner(), data);
            }
        }
        return Map.copyOf(result);
    }
    private static final class Scene {
        final FabricRollbackWorldAccessTest.Queries queries = new FabricRollbackWorldAccessTest.Queries();
        final FabricRollbackWorldAccess world = new FabricRollbackWorldAccess(queries);
        final Map<UUID, FabricRollbackNativePlayerState> states;
        Scene() {
            queries.time = 20;
            var a = new Player(world.world(), A); var b = new Player(world.world(), B);
            a.setPosition(.5, 1, .5); b.setPosition(.5, 1, 2); a.age = 100; b.age = 100;
            states = Map.of(A, new FabricRollbackNativePlayerState(a, world, 300_000), B, new FabricRollbackNativePlayerState(b, world, 300_000));
        }
    }
    private static final class Player extends PlayerEntity {
        Player(World world, UUID id) { super(world, new GameProfile(id, "player" + id.getLeastSignificantBits())); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
}
