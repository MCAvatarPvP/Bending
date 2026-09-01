package com.projectkorra.projectkorra.fabric.client.prediction.block;

import org.junit.jupiter.api.Test;

import static com.projectkorra.projectkorra.fabric.client.prediction.block.DirectVisualOrderPolicy.Source.EXISTING;
import static com.projectkorra.projectkorra.fabric.client.prediction.block.DirectVisualOrderPolicy.Source.OBSERVED;
import static com.projectkorra.projectkorra.fabric.client.prediction.block.DirectVisualOrderPolicy.Source.RECEIPT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectVisualOrderPolicyTest {
    @Test
    void laterLocalAirWinsOverItsOlderSolidReceipt() {
        assertEquals(EXISTING, DirectVisualOrderPolicy.select(
                true, true, 7L, 12L,
                true, 12L, 7L, 11L));
    }

    @Test
    void revisionOrderOutranksActionOrderForLongLivedAbilities() {
        assertEquals(EXISTING, DirectVisualOrderPolicy.select(
                true, true, 4L, 30L,
                false, 0L, 9L, 20L));
        assertEquals(OBSERVED, DirectVisualOrderPolicy.select(
                false, false, 0L, 0L,
                true, 31L, 9L, 20L));
    }

    @Test
    void newerExactLocalWriteCanAdvanceAStaleMask() {
        assertEquals(RECEIPT, DirectVisualOrderPolicy.select(
                true, true, 4L, 20L,
                true, 20L, 9L, 31L));
    }

    @Test
    void actionOrderOnlyBreaksTiesForReceiptOnlyMasks() {
        assertEquals(EXISTING, DirectVisualOrderPolicy.select(
                true, false, 9L, 0L,
                false, 0L, 4L, 0L));
        assertEquals(RECEIPT, DirectVisualOrderPolicy.select(
                true, false, 4L, 0L,
                false, 0L, 9L, 0L));
    }

    @Test
    void exactLocalWriteBeatsAReceiptOnlyMaskEvenFromAnOlderAction() {
        assertEquals(RECEIPT, DirectVisualOrderPolicy.select(
                true, false, 9L, 0L,
                false, 0L, 4L, 31L));
    }
}
