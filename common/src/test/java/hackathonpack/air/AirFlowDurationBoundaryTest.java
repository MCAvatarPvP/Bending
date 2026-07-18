package hackathonpack.air;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards AirFlow's configured duration and finite particle drain. */
class AirFlowDurationBoundaryTest {
    @Test
    void removedFlowDrainsExistingParticlesWithoutEmittingForever() throws IOException {
        final String source = source();
        final String progress = method(source, "public void progress()", "private void tickParticles");
        final String tick = method(source, "private void tickParticles", "private double noise");
        final String remove = method(source, "public void remove()", "private enum State");

        assertTrue(progress.contains("this.launchTime + this.duration")
                        && progress.contains("tickParticles(true)"),
                "the launched source must stop on its configured millisecond duration");
        assertTrue(tick.contains("if (emitParticles)")
                        && tick.indexOf("if (emitParticles)") < tick.indexOf("this.particles.add"),
                "particle creation must be explicitly disabled while draining");
        assertTrue(remove.contains("tickParticles(false)")
                        && remove.contains("if (particles.isEmpty())"),
                "the post-duration task may move existing particles but cannot replenish them");
    }

    private static String source() throws IOException {
        Path path = Path.of("src/main/java/hackathonpack/air/AbstractAirFlow.java");
        if (!Files.exists(path)) path = Path.of("common").resolve(path);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }
}
