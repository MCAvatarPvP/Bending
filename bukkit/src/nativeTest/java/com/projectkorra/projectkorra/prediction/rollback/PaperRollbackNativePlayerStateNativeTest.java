package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntity;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityRegistry;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackNativePlayerStateNativeTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static final RollbackEntityBody.Rules MOTION_RULES = new RollbackEntityBody.Rules() {
        @Override public RollbackEntityBody.Pose teleport(RollbackEntityBody body, RollbackEntityBody.Pose destination) { return destination; }
        @Override public boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger) { return false; }
    };

    private static RollbackEntity logical(Scene scene) {
        return new RollbackEntity(RollbackEntityBody.nativeBacked(scene.state.identity(), scene.queries.logical, scene.state, MOTION_RULES));
    }

    @Test void logicalMovementAndNativePhysicsShareOneStoreAndRewindWithTerrain() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var view = logical(scene);
            var registry = new RollbackEntityRegistry(scene.queries.logical, 4);
            registry.add(view);
            scene.player.fallDistance = 1.23456789012345;
            var initial = view.body().kinematics();
            var saved = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(registry), List.of());
            view.setVelocity(new Vector(0.2, 0.3, 0.4));
            assertEquals(new Vec3(0.2, 0.3, 0.4), scene.player.getDeltaMovement());
            assertEquals(initial.fallDistance(), scene.player.fallDistance, "API velocity writes must preserve native double precision");
            assertTrue(scene.player.hurtMarked);
            var destination = new Location(scene.queries.logical, -0.5, 1.25, 0.5);
            destination.setYaw(90); destination.setPitch(-20);
            assertTrue(view.teleport(destination));
            assertEquals(-0.5, scene.player.getX()); assertEquals(1.25, scene.player.getY());
            assertEquals(90, scene.player.getYRot()); assertEquals(-20, scene.player.getXRot());
            scene.state.use(value -> {
                var player = (Player) value;
                player.travel(new Vec3(0, 0, 1));
                assertEquals(player.getX(), view.getLocation().getX());
                assertEquals(player.getY(), view.getLocation().getY());
                assertEquals(player.getBoundingBox().maxY, view.getBoundingBox().getMaxY());
                // Native callbacks may read/write the logical view without entering
                // another native step or allowing a mid-step checkpoint.
                view.setFallDistance(3);
                assertEquals(3, player.fallDistance);
                assertThrows(IllegalStateException.class, scene.state::captureRollbackState);
                return null;
            });
            assertEquals(List.of(view), List.copyOf(registry.nearby(view.getBoundingBox(), null)));
            scene.queries.block(com.projectkorra.projectkorra.platform.mc.Material.ICE, "minecraft:ice");
            saved.restore();
            assertEquals(initial, view.body().kinematics());
            assertEquals(initial, scene.state.readKinematics());
            assertEquals(com.projectkorra.projectkorra.platform.mc.Material.STONE, scene.queries.logical.getBlockAt(0, 0, 0).getType());
            assertEquals(List.of(view), List.copyOf(registry.nearby(view.getBoundingBox(), null)));
            assertNull(org.bukkit.Bukkit.getServer());
            return null;
        });
    }

    @Test void nativeMovementRejectsForeignIdentityDimensionsAndCrossThreadWrites() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var view = logical(scene);
            var initial = scene.state.readKinematics();
            var foreign = new RollbackEntityBody.Identity(new UUID(0, 999), 999, "foreign", com.projectkorra.projectkorra.platform.mc.entity.EntityType.PLAYER);
            assertThrows(IllegalArgumentException.class, () -> RollbackEntityBody.nativeBacked(foreign, scene.queries.logical, scene.state, MOTION_RULES));
            var invalid = new RollbackEntityBody.Kinematics(initial.pose(), new RollbackEntityBody.Motion(8, 8, 8),
                    initial.bounds(), 100, initial.onGround(), initial.fallDistance(), true);
            assertThrows(IllegalArgumentException.class, () -> view.body().kinematics(invalid));
            assertEquals(initial, scene.state.readKinematics());
            assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> scene.state.writeKinematics(initial)).join());
            scene.player.setId(scene.player.getId() + 1);
            assertThrows(IllegalStateException.class, view::getLocation);
            return null;
        });
    }

    @Test void privateBukkitAttributeViewsRestoreModifiersAndCanBeRetainedByListeners() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var type = new org.bukkit.craftbukkit.attribute.CraftAttribute(Attributes.MAX_HEALTH);
            var view = scene.player.getBukkitEntity().getAttribute(type);
            assertNotNull(view);
            assertEquals(20, view.getValue());
            var key = org.bukkit.NamespacedKey.minecraft("rollback_health");
            var modifier = new org.bukkit.attribute.AttributeModifier(key, 4, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
            scene.state.use(player -> { view.addModifier(modifier); return null; }); // Event callbacks run within native operations.
            assertEquals(24, scene.player.getMaxHealth());
            record Listener(org.bukkit.attribute.AttributeInstance retained) { }
            var listener = new Listener(view);
            var saved = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(listener), List.of());
            view.setBaseValue(30);
            view.removeModifier(key);
            assertEquals(30, view.getValue());
            assertNull(view.getModifier(key));
            saved.restore();
            assertSame(view, listener.retained());
            assertSame(type, view.getAttribute());
            assertEquals(20, view.getBaseValue());
            assertEquals(24, view.getValue());
            assertEquals(4, view.getModifier(key).getAmount());
            var transientKey = org.bukkit.NamespacedKey.minecraft("rollback_transient");
            view.addTransientModifier(new org.bukkit.attribute.AttributeModifier(transientKey, 2, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
            assertEquals(26, view.getValue());
            saved.restore();
            assertEquals(24, view.getValue());
            assertNull(view.getModifier(transientKey));
            assertNull(org.bukkit.Bukkit.getServer());
            return null;
        });
    }

    @Test void retainedAttributeViewsResolveNewInstancesAfterRewindAndRejectOtherThreads() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var before = scene.state.captureRollbackState();
            var view = scene.player.getBukkitEntity().getAttribute(new org.bukkit.craftbukkit.attribute.CraftAttribute(Attributes.LUCK));
            var discarded = scene.player.getAttribute(Attributes.LUCK);
            view.setBaseValue(7);
            scene.state.restoreRollbackState(before);
            assertEquals(0, view.getValue());
            assertNotSame(discarded, scene.player.getAttribute(Attributes.LUCK));
            view.setBaseValue(3);
            assertEquals(3, scene.player.getAttribute(Attributes.LUCK).getValue());
            assertEquals(7, discarded.getValue());
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> view.setBaseValue(9)).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(3, view.getValue());
            var foreign = new org.bukkit.craftbukkit.attribute.CraftAttribute(net.minecraft.core.Holder.direct(Attributes.LUCK.value()));
            assertThrows(IllegalArgumentException.class, () -> scene.player.getBukkitEntity().getAttribute(foreign));
            return null;
        });
    }

    @Test void checkpointRestoresMovementInventoryComponentsAttributesEffectsAndPrivateRandom() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            scene.travel();
            scene.state.captureRollbackState();
            var item = new ItemStack(Items.STONE, 4);
            item.set(DataComponents.CUSTOM_NAME, Component.literal("saved"));
            var sharpness = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
            var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(sharpness, 2);
            item.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
            scene.player.getInventory().setItem(0, item);
            scene.state.captureRollbackState();
            // Seed imported effect state directly. Applying Bukkit potion events is
            // a separate native event adapter and is not claimed by this fixture.
            var effect = new MobEffectInstance(MobEffects.SPEED, 80, 1);
            scene.player.getActiveEffectsMap().put(MobEffects.SPEED, effect);
            double speed = scene.player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
            var saved = scene.state.captureRollbackState();
            double gaussian = scene.player.getRandom().nextGaussian();
            Motion expected = scene.travel();
            item.setCount(2);
            item.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
            item.set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            scene.player.setHealth(7);
            effect.update(new MobEffectInstance(MobEffects.SPEED, 5, 2));
            scene.player.getActiveEffectsMap().clear();
            scene.player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.7);
            scene.state.restoreRollbackState(saved);
            assertSame(item, scene.player.getInventory().getItem(0));
            assertEquals(4, item.getCount());
            assertEquals("saved", item.get(DataComponents.CUSTOM_NAME).getString());
            assertEquals(2, item.get(DataComponents.ENCHANTMENTS).getLevel(sharpness));
            assertEquals(20, scene.player.getHealth());
            assertSame(effect, scene.player.getActiveEffectsMap().get(MobEffects.SPEED));
            assertEquals(80, effect.getDuration()); assertEquals(1, effect.getAmplifier());
            assertEquals(speed, scene.player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue());
            assertNotSame(Entity.SHARED_RANDOM, scene.player.getRandom());
            assertEquals(gaussian, scene.player.getRandom().nextGaussian());
            assertEquals(expected, scene.travel());
            // Repeat after native code has populated lazy caches.
            scene.state.captureRollbackState();
            return null;
        });
    }

    @Test void checkpointsKeepTerrainAndPlayerTogetherWithoutSharingRandomState() throws Exception {
        onTickThread(() -> {
            var first = new Scene(); var second = new Scene();
            assertNotSame(first.player.getRandom(), second.player.getRandom());
            var graph = new RollbackStateGraph(value -> false, field -> true, 100_000);
            var saved = graph.capture(List.of(first.state), List.of());
            Motion stone = first.travel();
            first.queries.block(com.projectkorra.projectkorra.platform.mc.Material.ICE, "minecraft:ice");
            first.travel();
            saved.restore();
            assertEquals(stone, first.travel());
            assertEquals(stone, second.travel());
            return null;
        });
    }

    @Test void nativeOwnershipForeignCheckpointsAndReentrancyAreGuarded() throws Exception {
        onTickThread(() -> {
            var first = new Scene(); var second = new Scene();
            assertThrows(IllegalArgumentException.class, () -> new PaperRollbackNativePlayerState(first.player, second.world, 55, 50_000));
            assertThrows(IllegalArgumentException.class, () -> new PaperRollbackNativePlayerState(first.player, first.world, 55, 50_000));
            var checkpoint = first.state.captureRollbackState();
            assertThrows(IllegalArgumentException.class, () -> second.state.restoreRollbackState(checkpoint));
            first.state.use(player -> {
                assertThrows(IllegalStateException.class, first.state::captureRollbackState);
                assertThrows(IllegalStateException.class, () -> first.state.use(other -> null));
                return null;
            });
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(first.state::captureRollbackState).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertThrows(ExpectedFailure.class, () -> first.state.use(player -> { throw new ExpectedFailure(); }));
            first.state.captureRollbackState();
            return null;
        });
    }

    @Test void crossPlayerCombatReferencesRestoreThroughTheOwningWorldRoster() throws Exception {
        onTickThread(() -> {
            var first = new Scene();
            var other = new PaperRollbackWorldAccessNativeTest.NativePlayer((Level) first.world.world());
            var otherState = new PaperRollbackNativePlayerState(other, first.world, 56, 50_000);
            first.player.setLastHurtByMob(other);
            other.setLastHurtByMob(first.player);
            var graph = new RollbackStateGraph(value -> false, field -> true, 100_000);
            var saved = graph.capture(List.of(first.state), List.of());
            first.player.setLastHurtByMob(null); other.setLastHurtByMob(null);
            first.player.setHealth(7); other.setHealth(8);
            saved.restore();
            assertSame(other, first.player.getLastHurtByMob());
            assertSame(first.player, other.getLastHurtByMob());
            assertEquals(20, first.player.getHealth()); assertEquals(20, other.getHealth());
            otherState.captureRollbackState();
            var latePlayer = new PaperRollbackWorldAccessNativeTest.NativePlayer((Level) first.world.world());
            assertThrows(IllegalStateException.class, () -> new PaperRollbackNativePlayerState(latePlayer, first.world, 57, 50_000));
            assertNull(latePlayer.getBukkitEntityRaw(), "failed enrollment must not partially bind a private wrapper");
            var foreign = new Scene();
            first.player.setLastHurtByMob(foreign.player);
            assertThrows(IllegalStateException.class, first.state::captureRollbackState);
            return null;
        });
    }

    @Test void lateMovementInputReplaysNativeWallContactAndFinalizesMatchingResults() throws Exception {
        onTickThread(() -> {
            var onTimeSimulation = new NativeSimulation(); var lateSimulation = new NativeSimulation();
            var limits = new RollbackEngine.Limits(3, 1, 10, 50_000_000);
            UUID participant = new UUID(0, 7);
            var onTime = new RollbackEngine<>(onTimeSimulation, Map.of(participant, false), limits, 0);
            var late = new RollbackEngine<>(lateSimulation, Map.of(participant, false), limits, 0);
            onTime.submit(participant, 1, true);
            onTime.advance(); late.advance();
            var expected = onTime.advance().head().effects();
            assertNotEquals(expected, late.advance().head().effects());
            late.submit(participant, 1, true);
            var corrected = late.reconcile();
            assertEquals(1, corrected.replayedFrom());
            assertEquals(expected, corrected.head().effects());
            for (int tick = 3; tick <= 6; tick++) {
                var direct = onTime.advance(); var replayed = late.advance();
                assertEquals(direct.head().effects(), replayed.head().effects());
                assertEquals(direct.finalizedEffects(), replayed.finalizedEffects());
            }
            return null;
        });
    }

    private static final class NativeSimulation implements RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, Motion> {
        final Scene scene = new Scene();
        final RollbackEntity view = logical(scene);
        final RollbackStateGraph graph = new RollbackStateGraph(value -> false, field -> true, 100_000);
        NativeSimulation() {
            scene.queries.block(0, 1, 1, com.projectkorra.projectkorra.platform.mc.Material.STONE, "minecraft:stone");
            scene.queries.block(0, 2, 1, com.projectkorra.projectkorra.platform.mc.Material.STONE, "minecraft:stone");
            scene.player.setDeltaMovement(0, 0, 0.8);
        }
        @Override public RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(view), List.of()); }
        @Override public void restore(RollbackStateGraph.Snapshot snapshot) { snapshot.restore(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Motion> effects) {
            if (inputs.values().iterator().next()) {
                var velocity = view.getVelocity();
                view.setVelocity(new Vector(velocity.getX() + 0.025, velocity.getY(), velocity.getZ()));
            }
            scene.state.use(value -> {
                var player = (Player) value;
                player.travel(new Vec3(inputs.values().iterator().next() ? 1 : 0, 0, 1));
                var state = view.body().kinematics(); var p = state.pose(); var v = state.velocity();
                effects.emit(new Motion(new Vec3(p.x(), p.y(), p.z()), new Vec3(v.x(), v.y(), v.z()), state.onGround(), state.fallDistance()));
                return null;
            });
        }
    }

    private static final class ExpectedFailure extends RuntimeException { }
    private record Motion(Vec3 position, Vec3 velocity, boolean onGround, double fallDistance) { }
    private static final class Scene {
        final PaperRollbackWorldAccessNativeTest.Queries queries = new PaperRollbackWorldAccessNativeTest.Queries();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(queries, 4);
        final Player player = new PaperRollbackWorldAccessNativeTest.NativePlayer((Level) world.world());
        final PaperRollbackNativePlayerState state = new PaperRollbackNativePlayerState(player, world, 55, 50_000);
        Scene() { player.setPos(0.5, 1, 0.5); player.setYRot(0); player.setXRot(0); player.setOnGround(true); }
        Motion travel() {
            return state.use(value -> {
                var nativePlayer = (Player) value;
                nativePlayer.travel(new Vec3(0, 0, 1));
                return new Motion(nativePlayer.position(), nativePlayer.getDeltaMovement(), nativePlayer.onGround(), nativePlayer.fallDistance);
            });
        }
    }
}
