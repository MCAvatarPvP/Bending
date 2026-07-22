package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;

import com.projectkorra.projectkorra.waterbending.WaterSpout;
import com.projectkorra.projectkorra.waterbending.WaterSpoutWave;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AbilityRemovalIdentityTest {
    @Test
    void sameNamedSpoutImplementationsHaveDifferentRemovalIdentities() {
        final String spout = AbilityRemovalSync.typeId(WaterSpout.class);
        final String wave = AbilityRemovalSync.typeId(WaterSpoutWave.class);

        assertEquals(WaterSpout.class.getName(), spout);
        assertEquals(WaterSpoutWave.class.getName(), wave);
        assertNotEquals(spout, wave,
                "a transient WaterSpoutWave removal must not close the live WaterSpout");
    }
}
