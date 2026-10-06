package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.listener.CommonAbilityCombatListener;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackCombatListenerTransferTest {
    private static final class Settings extends Config {
        Settings() { super(new RollbackConfiguration.Settings(Config.class.getName(),
                Map.of("FallingBlockCollisions", List.of("malformed", "earth, Wind")), Map.of(), true)); }
    }

    @Test void capturedSharedRulesImportAndPreserveVelocityCancellation() {
        var previous = ConfigManager.collisionConfig;
        ConfigManager.collisionConfig = new Settings();
        try {
            var listener = new CommonAbilityCombatListener();
            var source = new RollbackEventBus(32, 8);
            source.registerListener(listener);
            assertEquals(4, source.commonRegistrations().size());
            var catalog = RollbackGameplayCatalog.create(RollbackGameplayCatalog.installed(getClass().getClassLoader()), List.of());
            var codec = new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000));
            var copied = (RollbackEventBindings) codec.decode(codec.encode(List.of(RollbackEventBindings.capture(source)))).getFirst();
            assertNotSame(listener, copied.registrations().getFirst().listener());
            assertSame(copied.registrations().getFirst().listener(), copied.registrations().getFirst().owner());
            var replay = new RollbackEventBus(32, 8);
            copied.install(replay);
            Ability wind = (Ability) Proxy.newProxyInstance(Ability.class.getClassLoader(), new Class<?>[]{Ability.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getName")) return "Wind";
                        throw new AssertionError(method);
                    });
            var block = new FallingBlock() {
                @Override public boolean hasMetadata(String name) { return name.equals("earth"); }
            };
            for (var bus : List.of(source, replay)) {
                var event = new AbilityVelocityAffectEntityEvent(wind, block, new Vector(1, 2, 3));
                bus.call(event);
                assertTrue(event.isCancelled());
                for (var entity : List.of(block, new Player())) {
                    var unattributed = new AbilityVelocityAffectEntityEvent(null, entity, new Vector());
                    bus.call(unattributed);
                    assertFalse(unattributed.isCancelled());
                }
            }
            ConfigManager.collisionConfig = null;
            var alreadyCancelled = new AbilityVelocityAffectEntityEvent(wind, block, new Vector());
            alreadyCancelled.setCancelled(true);
            assertDoesNotThrow(() -> replay.call(alreadyCancelled), "Cancelled velocity must not access collision policy");
        } finally { ConfigManager.collisionConfig = previous; }
    }
}
