package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackRandomParityNativeTest {
    @Test void nativeRandomMethodsAndForksSupplyTheClientReference() throws Exception {
        var actual = new StringBuilder();
        for (long seed : new long[]{0, 57, 7001, Long.MIN_VALUE, Long.MAX_VALUE}) {
            var random = RandomSource.create(seed);
            actual.append(seed).append(' ').append(random.nextInt()).append(' ').append(random.nextInt(17)).append(' ').append(random.nextLong())
                    .append(' ').append(Float.toHexString(random.nextFloat())).append(' ').append(Double.toHexString(random.nextDouble()))
                    .append(' ').append(Double.toHexString(random.nextGaussian())).append(' ').append(Double.toHexString(random.nextGaussian()))
                    .append(' ').append(Double.toHexString(random.fork().nextDouble()));
            var split = random.forkPositional();
            actual.append(' ').append(Double.toHexString(split.at(-3, 27, 8).nextDouble()))
                    .append(' ').append(Double.toHexString(split.fromHashOf("world").nextDouble()))
                    .append(' ').append(Double.toHexString(split.fromSeed(91).nextDouble())).append('\n');
        }
        try (var source = getClass().getResourceAsStream("/rollback/native-random.txt")) {
            assertNotNull(source, "Current Paper random fixture:\n" + actual);
            assertEquals(new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), actual.toString(), "Current Paper random fixture:\n" + actual);
        }
    }
}
