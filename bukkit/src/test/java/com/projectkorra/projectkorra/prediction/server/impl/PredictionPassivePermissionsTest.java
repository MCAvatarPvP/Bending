package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PredictionPassivePermissionsTest {
    @Test
    void fullSnapshotKeepsFastSwimAndItsPassivePermission() throws Exception {
        final PaperPredictionServer server = serverWithFullAbilityPermissions();

        final List<String> permissions = server.predictionPermissions(player(Set.of()), null);

        assertEquals(PaperPredictionState.MAX_PREDICTION_PERMISSIONS, permissions.size());
        assertTrue(permissions.contains("bending.ability.fastswim"));
        assertTrue(permissions.contains("bending.water.passive"),
                "dropping the passive parent prevents local FastSwim even though Bukkit permits it");
    }

    @Test
    void explicitlyDeniedPassivePermissionStaysDenied() throws Exception {
        final PaperPredictionServer server = serverWithFullAbilityPermissions();

        final List<String> permissions = server.predictionPermissions(player(Set.of("bending.water.passive")), null);

        assertTrue(permissions.contains("bending.ability.fastswim"));
        assertFalse(permissions.contains("bending.water.passive"));
        assertFalse(permissions.contains("bending.*"));
    }

    private static PaperPredictionServer serverWithFullAbilityPermissions() throws Exception {
        final var constructor = PaperPredictionServer.class.getDeclaredConstructor(JavaPlugin.class);
        constructor.setAccessible(true);
        final PaperPredictionServer server = constructor.newInstance((JavaPlugin) null);
        final List<String> candidates = new ArrayList<>();
        candidates.add("bending.ability.FastSwim");
        for (int i = 0; i < PaperPredictionState.MAX_PREDICTION_PERMISSIONS; i++) {
            candidates.add("bending.ability.Test" + i);
        }
        candidates.add("bending.water.passive");
        candidates.add("bending.*");
        server.permissionCandidates = candidates;
        return server;
    }

    private static Player player(Set<String> denied) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEffectivePermissions" -> Set.of();
                    case "isOp" -> false;
                    case "hasPermission" -> !denied.contains(args[0]);
                    default -> throw new AssertionError(method);
                });
    }
}
