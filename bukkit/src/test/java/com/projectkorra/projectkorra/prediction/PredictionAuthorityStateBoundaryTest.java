package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards server-only player restrictions that common client code cannot infer. */
class PredictionAuthorityStateBoundaryTest {
    @Test
    void chiBlockIsPartOfEveryAuthoritativePlayerStatePath() throws IOException {
        String protocol = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionProtocol.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionProtocol.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String payloads = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java");
        String nativeServer = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(protocol.contains("static final int VERSION = 41"));
        assertTrue(payloads.contains("public static final int PROTOCOL_VERSION = 41"));
        assertTrue(protocol.contains("double airBlastDecay, boolean chiBlocked")
                        && protocol.contains("out.f64(airBlastDecay).bool(chiBlocked)"));
        assertTrue(payloads.contains("double airBlastDecay, boolean chiBlocked")
                        && payloads.contains("buf.writeDouble(airBlastDecay); buf.writeBoolean(chiBlocked)"));
        assertTrue(paper.contains("boolean chiBlocked = bending != null && bending.isChiBlocked()")
                        && paper.contains("Boolean.hashCode(chiBlocked)"));
        assertTrue(nativeServer.contains("boolean chiBlocked = bending != null && bending.isChiBlocked()")
                        && nativeServer.contains("Boolean.hashCode(chiBlocked)"));
        assertTrue(client.contains("chiBlocked = snapshot.chiBlocked()")
                        && client.contains("chiBlocked = state.chiBlocked()")
                        && client.contains("permissions, airBlastDecay, chiBlocked, regionProtection)"));
        assertTrue(runtime.contains("if (chiBlocked) bendingPlayer.blockChi();")
                        && runtime.contains("else bendingPlayer.unblockChi();"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
