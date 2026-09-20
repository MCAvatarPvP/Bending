package com.projectkorra.projectkorra.fabric.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Native mixin wiring is not exercised by the standalone common ability tests. */
class ProjectileImpactBoundaryTest {
    @Test void nativeClientCollisionsReachTheOwnedPredictionDispatcher() throws Exception {
        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/projectkorra.mixins.json")))
                .getAsJsonObject();
        assertTrue(config.getAsJsonArray("client").asList().stream()
                .anyMatch(entry -> entry.getAsString().equals("client.ProjectileEntityPredictionMixin")));
        String mixin = source("fabric/mixin/client/ProjectileEntityPredictionMixin.java");
        assertTrue(mixin.contains("@Inject(method = \"onCollision\""));
        assertTrue(mixin.contains("ExactPredictionRuntime.projectileHit"));
        assertTrue(mixin.contains("ci.cancel()"));
    }

    @Test void ordinaryProjectilesAndServerWorldsCannotEnterClientAbilityEvents() throws Exception {
        String runtime = source("fabric/client/prediction/impl/ExactPredictionEntities.java");
        assertTrue(runtime.contains("!this.ready || projectile == null"));
        assertTrue(runtime.contains("instanceof ClientWorld world"));
        assertTrue(runtime.contains("this.entityReconciliation.spawnAction(projectile)"));
        assertTrue(runtime.contains("action <= 0L || !this.actions.containsKey(action)"));
        assertTrue(runtime.contains("ExactPredictionRuntime.runWithAction("));
        assertTrue(runtime.contains("this.abilityActions.getOrDefault(cable, action)"),
                "sneaking while the cable flies must preserve the current action used by Paper");
        assertTrue(runtime.contains("impactAction, () -> this.platform.events().call(event)"));
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/com/projectkorra/projectkorra", path));
    }
}
