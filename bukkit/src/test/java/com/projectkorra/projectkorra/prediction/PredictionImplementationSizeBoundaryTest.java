package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionImplementationSizeBoundaryTest {
    private static final int MAX_SOURCE_LINES = 500;

    @Test
    void predictionEntrypointsDelegateToSmallImplementationClasses() throws IOException {
        final Path paper = locate(
                "src/main/java/com/projectkorra/projectkorra/prediction/server",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server");
        final Path fabric = locate(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client");

        final List<Path> sources = new ArrayList<>();
        sources.add(paper.resolve("PaperPredictionServer.java"));
        sources.add(fabric.resolve("ExactPredictionRuntime.java"));
        sources.add(fabric.resolve("PredictionClient.java"));
        addJavaSources(sources, paper.resolve("impl"));
        addJavaSources(sources, fabric.resolve("prediction").resolve("impl"));

        for (Path source : sources) {
            final long lines;
            try (Stream<String> content = Files.lines(source)) {
                lines = content.count();
            }
            assertTrue(lines <= MAX_SOURCE_LINES,
                    source + " contains " + lines + " lines; prediction classes must stay focused");
        }

        assertTrue(Files.readString(paper.resolve("PaperPredictionServer.java"))
                .contains("prediction.server.impl.PaperPredictionSnapshots"));
        assertTrue(Files.readString(fabric.resolve("ExactPredictionRuntime.java"))
                .contains("client.prediction.impl.ExactPredictionApiLifecycle"));
        assertTrue(Files.readString(fabric.resolve("PredictionClient.java"))
                .contains("client.prediction.impl.PredictionClientApi"));
    }

    private static Path locate(final String moduleRelative, final String rootRelative) {
        final Path module = Path.of(moduleRelative);
        return Files.exists(module) ? module : Path.of(rootRelative);
    }

    private static void addJavaSources(final List<Path> target, final Path directory)
            throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(target::add);
        }
    }
}
