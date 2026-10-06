package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPaperRandomTest {
    @Test void privateRandomMethodsAndForksMatchTheActualPaperReference() throws Exception {
        var actual = new StringBuilder();
        for (long seed : new long[]{0, 57, 7001, Long.MIN_VALUE, Long.MAX_VALUE}) {
            actual.append(line(seed, new FabricRollbackPaperRandom(seed)));
        }
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/native-random.txt"))) {
            assertEquals(new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), actual.toString());
        }
    }
    static String line(long seed, net.minecraft.util.math.random.Random random) {
        var actual = new StringBuilder();
        actual.append(seed).append(' ').append(random.nextInt()).append(' ').append(random.nextInt(17)).append(' ').append(random.nextLong())
                    .append(' ').append(Float.toHexString(random.nextFloat())).append(' ').append(Double.toHexString(random.nextDouble()))
                    .append(' ').append(Double.toHexString(random.nextGaussian())).append(' ').append(Double.toHexString(random.nextGaussian()))
                    .append(' ').append(Double.toHexString(random.split().nextDouble()));
            var split = random.nextSplitter();
            actual.append(' ').append(Double.toHexString(split.split(-3, 27, 8).nextDouble()))
                    .append(' ').append(Double.toHexString(split.split("world").nextDouble()))
                    .append(' ').append(Double.toHexString(split.split(91).nextDouble())).append('\n');
        return actual.toString();
    }
}
