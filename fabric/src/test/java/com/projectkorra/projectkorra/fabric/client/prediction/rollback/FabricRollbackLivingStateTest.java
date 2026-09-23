package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingEntity;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEquipment;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackInventory;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.GameMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackLivingStateTest {
    private static final UUID A = new UUID(0, 801), B = new UUID(0, 802);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void logicalDamageReadsNativeHealthImmunityKnockbackAndRestoresThroughRetainedAttribute() {
        var scene = new Scene(new World());
        var maximum = scene.target.getAttribute(Attribute.MAX_HEALTH);
        var before = new RollbackStateGraph(value -> false, field -> true, 20_000).capture(List.of(maximum), List.of());
        scene.target.damage(6, scene.attacker);
        assertEquals(14, scene.target.getHealth()); assertEquals(14, scene.nativeTarget.getHealth());
        assertEquals(scene.nativeTarget.timeUntilRegen, scene.target.getNoDamageTicks());
        // Verify the exposed native maximum against an actual fresh native hit.
        assertEquals(scene.target.getMaximumNoDamageTicks(), scene.nativeTarget.timeUntilRegen);
        assertEquals(6, scene.target.getLastDamage());
        assertEquals(scene.nativeTarget.getVelocity().y, scene.target.getVelocity().getY());
        assertTrue(scene.target.getVelocity().getY() > 0);
        assertSame(scene.nativeAttacker, scene.nativeTarget.getAttacker());
        var output = List.copyOf(scene.queries.outputs);
        scene.target.damage(4, scene.attacker);
        assertEquals(14, scene.target.getHealth()); assertEquals(output, scene.queries.outputs);
        maximum.setValue(24);
        assertEquals(24, scene.target.getMaxHealth()); assertEquals(24, scene.nativeTarget.getMaxHealth());
        assertEquals(24D, scene.target.living().attributes().get("MAX_HEALTH"));
        before.restore();
        assertEquals(20, maximum.getValue()); assertEquals(20, scene.target.getHealth());
        assertEquals(0, scene.target.getVelocity().lengthSquared()); assertEquals(0, scene.target.getLastDamage());
        assertTrue(scene.queries.outputs.isEmpty());
        scene.target.damage(6, scene.attacker);
        assertEquals(output, scene.queries.outputs);
    }

    @Test void logicalPotionsUseNativeMergingModifiersAndPaperPresentationDefaults() {
        var scene = new Scene(new World()); var before = scene.snapshot();
        var speed = scene.target.getAttribute(Attribute.valueOf("MOVEMENT_SPEED"));
        double initial = speed.getValue();
        assertTrue(scene.target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1), false));
        var nativeEffect = scene.nativeTarget.getStatusEffect(StatusEffects.SPEED);
        assertTrue(nativeEffect.isAmbient()); assertTrue(nativeEffect.shouldShowParticles()); assertTrue(nativeEffect.shouldShowIcon());
        assertEquals(80, scene.target.getPotionEffect(PotionEffectType.SPEED).getDuration());
        assertEquals(scene.nativeTarget.getAttributeValue(EntityAttributes.MOVEMENT_SPEED), speed.getValue());
        assertTrue(speed.getValue() > initial);
        var active = scene.snapshot();
        assertTrue(scene.target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, 0), true));
        assertEquals(1, scene.target.getPotionEffect(PotionEffectType.SPEED).getAmplifier(), "force does not replace native merging");
        scene.target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, 3));
        assertEquals(3, scene.target.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertTrue(scene.target.getActivePotionEffects().stream().anyMatch(effect -> effect.getType().name().equals("SPEED")));
        scene.target.removePotionEffect(PotionEffectType.SPEED);
        assertFalse(scene.target.hasPotionEffect(PotionEffectType.SPEED));
        active.restore();
        assertEquals(1, scene.target.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertSame(nativeEffect, scene.nativeTarget.getStatusEffect(StatusEffects.SPEED));
        before.restore();
        assertFalse(scene.target.hasPotionEffect(PotionEffectType.SPEED)); assertEquals(initial, speed.getValue());
    }

    @Test void vitalsAndLivenessFollowNativeStateAndInvalidWritesDoNotPartiallyApply() {
        var scene = new Scene(new World()); var before = scene.snapshot();
        scene.target.setHealth(8); scene.target.setNoDamageTicks(7); scene.target.setRemainingAir(100);
        scene.target.setAI(true);
        scene.target.getAttribute(Attribute.valueOf("MAX_ABSORPTION")).setValue(4);
        scene.target.living().absorption(3);
        assertEquals(8, scene.nativeTarget.getHealth()); assertEquals(7, scene.nativeTarget.timeUntilRegen);
        assertEquals(100, scene.nativeTarget.getAir()); assertEquals(3, scene.nativeTarget.getAbsorptionAmount());
        assertFalse(scene.target.living().vitals().ai());
        assertEquals(scene.nativeTarget.getStandingEyeHeight(), scene.target.getEyeHeight());
        assertThrows(IllegalArgumentException.class, () -> scene.target.setHealth(21));
        assertThrows(IllegalArgumentException.class, () -> scene.target.setHealth(0));
        assertThrows(IllegalArgumentException.class, () -> scene.target.setHealth(Double.MIN_VALUE));
        assertThrows(IllegalStateException.class, () -> scene.target.living().replaceAttributes(Map.of("MAX_HEALTH", 4D)));
        assertThrows(IllegalStateException.class, () -> scene.target.living().replacePotions(List.of()));
        assertEquals(8, scene.nativeTarget.getHealth());
        // Observe already changed native state, without pretending this fixture
        // implements the pending private death/drop path.
        scene.nativeTarget.setHealth(0);
        assertTrue(scene.target.isDead());
        before.restore();
        assertFalse(scene.target.isDead()); assertEquals(20, scene.target.getHealth());
        assertEquals(300, scene.target.getRemainingAir()); assertEquals(0, scene.target.living().vitals().absorption());
    }

    @Test void foreignSourcesAndMismatchedBackingsFailBeforeDamage() {
        var logicalWorld = new World(); var scene = new Scene(logicalWorld); var foreign = new Scene(logicalWorld);
        assertThrows(IllegalArgumentException.class, () -> scene.target.damage(6, foreign.attacker));
        assertThrows(IllegalArgumentException.class, () -> RollbackLivingState.nativeBacked(scene.target.body(), new UnusedEquipment(), scene.attackerState));
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(scene.target::getHealth).join());
        assertEquals(20, scene.target.getHealth()); assertTrue(scene.queries.outputs.isEmpty());
    }

    @Test void lateLogicalDamageReplaysNativeCombatAndFinalizesIdenticalOutputs() {
        var directSimulation = new Simulation(); var lateSimulation = new Simulation();
        var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
        var direct = new RollbackEngine<>(directSimulation, Map.of(A, false), limits, 1_000);
        var late = new RollbackEngine<>(lateSimulation, Map.of(A, false), limits, 1_000);
        direct.submit(A, 1, true); direct.advance(); late.advance(); direct.advance(); late.advance();
        assertEquals(14, directSimulation.scene.target.getHealth()); assertEquals(20, lateSimulation.scene.target.getHealth());
        late.submit(A, 1, true); assertEquals(1, late.reconcile().replayedFrom());
        assertEquals(directSimulation.scene.target.body().kinematics(), lateSimulation.scene.target.body().kinematics());
        assertEquals(directSimulation.scene.target.living().vitals(), lateSimulation.scene.target.living().vitals());
        for (int tick = 3; tick <= 6; tick++) assertEquals(direct.advance().finalizedEffects(), late.advance().finalizedEffects());
    }

    @Test void logicalArmorAndNativeDamageRewindThroughTheRetainedItemAlone() {
        var scene = new Scene(new World()); var inventory = scene.inventory();
        inventory.setItem(inventory.layout().chestplate(), scene.items.create(Material.DIAMOND_CHESTPLATE, 1));
        scene.target.getAttribute(Attribute.valueOf("ARMOR")).setValue(8); // Imported effective attribute; no equipment tick here.
        var armor = inventory.getChestplate();
        var nativeArmor = scene.nativeTarget.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
        var before = snapshot(armor);
        scene.target.damage(8, scene.attacker);
        assertTrue(scene.target.getHealth() > 12 && scene.target.getHealth() < 20);
        assertTrue(armor.getDurability() > 0); assertEquals(nativeArmor.getDamage(), armor.getDurability());
        var output = List.copyOf(scene.queries.outputs); double health = scene.target.getHealth(); short wear = armor.getDurability();
        before.restore();
        assertEquals(20, scene.target.getHealth()); assertEquals(0, armor.getDurability());
        assertSame(nativeArmor, scene.nativeTarget.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST));
        assertTrue(scene.queries.outputs.isEmpty());
        scene.target.damage(8, scene.attacker);
        assertEquals(health, scene.target.getHealth()); assertEquals(wear, armor.getDurability());
        assertEquals(output, scene.queries.outputs);
    }

    @Test void retainedMirrorsFollowNativeMutationsAndRestoreEvenAfterTheirSlotIsReplaced() {
        var scene = new Scene(new World()); var inventory = scene.inventory();
        inventory.setItemInMainHand(scene.items.create(Material.DIAMOND_BOOTS, 1));
        var first = inventory.getItemInMainHand(); var second = inventory.getItemInMainHand();
        var nativeItem = scene.nativeTarget.getMainHandStack();
        var before = snapshot(second, inventory, first);
        first.setDurability((short) 7); assertEquals(7, second.getDurability());
        var meta = first.getItemMeta(); meta.setDisplayName("retained"); first.setItemMeta(meta);
        assertEquals("retained", second.getItemMeta().getDisplayName());
        assertEquals("retained", nativeItem.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME).getString());
        first.setType(Material.DIAMOND_HELMET);
        assertSame(nativeItem, scene.nativeTarget.getMainHandStack()); assertEquals(Material.DIAMOND_HELMET, second.getType());
        first.setItemMeta(null); assertFalse(second.hasItemMeta());
        var detached = first.clone(); detached.setAmount(9); assertEquals(1, first.getAmount());
        first.setAmount(0); assertNull(inventory.getItem(0));
        first.setAmount(1); assertEquals(Material.DIAMOND_HELMET, inventory.getItem(0).getType());
        inventory.setItem(0, scene.items.create(Material.STONE, 3));
        var removed = snapshot(first, second, inventory);
        first.setAmount(5); first.setDurability((short) 11);
        removed.restore();
        assertEquals(1, first.getAmount()); assertEquals(0, second.getDurability());
        assertEquals(Material.STONE, inventory.getItem(0).getType());
        before.restore();
        assertSame(nativeItem, scene.nativeTarget.getMainHandStack());
        assertEquals(Material.DIAMOND_BOOTS, first.getType()); assertEquals(0, second.getDurability());
        first.setType(Material.AIR);
        assertEquals(Material.DIAMOND_BOOTS, inventory.getItem(0).getType());
        before.restore(); first.setDurability((short) 2); assertEquals(2, second.getDurability());
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(first::getAmount).join());
    }

    @Test void logicalInventoryOperationsUseNativeSlotsAndRejectAnotherPlayersEquipment() {
        var scene = new Scene(new World()); var inventory = scene.inventory(); var before = snapshot(inventory);
        int maximum = scene.nativeTarget.getInventory().getMaxCountPerStack();
        inventory.setHeldItemSlot(4);
        inventory.setItemInMainHand(scene.items.create(Material.STONE, 60));
        assertTrue(inventory.addItem(scene.items.create(Material.STONE, 7)).isEmpty());
        assertEquals(4, scene.nativeTarget.getInventory().getSelectedSlot());
        assertEquals(64, scene.nativeTarget.getMainHandStack().getCount());
        assertEquals(3, scene.nativeTarget.getInventory().getStack(0).getCount());
        inventory.removeItem(scene.items.create(Material.STONE, 5));
        assertEquals(62, scene.nativeTarget.getMainHandStack().getCount());
        inventory.setItem(42, scene.items.create(Material.STONE, 1));
        assertEquals(1, scene.nativeTarget.getInventory().getStack(42).getCount());
        assertThrows(IllegalArgumentException.class, () -> RollbackLivingState.nativeBacked(scene.target.body(), scene.attacker.getEquipment(), scene.targetState));
        assertThrows(IllegalArgumentException.class, () -> inventory.maximumStack(16));
        before.restore();
        assertEquals(0, inventory.getHeldItemSlot()); assertEquals(maximum, inventory.maximumStack());
        assertTrue(scene.nativeTarget.getMainHandStack().isEmpty()); assertNull(inventory.getItem(42));
        var empty = inventory.getItemInMainHand(); empty.setType(Material.STONE); empty.setAmount(5);
        assertTrue(scene.nativeTarget.getMainHandStack().isEmpty());
    }

    private static RollbackStateGraph.Snapshot snapshot(Object... roots) {
        return new RollbackStateGraph(value -> false, field -> true, 20_000).capture(List.of(roots), List.of());
    }

    @Test void playerControlsChangeNativeFlightSprintAndVitalsAndRewindTogether() {
        var scene = new Scene(new World()); var player = scene.controlled();
        var speed = player.getAttribute(Attribute.valueOf("MOVEMENT_SPEED")); double baseSpeed = speed.getValue();
        var controls = player.state().controls(); var before = snapshot(player);
        assertThrows(IllegalArgumentException.class, () -> player.setFlying(true));
        assertEquals(controls, player.state().controls());
        player.setAllowFlight(true); player.setFlying(true); player.setFlySpeed(0.3F);
        assertTrue(scene.nativeTarget.getAbilities().flying); assertEquals(0.15F, scene.nativeTarget.getAbilities().getFlySpeed());
        player.setAllowFlight(false); assertFalse(player.isFlying()); assertFalse(player.getAllowFlight());
        player.setSprinting(true); assertTrue(scene.nativeTarget.isSprinting()); assertTrue(speed.getValue() > baseSpeed);
        player.setSneaking(true); player.setGliding(true); player.setCanPickupItems(false);
        player.setExp(0.7F); player.setExhaustion(2);
        assertTrue(scene.nativeTarget.isSneaking()); assertTrue(scene.nativeTarget.isGliding());
        assertFalse(player.getCanPickupItems()); assertEquals(0.7F, scene.nativeTarget.experienceProgress);
        assertEquals(2, ((com.projectkorra.projectkorra.fabric.mixin.client.HungerManagerRollbackAccess) scene.nativeTarget.getHungerManager()).rollback$exhaustion());
        scene.nativeTarget.getHungerManager().addExhaustion(1); assertEquals(3, player.getExhaustion());
        assertThrows(IllegalStateException.class, () -> player.state().controls(controls));
        var detached = new RollbackLivingState(player.body(), player.state().living().vitals(), player.state().living().attributes(),
                player.getActivePotionEffects(), player.getEquipment(), scene.targetState);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerState.nativeBacked(detached, scene.inventory(),
                player.state().profile(), Set.of(), player.getScoreboard(), unusedPlayerRules(), scene.targetState));
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(player::isSneaking).join());
        before.restore();
        assertEquals(controls, player.state().controls()); assertEquals(baseSpeed, speed.getValue());
        assertTrue(player.getCanPickupItems());
    }

    @Test void nativeControlReadsAndRepeatedGlowingSettersRetainTheExplicitTag() {
        var scene = new Scene(new World()); var player = scene.controlled(); var before = snapshot(player);
        player.state().flag(RollbackPlayerState.Flag.SWIMMING, true); assertTrue(scene.nativeTarget.isSwimming());
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0));
        assertTrue(player.isGlowing()); assertFalse(scene.nativeTarget.isGlowingLocal());
        player.setGlowing(true);
        player.removePotionEffect(PotionEffectType.GLOWING);
        assertTrue(player.isGlowing()); assertTrue(scene.nativeTarget.isGlowingLocal());
        assertThrows(IllegalArgumentException.class, () -> scene.attackerState.changeControls(player.state(), RollbackPlayerState.Control.SPRINTING, player.state().controls()));
        before.restore();
        assertFalse(player.isGlowing()); assertFalse(scene.nativeTarget.isSwimming());
    }

    @Test void lateSprintControlReplaysTheSameNativePlayerTick() {
        assertControlReplay(false);
    }

    @Test void lateJumpControlReplaysTheSameNativePlayerTick() {
        assertControlReplay(true);
    }

    private void assertControlReplay(boolean jump) {
        var first = new ControlSimulation(jump); var second = new ControlSimulation(jump);
        var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
        var direct = new RollbackEngine<>(first, Map.of(B, false), limits, 1_000);
        var late = new RollbackEngine<>(second, Map.of(B, false), limits, 1_000);
        direct.submit(B, 1, true); direct.advance(); late.advance();
        assertNotEquals(first.scene.targetState.readKinematics(), second.scene.targetState.readKinematics());
        late.submit(B, 1, true); late.reconcile();
        assertEquals(first.scene.targetState.readKinematics(), second.scene.targetState.readKinematics());
        assertEquals(first.player.state().controls(), second.player.state().controls());
        for (int tick = 2; tick <= 5; tick++) assertEquals(direct.advance().finalizedEffects(), late.advance().finalizedEffects());
    }

    private static final class ControlSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, RollbackEntityBody.Kinematics> {
        final Scene scene = new Scene(new World()); final RollbackPlayer player = scene.controlled();
        final boolean jump;
        ControlSimulation(boolean jump) { this.jump = jump; }
        @Override public RollbackStateGraph.Snapshot snapshot() { return FabricRollbackLivingStateTest.snapshot(player); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<RollbackEntityBody.Kinematics> effects) {
            player.setSprinting(jump || inputs.get(B));
            scene.targetState.movementInput(new RollbackMovementInput(0, 1, jump && inputs.get(B), 0, 0));
            scene.targetState.tick();
            effects.emit(player.body().kinematics());
        }
    }

    @Test void lateInputRetractsArmorWearAlongsideTheDiscardedHit() {
        var directSimulation = new AvoidedHit(); var lateSimulation = new AvoidedHit();
        var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
        var direct = new RollbackEngine<>(directSimulation, Map.of(B, false), limits, 1_000);
        var late = new RollbackEngine<>(lateSimulation, Map.of(B, false), limits, 1_000);
        direct.submit(B, 1, true); direct.advance(); late.advance();
        assertTrue(lateSimulation.armor.getDurability() > 0);
        assertTrue(lateSimulation.scene.target.getHealth() < 20);
        late.submit(B, 1, true); assertEquals(1, late.reconcile().replayedFrom());
        assertEquals(0, lateSimulation.armor.getDurability());
        assertEquals(directSimulation.scene.target.living().vitals(), lateSimulation.scene.target.living().vitals());
        assertEquals(directSimulation.scene.target.body().kinematics(), lateSimulation.scene.target.body().kinematics());
        assertTrue(lateSimulation.scene.queries.outputs.isEmpty());
        for (int tick = 2; tick <= 5; tick++) assertEquals(direct.advance().finalizedEffects(), late.advance().finalizedEffects());
        assertEquals(0, lateSimulation.armor.getDurability());
    }

    /** Input acceptance fixture; production collision/defence dispatch remains dynamic. */
    private static final class AvoidedHit implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, FabricRollbackWorldAccess.Output> {
        final Scene scene = new Scene(new World());
        final com.projectkorra.projectkorra.platform.mc.inventory.ItemStack armor;
        AvoidedHit() {
            var inventory = scene.inventory();
            inventory.setItem(inventory.layout().chestplate(), scene.items.create(Material.DIAMOND_CHESTPLATE, 1));
            scene.target.getAttribute(Attribute.valueOf("ARMOR")).setValue(8);
            armor = inventory.getChestplate();
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return FabricRollbackLivingStateTest.snapshot(scene.target, scene.attacker, armor); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<FabricRollbackWorldAccess.Output> effects) {
            scene.queries.time = tick; scene.queries.outputs.clear();
            if (tick == 1 && !inputs.get(B)) scene.target.damage(8, scene.attacker);
            scene.queries.outputs.forEach(effects::emit);
        }
    }

    private static final class Simulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, FabricRollbackWorldAccess.Output> {
        final Scene scene = new Scene(new World());
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return false; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<FabricRollbackWorldAccess.Output> effects) {
            scene.queries.time = tick; scene.queries.outputs.clear();
            if (inputs.get(A)) scene.target.damage(6, scene.attacker);
            scene.queries.outputs.forEach(effects::emit);
        }
    }
    private static final RollbackEntityBody.Rules BODY_RULES = new RollbackEntityBody.Rules() {
        @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
        @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
    };
    private static final class Scene {
        final FabricRollbackWorldAccessTest.Queries queries = new FabricRollbackWorldAccessTest.Queries(
                new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(-4, -4, -4, 9, 10, 12));
        final FabricRollbackWorldAccess world = new FabricRollbackWorldAccess(queries);
        final Player nativeAttacker = new Player(world.world(), A, "attacker"), nativeTarget = new Player(world.world(), B, "target");
        final FabricRollbackNativePlayerState attackerState = new FabricRollbackNativePlayerState(nativeAttacker, world, 20_000);
        final FabricRollbackNativePlayerState targetState = new FabricRollbackNativePlayerState(nativeTarget, world, 20_000);
        final RollbackNativeItems<net.minecraft.item.ItemStack> items = new RollbackNativeItems<>(new FabricRollbackItems(queries.registries(), Map.of(
                "minecraft:air", RollbackNativeItems.Kind.GENERIC, "minecraft:stone", RollbackNativeItems.Kind.GENERIC,
                "minecraft:diamond_boots", RollbackNativeItems.Kind.GENERIC, "minecraft:diamond_helmet", RollbackNativeItems.Kind.GENERIC,
                "minecraft:diamond_chestplate", RollbackNativeItems.Kind.GENERIC)));
        final RollbackLivingEntity attacker, target;
        Scene(World logicalWorld) {
            nativeAttacker.setId(801); nativeTarget.setId(802);
            nativeAttacker.setPosition(0, 1, 0); nativeTarget.setPosition(1, 1, 0); nativeTarget.setOnGround(true);
            nativeAttacker.setYaw(0); nativeAttacker.setPitch(0); nativeTarget.setYaw(0); nativeTarget.setPitch(0);
            nativeAttacker.getRandom().setSeed(53); nativeTarget.getRandom().setSeed(59);
            attacker = view(attackerState, logicalWorld); target = view(targetState, logicalWorld);
        }
        RollbackLivingEntity view(FabricRollbackNativePlayerState nativeState, World logicalWorld) {
            var body = RollbackEntityBody.nativeBacked(nativeState.identity(), logicalWorld, nativeState, BODY_RULES);
            return new RollbackLivingEntity(RollbackLivingState.nativeBacked(body, new RollbackEquipment(FabricRollbackInventory.bind(nativeState, items)), nativeState));
        }
        RollbackInventory inventory() { return ((RollbackEquipment) target.getEquipment()).inventory(); }
        RollbackPlayer controlled() {
            var body = RollbackEntityBody.nativeBacked(targetState.identity(), target.getWorld(), targetState, BODY_RULES);
            var living = RollbackLivingState.nativeBacked(body, target.getEquipment(), targetState);
            return new RollbackPlayer(RollbackPlayerState.nativeBacked(living, inventory(),
                    new RollbackPlayerState.Profile("target", "SURVIVAL", "RIGHT", true, false, true, 100),
                    Set.of(), new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(), unusedPlayerRules(), targetState));
        }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 20_000).capture(List.of(target, attacker), List.of()); }
    }
    private static final class Player extends FabricRollbackSimulatedPlayer {
        Player(net.minecraft.world.World world, UUID id, String name) { super(world, new GameProfile(id, name)); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
    /** Used only to verify rejection of a mismatched movement/combat source. */
    private static final class UnusedEquipment extends EntityEquipment implements RollbackStateCell<Void> {
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
    }
    /** Fail if the native control tests accidentally use unrelated session services. */
    private static RollbackPlayerState.Rules unusedPlayerRules() {
        return (RollbackPlayerState.Rules) java.lang.reflect.Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(),
                new Class<?>[]{RollbackPlayerState.Rules.class}, (proxy, method, arguments) -> { throw new AssertionError("Unexpected fixture service: " + method.getName()); });
    }
}
