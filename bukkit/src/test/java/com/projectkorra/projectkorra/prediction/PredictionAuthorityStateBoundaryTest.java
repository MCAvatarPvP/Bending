package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards server-only player restrictions that common client code cannot infer. */
class PredictionAuthorityStateBoundaryTest {
    @Test
    void chiBlockIsPartOfEveryAuthoritativePlayerStatePath() throws IOException {
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

        assertTrue(protocol.contains("static final int VERSION = 47"));
        assertTrue(payloads.contains("public static final int PROTOCOL_VERSION = 47"));
        assertTrue(protocol.contains("double airBlastDecay, boolean chiBlocked")
                        && protocol.contains("out.f64(airBlastDecay).bool(chiBlocked)"));
        assertTrue(payloads.contains("double airBlastDecay, boolean chiBlocked")
                        && payloads.contains("buf.writeDouble(airBlastDecay); buf.writeBoolean(chiBlocked)"));
        assertTrue(paper.contains("boolean chiBlocked = bending != null && bending.isChiBlocked()")
                        && paper.contains("Boolean.hashCode(chiBlocked)"));
        assertTrue(client.contains("chiBlocked = snapshot.chiBlocked()")
                        && client.contains("chiBlocked = state.chiBlocked()")
                        && client.contains("permissions, airBlastDecay, chiBlocked, regionProtection)"));
        assertTrue(runtime.contains("if (chiBlocked) {")
                        && runtime.contains("this.bendingPlayer.blockChi();")
                        && runtime.contains("this.bendingPlayer.unblockChi();"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
