package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackDamageTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void hotbarSelectionCancelsAndRewindsNativeItemUseAndEquipment() {
        var fixture = new Fixture();
        fixture.target.getInventory().setSelectedSlot(0);
        fixture.target.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.SHIELD));
        fixture.target.getInventory().setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD));
        fixture.targetState.use(player -> { player.setCurrentHand(net.minecraft.util.Hand.MAIN_HAND); return null; });
        var saved = fixture.snapshot();
        assertTrue(fixture.targetState.selectSlot(1));
        assertEquals(1, fixture.target.getInventory().getSelectedSlot()); assertFalse(fixture.target.isUsingItem());
        var output = List.copyOf(fixture.queries.outputs); assertFalse(output.isEmpty());
        saved.restore();
        assertEquals(0, fixture.target.getInventory().getSelectedSlot()); assertTrue(fixture.target.isUsingItem());
        assertTrue(fixture.queries.outputs.isEmpty());
        fixture.queries.cancelSlot = true;
        assertFalse(fixture.targetState.selectSlot(1));
        assertEquals(0, fixture.target.getInventory().getSelectedSlot()); assertTrue(fixture.target.isUsingItem());
        assertTrue(fixture.queries.outputs.isEmpty());
        fixture.queries.cancelSlot = false;
        assertTrue(fixture.targetState.selectSlot(1)); assertEquals(output, fixture.queries.outputs);
        int events = fixture.queries.events.size(), outputs = fixture.queries.outputs.size();
        assertTrue(fixture.targetState.selectSlot(1));
        assertEquals(events, fixture.queries.events.size()); assertEquals(outputs, fixture.queries.outputs.size());
        fixture.target.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.SHIELD));
        fixture.targetState.use(player -> { player.setCurrentHand(net.minecraft.util.Hand.OFF_HAND); return null; });
        assertTrue(fixture.targetState.selectSlot(2)); assertTrue(fixture.target.isUsingItem());
        assertThrows(IllegalArgumentException.class, () -> fixture.targetState.selectSlot(9));
    }

    @Test void handSwapPreservesItemAliasesAndRewindsEquipmentOutputs() {
        var fixture = new Fixture();
        var main = new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD);
        var off = new net.minecraft.item.ItemStack(net.minecraft.item.Items.SHIELD);
        fixture.target.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, main);
        fixture.target.setStackInHand(net.minecraft.util.Hand.OFF_HAND, off);
        fixture.targetState.use(player -> { player.setCurrentHand(net.minecraft.util.Hand.OFF_HAND); return null; });
        assertTrue(fixture.target.isUsingItem());
        var saved = fixture.snapshot();
        assertTrue(fixture.targetState.swapHands());
        assertFalse(fixture.target.isUsingItem());
        assertSame(off, fixture.target.getMainHandStack());
        assertSame(main, fixture.target.getOffHandStack());
        var output = List.copyOf(fixture.queries.outputs);
        assertFalse(output.isEmpty());
        saved.restore();
        assertTrue(fixture.target.isUsingItem());
        assertSame(main, fixture.target.getMainHandStack());
        assertSame(off, fixture.target.getOffHandStack());
        assertTrue(fixture.queries.outputs.isEmpty());
        assertTrue(fixture.targetState.swapHands());
        assertEquals(output, fixture.queries.outputs);
    }

    @Test void nativeHandSwingAndDetachedAnimationRewindTogether() {
        var fixture = new Fixture(); var before = fixture.snapshot();
        fixture.targetState.swing(false);
        assertTrue(fixture.target.handSwinging); assertEquals(-1, fixture.target.handSwingTicks);
        var output = (FabricRollbackPacketData.Tracked) fixture.queries.outputs.getLast();
        assertEquals(fixture.target.getUuid(), output.entity()); assertFalse(output.includeSelf());
        assertEquals(new FabricRollbackPacketData.Animation(fixture.target.getId(), 0), output.data());
        fixture.targetState.swing(false);
        assertEquals(2, fixture.queries.outputs.size());
        fixture.target.handSwingTicks = 0;
        fixture.targetState.swing(false);
        assertEquals(2, fixture.queries.outputs.size());
        before.restore(); assertFalse(fixture.target.handSwinging); assertTrue(fixture.queries.outputs.isEmpty());
        fixture.targetState.swing(false); assertEquals(output, fixture.queries.outputs.getLast());
        before.restore(); fixture.targetState.swing(true);
        assertEquals(net.minecraft.util.Hand.OFF_HAND, fixture.target.preferredHand);
        assertEquals(new FabricRollbackPacketData.Animation(fixture.target.getId(), 3),
                ((FabricRollbackPacketData.Tracked) fixture.queries.outputs.getLast()).data());
    }
    @Test void nativeHitRewindsHealthImmunityKnockbackSourceAndOutputsTogether() {
        var fixture = new Fixture();
        var saved = fixture.snapshot();
        var source = fixture.world.world().getDamageSources().playerAttack(fixture.attacker);
        assertTrue(fixture.targetState.damage(source, 6));
        var result = fixture.result();
        assertEquals(14, result.health);
        assertEquals(20, result.regen);
        assertTrue(result.vx > 0);
        assertTrue(result.vy > 0);
        assertEquals(fixture.attacker.getUuid(), result.attacker);
        assertEquals(1, result.outputs.stream().filter(FabricRollbackWorldAccess.DamageOutput.class::isInstance).count());
        assertEquals(1, result.events.size());
        assertFalse(fixture.targetState.damage(source, 4)); // Native immunity rejects a weaker second hit.
        assertEquals(result, fixture.result());
        fixture.snapshot(); // Includes the native damage tracker and lazy attacker reference.
        saved.restore();
        assertEquals(20, fixture.target.getHealth());
        assertNull(fixture.target.getAttacker());
        assertTrue(fixture.queries.outputs.isEmpty());
        assertTrue(fixture.queries.events.isEmpty());
        assertTrue(fixture.targetState.damage(source, 6));
        assertEquals(result, fixture.result());
    }

    @Test void nativeDamageHonorsCapturedGameRulesArmorEffectsAndAbsorption() {
        var fixture = new Fixture();
        var source = fixture.world.world().getDamageSources().inFire();
        assertTrue(source.isIn(DamageTypeTags.IS_FIRE)); // Real data-pack tags, not synthetic damage rules.
        fixture.queries.rules.put(GameRules.FIRE_DAMAGE, false);
        var saved = fixture.snapshot();
        assertFalse(fixture.targetState.damage(source, 8));
        fixture.queries.rules.put(GameRules.FIRE_DAMAGE, true);
        fixture.target.getAttributeInstance(EntityAttributes.ARMOR).setBaseValue(12);
        fixture.target.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 80));
        fixture.target.getAttributeInstance(EntityAttributes.MAX_ABSORPTION).setBaseValue(4);
        fixture.target.setAbsorptionAmount(1);
        assertEquals(1, fixture.target.getAbsorptionAmount());
        assertTrue(fixture.targetState.damage(source, 8));
        assertTrue(fixture.target.getHealth() > 12 && fixture.target.getHealth() < 20);
        assertEquals(0, fixture.target.getAbsorptionAmount());
        saved.restore();
        assertEquals(20, fixture.target.getHealth());
        assertEquals(0, fixture.target.getAttributeValue(EntityAttributes.ARMOR));
        assertFalse(fixture.target.hasStatusEffect(StatusEffects.RESISTANCE));
        assertFalse(fixture.targetState.damage(source, 8));
    }

    @Test void equippedNativeEnchantmentsAndDurabilityParticipateInTheHitCheckpoint() {
        var fixture = new Fixture();
        var chest = new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_CHESTPLATE);
        chest.addEnchantment(fixture.queries.registries().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                .getOrThrow(net.minecraft.enchantment.Enchantments.PROTECTION), 2);
        fixture.target.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, chest);
        // This fixture imports the effective attribute before damage; equipment ticks are separate coverage.
        fixture.target.getAttributeInstance(EntityAttributes.ARMOR).setBaseValue(8);
        var source = fixture.world.world().getDamageSources().playerAttack(fixture.attacker);
        var saved = fixture.snapshot();
        assertTrue(fixture.targetState.damage(source, 8));
        var expected = fixture.result();
        assertTrue(expected.health > 12 && expected.health < 20);
        int damage = chest.getDamage();
        assertTrue(damage > 0);
        fixture.snapshot();
        saved.restore();
        assertSame(chest, fixture.target.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST));
        assertEquals(0, chest.getDamage());
        assertTrue(fixture.targetState.damage(source, 8));
        assertEquals(damage, chest.getDamage());
        assertEquals(expected, fixture.result());
    }

    @Test void lateNativeInvulnerabilityInputRetractsDamageAndItsProvisionalEvents() {
        var first = new CombatSimulation(); var second = new CombatSimulation();
        var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
        UUID participant = first.fixture.target.getUuid();
        var onTime = new RollbackEngine<>(first, Map.of(participant, false), limits, 0);
        var late = new RollbackEngine<>(second, Map.of(participant, false), limits, 0);
        onTime.submit(participant, 1, true);
        onTime.advance(); late.advance();
        var expected = onTime.advance().head().effects();
        assertNotEquals(expected, late.advance().head().effects());
        assertFalse(second.fixture.queries.outputs.isEmpty());
        late.submit(participant, 1, true);
        assertEquals(expected, late.reconcile().head().effects());
        assertTrue(second.fixture.queries.outputs.isEmpty());
        assertTrue(second.fixture.queries.events.isEmpty());
        assertEquals(first.fixture.result(), second.fixture.result());
        assertEquals(onTime.advance().head().effects(), late.advance().head().effects());
    }

    @Test void invalidAndForeignDamageIsRejectedBeforeStateChanges() {
        var fixture = new Fixture();
        var other = new Fixture();
        var before = fixture.result();
        assertThrows(IllegalArgumentException.class, () -> fixture.targetState.damage(
                fixture.world.world().getDamageSources().playerAttack(other.attacker), 6));
        assertThrows(IllegalArgumentException.class, () -> fixture.targetState.damage(
                fixture.world.world().getDamageSources().generic(), Float.NaN));
        assertEquals(before, fixture.result());
    }

    private record Result(float health, int regen, int hurt, double vx, double vy, double vz, UUID attacker,
                          List<FabricRollbackWorldAccess.Output> outputs, List<FabricRollbackWorldAccessTest.Queries.Event> events) { }
    private static final class CombatSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, Result> {
        final Fixture fixture = new Fixture();
        @Override public RollbackStateGraph.Snapshot snapshot() { return fixture.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Result> effects) {
            fixture.queries.time = tick;
            fixture.target.getAbilities().invulnerable = inputs.values().iterator().next();
            if (tick == 1) fixture.targetState.damage(fixture.world.world().getDamageSources().playerAttack(fixture.attacker), 6);
            effects.emit(fixture.result());
        }
    }
    private static final class Fixture {
        final FabricRollbackWorldAccessTest.Queries queries = new FabricRollbackWorldAccessTest.Queries();
        final FabricRollbackWorldAccess world = new FabricRollbackWorldAccess(queries);
        final Player attacker = new Player(world.world(), 1);
        final Player target = new Player(world.world(), 2);
        final FabricRollbackNativePlayerState attackerState = new FabricRollbackNativePlayerState(attacker, world, 20_000);
        final FabricRollbackNativePlayerState targetState = new FabricRollbackNativePlayerState(target, world, 20_000);
        final RollbackStateGraph graph = new RollbackStateGraph(value -> false, field -> true, 2_000);
        Fixture() {
            attacker.setPosition(-1, 1, 0);
            target.setPosition(0, 1, 0);
            target.setOnGround(true);
            target.getRandom().setSeed(77);
            queries.time = 20;
        }
        RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(targetState), List.of()); }
        Result result() {
            var velocity = target.getVelocity();
            return new Result(target.getHealth(), target.timeUntilRegen, target.hurtTime, velocity.x, velocity.y, velocity.z,
                    target.getAttacker() == null ? null : target.getAttacker().getUuid(), List.copyOf(queries.outputs), List.copyOf(queries.events));
        }
    }
    private static final class Player extends PlayerEntity {
        Player(World world, long id) { super(world, new GameProfile(new UUID(0, id), "damage" + id)); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
}
