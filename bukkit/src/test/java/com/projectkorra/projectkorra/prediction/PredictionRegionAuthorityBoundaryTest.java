package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionRegionAuthorityBoundaryTest {
    @Test
    void spatialProtectionUsesAuthoritativeServerBoxesOnTheClient() throws IOException {
        String protocol = read("src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        String worldGuard = read("src/main/java/com/projectkorra/projectkorra/region/WorldGuard.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/region/WorldGuard.java");
        String payloads = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(protocol.contains("writeRegionProtection(out, regionProtection)"));
        assertTrue(payloads.contains("RegionProtectionAuthority.Snapshot regionProtection")
                && payloads.contains("readRegionProtection(buf)")
                && payloads.contains("writeRegionProtection(buf, regionProtection)"));
        assertTrue(paper.contains("regionProtectionSnapshot(player, bending, binds, session)"));
        assertTrue(worldGuard.contains("ProtectedCuboidRegion")
                && worldGuard.contains("isRegionProtected(player, sample, ability)"));
        assertTrue(client.contains("regionProtection = snapshot.regionProtection()")
                && client.contains("regionProtection = state.regionProtection()"));
        assertTrue(runtime.contains("RegionProtectionAuthority.install(this.bendingPlayer.getPlayer(), regionProtection)"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
