package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackRoundDamageTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), ID = new UUID(0, 9);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void lethalRoundCancellationPrecedesDurabilityAbsorptionImmunityAndKnockback() {
        var f = new Fixture();
        var chest = new ItemStack(Items.DIAMOND_CHESTPLATE); f.target.equipStack(EquipmentSlot.CHEST, chest);
        f.target.getAttributeInstance(EntityAttributes.ARMOR).setBaseValue(8);
        f.target.getAttributeInstance(EntityAttributes.MAX_ABSORPTION).setBaseValue(4); f.target.setAbsorptionAmount(2);
        f.target.setHealth(4);
        var before = f.snapshot(); f.round.beginTick(1);
        assertFalse(f.hit(25));
        assertEquals(20, f.target.getHealth()); assertEquals(2, f.target.getAbsorptionAmount());
        assertEquals(0, chest.getDamage()); assertEquals(0, f.target.timeUntilRegen);
        assertEquals(0, f.target.getVelocity().lengthSquared()); assertEquals(0, f.policy.committed);
        assertTrue(f.round.ended()); assertTrue(f.queries.outputs.isEmpty());
        before.restore();
        assertEquals(4, f.target.getHealth()); assertFalse(f.round.ended());
        f.round.beginTick(1); assertTrue(f.hit(3)); assertFalse(f.round.ended());
    }

    @Test void nativeModifiersCancellationAndTotemHandsKeepSharedRoundDecisions() {
        var f = new Fixture(); var before = f.snapshot(); f.round.beginTick(1);
        f.policy.scale = .1; assertTrue(f.hit(25)); assertEquals(17.5, f.target.getHealth()); assertFalse(f.round.ended());
        before.restore(); f.round.beginTick(1); f.policy.cancel = true;
        assertFalse(f.hit(25)); assertEquals(20, f.target.getHealth()); assertTrue(f.round.provisionalDefeats().isEmpty());
        for (var hand : Hand.values()) {
            before.restore(); f.round.beginTick(1);
            f.target.setStackInHand(hand, new ItemStack(Items.TOTEM_OF_UNDYING));
            assertTrue(f.hit(100)); assertEquals(1, f.target.getHealth());
            assertTrue(f.target.getStackInHand(hand).isEmpty()); assertTrue(f.round.provisionalDefeats().isEmpty());
        }
    }

    @Test void nonlethalNativeHitRestoresItsEventBoundaryAndConfiguredImmunity() {
        var f = new Fixture(); f.target.rollbackInvulnerableDuration = 30;
        var before = f.snapshot(); f.round.beginTick(1);
        assertTrue(f.hit(6)); assertEquals(14, f.target.getHealth()); assertEquals(30, f.target.timeUntilRegen);
        assertEquals(30, f.targetState.readVitals().maximumNoDamageTicks());
        assertTrue(f.target.getVelocity().x > 0); assertEquals(A, f.target.getAttacker().getUuid());
        assertFalse(f.hit(4)); assertEquals(1, f.policy.events);
        assertTrue(f.hit(8)); assertEquals(12, f.target.getHealth()); assertEquals(2, f.policy.events);
        before.restore(); assertEquals(20, f.target.getHealth()); assertEquals(0, f.policy.events);
        assertEquals(0, f.target.timeUntilRegen); assertNull(f.target.getAttacker());
        f.round.beginTick(1); assertTrue(f.hit(6)); assertEquals(14, f.target.getHealth());
    }

    @Test void eventArmorEditsAreAppliedBeforeTheRoundAndNativeWear() {
        var f = new Fixture(); f.target.getAttributeInstance(EntityAttributes.ARMOR).setBaseValue(20);
        f.target.setHealth(6); f.policy.ignoreArmor = true; f.round.beginTick(1);
        assertFalse(f.hit(8)); assertTrue(f.round.ended()); assertEquals(20, f.target.getHealth());
    }

    @Test void lateDefenceRetractsNativeHealthAndRoundDefeatTogether() {
        var simulation = new RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, String>() {
            final Fixture f = new Fixture();
            @Override public RollbackStateGraph.Snapshot snapshot() { return f.snapshot(); }
            @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
            @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> effects) {
                f.round.beginTick(tick);
                if (tick == 1 && !inputs.get(B)) f.hit(25);
                if (f.round.ended()) effects.emit("defeat");
            }
        };
        var engine = new RollbackEngine<>(simulation, Map.of(A, false, B, false), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1000);
        assertEquals(List.of("defeat"), engine.advance().head().effects()); engine.advance();
        engine.submit(B, 1, true); assertTrue(engine.reconcile().head().effects().isEmpty());
        assertTrue(engine.advance().finalizedEffects().isEmpty()); assertTrue(engine.advance().finalizedEffects().isEmpty());
    }

    @Test void onlyOwnedCompleteRostersCanBindBeforeAnyCheckpoint() {
        var f = new Fixture(false); f.world.sealPlayers(); f.world.bindRound(f.round, f.policy);
        assertThrows(IllegalStateException.class, () -> f.world.bindRound(f.round, f.policy));
        var saved = new Fixture(false); saved.targetState.captureRollbackState();
        assertThrows(IllegalStateException.class, () -> saved.world.bindRound(saved.round, saved.policy));
        var foreign = new Fixture(); f.round.beginTick(1);
        assertThrows(IllegalArgumentException.class, () -> f.targetState.damage(f.world.world().getDamageSources().playerAttack(foreign.attacker), 25));
    }

    @Test void privateClientMatchesActualPaperNativeDamageFixtureExactly() throws Exception {
        try (var stream = getClass().getResourceAsStream("/rollback/round-damage.csv")) {
            var lines = new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            for (var line : lines) {
                var values = line.split(",");
                assertEquals(String.join(",", Arrays.copyOfRange(values, 10, values.length)), parity(values), values[0]);
            }
        }
    }
    private static String parity(String[] values) {
        var f = new Fixture();
        f.target.setHealth(Float.parseFloat(values[2]));
        f.target.getAttributeInstance(EntityAttributes.ARMOR).setBaseValue(Double.parseDouble(values[3]));
        f.target.getAttributeInstance(EntityAttributes.MAX_ABSORPTION).setBaseValue(4);
        f.target.setAbsorptionAmount(Float.parseFloat(values[4]));
        int resistance = Integer.parseInt(values[5]), protection = Integer.parseInt(values[6]);
        if (resistance > 0) f.target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.RESISTANCE, 80, resistance - 1));
        ItemStack chest = Double.parseDouble(values[3]) > 0 || protection > 0 ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY;
        if (!chest.isEmpty()) {
            if (protection > 0) chest.addEnchantment(f.queries.registries().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                    .getOrThrow(net.minecraft.enchantment.Enchantments.PROTECTION), protection);
            f.target.equipStack(EquipmentSlot.CHEST, chest);
        }
        f.policy.scale = Double.parseDouble(values[7]);
        var access = (com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackCombatAccess) (PlayerEntity) f.target;
        access.rollback$lastDamageTaken(Float.parseFloat(values[8]));
        if (access.rollback$lastDamageTaken() > 0) f.target.timeUntilRegen = 20;
        if (Boolean.parseBoolean(values[9])) f.target.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        if (values[0].startsWith("shield")) {
            f.target.shield(); f.policy.cancel = values[0].equals("shield-cancelled");
        }
        f.round.beginTick(1); boolean accepted = f.hit(Float.parseFloat(values[1]));
        return accepted + "," + f.target.getHealth() + "," + f.target.getAbsorptionAmount() + "," + chest.getDamage()
                + "," + f.target.timeUntilRegen + "," + access.rollback$lastDamageTaken() + "," + f.target.getVelocity().x + "," + f.target.getVelocity().y
                + "," + f.target.getVelocity().z + "," + f.round.ended() + "," + f.target.getOffHandStack().getCount() + "," + f.target.getOffHandStack().getDamage();
    }

    private static final class Fixture {
        final FabricRollbackWorldAccessTest.Queries queries = new FabricRollbackWorldAccessTest.Queries();
        final FabricRollbackWorldAccess world = new FabricRollbackWorldAccess(queries);
        final Body attacker = new Body(world.world(), A), target = new Body(world.world(), B);
        final FabricRollbackNativePlayerState attackerState = new FabricRollbackNativePlayerState(attacker, world, 200_000);
        final FabricRollbackNativePlayerState targetState = new FabricRollbackNativePlayerState(target, world, 200_000);
        final RollbackRound round = new RollbackRound(ID, Map.of(A, A, B, B));
        final Policy policy = new Policy();
        Fixture() { this(true); }
        Fixture(boolean bind) {
            attacker.setPosition(-1, 1, 0); target.setPosition(0, 1, 0); target.setOnGround(true); target.getRandom().setSeed(77);
            if (bind) world.bindRound(round, policy);
        }
        boolean hit(float amount) { return targetState.damage(world.world().getDamageSources().playerAttack(attacker), amount); }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(targetState), List.of()); }
    }
    private static final class Body extends FabricRollbackSimulatedPlayer {
        Body(World world, UUID id) { super(world, new GameProfile(id, "round" + id.getLeastSignificantBits())); }
        void shield() {
            setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD)); setHeadYaw(90);
            setCurrentHand(Hand.OFF_HAND); itemUseTimeLeft -= 6;
        }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
    private static final class Policy implements FabricRollbackWorldAccess.DamagePolicy<Policy.Saved> {
        record Saved(double scale, boolean cancel, boolean ignoreArmor, int events, int committed) { }
        double scale = 1; boolean cancel, ignoreArmor; int events, committed;
        @Override public void event(FabricRollbackDamageEvent event) {
            events++;
            var logical = new com.projectkorra.projectkorra.platform.mc.entity.Player() {
                @Override public UUID getUniqueId() { return event.player().getUuid(); }
            };
            var view = event.commonEvent(logical, com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            view.setDamage(view.getDamage() * scale); view.setCancelled(cancel);
            if (ignoreArmor) view.setDamage(com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent.DamageModifier.ARMOR, 0);
        }
        @Override public void resetAttackCooldown(PlayerEntity attacker, PlayerEntity target) { committed++; attacker.resetTicksSinceLastAttack(); }
        @Override public void exhaustion(PlayerEntity player, DamageSource source, float amount) { player.addExhaustion(amount); }
        @Override public void knockback(PlayerEntity player, DamageSource source, double strength, double x, double z) { player.takeKnockback(strength, x, z); }
        @Override public void death(PlayerEntity player, DamageSource source) { throw new AssertionError("Round bypassed native death cancellation"); }
        @Override public boolean skipDamageTickWhenShieldBlocked() { return false; }
        @Override public Saved captureRollbackState() { return new Saved(scale, cancel, ignoreArmor, events, committed); }
        @Override public void restoreRollbackState(Saved state) { scale = state.scale; cancel = state.cancel; ignoreArmor = state.ignoreArmor; events = state.events; committed = state.committed; }
    }
}
