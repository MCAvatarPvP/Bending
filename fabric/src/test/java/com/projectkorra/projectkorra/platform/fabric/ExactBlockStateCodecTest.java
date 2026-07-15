package com.projectkorra.projectkorra.platform.fabric;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactBlockStateCodecTest {
    @Test
    void adaptersCaptureAndParseEveryGenericNativeProperty() throws IOException {
        Path adapterPath = Path.of("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");
        Path runtimePath = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(adapterPath)) adapterPath = Path.of("fabric").resolve(adapterPath);
        if (!Files.exists(runtimePath)) runtimePath = Path.of("fabric").resolve(runtimePath);

        String adapter = Files.readString(adapterPath);
        String runtime = Files.readString(runtimePath);

        assertTrue(adapter.contains("data.setExactState(serializeBlockState(state))"));
        assertTrue(adapter.contains("property.parse(value)"));
        assertTrue(adapter.contains("state.with(property, parsed.get())"));
        assertTrue(runtime.contains("!materialName.contains(\";\")"));
        assertTrue(runtime.contains("return FabricMC.blockState(materialName)"));
    }
}
