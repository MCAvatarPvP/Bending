package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import dev.lrxh.neptune.feature.rollback.RollbackRoundEvents;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingEntity;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

/** Real private ServerPlayer damage crosses Neptune's round rules; no Bukkit server is started. */
class NeptuneRollbackDamageTest {
    private static final UUID SESSION = new UUID(0, 90), TARGET = new UUID(0, 91), ATTACKER = new UUID(0, 92);

    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void logicalAbilityDamageAndNeptuneDefeatShareTheSameNativeHealth() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var logicalWorld = new World();
            var target = logical(scene.targetState, logicalWorld); var attacker = logical(scene.attackerState, logicalWorld);
            target.setHealth(6);
            var before = new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(target, attacker), List.of());
            scene.combat.round.beginTick(1);
            target.damage(10, attacker);
            assertEquals(20, target.getHealth()); assertEquals(20, scene.target.getBukkitEntity().getHealth());
            assertEquals(List.of(new RollbackRound.Defeat(SESSION, 1, TARGET, ATTACKER)), scene.combat.round.provisionalDefeats());
            var delivered = new ArrayList<RollbackRound.Defeat>();
            scene.combat.round.finalizeThrough(0, delivered::add);
            assertTrue(delivered.isEmpty());
            before.restore();
            assertEquals(6, target.getHealth()); assertTrue(scene.combat.round.provisionalDefeats().isEmpty());
            assertTrue(scene.combat.round.active(TARGET));
            scene.combat.round.beginTick(1);
            target.damage(3, attacker);
            assertEquals(3, target.getHealth()); assertFalse(scene.combat.round.ended());
            scene.combat.round.finalizeThrough(1, delivered::add);
            assertTrue(delivered.isEmpty());
            return null;
        });
    }

    private static RollbackLivingEntity logical(PaperRollbackNativePlayerState state, World world) {
        var rules = new RollbackEntityBody.Rules() {
            @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
            @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
        };
        var body = RollbackEntityBody.nativeBacked(state.identity(), world, state, rules);
        return new RollbackLivingEntity(RollbackLivingState.nativeBacked(body, new UnusedEquipment(), state));
    }
    /** Equipment access is outside this bridge contract test. */
    private static final class UnusedEquipment extends EntityEquipment implements RollbackStateCell<Void> {
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
    }

    @Test void lethalNativeDamageRecordsAReversibleDefeatInsteadOfVanillaDeath() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            scene.target.getBukkitEntity().setHealth(6);
            var initialOutputs = List.copyOf(scene.combat.delegate.outputs);
            var before = scene.snapshot();
            scene.combat.round.beginTick(1);
            assertFalse(scene.hit(10), "Neptune cancels the lethal damage event");
            assertEquals(20, scene.target.getHealth());
            assertTrue(scene.target.isAlive()); assertTrue(scene.combat.round.ended());
            assertEquals(List.of(new RollbackRound.Defeat(SESSION, 1, TARGET, ATTACKER)), scene.combat.round.provisionalDefeats());
            var delivered = new ArrayList<RollbackRound.Defeat>();
            scene.combat.round.finalizeThrough(0, delivered::add);
            assertTrue(delivered.isEmpty());
            assertFalse(scene.hit(25));
            assertEquals(1, scene.combat.round.provisionalDefeats().size());
            before.restore();
            assertEquals(6, scene.target.getHealth());
            assertTrue(scene.combat.round.active(TARGET)); assertTrue(scene.combat.round.active(ATTACKER));
            assertTrue(scene.combat.round.provisionalDefeats().isEmpty());
            assertEquals(initialOutputs, scene.combat.delegate.outputs); assertTrue(scene.combat.delegate.events.isEmpty());
            assertFalse(scene.target.queueHealthUpdatePacket);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void damageModifiersCancellationAndBothTotemHandsKeepTheOrdinaryMatchRule() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var before = scene.snapshot();
            scene.combat.round.beginTick(1);
            scene.combat.delegate.multiplier = 0.1;
            assertTrue(scene.hit(25));
            assertEquals(17.5, scene.target.getHealth()); assertFalse(scene.combat.round.ended());
            before.restore(); scene.combat.round.beginTick(1);
            scene.combat.delegate.cancel = true;
            assertFalse(scene.hit(25));
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.round.provisionalDefeats().isEmpty());
            for (var hand : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                before.restore(); scene.combat.round.beginTick(1);
                var totem = new ItemStack(Items.TOTEM_OF_UNDYING);
                scene.target.setItemSlot(hand, totem);
                var withTotem = scene.snapshot();
                assertTrue(scene.hit(100));
                assertEquals(1, scene.target.getHealth()); assertTrue(totem.isEmpty());
                assertTrue(scene.combat.round.provisionalDefeats().isEmpty());
                withTotem.restore();
                assertEquals(1, totem.getCount()); assertEquals(20, scene.target.getHealth());
                assertTrue(scene.target.getActiveEffects().isEmpty());
                assertTrue(scene.combat.delegate.outputs.isEmpty());
            }
            return null;
        });
    }

    @Test void lateCorrectionRetractsTheNativeHitAndDefeatBeforeTheFinalizedFrontier() throws Exception {
        onTickThread(() -> {
            var onTime = new Simulation(); var delayed = new Simulation();
            var direct = engine(onTime); var replay = engine(delayed);
            direct.submit(TARGET, 1, true);
            direct.advance(); replay.advance(); direct.advance(); replay.advance();
            assertFalse(onTime.scene.combat.round.ended()); assertTrue(delayed.scene.combat.round.ended());
            replay.submit(TARGET, 1, true);
            assertEquals(1, replay.reconcile().replayedFrom());
            assertFalse(delayed.scene.combat.round.ended());
            assertTrue(delayed.scene.combat.round.provisionalDefeats().isEmpty());
            assertEquals(20, delayed.scene.target.getHealth());
            var delivered = new ArrayList<RollbackRound.Defeat>();
            for (int tick = 3; tick <= 7; tick++) {
                var expected = direct.advance(); var actual = replay.advance();
                assertEquals(expected.finalizedEffects(), actual.finalizedEffects());
                delayed.scene.combat.round.finalizeThrough(actual.confirmed().tick(), delivered::add);
            }
            assertTrue(delivered.isEmpty());
            assertFalse(delayed.scene.target.queueHealthUpdatePacket);
            return null;
        });
    }

    @Test void retainedDefeatFinalizesOnceAndSurvivesReplayAfterItsConfirmedTick() throws Exception {
        onTickThread(() -> {
            var simulation = new Simulation(); var engine = engine(simulation);
            var delivered = new ArrayList<RollbackRound.Defeat>();
            for (int tick = 1; tick <= 6; tick++) {
                var update = engine.advance();
                simulation.scene.combat.round.finalizeThrough(update.confirmed().tick(), delivered::add);
                if (tick < 4) assertTrue(delivered.isEmpty());
                if (tick == 4) {
                    assertEquals(RollbackEngine.Submission.FINALIZED, engine.submit(TARGET, 1, true));
                    engine.submit(TARGET, 3, true);
                    assertEquals(3, engine.reconcile().replayedFrom());
                }
            }
            assertEquals(List.of(new RollbackRound.Defeat(SESSION, 1, TARGET, ATTACKER)), delivered);
            assertTrue(simulation.scene.combat.round.provisionalDefeats().isEmpty());
            return null;
        });
    }

    @Test void bridgeRejectsForeignReplicasEvenWhenTheirParticipantIdsMatch() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var foreign = new Scene();
            assertThrows(IllegalArgumentException.class, () -> new RollbackRoundEvents(scene.combat.round,
                    Map.of(TARGET, scene.target.getBukkitEntity())));
            foreign.combat.events = scene.combat.events;
            scene.combat.round.beginTick(1); foreign.combat.round.beginTick(1);
            assertThrows(IllegalArgumentException.class, () -> foreign.hit(25));
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.round.provisionalDefeats().isEmpty());
            // Also reject a foreign causing player when the victim is owned.
            scene.combat.events = new RollbackRoundEvents(scene.combat.round,
                    Map.of(TARGET, scene.target.getBukkitEntity(), ATTACKER, foreign.attacker.getBukkitEntity()));
            assertThrows(IllegalArgumentException.class, () -> scene.hit(25));
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.round.provisionalDefeats().isEmpty());
            return null;
        });
    }

    @Test void bukkitPositiveHealthResetKeepsNativeValidationWithoutOpeningVanillaDeath() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var player = scene.target.getBukkitEntity();
            assertThrows(IllegalArgumentException.class, () -> player.setHealth(21));
            assertThrows(IllegalArgumentException.class, () -> player.setHealth(Double.NaN));
            assertThrows(IllegalStateException.class, () -> player.setHealth(0));
            assertThrows(IllegalStateException.class, () -> player.setHealth(Double.MIN_VALUE));
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.delegate.outputs.isEmpty());
            assertThrows(IllegalStateException.class, () -> player.getInventory().clear());
            return null;
        });
    }

    private static RollbackEngine<RollbackStateGraph.Snapshot, Boolean, PaperRollbackCombatAccess.Output> engine(Simulation simulation) {
        return new RollbackEngine<>(simulation, Map.of(TARGET, false, ATTACKER, false), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1_000);
    }

    private static final class Simulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, PaperRollbackCombatAccess.Output> {
        final Scene scene = new Scene();
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<PaperRollbackCombatAccess.Output> effects) {
            scene.combat.round.beginTick(tick); scene.combat.delegate.time = tick;
            scene.combat.delegate.outputs.clear();
            // Fixture collision outcome only: this test does not stand in for
            // movement, dynamic ability progression or full duel integration.
            if (tick == 1 && !inputs.get(TARGET)) scene.hit(25);
            scene.combat.delegate.outputs.forEach(effects::emit);
        }
    }

    private static final class Scene {
        final Combat combat = new Combat();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        final PaperRollbackNativePlayerState targetState = player(TARGET, "rollback_target", 103);
        final PaperRollbackNativePlayerState attackerState = player(ATTACKER, "rollback_attacker", 107);
        final ServerPlayer target = targetState.use(value -> (ServerPlayer) value);
        final ServerPlayer attacker = attackerState.use(value -> (ServerPlayer) value);
        Scene() {
            target.setId(901); attacker.setId(902);
            target.setPos(1, 1, 0); attacker.setPos(0, 1, 0); target.setOnGround(true);
            target.setYRot(0); target.setXRot(0); attacker.setYRot(0); attacker.setXRot(0);
            combat.events = new RollbackRoundEvents(combat.round, Map.of(TARGET, target.getBukkitEntity(), ATTACKER, attacker.getBukkitEntity()));
        }
        PaperRollbackNativePlayerState player(UUID id, String name, long seed) {
            return PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(id, name),
                    ClientInformation.createDefault(), GameType.SURVIVAL, seed, 200_000);
        }
        boolean hit(float amount) { return targetState.damage(world.world().damageSources().playerAttack(attacker), amount); }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(targetState), List.of()); }
    }

    private static final class Combat implements PaperRollbackWorldAccess.Combat<RollbackRound.Checkpoint> {
        final PaperRollbackDamageNativeTest.Combat delegate = new PaperRollbackDamageNativeTest.Combat();
        final RollbackRound round = new RollbackRound(SESSION, Map.of(TARGET, TARGET, ATTACKER, ATTACKER));
        RollbackRoundEvents events;
        @Override public Object registryAccess() { return delegate.registryAccess(); }
        @Override public Object difficulty() { return delegate.difficulty(); }
        @Override public Object random() { return delegate.random(); }
        @Override public Object rule(Object rule) { return delegate.rule(rule); }
        @Override public long time() { return delegate.time(); }
        @Override public long nextSoundSeed() { return delegate.nextSoundSeed(); }
        @Override public boolean skipVanillaDamageTickWhenShieldBlocked() { return delegate.skipVanillaDamageTickWhenShieldBlocked(); }
        @Override public boolean updateEquipmentOnPlayerActions() { return delegate.updateEquipmentOnPlayerActions(); }
        @Override public boolean allowNonPlayerEntitiesOnScoreboards() { return delegate.allowNonPlayerEntitiesOnScoreboards(); }
        @Override public boolean pvpAllowed() { return delegate.pvpAllowed(); }
        @Override public boolean allowPlayerCrammingDamage() { return delegate.allowPlayerCrammingDamage(); }
        @Override public int maximumEntityCollisions() { return delegate.maximumEntityCollisions(); }
        @Override public float jumpExhaustion(boolean sprinting) { return delegate.jumpExhaustion(sprinting); }
        @Override public PaperRollbackWorldAccess.WorldPolicy worldPolicy() { return delegate.worldPolicy(); }
        @Override public int containerUpdateRate() { return delegate.containerUpdateRate(); }
        @Override public float regenerationExhaustion() { return delegate.regenerationExhaustion(); }
        @Override public boolean parrotsStayOnShoulder() { return delegate.parrotsStayOnShoulder(); }
        @Override public void event(Event event) {
            delegate.event(event);
            if (event instanceof EntityDamageEvent damage) events.accept(damage);
        }
        @Override public void gameEvent(Object event, Object position, Object context) { delegate.gameEvent(event, position, context); }
        @Override public void output(PaperRollbackCombatAccess.Output output) { delegate.output(output); }
        @Override public RollbackRound.Checkpoint captureRollbackState() { return round.snapshot(); }
        @Override public void restoreRollbackState(RollbackRound.Checkpoint saved) { round.restore(saved); }
        @Override public List<?> rollbackReferences() { return List.of(delegate); }
    }
}
