package com.jedk1.jedcore.ability.airbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards shutdown of the constructor-bypassed addon prototype. */
class AirBladeShutdownTest {
    @Test
    void addonPrototypeCanStopWithoutConstructedDisplayState() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/jedk1/jedcore/ability/airbending/AirBlade.java");
        if (!Files.exists(sourcePath)) sourcePath = Path.of("common").resolve(sourcePath);
        final String source = Files.readString(sourcePath);
        final int start = source.indexOf("public void stop()");
        final int end = source.indexOf("public boolean isEnabled()", start);
        assertTrue(start >= 0 && end > start);
        final String stop = source.substring(start, end);

        assertTrue(stop.contains("if (bladeHeadDisplays == null)"),
                "addon prototypes bypass field initialization and must still be safe to stop");
    }
}
