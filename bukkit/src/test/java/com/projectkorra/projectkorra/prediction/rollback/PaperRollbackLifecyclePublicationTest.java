package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.PKListener;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackLifecyclePublicationTest {
    @Test void lifecyclePublisherCannotReachTheLiveServerDuringReplay() {
        var listener = new PKListener(null);
        try (var clock = RollbackClock.at(0, 1, 50_000_000)) {
            assertThrows(IllegalStateException.class, () -> listener.publishLifecycleEffect(
                    new CommonAbilityLifecycleListener.ConsoleCommand("test")));
        }
    }
    @Test void nativeListenerIsNotCapturedAsACommonGameplayHandler() {
        for (var method : PKListener.class.getMethods()) {
            if (method.getParameterCount() == 1
                    && com.projectkorra.projectkorra.platform.mc.event.Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                assertNull(method.getAnnotation(org.bukkit.event.EventHandler.class), method.toString());
                assertNull(method.getAnnotation(com.projectkorra.projectkorra.platform.mc.event.EventHandler.class), method.toString());
            }
        }
    }
}
