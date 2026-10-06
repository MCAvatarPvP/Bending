package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.block.*;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PredictionServicesTest {
    @Test void allPredictionListenersDispatchToPrivateServicesAndRestoreTheLiveListeners() throws Exception {
        Class<?>[] hooks = {AbilityRemovalSync.class, AbilityCheckpointSync.class, CooldownSync.class,
                PlayerStatusSync.class, AbilityStateSync.class, GlidingStateSync.class, VelocitySync.class,
                DirectBlockSync.class, TempBlockSync.class, TempFallingBlockSync.class, PredictedContactSync.class};
        Map<Field, Object> originals = new LinkedHashMap<>();
        List<String> live = new ArrayList<>(), privateCalls = new ArrayList<>();
        var builder = PredictionServices.builder();
        try {
            for (Class<?> hook : hooks) {
                Field field = hook.getDeclaredField("listener"); field.setAccessible(true);
                originals.put(field, field.get(null));
                field.set(null, listener(field.getType(), live));
                bind(builder, field.getType(), listener(field.getType(), privateCalls));
            }
            exerciseHooks();
            List<String> baseline = List.copyOf(live); live.clear();
            try (var scope = PredictionServices.using(builder.build())) {
                exerciseHooks();
                assertEquals(baseline, privateCalls);
                assertTrue(live.isEmpty());
                // A private call cannot replace the installed global listener.
                assertThrows(IllegalStateException.class, () -> VelocitySync.install(null));
                assertThrows(IllegalStateException.class, () -> CooldownSync.clear(null));
            }
            exerciseHooks();
            assertEquals(baseline, live);
            for (Class<?> hook : hooks) {
                assertTrue(baseline.stream().anyMatch(call -> call.startsWith(hook.getSimpleName() + ":")), hook.getName());
            }
        } finally { for (var entry : originals.entrySet()) entry.getKey().set(null, entry.getValue()); }
    }

    @Test void missingBindingsNeverFallBackAndExplicitDisableOnlyAppliesInsideItsScope() {
        Runnable live = () -> { };
        var builder = PredictionServices.builder();
        var missing = builder.build();
        builder.disable(Runnable.class);
        try (var outer = PredictionServices.using(missing)) {
            assertThrows(IllegalStateException.class, () -> PredictionServices.current(Runnable.class, live));
            try (var inner = PredictionServices.using(builder.build())) {
                assertNull(PredictionServices.current(Runnable.class, live));
                assertThrows(IllegalStateException.class, outer::close);
            }
            assertThrows(IllegalStateException.class, () -> PredictionServices.current(Runnable.class, live));
        }
        assertSame(live, PredictionServices.current(Runnable.class, live));
    }

    @Test void scopesAreThreadLocalAndCanOnlyBeClosedByTheirOwner() {
        Runnable live = () -> { };
        try (var scope = PredictionServices.using(PredictionServices.builder().disable(Runnable.class).build())) {
            assertSame(live, CompletableFuture.supplyAsync(() -> PredictionServices.current(Runnable.class, live)).join());
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(scope::close).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertNull(PredictionServices.current(Runnable.class, live));
        }
        assertFalse(PredictionServices.active());
    }

    @Test void privateFallingBlockPublicationFailureIsNotSwallowed() {
        RuntimeException failure = new IllegalStateException("output encoding failed");
        var listener = new TempFallingBlockSync.Listener() {
            @Override public int beforeSpawn(CoreAbility ability, Location location, BlockData data) { throw failure; }
            @Override public void onSpawn(CoreAbility ability, FallingBlock block, int ordinal) { throw failure; }
        };
        try (var scope = PredictionServices.using(PredictionServices.builder().bind(TempFallingBlockSync.Listener.class, listener).build())) {
            var ability = new TestAbility();
            assertSame(failure, assertThrows(RuntimeException.class, () -> TempFallingBlockSync.prepare(ability, new Location(), new BlockData(Material.STONE))));
            assertSame(failure, assertThrows(RuntimeException.class, () -> TempFallingBlockSync.publish(ability, new FallingBlock(), 1)));
        }
    }

    @Test void regionAuthorityReadsPrivateSnapshotAndRestoresTheLiveDecision() throws Exception {
        Field snapshot = RegionProtectionAuthority.class.getDeclaredField("snapshot"); snapshot.setAccessible(true);
        Field hookField = RegionProtectionAuthority.class.getDeclaredField("AUTHORITY_HOOK"); hookField.setAccessible(true);
        var hook = (RegionProtectionHook) hookField.get(null);
        Object before = snapshot.get(null);
        var player = new Player();
        var location = new Location(new World() { @Override public String getName() { return "arena"; } }, 0, 0, 0);
        var protectedState = new RegionProtectionAuthority.Snapshot("arena", List.of(),
                List.of(new RegionProtectionAuthority.Box(1, 0, 0, 0, 0, 0, 0)));
        try {
            snapshot.set(null, protectedState);
            assertTrue(hook.isRegionProtected(player, location, null));
            try (var scope = PredictionServices.using(PredictionServices.builder()
                    .bind(RegionProtectionAuthority.Snapshot.class, RegionProtectionAuthority.Snapshot.empty()).build())) {
                assertFalse(hook.isRegionProtected(player, location, null));
                assertThrows(IllegalStateException.class, () -> RegionProtectionAuthority.clear(player));
            }
            assertTrue(hook.isRegionProtected(player, location, null));
            try (var scope = PredictionServices.using(PredictionServices.empty())) {
                assertThrows(IllegalStateException.class, () -> hook.isRegionProtected(player, location, null));
            }
        } finally { snapshot.set(null, before); }
    }

    private static void exerciseHooks() {
        var ability = new TestAbility();
        var player = ability.getPlayer();
        var bender = new BendingPlayer(player);
        AbilityRemovalSync.publish(ability);
        AbilityRemovalSync.ownerTransferred(ability, new UUID(0, 1), new UUID(0, 2));
        AbilityCheckpointSync.publish(ability);
        CooldownSync.added(bender, "dynamic", 500);
        CooldownSync.removed(bender, "dynamic");
        CooldownSync.airBlastReset(bender);
        CooldownSync.airBlastRegenerated(bender);
        PlayerStatusSync.chiBlockedChanged(bender, true);
        AbilityStateSync.apply(ability, player, new AbilityStateSync.FlightState(true, true, 0.1F), () -> { });
        GlidingStateSync.apply(ability, player, true, () -> { });
        VelocitySync.publish(ability, player, new Vector(1, 0, 0));
        DirectBlockSync.beforeWorldChange(new Block(), new BlockData(Material.STONE));
        TempBlockSync.hasAuthoritativeLayer(new Block());
        TempFallingBlockSync.prepare(ability, new Location(), new BlockData(Material.STONE));
        TempFallingBlockSync.publish(ability, new FallingBlock(), 1);
        assertTrue(PredictedContactSync.mark(ability, new Player() { @Override public UUID getUniqueId() { return new UUID(0, 2); } }));
    }

    private static Object listener(Class<?> type, List<String> calls) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            calls.add(type.getEnclosingClass().getSimpleName() + ":" + method.getName());
            if (method.getReturnType() == boolean.class) return false; // Ordinary client suppresses remote mutations.
            if (method.getReturnType() == int.class) return 1;
            return null;
        });
    }
    private static <T> void bind(PredictionServices.Builder builder, Class<T> type, Object value) { builder.bind(type, type.cast(value)); }

    private static final class TestAbility extends CoreAbility {
        TestAbility() { super(null); player = new Player(); }
        @Override public boolean isStarted() { return true; }
        @Override public boolean isRemoved() { return false; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "TestAbility"; }
        @Override public Element getElement() { return Element.FIRE; }
        @Override public Location getLocation() { return new Location(); }
    }
}
