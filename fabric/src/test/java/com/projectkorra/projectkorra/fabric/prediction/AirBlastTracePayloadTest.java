package com.projectkorra.projectkorra.fabric.prediction;

import com.projectkorra.projectkorra.prediction.AirBlastTraceSync;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AirBlastTracePayloadTest {
    @Test
    void fabricReceiptPreservesTheCommonParitySampleExactly() {
        AirBlastTraceSync.Trace trace = new AirBlastTraceSync.Trace(
                4, AirBlastTraceSync.Phase.BLOCKED, 2,
                2.25, 65.62, -3.75, 30.0F, 89.0F,
                2.25, 64.2, -1.25, 2.28, 63.0, -1.2,
                2.25, 63.1, -1.25, 0.02, -0.999, 0.04,
                23.0, 11.0, 1.5, 1.2, 1.0,
                2, 63, -2, "STONE", true);

        PredictionPayloads.AirBlastTraceReceipt receipt =
                PredictionPayloads.AirBlastTraceReceipt.from(UUID.randomUUID(), 17L, 90L, trace);

        assertEquals(trace, receipt.trace());
    }
}
