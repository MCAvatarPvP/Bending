package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.earthbending.EarthBlast;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.prediction.rollback.RollbackTimeShift;
import com.projectkorra.projectkorra.waterbending.WaterSpoutWave;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollbackElementalTimersTest {
    @Test
    void confirmedMotionAndChargePhasesKeepTheirRemainingTime() throws Exception {
        try (var world = new com.projectkorra.projectkorra.support.AbilityWorld()) {
            var allocator = new ObjenesisStd();
            var jet = allocator.newInstance(FireJet.class);
            jet.setTime(1_200);
            jet.setDuration(1_500);
            var blast = allocator.newInstance(EarthBlast.class);
            blast.setTime(1_950);
            blast.setInterval(80);
            var wave = allocator.newInstance(WaterSpoutWave.class);
            wave.setTime(0);
            wave.setChargeTime(2_100);

            var time = RollbackTimeShift.between(2_000, 22_000);
            for (CoreAbility ability : new CoreAbility[]{jet, blast, wave}) {
                ability.rebaseRollbackTimers(time);
            }

            assertEquals(700L, jet.getTime() + jet.getDuration() - 22_000L);
            assertEquals(30L, blast.getTime() + blast.getInterval() - 22_000L);
            assertEquals(0L, wave.getTime());
            assertEquals(2_100.0, wave.getChargeTime());
        }
    }
}
