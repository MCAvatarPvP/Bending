package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingEntity;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLivingState;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEquipment;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackInventory;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackLivingStateNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    private static final UUID A = new UUID(0, 801), B = new UUID(0, 802);

    @Test void logicalDamageReadsNativeHealthImmunityKnockbackAndRestoresThroughRetainedAttribute() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World());
            var maximum = scene.target.getAttribute(Attribute.MAX_HEALTH);
            var before = new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(maximum), List.of());
            scene.target.damage(6, scene.attacker);
            assertEquals(14, scene.target.getHealth()); assertEquals(14, scene.nativeTarget.getHealth());
            assertEquals(scene.nativeTarget.invulnerableTime, scene.target.getNoDamageTicks());
            assertEquals(scene.nativeTarget.lastHurt, scene.target.getLastDamage());
            assertEquals(scene.nativeTarget.getDeltaMovement().y, scene.target.getVelocity().getY());
            assertTrue(scene.target.getVelocity().getY() > 0);
            assertSame(scene.nativeAttacker, scene.nativeTarget.getLastHurtByMob());
            var output = List.copyOf(scene.combat.outputs);
            maximum.setValue(24);
            assertEquals(24, scene.target.getMaxHealth()); assertEquals(24, scene.nativeTarget.getMaxHealth());
            before.restore();
            assertEquals(20, maximum.getValue()); assertEquals(20, scene.target.getHealth());
            assertEquals(0, scene.target.getVelocity().lengthSquared());
            assertTrue(scene.combat.outputs.isEmpty());
            scene.target.damage(6, scene.attacker);
            assertEquals(output, scene.combat.outputs);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void nativePotionMergingCancellationAndAttributeEffectsStayVisibleThroughLogicalViews() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World()); var before = scene.snapshot();
            var speed = scene.target.getAttribute(Attribute.valueOf("MOVEMENT_SPEED"));
            double initialSpeed = speed.getValue();
            scene.combat.cancelEffects = true;
            assertTrue(scene.target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1), true));
            assertFalse(scene.target.hasPotionEffect(PotionEffectType.SPEED));
            before.restore();
            scene.target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1));
            assertEquals(80, scene.target.getPotionEffect(PotionEffectType.SPEED).getDuration());
            var ordinary = new org.bukkit.potion.PotionEffect(new org.bukkit.craftbukkit.potion.CraftPotionEffectType(net.minecraft.world.effect.MobEffects.SPEED), 80, 1);
            var actual = scene.nativeTarget.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
            assertEquals(ordinary.isAmbient(), actual.isAmbient());
            assertEquals(ordinary.hasParticles(), actual.isVisible());
            assertEquals(ordinary.hasIcon(), actual.showIcon());
            assertTrue(speed.getValue() > initialSpeed);
            var active = scene.snapshot(); var expected = List.copyOf(scene.combat.outputs);
            scene.target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, 3));
            assertEquals(3, scene.target.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
            assertTrue(scene.target.getActivePotionEffects().stream().anyMatch(effect -> effect.getType().name().equals("SPEED")));
            scene.target.removePotionEffect(PotionEffectType.SPEED);
            assertFalse(scene.target.hasPotionEffect(PotionEffectType.SPEED));
            active.restore();
            assertEquals(1, scene.target.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
            assertEquals(expected, scene.combat.outputs);
            assertTrue(speed.getValue() > initialSpeed);
            before.restore();
            assertFalse(scene.target.hasPotionEffect(PotionEffectType.SPEED)); assertEquals(initialSpeed, speed.getValue());
            return null;
        });
    }

    @Test void logicalVitalsWriteNativeStateAndAirEventsWithoutBypassingNativeValidation() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World()); scene.nativeTarget.valid = true;
            var before = scene.snapshot();
            scene.target.setHealth(8); scene.target.setNoDamageTicks(7);
            scene.target.setRemainingAir(100); scene.target.setAI(true);
            assertEquals(8, scene.nativeTarget.getHealth()); assertEquals(8, scene.nativeTarget.getBukkitEntity().getHealth());
            assertEquals(7, scene.nativeTarget.invulnerableTime); assertEquals(100, scene.nativeTarget.getAirSupply());
            assertFalse(scene.target.living().vitals().ai());
            assertTrue(scene.combat.events.contains(org.bukkit.event.entity.EntityAirChangeEvent.class.getName()));
            scene.combat.multiplier = 0.5;
            scene.target.setRemainingAir(80); assertEquals(40, scene.target.getRemainingAir());
            scene.combat.cancel = true;
            scene.target.setRemainingAir(10); assertEquals(40, scene.target.getRemainingAir());
            assertThrows(IllegalArgumentException.class, () -> scene.target.setHealth(21));
            assertThrows(IllegalArgumentException.class, () -> scene.target.setHealth(0));
            assertThrows(IllegalArgumentException.class, () -> scene.target.setHealth(Double.MIN_VALUE));
            assertThrows(IllegalStateException.class, () -> scene.target.living().replaceAttributes(Map.of("MAX_HEALTH", 4D)));
            assertThrows(IllegalStateException.class, () -> scene.target.living().replacePotions(List.of()));
            assertEquals(8, scene.nativeTarget.getHealth());
            before.restore();
            assertEquals(20, scene.target.getHealth()); assertEquals(300, scene.target.getRemainingAir());
            assertTrue(scene.combat.events.isEmpty()); assertTrue(scene.combat.outputs.isEmpty());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void foreignNativeSourcesAndMismatchedBackingsFailBeforeDamage() throws Exception {
        onTickThread(() -> {
            var logicalWorld = new World(); var scene = new Scene(logicalWorld); var foreign = new Scene(logicalWorld);
            assertThrows(IllegalArgumentException.class, () -> scene.target.damage(6, foreign.attacker));
            assertThrows(IllegalArgumentException.class, () -> RollbackLivingState.nativeBacked(scene.target.body(), new UnusedEquipment(), scene.attackerState));
            assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(scene.target::getHealth).join());
            assertEquals(20, scene.target.getHealth()); assertTrue(scene.combat.outputs.isEmpty());
            return null;
        });
    }

    @Test void lateLogicalDamageReplaysNativeCombatAndFinalizesIdenticalOutputs() throws Exception {
        onTickThread(() -> {
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
            return null;
        });
    }

    @Test void logicalArmorAndNativeDamageRewindThroughTheRetainedItemAlone() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World());
            var inventory = scene.inventory();
            inventory.setItem(inventory.layout().chestplate(), scene.items.create(Material.DIAMOND_CHESTPLATE, 1));
            scene.target.getAttribute(Attribute.valueOf("ARMOR")).setValue(8); // Imported effective attribute; no equipment tick here.
            var armor = inventory.getChestplate();
            var nativeArmor = scene.nativeTarget.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            var before = snapshot(armor);
            scene.target.damage(8, scene.attacker);
            assertTrue(scene.target.getHealth() > 12 && scene.target.getHealth() < 20);
            assertTrue(armor.getDurability() > 0);
            assertEquals(nativeArmor.getDamageValue(), armor.getDurability());
            var output = List.copyOf(scene.combat.outputs); double health = scene.target.getHealth();
            short wear = armor.getDurability();
            before.restore();
            assertEquals(20, scene.target.getHealth()); assertEquals(0, armor.getDurability());
            assertSame(nativeArmor, scene.nativeTarget.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
            assertTrue(scene.combat.outputs.isEmpty());
            scene.target.damage(8, scene.attacker);
            assertEquals(health, scene.target.getHealth()); assertEquals(wear, armor.getDurability());
            assertEquals(output, scene.combat.outputs);
            return null;
        });
    }

    @Test void retainedMirrorsFollowNativeMutationsAndRestoreEvenAfterTheirSlotIsReplaced() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World()); var inventory = scene.inventory();
            inventory.setItemInMainHand(scene.items.create(Material.DIAMOND_BOOTS, 1));
            var first = inventory.getItemInMainHand(); var second = inventory.getItemInMainHand();
            var nativeItem = scene.nativeTarget.getMainHandItem();
            var before = snapshot(second, inventory, first);
            first.setDurability((short) 7); assertEquals(7, second.getDurability());
            var meta = first.getItemMeta(); meta.setDisplayName("retained"); first.setItemMeta(meta);
            assertEquals("retained", second.getItemMeta().getDisplayName());
            assertEquals("retained", nativeItem.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString());
            first.setType(Material.DIAMOND_HELMET);
            assertSame(nativeItem, scene.nativeTarget.getMainHandItem());
            assertEquals(Material.DIAMOND_HELMET, second.getType());
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
            assertSame(nativeItem, scene.nativeTarget.getMainHandItem());
            assertEquals(Material.DIAMOND_BOOTS, first.getType()); assertEquals(0, second.getDurability());
            first.setType(Material.AIR); // CraftItemStack detaches this mirror, not the slot.
            assertEquals(Material.DIAMOND_BOOTS, inventory.getItem(0).getType());
            before.restore(); first.setDurability((short) 2); assertEquals(2, second.getDurability());
            assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(first::getAmount).join());
            return null;
        });
    }

    @Test void logicalInventoryOperationsUseNativeSlotsAndRejectAnotherPlayersEquipment() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World()); var inventory = scene.inventory();
            int maximum = scene.nativeTarget.getInventory().getMaxStackSize();
            var before = snapshot(inventory);
            inventory.setHeldItemSlot(4); inventory.maximumStack(16);
            inventory.setItemInMainHand(scene.items.create(Material.STONE, 12));
            assertTrue(inventory.addItem(scene.items.create(Material.STONE, 7)).isEmpty());
            assertEquals(4, scene.nativeTarget.getInventory().getSelectedSlot());
            assertEquals(16, scene.nativeTarget.getMainHandItem().getCount());
            assertEquals(3, scene.nativeTarget.getInventory().getItem(0).getCount());
            inventory.removeItem(scene.items.create(Material.STONE, 5));
            assertEquals(14, scene.nativeTarget.getMainHandItem().getCount());
            inventory.setItem(42, scene.items.create(Material.STONE, 1));
            assertEquals(1, scene.nativeTarget.getInventory().getItem(42).getCount());
            assertThrows(IllegalArgumentException.class, () -> RollbackLivingState.nativeBacked(scene.target.body(), scene.attacker.getEquipment(), scene.targetState));
            before.restore();
            assertEquals(0, inventory.getHeldItemSlot()); assertEquals(maximum, inventory.maximumStack());
            assertTrue(scene.nativeTarget.getMainHandItem().isEmpty()); assertNull(inventory.getItem(42));
            var empty = inventory.getItemInMainHand(); empty.setType(Material.STONE); empty.setAmount(5);
            assertTrue(scene.nativeTarget.getMainHandItem().isEmpty());
            return null;
        });
    }

    private static RollbackStateGraph.Snapshot snapshot(Object... roots) {
        return new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(roots), List.of());
    }

    @Test void playerControlsChangeNativeFlightAndSprintAndRewindWithAbilityPackets() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World()); var player = scene.controlled();
            var speed = player.getAttribute(Attribute.valueOf("MOVEMENT_SPEED")); double baseSpeed = speed.getValue();
            var controls = player.state().controls(); var before = snapshot(player);
            assertThrows(IllegalArgumentException.class, () -> player.setFlying(true));
            assertEquals(controls, player.state().controls()); assertTrue(scene.combat.outputs.isEmpty());
            player.setAllowFlight(true); player.setFlying(true); player.setFlySpeed(0.3F);
            assertTrue(scene.nativeTarget.getAbilities().flying); assertEquals(0.15F, scene.nativeTarget.getAbilities().flyingSpeed);
            assertEquals(3, scene.combat.outputs.stream().filter(PaperRollbackConnection.PacketOutput.class::isInstance).count());
            player.setAllowFlight(false); assertFalse(player.isFlying()); assertFalse(player.getAllowFlight());
            player.setSprinting(true); assertTrue(scene.nativeTarget.isSprinting()); assertTrue(speed.getValue() > baseSpeed);
            player.setSneaking(true); player.setGliding(true); player.setCanPickupItems(false);
            player.setExp(0.7F); player.setExhaustion(2);
            assertTrue(scene.nativeTarget.isShiftKeyDown()); assertTrue(scene.nativeTarget.isFallFlying());
            assertFalse(scene.nativeTarget.bukkitPickUpLoot); assertEquals(0.7F, scene.nativeTarget.experienceProgress);
            assertEquals(-1, scene.nativeTarget.lastSentExp); assertEquals(2, scene.nativeTarget.getFoodData().exhaustionLevel);
            scene.nativeTarget.getFoodData().exhaustionLevel = 3;
            assertEquals(3, player.getExhaustion());
            assertThrows(IllegalStateException.class, () -> player.state().controls(controls));
            var detached = new RollbackLivingState(player.body(), player.state().living().vitals(), player.state().living().attributes(),
                    player.getActivePotionEffects(), player.getEquipment(), scene.targetState);
            assertThrows(IllegalArgumentException.class, () -> RollbackPlayerState.nativeBacked(detached, scene.inventory(),
                    player.state().profile(), Set.of(), player.getScoreboard(), unusedPlayerRules(), scene.targetState));
            assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(player::isSneaking).join());
            before.restore();
            assertEquals(controls, player.state().controls()); assertEquals(baseSpeed, speed.getValue());
            assertTrue(scene.combat.outputs.isEmpty()); assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void nativeControlEventsAndRepeatedGlowingSettersRetainPaperSemantics() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(new World()); var player = scene.controlled(); scene.nativeTarget.valid = true;
            var before = snapshot(player);
            scene.combat.cancel = true;
            player.state().flag(RollbackPlayerState.Flag.SWIMMING, true);
            assertFalse(scene.nativeTarget.isSwimming());
            assertTrue(scene.combat.events.contains(org.bukkit.event.entity.EntityToggleSwimEvent.class.getName()));
            scene.combat.cancel = false;
            player.state().flag(RollbackPlayerState.Flag.SWIMMING, true); assertTrue(scene.nativeTarget.isSwimming());
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0));
            assertTrue(player.isGlowing()); assertFalse(scene.nativeTarget.hasGlowingTag());
            player.setGlowing(true); // Same visible value, but now retain the explicit API tag.
            player.removePotionEffect(PotionEffectType.GLOWING);
            assertTrue(player.isGlowing()); assertTrue(scene.nativeTarget.hasGlowingTag());
            assertThrows(IllegalArgumentException.class, () -> scene.attackerState.changeControls(player.state(), RollbackPlayerState.Control.SPRINTING, player.state().controls()));
            before.restore();
            assertFalse(player.isGlowing()); assertFalse(scene.nativeTarget.isSwimming());
            assertTrue(scene.combat.events.isEmpty());
            return null;
        });
    }

    @Test void lateSprintControlReplaysTheSameNativeServerPlayerTick() throws Exception {
        assertControlReplay(false);
    }

    @Test void lateJumpControlReplaysTheSameNativeServerPlayerTick() throws Exception {
        assertControlReplay(true);
    }

    private void assertControlReplay(boolean jump) throws Exception {
        onTickThread(() -> {
            var first = new ControlSimulation(jump); var second = new ControlSimulation(jump);
            var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
            var direct = new RollbackEngine<>(first, Map.of(B, false), limits, 1_000, 1_000_000_000);
            var late = new RollbackEngine<>(second, Map.of(B, false), limits, 1_000, 1_000_000_000);
            direct.submit(B, 1, true); direct.advance(); late.advance();
            assertNotEquals(first.scene.targetState.readKinematics(), second.scene.targetState.readKinematics());
            late.submit(B, 1, true); late.reconcile();
            assertEquals(first.scene.targetState.readKinematics(), second.scene.targetState.readKinematics());
            assertEquals(first.player.state().controls(), second.player.state().controls());
            assertEquals(first.scene.combat.events, second.scene.combat.events);
            for (int tick = 2; tick <= 5; tick++) assertEquals(direct.advance().finalizedEffects(), late.advance().finalizedEffects());
            return null;
        });
    }

    private record TickResult(RollbackEntityBody.Kinematics motion, int age, int damageImmunity, int food, float exhaustion,
                              List<PaperRollbackCombatAccess.Output> outputs, List<String> events) { }
    private static final class ControlSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, TickResult> {
        final Scene scene = new Scene(new World()); final RollbackPlayer player = scene.controlled();
        final boolean jump;
        ControlSimulation(boolean jump) {
            this.jump = jump;
            scene.nativeTarget.setPos(0.5, 1, 0.5);
            // Import the active-world flag: Paper deliberately immobilizes invalid players.
            scene.nativeTarget.valid = true;
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 4; z++) scene.queries.block(x, 0, z, Material.STONE, "minecraft:stone");
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return PaperRollbackLivingStateNativeTest.snapshot(player); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<TickResult> effects) {
            scene.combat.time = tick;
            scene.combat.outputs.clear(); scene.combat.events.clear();
            player.setSprinting(jump || inputs.get(B));
            scene.targetState.movementInput(new RollbackMovementInput(0, 1, jump && inputs.get(B), 0, 0));
            scene.targetState.tick();
            effects.emit(new TickResult(player.body().kinematics(), scene.nativeTarget.tickCount, scene.nativeTarget.invulnerableTime,
                    scene.nativeTarget.getFoodData().getFoodLevel(), player.getExhaustion(), List.copyOf(scene.combat.outputs), List.copyOf(scene.combat.events)));
        }
    }

    @Test void privateJumpCancellationAndJumpTimingRewindWithMovement() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(true);
            var scene = simulation.scene;
            var before = simulation.snapshot();
            var input = new RollbackMovementInput(0, 1, true, 0, 0);
            scene.combat.cancel = true;
            try (var clock = RollbackClock.at(1_000, 1_000_000_000, 1, 50_000_000)) {
                scene.targetState.movementInput(input); scene.targetState.stepMovement();
            }
            assertEquals(1, scene.nativeTarget.getY());
            assertTrue(scene.combat.events.contains(com.destroystokyo.paper.event.entity.EntityJumpEvent.class.getName()));
            assertEquals(0, simulation.player.getExhaustion());
            before.restore();
            try (var clock = RollbackClock.at(1_000, 1_000_000_000, 1, 50_000_000)) {
                simulation.player.setSprinting(true);
                scene.targetState.movementInput(input); scene.targetState.stepMovement();
            }
            assertTrue(scene.nativeTarget.getY() > 1);
            assertEquals(0.2F, simulation.player.getExhaustion());
            var expectedMotion = scene.targetState.readKinematics();
            var expectedEvents = List.copyOf(scene.combat.events);
            before.restore();
            try (var clock = RollbackClock.at(1_000, 1_000_000_000, 1, 50_000_000)) {
                simulation.player.setSprinting(true);
                scene.targetState.movementInput(input); scene.targetState.stepMovement();
            }
            assertEquals(expectedMotion, scene.targetState.readKinematics());
            assertEquals(expectedEvents, scene.combat.events);
            assertEquals(0.2F, simulation.player.getExhaustion());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void bodyTickAdvancesPoseEffectsAndTimersAndRestoresTheirNativeState() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(false);
            var scene = simulation.scene;
            simulation.player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 2, 0));
            scene.nativeTarget.hurtTime = 3;
            var before = simulation.snapshot();
            simulation.player.setSneaking(true);
            scene.targetState.movementInput(new RollbackMovementInput(0, 0, false, 25, -15));
            scene.targetState.tickPlayerBody();
            assertEquals(1, scene.nativeTarget.tickCount);
            assertEquals(2, scene.nativeTarget.hurtTime);
            assertEquals(1, simulation.player.getPotionEffect(PotionEffectType.SPEED).getDuration());
            assertEquals(net.minecraft.world.entity.Pose.CROUCHING, scene.nativeTarget.getPose());
            assertEquals(1.5, scene.nativeTarget.getBoundingBox().getYsize(), 1.0E-6);
            scene.targetState.tickPlayerBody();
            assertFalse(simulation.player.hasPotionEffect(PotionEffectType.SPEED));
            var expected = scene.targetState.readKinematics();
            var expectedEvents = List.copyOf(scene.combat.events);
            before.restore();
            assertEquals(0, scene.nativeTarget.tickCount);
            assertEquals(3, scene.nativeTarget.hurtTime);
            assertEquals(2, simulation.player.getPotionEffect(PotionEffectType.SPEED).getDuration());
            assertEquals(net.minecraft.world.entity.Pose.STANDING, scene.nativeTarget.getPose());
            simulation.player.setSneaking(true);
            scene.targetState.movementInput(new RollbackMovementInput(0, 0, false, 25, -15));
            scene.targetState.tickPlayerBody(); scene.targetState.tickPlayerBody();
            assertEquals(expected, scene.targetState.readKinematics());
            assertEquals(expectedEvents, scene.combat.events);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void bodyTickReplaysRegenerationAndBurnDamageThroughPrivateCombatEvents() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(false);
            var scene = simulation.scene;
            simulation.player.setHealth(12);
            simulation.player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 10, 5));
            var before = simulation.snapshot();
            scene.combat.cancel = true;
            scene.targetState.tickPlayerBody();
            assertEquals(12, simulation.player.getHealth());
            assertEquals(9, simulation.player.getPotionEffect(PotionEffectType.REGENERATION).getDuration());
            assertTrue(scene.combat.events.contains(io.papermc.paper.event.entity.EntityEffectTickEvent.class.getName()));
            before.restore();
            scene.targetState.tickPlayerBody();
            assertEquals(13, simulation.player.getHealth());
            assertTrue(scene.combat.events.contains(org.bukkit.event.entity.EntityRegainHealthEvent.class.getName()));
            var expectedEvents = List.copyOf(scene.combat.events);
            before.restore();
            scene.targetState.tickPlayerBody();
            assertEquals(13, simulation.player.getHealth());
            assertEquals(expectedEvents, scene.combat.events);
            simulation.player.removePotionEffect(PotionEffectType.REGENERATION);
            scene.nativeTarget.setRemainingFireTicks(40);
            var burning = simulation.snapshot();
            scene.targetState.tickPlayerBody();
            assertEquals(12, simulation.player.getHealth());
            assertEquals(39, scene.nativeTarget.getRemainingFireTicks());
            expectedEvents = List.copyOf(scene.combat.events);
            var expectedOutputs = List.copyOf(scene.combat.outputs);
            burning.restore();
            scene.targetState.tickPlayerBody();
            assertEquals(12, simulation.player.getHealth());
            assertEquals(expectedEvents, scene.combat.events);
            assertEquals(expectedOutputs, scene.combat.outputs);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void bodyTickUsesCapturedVoidPolicyAndPrivateDamageDispatch() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(false);
            var scene = simulation.scene;
            var before = simulation.snapshot();
            scene.combat.worldPolicy = new PaperRollbackWorldAccess.WorldPolicy(
                    org.bukkit.World.Environment.NORMAL, true, 3, 66, java.util.OptionalInt.empty());
            scene.targetState.tickPlayerBody();
            assertEquals(17, simulation.player.getHealth());
            assertEquals(org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID,
                    scene.nativeTarget.getBukkitEntity().getLastDamageCause().getCause());
            before.restore();
            scene.targetState.tickPlayerBody();
            assertEquals(20, simulation.player.getHealth());
            assertNull(scene.nativeTarget.getBukkitEntity().getLastDamageCause());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void serverTickAdvancesInvulnerabilityHungerAndBodyOnceAndRestores() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(false);
            var scene = simulation.scene;
            scene.nativeTarget.invulnerableTime = 3;
            scene.nativeTarget.getFoodData().setFoodLevel(10);
            scene.nativeTarget.getFoodData().setSaturation(0);
            simulation.player.setExhaustion(5);
            var before = simulation.snapshot();
            scene.targetState.tick();
            assertEquals(1, scene.nativeTarget.tickCount);
            assertEquals(2, scene.nativeTarget.invulnerableTime);
            assertEquals(9, scene.nativeTarget.getFoodData().getFoodLevel());
            assertEquals(1, simulation.player.getExhaustion());
            assertTrue(scene.combat.events.contains(org.bukkit.event.entity.FoodLevelChangeEvent.class.getName()));
            var expectedMotion = scene.targetState.readKinematics();
            var expectedEvents = List.copyOf(scene.combat.events);
            var expectedOutputs = List.copyOf(scene.combat.outputs);
            before.restore();
            assertEquals(0, scene.nativeTarget.tickCount);
            assertEquals(3, scene.nativeTarget.invulnerableTime);
            assertEquals(10, scene.nativeTarget.getFoodData().getFoodLevel());
            assertEquals(5, simulation.player.getExhaustion());
            scene.targetState.tick();
            assertEquals(expectedMotion, scene.targetState.readKinematics());
            assertEquals(expectedEvents, scene.combat.events);
            assertEquals(expectedOutputs, scene.combat.outputs);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void serverTickDetachesEquipmentAndInventoryPacketsAndReplaysTheirUpdates() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(false);
            var scene = simulation.scene;
            scene.inventory().setItem(0, scene.items.create(Material.STONE, 3));
            scene.inventory().setItem(scene.inventory().layout().chestplate(), scene.items.create(Material.DIAMOND_CHESTPLATE, 1));
            var before = simulation.snapshot();
            scene.targetState.tick();
            var tracked = scene.combat.outputs.stream().filter(PaperRollbackPacketData.Tracked.class::isInstance)
                    .map(PaperRollbackPacketData.Tracked.class::cast).toList();
            assertEquals(1, tracked.size());
            assertEquals(B, tracked.getFirst().entity()); assertFalse(tracked.getFirst().includeSelf());
            var equipment = (PaperRollbackPacketData.Equipment) tracked.getFirst().data();
            assertTrue(equipment.sanitize());
            var mainHand = equipment.slots().stream().filter(slot -> slot.slot().equals("mainhand")).findFirst().orElseThrow().item();
            var codec = new PaperRollbackItemCodec(scene.world.world().registryAccess());
            assertEquals(3, codec.decode(mainHand).getCount());
            assertTrue(scene.combat.outputs.stream().anyMatch(value -> value instanceof PaperRollbackPacketData.Direct packet
                    && packet.data() instanceof PaperRollbackPacketData.Content));
            var expected = List.copyOf(scene.combat.outputs);
            scene.nativeTarget.getMainHandItem().setCount(1);
            assertEquals(3, codec.decode(mainHand).getCount());
            before.restore();
            scene.targetState.tick();
            assertEquals(expected, scene.combat.outputs);
            assertTrue(scene.combat.events.contains(io.papermc.paper.event.player.PlayerInventorySlotChangeEvent.class.getName()));

            scene.combat.outputs.clear(); scene.combat.events.clear();
            var initialized = simulation.snapshot();
            scene.nativeTarget.getMainHandItem().setCount(2);
            scene.targetState.tick();
            var deltas = scene.combat.outputs.stream().filter(PaperRollbackPacketData.Direct.class::isInstance)
                    .map(PaperRollbackPacketData.Direct.class::cast).map(PaperRollbackPacketData.Direct::data)
                    .filter(PaperRollbackPacketData.Slot.class::isInstance).map(PaperRollbackPacketData.Slot.class::cast).toList();
            assertEquals(1, deltas.size());
            assertEquals(36, deltas.getFirst().slot()); // Native menu index of hotbar slot zero.
            assertEquals(2, codec.decode(deltas.getFirst().item()).getCount());
            assertTrue(scene.combat.outputs.stream().noneMatch(value -> value instanceof PaperRollbackPacketData.Direct packet
                    && packet.data() instanceof PaperRollbackPacketData.Content));
            var deltaOutputs = List.copyOf(scene.combat.outputs);
            var deltaEvents = List.copyOf(scene.combat.events);
            initialized.restore();
            assertEquals(3, scene.nativeTarget.getMainHandItem().getCount());
            scene.nativeTarget.getMainHandItem().setCount(2);
            scene.targetState.tick();
            assertEquals(deltaOutputs, scene.combat.outputs);
            assertEquals(deltaEvents, scene.combat.events);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void unloadedServerTickRejectsBeforeMenuInitializationOrPlayerMutation() throws Exception {
        onTickThread(() -> {
            var simulation = new ControlSimulation(false);
            var scene = simulation.scene;
            scene.targetState.clientLoaded(false);
            scene.inventory().setItem(0, scene.items.create(Material.STONE, 3));
            var motion = scene.targetState.readKinematics();
            assertThrows(IllegalStateException.class, scene.targetState::tick);
            assertEquals(0, scene.nativeTarget.tickCount);
            assertEquals(motion, scene.targetState.readKinematics());
            assertTrue(scene.combat.outputs.isEmpty());
            assertTrue(scene.combat.events.isEmpty());
            scene.targetState.clientLoaded(true);
            scene.targetState.tick();
            assertEquals(1, scene.nativeTarget.tickCount);
            assertTrue(scene.combat.outputs.stream().anyMatch(value -> value instanceof PaperRollbackPacketData.Direct packet
                    && packet.data() instanceof PaperRollbackPacketData.Content));
            return null;
        });
    }

    @Test void lateInputRetractsArmorWearAlongsideTheDiscardedHit() throws Exception {
        onTickThread(() -> {
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
            assertTrue(lateSimulation.scene.combat.outputs.isEmpty());
            for (int tick = 2; tick <= 5; tick++) assertEquals(direct.advance().finalizedEffects(), late.advance().finalizedEffects());
            assertEquals(0, lateSimulation.armor.getDurability());
            return null;
        });
    }

    /** Input acceptance fixture; production collision/defence dispatch remains dynamic. */
    private static final class AvoidedHit implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, PaperRollbackCombatAccess.Output> {
        final Scene scene = new Scene(new World());
        final com.projectkorra.projectkorra.platform.mc.inventory.ItemStack armor;
        AvoidedHit() {
            var inventory = scene.inventory();
            inventory.setItem(inventory.layout().chestplate(), scene.items.create(Material.DIAMOND_CHESTPLATE, 1));
            scene.target.getAttribute(Attribute.valueOf("ARMOR")).setValue(8);
            armor = inventory.getChestplate();
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return PaperRollbackLivingStateNativeTest.snapshot(scene.target, scene.attacker, armor); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<PaperRollbackCombatAccess.Output> effects) {
            scene.combat.time = tick; scene.combat.outputs.clear();
            if (tick == 1 && !inputs.get(B)) scene.target.damage(8, scene.attacker);
            scene.combat.outputs.forEach(effects::emit);
        }
    }

    private static final class Simulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, PaperRollbackCombatAccess.Output> {
        final Scene scene = new Scene(new World());
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot saved) { saved.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return false; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<PaperRollbackCombatAccess.Output> effects) {
            scene.combat.time = tick; scene.combat.outputs.clear();
            if (inputs.get(A)) scene.target.damage(6, scene.attacker);
            scene.combat.outputs.forEach(effects::emit);
        }
    }
    private static final RollbackEntityBody.Rules BODY_RULES = new RollbackEntityBody.Rules() {
        @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
        @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
    };
    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccessNativeTest.Queries queries = new PaperRollbackWorldAccessNativeTest.Queries(
                new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(-4, -4, -4, 9, 10, 12));
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(queries, combat, 4);
        final PaperRollbackNativePlayerState attackerState = player(A, "attacker", 53), targetState = player(B, "target", 59);
        final ServerPlayer nativeAttacker = attackerState.use(value -> (ServerPlayer) value), nativeTarget = targetState.use(value -> (ServerPlayer) value);
        final RollbackNativeItems<net.minecraft.world.item.ItemStack> items = new RollbackNativeItems<>(new PaperRollbackItems(world.world().registryAccess()));
        final RollbackLivingEntity attacker, target;
        Scene(World logicalWorld) {
            nativeAttacker.setId(801); nativeTarget.setId(802);
            nativeAttacker.setPos(0, 1, 0); nativeTarget.setPos(1, 1, 0); nativeTarget.setOnGround(true);
            nativeAttacker.setYRot(0); nativeAttacker.setXRot(0); nativeTarget.setYRot(0); nativeTarget.setXRot(0);
            attacker = view(attackerState, logicalWorld); target = view(targetState, logicalWorld);
        }
        PaperRollbackNativePlayerState player(UUID id, String name, long seed) {
            return PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(id, name), ClientInformation.createDefault(), GameType.SURVIVAL, seed, 200_000);
        }
        RollbackLivingEntity view(PaperRollbackNativePlayerState nativeState, World logicalWorld) {
            var body = RollbackEntityBody.nativeBacked(nativeState.identity(), logicalWorld, nativeState, BODY_RULES);
            return new RollbackLivingEntity(RollbackLivingState.nativeBacked(body, new RollbackEquipment(PaperRollbackInventory.bind(nativeState, items)), nativeState));
        }
        RollbackInventory inventory() { return ((RollbackEquipment) target.getEquipment()).inventory(); }
        RollbackPlayer controlled() {
            var body = RollbackEntityBody.nativeBacked(targetState.identity(), target.getWorld(), targetState, BODY_RULES);
            var living = RollbackLivingState.nativeBacked(body, target.getEquipment(), targetState);
            return new RollbackPlayer(RollbackPlayerState.nativeBacked(living, inventory(),
                    new RollbackPlayerState.Profile("target", "SURVIVAL", "RIGHT", true, false, true, 100),
                    Set.of(), new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(), unusedPlayerRules(), targetState));
        }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 200_000).capture(List.of(target, attacker), List.of()); }
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
