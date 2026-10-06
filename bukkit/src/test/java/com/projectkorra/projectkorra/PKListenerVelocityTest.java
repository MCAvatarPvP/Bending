package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PKListenerVelocityTest {
    @Test void unattributedVelocityDoesNotRequireAbilityCollisionRules() {
        var listener = new PKListener(null);
        for (var entity : java.util.List.of(new FallingBlock(), new Player())) {
            var velocity = new Vector(1, 2, 3);
            var event = new AbilityVelocityAffectEntityEvent(null, entity, velocity);
            assertDoesNotThrow(() -> listener.onAbilityVelocity(event));
            assertFalse(event.isCancelled());
            assertSame(velocity, event.getVelocity());
            assertSame(entity, event.getAffected());
        }
    }
}
