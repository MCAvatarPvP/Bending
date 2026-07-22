package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards permission-gated constructor branches such as WaterSpoutWave. */
class PredictionPermissionParityBoundaryTest {
    @Test
    void paperPermissionDecisionsReachTheClientBeforeAbilityConstruction() throws IOException {
        String protocol = read("src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        String payloads = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        String wrapper = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");

        assertTrue(protocol.contains("static final int VERSION = 47"));
        assertTrue(payloads.contains("public static final int PROTOCOL_VERSION = 47"));
        assertTrue(protocol.contains("List<String> permissions, double airBlastDecay")
                        && protocol.contains("writeStrings(out, permissions)"));
        assertTrue(payloads.contains("List<String> permissions, double airBlastDecay")
                        && payloads.contains("readStrings(buf, 2_048), buf.readDouble()")
                        && payloads.contains("writeStrings(buf, permissions)"));

        String decisions = between(paper, "private static List<String> predictionPermissions",
                "/**\n     * A client can join");
        assertTrue(decisions.contains("Bukkit.getPluginManager().getPermissions()")
                        && decisions.contains("player.getEffectivePermissions()")
                        && decisions.contains("player.hasPermission(node)")
                        && decisions.contains("expandPermissionCandidates(registered)"),
                "Paper must evaluate registered feature nodes through the player's real permission context");
        assertTrue(decisions.contains("permission.getChildren().keySet()"),
                "plugin.yml child nodes must be synchronized even when Bukkit did not register them as roots");
        assertTrue(decisions.contains("candidates.add(\"bending.ability.WaterSpout.Wave\")"),
                "the Wave child permission must remain discoverable when Bukkit omits plugin.yml children");
        assertTrue(paper.contains("permissions.hashCode()")
                        && paper.contains("permissions, airBlastDecay, chiBlocked, regionProtection, activeFlights")
                        && paper.contains("permissions, airBlastDecay, chiBlocked, regionProtection);"),
                "permission changes must invalidate both live state and initial snapshots");
        assertTrue(client.contains("permissions = snapshot.permissions()")
                        && client.contains("permissions = state.permissions()")
                        && client.contains("elements, subElements, permissions, airBlastDecay, chiBlocked, regionProtection)"));

        int seed = runtime.indexOf("this.grantedPermissions = ClientPredictionConfig.normalizePermissions(permissions);");
        int playerConstruction = runtime.indexOf("this.bendingPlayer = new BendingPlayer(player);");
        assertTrue(seed >= 0 && playerConstruction > seed,
                "constructor-time canBend/permission checks must see Paper's snapshot, not a permissive default");
        assertTrue(runtime.contains("public static boolean hasPermission(String permission)")
                        && runtime.contains("granted.endsWith(\".*\")"));
        String clientPermission = between(wrapper,
                "@Override public boolean hasPermission(String permission)",
                "@Override public boolean isOnline()");
        assertTrue(clientPermission.contains("ExactPredictionRuntime.hasPermission(permission)"));
        assertFalse(clientPermission.contains("return true"),
                "the predicting player may never assume a permission Paper can deny");
    }

    @Test
    void waterSpoutWaveProbeUsesTheSynchronizedDecision() throws IOException {
        String spout = read("../common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpout.java",
                "common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpout.java");
        String wave = read("../common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpoutWave.java",
                "common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpoutWave.java");

        assertTrue(spout.contains("new WaterSpoutWave(player, WaterSpoutWave.AbilityType.CLICK)")
                        && spout.contains("spoutWave.isStarted() && !spoutWave.isRemoved()"),
                "the Wave probe is the branch that decides whether normal WaterSpout starts");
        assertTrue(wave.contains("bPlayer.isOnCooldown(\"WaterSpoutWave\")")
                        && wave.contains("player.hasPermission(\"bending.ability.WaterSpout.Wave\")"),
                "cooldown-dependent WaterSpout behavior must share the same Wave permission decision on both loaders");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from));
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
