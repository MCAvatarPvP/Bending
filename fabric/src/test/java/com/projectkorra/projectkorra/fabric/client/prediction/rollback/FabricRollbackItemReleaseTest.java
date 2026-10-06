package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackItemReleaseTest {
    private static AutoCloseable clock() throws Exception {
        var method = RollbackClock.class.getDeclaredMethod("at", long.class, long.class, long.class, long.class);
        method.setAccessible(true); return (AutoCloseable) method.invoke(null, 1000L, 2000L, 4L, 50_000_000L);
    }
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    @Test void eventChangesAreVisibleToTheFollowingItemCallback() throws Exception {
        var roster = FabricRollbackPlayerRendererTest.roster();
        var state = roster.players().values().iterator().next(); var player = state.ownedPlayer();
        var calls = new ArrayList<String>();
        var release = new FabricRollbackItemRelease(roster.players().values(), new FabricRollbackItemRelease.Items<Void>() {
            @Override public void stopped(LivingEntity owner, ItemStack stack, int usedTicks) {
                assertFalse(stack.isEmpty()); owner.clearActiveItem(); calls.add("event");
            }
            @Override public void release(ItemStack stack, World world, LivingEntity owner, int remaining) {
                assertTrue(stack.isEmpty()); assertEquals(0, remaining); calls.add("item");
            }
            @Override public void update(LivingEntity owner) { fail("Cleared item has no release update"); }
            @Override public Void captureRollbackState() { return null; }
            @Override public void restoreRollbackState(Void ignored) { }
            @Override public Collection<?> rollbackReferences() { return List.of(calls); }
        });
        player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.SHIELD));
        state.use(value -> { value.setCurrentHand(Hand.MAIN_HAND); return null; });
        try (var clock = clock()) { release.release(state); }
        assertEquals(List.of("event", "item"), calls); assertFalse(player.isUsingItem());
    }
    @Test void nativeReleasePreservesPaperEventOrderAndRewindsWithoutLiveMutation() throws Exception {
        var roster = FabricRollbackPlayerRendererTest.roster();
        var state = roster.players().values().iterator().next(); var player = state.ownedPlayer();
        var foreign = FabricRollbackPlayerRendererTest.roster().players().values().iterator().next();
        var calls = new ArrayList<String>();
        var release = new FabricRollbackItemRelease(roster.players().values(), new FabricRollbackItemRelease.Items<Void>() {
            @Override public void stopped(LivingEntity owner, ItemStack stack, int usedTicks) {
                assertSame(player, owner); assertSame(player.getActiveItem(), stack);
                assertTrue(player.isUsingItem()); assertEquals(0, usedTicks); calls.add("event");
            }
            @Override public void release(ItemStack stack, World world, LivingEntity owner, int remaining) {
                assertSame(player, owner); assertSame(player.getEntityWorld(), world);
                assertSame(player.getActiveItem(), stack); assertTrue(player.isUsingItem()); calls.add("item");
            }
            @Override public void update(LivingEntity owner) { fail("Shield has no release update"); }
            @Override public Void captureRollbackState() { return null; }
            @Override public void restoreRollbackState(Void ignored) { }
            @Override public Collection<?> rollbackReferences() { return List.of(calls); }
        });
        player.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
        state.use(value -> { value.setCurrentHand(Hand.OFF_HAND); return null; });
        var saved = new RollbackStateGraph(value -> false, field -> true, 500_000).capture(List.of(release), List.of());
        assertThrows(IllegalStateException.class, () -> release.release(state));
        for (int replay = 0; replay < 2; replay++) {
            try (var clock = clock()) {
                assertThrows(IllegalArgumentException.class, () -> release.release(foreign));
                assertTrue(calls.isEmpty()); release.release(state);
                assertEquals(List.of("event", "item"), calls);
                assertFalse(player.isUsingItem()); assertTrue(player.getActiveItem().isEmpty());
                release.release(state); assertEquals(2, calls.size());
            }
            saved.restore(); assertTrue(player.isUsingItem()); assertTrue(calls.isEmpty());
        }
    }
}
