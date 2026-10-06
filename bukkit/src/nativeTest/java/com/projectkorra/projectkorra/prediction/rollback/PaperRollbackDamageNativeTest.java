package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;
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

class PaperRollbackDamageNativeTest {
    private static RegistryAccess.Frozen registries;
    @BeforeAll static synchronized void bootstrap() {
        if (registries != null) return;
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()))) {
            TagLoader.loadTagsForExistingRegistries(resources, statics).forEach(Registry.PendingTags::apply);
            var dynamic = RegistryDataLoader.load(resources, statics.registries()
                    .<net.minecraft.core.HolderLookup.RegistryLookup<?>>map(RegistryAccess.RegistryEntry::value).toList(), RegistryDataLoader.WORLDGEN_REGISTRIES);
            registries = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(statics.registries(), dynamic.registries())).freeze();
        }
    }

    @Test void nativeHandSwingAndDetachedAnimationRewindTogether() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var before = scene.snapshot();
            scene.targetState.swing(false);
            assertTrue(scene.target.swinging); assertEquals(-1, scene.target.swingTime);
            var output = (PaperRollbackPacketData.Tracked) scene.combat.outputs.getLast();
            assertEquals(scene.target.getUUID(), output.entity()); assertFalse(output.includeSelf());
            assertEquals(new PaperRollbackPacketData.Animation(scene.target.getId(), 0), output.data());
            scene.targetState.swing(false);
            assertEquals(2, scene.combat.outputs.size(), "Native pre-animation swings retain their original cadence");
            scene.target.swingTime = 0;
            scene.targetState.swing(false);
            assertEquals(2, scene.combat.outputs.size(), "An animation in its first half must not restart");
            before.restore(); assertFalse(scene.target.swinging); assertTrue(scene.combat.outputs.isEmpty());
            scene.targetState.swing(false);
            assertEquals(output, scene.combat.outputs.getLast());
            before.restore(); scene.targetState.swing(true);
            assertEquals(net.minecraft.world.InteractionHand.OFF_HAND, scene.target.swingingArm);
            assertEquals(new PaperRollbackPacketData.Animation(scene.target.getId(), 3),
                    ((PaperRollbackPacketData.Tracked) scene.combat.outputs.getLast()).data());
            return null;
        });
    }
    @Test void nativePaperHitRestoresDamageKnockbackEventsAndLastDamageCauseTogether() throws Exception {
        onTickThread(() -> {
            assertNull(Bukkit.getServer());
            var scene = new Scene();
            var saved = scene.snapshot();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.PLAYER_ATTACK), scene.attacker);
            assertTrue(scene.targetState.damage(source, 6));
            var expected = scene.result();
            assertEquals(14, expected.health);
            assertTrue(expected.vx > 0 && expected.vy > 0);
            assertEquals(20, scene.target.invulnerableTime);
            assertSame(scene.attacker, scene.target.getLastHurtByMob());
            assertNotNull(scene.target.getBukkitEntity().getLastDamageCause());
            assertTrue(scene.combat.events.stream().anyMatch(name -> name.contains("EntityDamageByEntityEvent")));
            assertFalse(scene.targetState.damage(source, 4));
            assertEquals(expected, scene.result());
            scene.snapshot();
            saved.restore();
            assertEquals(20, scene.target.getHealth());
            assertNull(scene.target.getBukkitEntity().getLastDamageCause());
            assertTrue(scene.combat.outputs.isEmpty()); assertTrue(scene.combat.events.isEmpty());
            assertTrue(scene.targetState.damage(source, 6));
            assertEquals(expected, scene.result());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void nativeArmorEnchantmentsDurabilityAndEventChangesRewindTogether() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var chest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
            var enchantments = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
            enchantments.set(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PROTECTION), 2);
            chest.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, enchantments.toImmutable());
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, chest);
            // Import the native effective armor attribute; equipment ticking is separate coverage.
            scene.target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(8);
            scene.combat.multiplier = 0.5;
            var saved = scene.snapshot();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.PLAYER_ATTACK), scene.attacker);
            assertTrue(scene.targetState.damage(source, 8));
            var expected = scene.result();
            assertTrue(expected.health > 16 && expected.health < 20);
            int damage = chest.getDamageValue();
            assertTrue(damage > 0);
            assertEquals(4, scene.target.getBukkitEntity().getLastDamageCause().getDamage());
            scene.snapshot();
            saved.restore();
            assertEquals(0, chest.getDamageValue());
            assertSame(chest, scene.target.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
            assertTrue(scene.targetState.damage(source, 8));
            assertEquals(damage, chest.getDamageValue());
            assertEquals(expected, scene.result());
            return null;
        });
    }

    @Test void latePrivateDamageCancellationRetractsTheHitAndFinalizesTheSameResult() throws Exception {
        onTickThread(() -> {
            var first = new Simulation(); var second = new Simulation();
            UUID participant = first.scene.target.getUUID();
            var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
            var onTime = new RollbackEngine<>(first, Map.of(participant, false), limits, 0);
            var late = new RollbackEngine<>(second, Map.of(participant, false), limits, 0);
            onTime.submit(participant, 1, true);
            onTime.advance(); late.advance();
            var expected = onTime.advance().head().effects();
            assertNotEquals(expected, late.advance().head().effects());
            assertEquals(14, second.scene.target.getHealth());
            late.submit(participant, 1, true);
            assertEquals(expected, late.reconcile().head().effects());
            assertEquals(20, second.scene.target.getHealth());
            assertTrue(second.scene.combat.outputs.isEmpty());
            assertTrue(second.scene.combat.causal.isEmpty());
            assertNull(second.scene.target.getBukkitEntity().getLastDamageCause());
            for (int tick = 2; tick < 6; tick++) {
                var direct = onTime.advance(); var replayed = late.advance();
                assertEquals(direct.head().effects(), replayed.head().effects());
                assertEquals(direct.finalizedEffects(), replayed.finalizedEffects());
            }
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void lateEquipmentCancellationRestoresBrokenItemAndRetractsBreakStatus() throws Exception {
        onTickThread(() -> {
            var first = new Simulation(true); var second = new Simulation(true);
            UUID participant = first.scene.target.getUUID();
            var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
            var onTime = new RollbackEngine<>(first, Map.of(participant, false), limits, 0);
            var late = new RollbackEngine<>(second, Map.of(participant, false), limits, 0);
            var retainedItem = second.scene.target.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            int originalDamage = retainedItem.getDamageValue();
            onTime.submit(participant, 1, true);
            onTime.advance(); late.advance();
            var expected = onTime.advance().head().effects();
            assertNotEquals(expected, late.advance().head().effects());
            assertEquals(0, retainedItem.getCount());
            assertTrue(second.scene.combat.outputs.stream().anyMatch(PaperRollbackCombatAccess.StatusOutput.class::isInstance));
            second.scene.snapshot(); // Post-break native state must remain capturable too.
            late.submit(participant, 1, true);
            assertEquals(expected, late.reconcile().head().effects());
            assertSame(retainedItem, second.scene.target.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
            assertEquals(1, retainedItem.getCount());
            assertEquals(originalDamage, retainedItem.getDamageValue());
            assertTrue(second.scene.target.getHealth() < 20); // Cancelling wear does not cancel the hit.
            assertFalse(second.scene.combat.outputs.stream().anyMatch(PaperRollbackCombatAccess.StatusOutput.class::isInstance));
            for (int tick = 0; tick < 4; tick++) {
                var a = onTime.advance(); var b = late.advance();
                assertEquals(a.head().effects(), b.head().effects());
                assertEquals(a.finalizedEffects(), b.finalizedEffects());
            }
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void foreignSourcesAndUnimplementedLethalPathsCannotReachLiveBukkit() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var foreign = new Scene();
            var before = scene.result();
            var registry = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
            assertThrows(IllegalArgumentException.class, () -> scene.targetState.damage(new DamageSource(registry.getOrThrow(DamageTypes.PLAYER_ATTACK), foreign.attacker), 6));
            assertEquals(before, scene.result());
            var saved = scene.snapshot();
            var failure = assertThrows(IllegalStateException.class, () -> scene.targetState.damage(new DamageSource(registry.getOrThrow(DamageTypes.GENERIC)), 100));
            assertTrue(failure.getMessage().contains("Lethal damage"));
            saved.restore();
            assertEquals(before, scene.result());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void nativeDeathProtectionRewindsConsumedItemEffectsAndResurrectionStatus() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var totem = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, totem);
            var saved = scene.snapshot();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC));
            assertTrue(scene.targetState.damage(source, 100));
            var expected = resurrectionResult(scene);
            assertEquals(1, scene.target.getHealth());
            assertEquals(8, scene.target.getAbsorptionAmount());
            assertEquals(0, totem.getCount());
            assertEquals(3, scene.target.getActiveEffects().size());
            assertEquals(900, scene.target.getEffect(net.minecraft.world.effect.MobEffects.REGENERATION).getDuration());
            assertTrue(scene.combat.events.contains("resurrect:OFF_HAND"));
            assertTrue(scene.combat.outputs.contains(new PaperRollbackCombatAccess.StatusOutput(scene.target.getUUID(), (byte) 35)));
            scene.snapshot(); // State after native modifier/effect creation must also be capturable.
            saved.restore();
            assertEquals(20, scene.target.getHealth());
            assertEquals(0, scene.target.getAbsorptionAmount());
            assertEquals(1, totem.getCount());
            assertTrue(scene.target.getActiveEffects().isEmpty());
            assertTrue(scene.combat.outputs.isEmpty());
            assertTrue(scene.targetState.damage(source, 100));
            assertEquals(expected, resurrectionResult(scene));
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void arbitraryItemsUseTheirNativeOrderedDeathProtectionEffects() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
            item.set(net.minecraft.core.component.DataComponents.DEATH_PROTECTION, new net.minecraft.world.item.component.DeathProtection(List.of(
                    new net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect(List.of(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 80, 2))),
                    new net.minecraft.world.item.consume_effects.PlaySoundConsumeEffect(net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP)),
                    new net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect(net.minecraft.world.effect.MobEffects.SPEED))));
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, item);
            var saved = scene.snapshot();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC));
            assertTrue(scene.targetState.damage(source, 100));
            var expected = resurrectionResult(scene);
            assertEquals(1, scene.target.getHealth());
            assertTrue(scene.target.getActiveEffects().isEmpty());
            assertEquals(0, item.getCount());
            assertTrue(scene.combat.events.contains("resurrect:HAND"));
            assertTrue(scene.combat.events.stream().anyMatch(value -> value.startsWith("potion:TOTEM:ADDED:")));
            assertTrue(scene.combat.events.stream().anyMatch(value -> value.startsWith("potion:TOTEM:REMOVED:")));
            assertTrue(scene.combat.outputs.stream().anyMatch(value -> value instanceof PaperRollbackCombatAccess.SoundOutput sound && sound.sound().equals("minecraft:entity.player.levelup")));
            scene.snapshot();
            saved.restore();
            assertTrue(scene.targetState.damage(source, 100));
            assertEquals(expected, resurrectionResult(scene));
            return null;
        });
    }

    private record ResurrectionResult(Result damage, float absorption, int mainHand, int offHand, List<String> effects) { }
    private static ResurrectionResult resurrectionResult(Scene scene) {
        return new ResurrectionResult(scene.result(), scene.target.getAbsorptionAmount(), scene.target.getMainHandItem().getCount(), scene.target.getOffhandItem().getCount(),
                scene.target.getActiveEffects().stream().map(effect -> effect.getEffect().unwrapKey().orElseThrow().identifier() + ":" + effect.getDuration() + ":" + effect.getAmplifier()).sorted().toList());
    }

    @Test void actualServerPlayerRestoresNativeHealthInventoryClientOptionsAndGameModeState() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var information = net.minecraft.server.level.ClientInformation.createDefault();
            var profile = new GameProfile(new UUID(0, 80), "rollback_server");
            var state = PaperRollbackNativePlayerState.serverPlayer(scene.world, profile, information, GameType.CREATIVE, 85, 100_000);
            var player = state.use(value -> (net.minecraft.server.level.ServerPlayer) value);
            assertInstanceOf(net.minecraft.server.level.ServerPlayer.class, player);
            assertSame(player, player.getBukkitEntity().getHandle());
            assertSame(scene.world.world(), player.level());
            assertNotNull(player.connection);
            assertSame(player, player.connection.getPlayer());
            assertTrue(player.getAbilities().instabuild);
            assertEquals(GameType.CREATIVE, player.gameMode());
            assertEquals(information, player.clientInformation());
            assertEquals(20, player.getHealth());
            assertNotSame(net.minecraft.world.entity.Entity.SHARED_RANDOM, player.random);
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 4);
            player.getInventory().setItem(0, item);
            player.setHealth(11);
            var graph = new RollbackStateGraph(value -> false, field -> true, 100_000);
            var saved = graph.capture(List.of(state), List.of());
            double random = player.random.nextGaussian();
            player.setHealth(2);
            item.setCount(1);
            player.getAbilities().instabuild = false;
            player.gameMode.captureSentBlockEntities = true;
            player.getFoodData().setFoodLevel(3);
            player.newLevel = 9;
            player.language = "fr_fr";
            saved.restore();
            assertEquals(11, player.getHealth());
            assertEquals(11, player.getBukkitEntity().getHealth());
            assertEquals(11, player.getEntityData().get(net.minecraft.world.entity.LivingEntity.DATA_HEALTH_ID));
            assertSame(item, player.getInventory().getItem(0));
            assertEquals(4, item.getCount());
            assertTrue(player.getAbilities().instabuild);
            assertFalse(player.gameMode.captureSentBlockEntities);
            assertEquals(20, player.getFoodData().getFoodLevel());
            assertEquals(0, player.newLevel);
            assertEquals(information, player.clientInformation());
            assertEquals(random, player.random.nextGaussian());
            player.setHealth(500);
            assertEquals(20, player.getHealth());
            player.setHealth(Float.NaN);
            assertEquals(20, player.getHealth());
            player.setHealth(-1);
            assertEquals(0, player.getHealth());
            saved.restore();
            state.captureRollbackState();
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void serverPlayerConstructionCannotAdoptForeignServicesOrConnections() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var profile = new GameProfile(new UUID(0, 81), "rollback_guard");
            var information = net.minecraft.server.level.ClientInformation.createDefault();
            var state = PaperRollbackNativePlayerState.serverPlayer(scene.world, profile, information, GameType.SURVIVAL, 86, 100_000);
            var player = state.use(value -> (net.minecraft.server.level.ServerPlayer) value);
            var another = PaperRollbackNativePlayerState.serverPlayer(scene.world,
                    new GameProfile(new UUID(0, 82), "rollback_other"), information, GameType.SURVIVAL, 87, 100_000);
            var foreign = another.use(value -> (net.minecraft.server.level.ServerPlayer) value);
            assertNotSame(player.getStats(), foreign.getStats());
            assertNotSame(player.getAdvancements(), foreign.getAdvancements());
            assertThrows(IllegalStateException.class, () -> player.getStats().save());
            assertThrows(IllegalStateException.class, () -> player.getAdvancements().save());
            assertThrows(IllegalStateException.class, () -> player.getTextFilter().processStreamMessage("test"));
            var connection = player.connection;
            player.connection = com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell
                    .create(net.minecraft.server.network.ServerGamePacketListenerImpl.class).instance();
            try {
                assertThrows(IllegalStateException.class, state::captureRollbackState);
                assertThrows(IllegalStateException.class, () -> player.setHealth(7));
            } finally { player.connection = connection; }
            assertThrows(IllegalArgumentException.class, () -> new PaperRollbackNativePlayerState(player, scene.world, 86, 100_000));
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC));
            assertTrue(state.damage(source, 1));
            assertEquals(19, player.getHealth());
            state.captureRollbackState();
            assertThrows(IllegalArgumentException.class, () -> state.restoreRollbackState(another.captureRollbackState()));
            assertThrows(IllegalStateException.class, () -> PaperRollbackNativePlayerState.serverPlayer(scene.world, profile, information, GameType.SURVIVAL, 86, 100_000));
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void restoredServerPlayerDropsContainerHashesFromDiscardedMutableComponents() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var state = PaperRollbackNativePlayerState.serverPlayer(scene.world,
                    new GameProfile(new UUID(0, 83), "rollback_cache"), net.minecraft.server.level.ClientInformation.createDefault(),
                    GameType.SURVIVAL, 88, 100_000);
            var player = state.use(value -> (net.minecraft.server.level.ServerPlayer) value);
            var component = net.minecraft.network.chat.Component.literal("original");
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
            item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, component);
            player.getInventory().setItem(0, item);
            var typed = new net.minecraft.core.component.TypedDataComponent<>(net.minecraft.core.component.DataComponents.CUSTOM_NAME, component);
            var cache = PaperRollbackPrivateAccess.containerHashes(player);
            int originalHash = cache.getUnchecked(typed);
            assertEquals(1, cache.size());
            var saved = state.captureRollbackState();
            component.append(" discarded");
            int discardedHash = cache.getUnchecked(typed);
            assertNotEquals(originalHash, discardedHash);
            state.restoreRollbackState(saved);
            assertEquals("original", component.getString());
            assertEquals(0, cache.size());
            assertEquals(originalHash, cache.getUnchecked(typed));
            state.restoreRollbackState(saved);
            assertEquals(0, cache.size());
            return null;
        });
    }

    @Test void privateResurrectionListenerCanCancelOrAllowWithoutAnItem() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var totem = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, totem);
            scene.combat.cancelResurrection = true;
            var saved = scene.snapshot();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC));
            var failure = assertThrows(IllegalStateException.class, () -> scene.targetState.damage(source, 100));
            assertTrue(failure.getMessage().contains("death/spawn"));
            assertEquals(1, totem.getCount());
            assertTrue(scene.target.getActiveEffects().isEmpty());
            assertFalse(scene.combat.outputs.contains(new PaperRollbackCombatAccess.StatusOutput(scene.target.getUUID(), (byte) 35)));
            saved.restore();
            assertEquals(20, scene.target.getHealth());

            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, net.minecraft.world.item.ItemStack.EMPTY);
            scene.combat.cancelResurrection = false;
            scene.combat.allowResurrection = true;
            var noItem = scene.snapshot();
            assertTrue(scene.targetState.damage(source, 100));
            var expected = resurrectionResult(scene);
            assertEquals(1, scene.target.getHealth());
            assertEquals(3, scene.target.getActiveEffects().size()); // Native Paper default for an uncancelled event without an item.
            assertTrue(scene.combat.events.contains("resurrect:null"));
            noItem.restore();
            assertTrue(scene.targetState.damage(source, 100));
            assertEquals(expected, resurrectionResult(scene));
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void latePotionCancellationRetractsNativeAttributesAndMatchesFinalizedEffects() throws Exception {
        onTickThread(() -> {
            var first = new ResurrectionSimulation(); var second = new ResurrectionSimulation();
            UUID participant = first.scene.target.getUUID();
            var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
            var onTime = new RollbackEngine<>(first, Map.of(participant, false), limits, 0);
            var late = new RollbackEngine<>(second, Map.of(participant, false), limits, 0);
            onTime.submit(participant, 1, true);
            onTime.advance(); late.advance();
            var expected = onTime.advance().head().effects();
            assertNotEquals(expected, late.advance().head().effects());
            assertEquals(8, second.scene.target.getAbsorptionAmount());
            assertEquals(8, second.scene.target.getMaxAbsorption());
            assertEquals(3, second.scene.target.getActiveEffects().size());
            late.submit(participant, 1, true);
            assertEquals(expected, late.reconcile().head().effects());
            assertEquals(1, second.scene.target.getHealth()); // Effect cancellation does not undo resurrection.
            assertEquals(0, second.scene.target.getAbsorptionAmount());
            assertEquals(0, second.scene.target.getMaxAbsorption());
            assertTrue(second.scene.target.getActiveEffects().isEmpty());
            assertTrue(second.scene.target.getOffhandItem().isEmpty());
            for (int tick = 2; tick < 6; tick++) {
                var direct = onTime.advance(); var replayed = late.advance();
                assertEquals(direct.head().effects(), replayed.head().effects());
                assertEquals(direct.finalizedEffects(), replayed.finalizedEffects());
            }
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void bypassDamageAndUnauditedConsumeEffectsCannotEscapeIntoLiveServices() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var totem = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, totem);
            var saved = scene.snapshot();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.FELL_OUT_OF_WORLD));
            assertThrows(IllegalStateException.class, () -> scene.targetState.damage(source, 100));
            assertEquals(1, totem.getCount());
            assertFalse(scene.combat.events.stream().anyMatch(event -> event.startsWith("resurrect:")));
            saved.restore();
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
            item.set(net.minecraft.core.component.DataComponents.DEATH_PROTECTION, new net.minecraft.world.item.component.DeathProtection(
                    List.of(new net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect())));
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, item);
            var beforeTeleport = scene.snapshot();
            var expected = resurrectionResult(scene);
            var generic = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC));
            var failure = assertThrows(IllegalArgumentException.class, () -> scene.targetState.damage(generic, 100));
            assertTrue(failure.getMessage().contains("Unbound native override"));
            assertEquals(0, scene.target.getX());
            beforeTeleport.restore();
            assertEquals(expected, resurrectionResult(scene));
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void clearingExistingNativeEffectsRestoresTheirInstancesAndModifiers() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var source = new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC));
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
            assertTrue(scene.targetState.damage(source, 100));
            var absorption = scene.target.getEffect(net.minecraft.world.effect.MobEffects.ABSORPTION);
            scene.target.invulnerableTime = 0;
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
            item.set(net.minecraft.core.component.DataComponents.DEATH_PROTECTION, new net.minecraft.world.item.component.DeathProtection(
                    List.of(new net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect())));
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, item);
            var saved = scene.snapshot();
            var before = resurrectionResult(scene);
            assertTrue(scene.targetState.damage(source, 100));
            var expected = resurrectionResult(scene);
            assertTrue(scene.target.getActiveEffects().isEmpty());
            assertEquals(0, scene.target.getMaxAbsorption());
            assertTrue(scene.combat.events.stream().anyMatch(value -> value.startsWith("potion:TOTEM:CLEARED:")));
            saved.restore();
            assertSame(absorption, scene.target.getEffect(net.minecraft.world.effect.MobEffects.ABSORPTION));
            assertEquals(8, scene.target.getMaxAbsorption());
            assertEquals(before, resurrectionResult(scene));
            assertTrue(scene.targetState.damage(source, 100));
            assertEquals(expected, resurrectionResult(scene));
            return null;
        });
    }

    private static final class ResurrectionSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, ResurrectionResult> {
        final Scene scene = new Scene();
        ResurrectionSimulation() {
            scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
        @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<ResurrectionResult> effects) {
            scene.combat.time = tick;
            scene.combat.cancelEffects = inputs.values().iterator().next();
            if (tick == 1) scene.targetState.damage(new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.GENERIC)), 100);
            effects.emit(resurrectionResult(scene));
        }
    }

    private record Result(float health, int immunity, double vx, double vy, double vz, int chestCount, int chestDamage,
                          List<PaperRollbackCombatAccess.Output> output, List<String> events, List<String> causal) { }
    private static final class Simulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, Result> {
        final Scene scene = new Scene();
        final boolean equipmentCancellation;
        Simulation() { this(false); }
        Simulation(boolean equipmentCancellation) {
            this.equipmentCancellation = equipmentCancellation;
            if (equipmentCancellation) {
                var chest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
                chest.setDamageValue(chest.getMaxDamage() - 1);
                scene.target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, chest);
            }
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
        @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
        @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Result> effects) {
            scene.combat.time = tick;
            if (equipmentCancellation) scene.combat.cancelEquipment = inputs.values().iterator().next();
            else scene.combat.cancel = inputs.values().iterator().next();
            if (tick == 1) scene.targetState.damage(new DamageSource(registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.PLAYER_ATTACK), scene.attacker), 6);
            effects.emit(scene.result());
        }
    }
    private static final class Scene {
        final Combat combat = new Combat();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        final NativePlayer attacker = new NativePlayer((Level) world.world(), 1);
        final NativePlayer target = new NativePlayer((Level) world.world(), 2);
        final PaperRollbackNativePlayerState attackerState = new PaperRollbackNativePlayerState(attacker, world, 31, 50_000);
        final PaperRollbackNativePlayerState targetState = new PaperRollbackNativePlayerState(target, world, 17, 50_000);
        final RollbackStateGraph graph = new RollbackStateGraph(value -> false, field -> true, 100_000);
        Scene() { attacker.setPos(-1, 1, 0); target.setPos(0, 1, 0); target.setOnGround(true); }
        RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(targetState), List.of()); }
        Result result() {
            var velocity = target.getDeltaMovement();
            var chest = target.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            return new Result(target.getHealth(), target.invulnerableTime, velocity.x, velocity.y, velocity.z, chest.getCount(), chest.getDamageValue(),
                    List.copyOf(combat.outputs), List.copyOf(combat.events), List.copyOf(combat.causal));
        }
    }
    private static final class NativePlayer extends Player {
        NativePlayer(Level world, long id) { super(world, new GameProfile(new UUID(0, id), "damage" + id)); }
        @Override public GameType gameMode() { return GameType.SURVIVAL; }
    }
    static class Combat implements PaperRollbackWorldAccess.Combat<Combat.Snapshot> {
        record Snapshot(long time, boolean cancel, boolean cancelEquipment, double multiplier, Map<GameRule<?>, Object> rules,
                        boolean cancelResurrection, boolean allowResurrection, boolean cancelEffects, boolean cancelStatistics,
                        boolean cancelAdvancements, boolean replaceAdvancementMessage, boolean pvp, boolean cramming,
                        PaperRollbackWorldAccess.WorldPolicy worldPolicy) { }
        final RandomSource random = RandomSource.create(57);
        final RollbackRandom soundRandom = new RollbackRandom(53);
        final Map<GameRule<?>, Object> rules = new java.util.HashMap<>();
        final ArrayList<PaperRollbackCombatAccess.Output> outputs = new ArrayList<>();
        final ArrayList<String> events = new ArrayList<>();
        final ArrayList<String> causal = new ArrayList<>();
        long time = 20;
        boolean cancel;
        boolean cancelEquipment;
        boolean cancelResurrection;
        boolean allowResurrection;
        boolean cancelEffects;
        boolean cancelStatistics;
        boolean cancelAdvancements;
        boolean replaceAdvancementMessage;
        boolean pvp = true, cramming = true;
        PaperRollbackWorldAccess.WorldPolicy worldPolicy = new PaperRollbackWorldAccess.WorldPolicy(
                org.bukkit.World.Environment.NORMAL, true, 4, -64, java.util.OptionalInt.empty());
        double multiplier = 1;
        Combat() { BuiltInRegistries.GAME_RULE.forEach(rule -> rules.put(rule, rule.defaultValue())); }
        @Override public Object registryAccess() { return registries; }
        @Override public Object difficulty() { return Difficulty.NORMAL; }
        @Override public Object random() { return random; }
        @Override public Object rule(Object rule) { return java.util.Objects.requireNonNull(rules.get(rule)); }
        @Override public long time() { return time; }
        @Override public long nextSoundSeed() { return soundRandom.nextLong(); }
        @Override public boolean skipVanillaDamageTickWhenShieldBlocked() { return false; }
        @Override public boolean updateEquipmentOnPlayerActions() { return true; }
        @Override public boolean allowNonPlayerEntitiesOnScoreboards() { return false; }
        @Override public boolean pvpAllowed() { return pvp; }
        @Override public boolean allowPlayerCrammingDamage() { return cramming; }
        @Override public int maximumEntityCollisions() { return 8; }
        @Override public float jumpExhaustion(boolean sprinting) { return sprinting ? 0.2F : 0.05F; }
        @Override public PaperRollbackWorldAccess.WorldPolicy worldPolicy() { return worldPolicy; }
        @Override public int containerUpdateRate() { return 1; }
        @Override public float regenerationExhaustion() { return 6; }
        @Override public boolean parrotsStayOnShoulder() { return false; }
        @Override public void event(Event event) {
            events.add(event.getClass().getName());
            if (event instanceof EntityDamageEvent damage) { damage.setDamage(damage.getDamage() * multiplier); damage.setCancelled(cancel); }
            if (event instanceof org.bukkit.event.entity.EntityAirChangeEvent air) { air.setAmount((int) (air.getAmount() * multiplier)); air.setCancelled(cancel); }
            if (event instanceof org.bukkit.event.entity.EntityToggleSwimEvent swim) swim.setCancelled(cancel);
            if (event instanceof org.bukkit.event.player.PlayerToggleFlightEvent flight && cancel) flight.setCancelled(true);
            if (event instanceof org.bukkit.event.entity.EntityToggleGlideEvent glide && cancel) glide.setCancelled(true);
            if (event instanceof com.destroystokyo.paper.event.entity.EntityJumpEvent jump) jump.setCancelled(cancel);
            if (event instanceof io.papermc.paper.event.entity.EntityEffectTickEvent tick) tick.setCancelled(cancel);
            if (event instanceof io.papermc.paper.event.entity.EntityDamageItemEvent damage) damage.setCancelled(cancelEquipment);
            if (event instanceof org.bukkit.event.entity.EntityResurrectEvent resurrection) {
                events.add("resurrect:" + resurrection.getHand());
                if (cancelResurrection) resurrection.setCancelled(true);
                if (allowResurrection) resurrection.setCancelled(false);
            }
            if (event instanceof org.bukkit.event.entity.EntityPotionEffectEvent effect) {
                events.add("potion:" + effect.getCause() + ":" + effect.getAction() + ":" + effect.getModifiedType().getKey());
                effect.setCancelled(cancelEffects);
            }
            if (event instanceof org.bukkit.event.player.PlayerStatisticIncrementEvent statistic) {
                events.add("stat:" + statistic.getStatistic() + ":" + statistic.getPreviousValue() + ":" + statistic.getNewValue()
                        + ":" + statistic.getMaterial() + ":" + statistic.getEntityType());
                statistic.setCancelled(cancelStatistics);
            }
            if (event instanceof com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent criterion) {
                events.add("criterion:" + criterion.getAdvancement().getKey() + ":" + criterion.getCriterion());
                criterion.setCancelled(cancelAdvancements);
            }
            if (event instanceof org.bukkit.event.player.PlayerAdvancementDoneEvent advancement) {
                events.add("advancement:" + advancement.getAdvancement().getKey());
                if (replaceAdvancementMessage) advancement.message(net.kyori.adventure.text.Component.text("private advancement"));
            }
        }
        @Override public void gameEvent(Object event, Object position, Object context) {
            // Explicit empty-listener fixture: only the detached event journal is changed.
            causal.add(event.toString() + ":" + position);
        }
        @Override public void output(PaperRollbackCombatAccess.Output output) { outputs.add(output); }
        @Override public Snapshot captureRollbackState() { return new Snapshot(time, cancel, cancelEquipment, multiplier, Map.copyOf(rules), cancelResurrection, allowResurrection, cancelEffects, cancelStatistics, cancelAdvancements, replaceAdvancementMessage, pvp, cramming, worldPolicy); }
        @Override public void restoreRollbackState(Snapshot snapshot) {
            time = snapshot.time; cancel = snapshot.cancel; cancelEquipment = snapshot.cancelEquipment; multiplier = snapshot.multiplier;
            rules.clear(); rules.putAll(snapshot.rules);
            cancelResurrection = snapshot.cancelResurrection; allowResurrection = snapshot.allowResurrection; cancelEffects = snapshot.cancelEffects;
            cancelStatistics = snapshot.cancelStatistics;
            cancelAdvancements = snapshot.cancelAdvancements; replaceAdvancementMessage = snapshot.replaceAdvancementMessage;
            pvp = snapshot.pvp; cramming = snapshot.cramming;
            worldPolicy = snapshot.worldPolicy;
        }
        @Override public List<?> rollbackReferences() { return List.of(random, soundRandom, outputs, events, causal); }
    }
}
