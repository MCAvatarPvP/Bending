package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPrivateGameplayRegistriesTest {
    @Test void importedComboMaterialAndActivationStateIsInvisibleToOtherThreadsAndRewinds() throws Exception {
        try (var world = new AbilityWorld()) {
            var liveAuthors = ComboManager.getAuthors();
            var materialField = ElementalAbility.class.getDeclaredField("EARTH_BLOCKS");
            materialField.setAccessible(true);
            @SuppressWarnings("unchecked") var earth = (Set<String>) materialField.get(null);
            String key = "test:imported-registry";
            var old = liveAuthors.put(key, "outside");
            boolean added = earth.add(key);
            AbilityActivationManager.beginTracking();
            try {
                var registries = new RollbackPrivateRegistries();
                var graph = new RollbackStateGraph(value -> java.lang.reflect.Proxy.isProxyClass(value.getClass()), field -> true, 100_000);
                var domain = RollbackDomain.create(graph, List.of(), List.of(registries), Platform.current(), () -> {
                    assertNotSame(liveAuthors, ComboManager.getAuthors());
                    assertFalse(ElementalAbility.getEarthbendableBlocks().contains(key));
                    ComboManager.getAuthors().put(key, "inside");
                    // Marking the private empty stack must not mark the live caller's pending frame.
                    AbilityActivationManager.markHandled();
                });
                var initial = domain.call(domain::capture);
                domain.call(() -> {
                    assertEquals("inside", ComboManager.getAuthors().put(key, "predicted"));
                    assertEquals("outside", CompletableFuture.supplyAsync(() -> ComboManager.getAuthors().get(key)).join());
                    assertTrue(CompletableFuture.supplyAsync(() -> ElementalAbility.getEarthbendableBlocks().contains(key)).join());
                    AbilityActivationManager.beginTracking();
                    AbilityActivationManager.markHandled();
                    assertTrue(AbilityActivationManager.finishTracking());
                    return null;
                });
                domain.call(() -> { domain.restore(initial); return null; });
                assertEquals("inside", domain.call(() -> ComboManager.getAuthors().get(key)));
                assertEquals("outside", liveAuthors.get(key));
                assertTrue(earth.contains(key));
            } finally {
                boolean handled = AbilityActivationManager.finishTracking();
                if (old == null) liveAuthors.remove(key); else liveAuthors.put(key, old);
                if (added) earth.remove(key);
                assertFalse(handled);
            }
        }
    }
}
