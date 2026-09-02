package com.projectkorra.projectkorra.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a prediction entrypoint together with the implementation sources that
 * now own its behavior. Source-boundary tests can remain focused on behavior
 * instead of depending on a monolithic file layout.
 */
public final class PredictionSourceBundle {
    private static final List<String> PAPER_IMPLEMENTATION = List.of(
            "PaperPredictionUtilities.java",
            "PaperPredictionTransport.java",
            "PaperPredictionTempBlocks.java",
            "PaperPredictionEffects.java",
            "PaperPredictionInput.java",
            "PaperPredictionDelivery.java",
            "PaperPredictionSnapshots.java",
            "PaperPredictionState.java"
    );
    private static final List<String> FABRIC_IMPLEMENTATION = List.of(
            "ExactPredictionApiCore.java",
            "ExactPredictionApiBlocks.java",
            "ExactPredictionApiLifecycle.java",
            "ExactPredictionStartup.java",
            "ExactPredictionInput.java",
            "ExactPredictionTick.java",
            "ExactPredictionReconciliation.java",
            "ExactPredictionLifecycle.java",
            "ExactPredictionRemoval.java",
            "ExactPredictionTransfer.java",
            "ExactPredictionPlayerState.java",
            "ExactPredictionEntities.java",
            "ExactPredictionState.java"
    );
    private static final List<String> CLIENT_IMPLEMENTATION = List.of(
            "PredictionClientApi.java",
            "PredictionClientNativeInput.java",
            "PredictionClientServerState.java",
            "PredictionClientWorldState.java",
            "PredictionClientTick.java",
            "PredictionClientInput.java",
            "PredictionClientLifecycle.java",
            "PredictionClientState.java"
    );

    private PredictionSourceBundle() {
    }

    public static String read(final Path path) throws IOException {
        final String source = Files.readString(path);
        final String name = path.getFileName().toString();
        if ("PaperPredictionServer.java".equals(name)) {
            return source + readImplementations(path.getParent().resolve("impl"), PAPER_IMPLEMENTATION);
        }
        if ("ExactPredictionRuntime.java".equals(name)) {
            return source + readImplementations(
                    path.getParent().resolve("prediction").resolve("impl"),
                    FABRIC_IMPLEMENTATION);
        }
        if ("PredictionClient.java".equals(name)) {
            return source + readImplementations(
                    path.getParent().resolve("prediction").resolve("impl"),
                    CLIENT_IMPLEMENTATION);
        }
        return source;
    }

    private static String readImplementations(final Path directory,
                                              final List<String> files) throws IOException {
        final StringBuilder source = new StringBuilder();
        for (String file : files) {
            source.append('\n').append(Files.readString(directory.resolve(file))
                    .replace("protected ", "private "));
        }
        return source.toString();
    }
}
