package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackNativeEventsNativeTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void nativeKnockbackUsesPrivateBukkitThenPaperEventsAndRewindsItsResult() throws Exception {
        onTickThread(() -> {
            assertNull(Bukkit.getServer());
            var scene = new Scene();
            var eventNames = new ArrayList<String>();
            var router = new PaperRollbackNativeEvents(event -> {
                eventNames.add(event.getClass().getName());
                if (event instanceof org.bukkit.event.entity.EntityKnockbackEvent knockback) {
                    knockback.setFinalKnockback(new org.bukkit.util.Vector(0.2, 0.1, 0));
                }
            }, entity -> entity == scene.player.getBukkitEntity());
            var builder = new RollbackNativeMethods(); router.bind(builder);
            var method = LivingEntity.class.getDeclaredMethod("knockback", double.class, double.class, double.class,
                    Entity.class, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.class);
            var copied = builder.copy(method).build().get(method);
            scene.player.setOnGround(true);
            var saved = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(scene.state, eventNames), List.of());
            invoke(copied, scene.player, 0.4D, -1D, 0D, null, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.DAMAGE);
            var expected = scene.player.getDeltaMovement();
            assertEquals(0.2, expected.x, 1e-9);
            assertEquals(0.1, expected.y, 1e-9);
            assertEquals(List.of("org.bukkit.event.entity.EntityKnockbackEvent", "io.papermc.paper.event.entity.EntityKnockbackEvent"), eventNames);
            saved.restore();
            assertTrue(eventNames.isEmpty());
            invoke(copied, scene.player, 0.4D, -1D, 0D, null, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.DAMAGE);
            assertEquals(expected, scene.player.getDeltaMovement());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void cancelledOrForeignKnockbackCannotPublishAChangedVelocity() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var foreign = new Scene();
            var events = new ArrayList<Event>();
            var router = new PaperRollbackNativeEvents(event -> {
                events.add(event);
                ((org.bukkit.event.Cancellable) event).setCancelled(true);
            }, entity -> entity == scene.player.getBukkitEntity());
            var builder = new RollbackNativeMethods(); router.bind(builder);
            var method = LivingEntity.class.getDeclaredMethod("knockback", double.class, double.class, double.class,
                    Entity.class, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.class);
            var copied = builder.copy(method).build().get(method);
            var initial = scene.player.getDeltaMovement();
            invoke(copied, scene.player, 0.4D, -1D, 0D, null, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.DAMAGE);
            assertEquals(initial, scene.player.getDeltaMovement());
            assertEquals(2, events.size());
            events.clear();
            assertThrows(IllegalArgumentException.class, () -> invoke(copied, scene.player, 0.4D, -1D, 0D, foreign.player,
                    io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK));
            assertTrue(events.isEmpty());
            assertEquals(initial, scene.player.getDeltaMovement());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void copiedExhaustionFactoryUsesItsPrivateHandlerAndPropagatesFailure() throws Exception {
        onTickThread(() -> {
            var scene = new Scene();
            var received = new ArrayList<Event>();
            var router = new PaperRollbackNativeEvents(event -> {
                received.add(event);
                ((EntityExhaustionEvent) event).setExhaustion(0.25F);
            }, entity -> entity == scene.player.getBukkitEntity());
            var builder = new RollbackNativeMethods(); router.bind(builder);
            var method = CraftEventFactory.class.getDeclaredMethod("callPlayerExhaustionEvent", Player.class,
                    EntityExhaustionEvent.ExhaustionReason.class, float.class);
            var copied = builder.build().get(method);
            var event = (EntityExhaustionEvent) invoke(copied, scene.player, EntityExhaustionEvent.ExhaustionReason.DAMAGED, 1F);
            assertSame(event, received.getFirst());
            assertEquals(0.25F, event.getExhaustion());
            assertNull(Bukkit.getServer());
            var failing = new RollbackNativeMethods();
            new PaperRollbackNativeEvents(value -> { throw new ExpectedFailure(); }, entity -> true).bind(failing);
            var failed = failing.build().get(method);
            assertThrows(ExpectedFailure.class, () -> invoke(failed, scene.player, EntityExhaustionEvent.ExhaustionReason.DAMAGED, 1F));
            return null;
        });
    }

    @Test void itemInteractionFactoriesPreserveHandsAndPrivateCancellationWithoutLiveServer() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var foreign = new Scene();
            var received = new ArrayList<Event>();
            var router = new PaperRollbackNativeEvents(event -> {
                received.add(event);
                var interaction = (org.bukkit.event.player.PlayerInteractEvent) event;
                interaction.setUseItemInHand(Event.Result.DENY);
            }, entity -> entity == scene.player.getBukkitEntity());
            var builder = new RollbackNativeMethods(); router.bind(builder);
            var method = CraftEventFactory.class.getDeclaredMethod("callPlayerInteractEvent", Player.class,
                    org.bukkit.event.block.Action.class, net.minecraft.world.item.ItemStack.class,
                    net.minecraft.world.InteractionHand.class);
            var copied = builder.build().get(method);
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE, 3);
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                var event = (org.bukkit.event.player.PlayerInteractEvent) invoke(copied, scene.player,
                        org.bukkit.event.block.Action.RIGHT_CLICK_AIR, item, hand);
                assertSame(event, received.getLast());
                assertEquals(hand == net.minecraft.world.InteractionHand.MAIN_HAND
                        ? org.bukkit.inventory.EquipmentSlot.HAND : org.bukkit.inventory.EquipmentSlot.OFF_HAND, event.getHand());
                assertEquals(Event.Result.DENY, event.useItemInHand());
                assertNull(event.getClickedBlock()); assertEquals(3, event.getItem().getAmount());
                assertEquals(3, item.getCount());
            }
            received.clear();
            assertThrows(IllegalArgumentException.class, () -> invoke(copied, foreign.player,
                    org.bukkit.event.block.Action.RIGHT_CLICK_AIR, item, net.minecraft.world.InteractionHand.MAIN_HAND));
            assertTrue(received.isEmpty()); assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void blockInteractionFactoryRetainsTargetAndSeparateInitialCancellation() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var received = new ArrayList<Event>();
            var builder = new RollbackNativeMethods();
            new PaperRollbackNativeEvents(received::add, entity -> entity == scene.player.getBukkitEntity()).bind(builder);
            var method = CraftEventFactory.class.getDeclaredMethod("callPlayerInteractEvent", Player.class,
                    org.bukkit.event.block.Action.class, net.minecraft.core.BlockPos.class, net.minecraft.core.Direction.class,
                    net.minecraft.world.item.ItemStack.class, boolean.class, boolean.class,
                    net.minecraft.world.InteractionHand.class, net.minecraft.world.phys.Vec3.class);
            var copied = builder.build().get(method);
            var position = new net.minecraft.core.BlockPos(4, 7, -2);
            for (boolean denyBlock : new boolean[]{false, true}) for (boolean denyItem : new boolean[]{false, true}) {
                var event = (org.bukkit.event.player.PlayerInteractEvent) invoke(copied, scene.player,
                        org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, position, net.minecraft.core.Direction.UP,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE), denyBlock, denyItem,
                        net.minecraft.world.InteractionHand.OFF_HAND, new net.minecraft.world.phys.Vec3(4.25, 8, -1.5));
                assertSame(event, received.getLast());
                assertEquals(denyBlock ? Event.Result.DENY : Event.Result.ALLOW, event.useInteractedBlock());
                assertEquals(denyItem ? Event.Result.DENY : Event.Result.DEFAULT, event.useItemInHand());
                assertEquals(org.bukkit.inventory.EquipmentSlot.OFF_HAND, event.getHand());
                assertEquals(4, event.getClickedBlock().getX()); assertEquals(7, event.getClickedBlock().getY());
                assertEquals(-2, event.getClickedBlock().getZ());
                assertEquals(org.bukkit.block.BlockFace.UP, event.getBlockFace());
                assertEquals(new org.bukkit.util.Vector(.25, 1, .5), event.getClickedPosition());
            }
            assertNull(Bukkit.getServer()); return null;
        });
    }

    private static Object invoke(MethodHandle handle, Object... arguments) {
        try { return handle.invokeWithArguments(arguments); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }
    private static class ExpectedFailure extends RuntimeException { }
    private static final class Scene {
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), 4);
        final Player player = new PaperRollbackWorldAccessNativeTest.NativePlayer((Level) world.world());
        final PaperRollbackNativePlayerState state = new PaperRollbackNativePlayerState(player, world, 21, 50_000);
    }
}
