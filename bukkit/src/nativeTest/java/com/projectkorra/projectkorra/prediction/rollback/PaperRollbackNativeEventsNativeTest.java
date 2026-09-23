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
