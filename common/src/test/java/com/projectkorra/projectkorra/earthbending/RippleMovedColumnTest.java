package com.projectkorra.projectkorra.earthbending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RippleMovedColumnTest {
    @Test
    void distinctWrappersForTheSameColumnAreDeduplicated() {
        ShockwaveExecutionPolicy.MovedColumns columns = new ShockwaveExecutionPolicy.MovedColumns();

        assertTrue(columns.mark("world", 4, -3));
        assertFalse(columns.mark("world", 4, -3));
    }

    @Test
    void columnsInDifferentWorldsRemainIndependent() {
        ShockwaveExecutionPolicy.MovedColumns columns = new ShockwaveExecutionPolicy.MovedColumns();

        assertTrue(columns.mark("world", 4, -3));
        assertTrue(columns.mark("world_nether", 4, -3));
    }
}
