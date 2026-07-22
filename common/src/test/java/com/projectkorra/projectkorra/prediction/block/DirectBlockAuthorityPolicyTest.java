package com.projectkorra.projectkorra.prediction.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectBlockAuthorityPolicyTest {
    @Test
    void aConfirmedCausalEarthTransactionMayConcealPaper() {
        assertTrue(DirectBlockAuthorityPolicy.mayConceal(true, true, true));
        assertTrue(DirectBlockAuthorityPolicy.mayConceal(true, false, false));
        assertTrue(DirectBlockAuthorityPolicy.mayConceal(false, true, false));
        assertTrue(DirectBlockAuthorityPolicy.mayConceal(false, false, true));
        assertFalse(DirectBlockAuthorityPolicy.mayConceal(false, false, false));
    }
}
