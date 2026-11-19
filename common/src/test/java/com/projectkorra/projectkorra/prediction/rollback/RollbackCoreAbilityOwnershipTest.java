package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackCoreAbilityOwnershipTest {
    @Test void importedAbilityClockRewindsWhileAsyncReadersSeeTheLiveClock() throws Exception {
        var clock = CoreAbility.class.getDeclaredField("currentTick");
        clock.setAccessible(true);
        long previous = clock.getLong(null);
        clock.setLong(null, 43);
        try {
            var platform = (ProjectKorraPlatform)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { ProjectKorraPlatform.class }, (proxy, method, args) -> { throw new AssertionError(method); });
            var state = new RollbackPrivateRegistries();
            var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 2048),
                    List.of(), List.of(state), platform, () -> { });
            var saved = domain.call(domain::capture);
            domain.call(() -> {
                assertEquals(0, CoreAbility.getCurrentTick());
                CoreAbility.progressAll();
                assertEquals(1, CoreAbility.getCurrentTick());
                assertEquals(43L, CompletableFuture.supplyAsync(CoreAbility::getCurrentTick).join());
                return null;
            });
            assertEquals(43, CoreAbility.getCurrentTick());
            domain.call(() -> { domain.restore(saved); assertEquals(0, CoreAbility.getCurrentTick()); return null; });
        } finally { clock.setLong(null, previous); }
    }
}
