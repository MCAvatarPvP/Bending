package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RollbackHistoricalHitBoundaryTest {
    @Test void historicalQueryAdapterRejectsReplayBeforeLookingUpAnyLiveTarget() {
        var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> { throw new AssertionError(method); });
        var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 100), List.of(), List.of(), platform, () -> { });
        domain.call(() -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> PaperPredictionServer.augmentNearbyPlayers(null, null, null, null, null));
            assertTrue(failure.getMessage().contains("Historical hit claims"));
            return null;
        });
        assertDoesNotThrow(() -> PaperPredictionServer.augmentNearbyPlayers(null, null, null, null, null));
    }
}
