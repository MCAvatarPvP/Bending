package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.FallHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommonDamageHandlerTest {
    @Test void fallProtectionIsConsumedOnlyByLandingDamage() throws Exception {
        try (var world = new AbilityWorld()) {
            var player = world.player(0, 65, 0);
            FallHandler.stopFall(player);
            var fire = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FIRE, 3);
            CommonDamageHandler.fall(fire);
            assertFalse(fire.isCancelled()); assertTrue(FallHandler.contains(player));
            var fall = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 8);
            CommonDamageHandler.fall(fall);
            assertTrue(fall.isCancelled()); assertFalse(FallHandler.contains(player));
            var next = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 8);
            CommonDamageHandler.fall(next);
            assertFalse(next.isCancelled());
        }
    }
}
