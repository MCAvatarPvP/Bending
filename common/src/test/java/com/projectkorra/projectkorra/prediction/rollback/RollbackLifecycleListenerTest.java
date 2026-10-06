package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.configuration.*;
import com.projectkorra.projectkorra.event.*;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener.*;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackLifecycleListenerTest {
    private static final class Settings extends Config {
        Settings() { super(new RollbackConfiguration.Settings(Config.class.getName(),
                Map.of("Properties.FunnyCMD", "test {player}"), Map.of(), true)); }
    }
    @Test void importedLifecycleUsesPrivateOutputsAndLateInputDoesNotPublishLiveCommands() {
        var previous = ConfigManager.defaultConfig;
        ConfigManager.defaultConfig = new Settings();
        try {
            var liveEffects = new ArrayList<Effect>();
            var live = new CommonAbilityLifecycleListener(liveEffects::add);
            var source = new RollbackEventBus(32, 8); source.registerListener(live);
            assertEquals(6, source.commonRegistrations().size());
            var output = new RollbackStepOutput<Effect>();
            var targetBinding = new CommonAbilityLifecycleListener(output::accept);
            var types = RollbackGameplayCatalog.installed(getClass().getClassLoader());
            var limits = new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000);
            var sender = new RollbackGraphCodec(RollbackGameplayCatalog.create(types, List.of(live.rollbackEffectsBinding())), limits);
            var receiver = new RollbackGraphCodec(RollbackGameplayCatalog.create(types, List.of(targetBinding.rollbackEffectsBinding())), limits);
            var registrations = (RollbackEventBindings) receiver.decode(sender.encode(List.of(RollbackEventBindings.capture(source)))).getFirst();
            var replay = new RollbackEventBus(32, 8); registrations.install(replay);
            UUID id = new UUID(0, 1);
            var player = new Player() {
                @Override public UUID getUniqueId() { return id; }
                @Override public String getName() { return "Duelist"; }
                @Override public boolean hasPermission(String permission) { return true; }
            };
            Ability ability = (Ability) Proxy.newProxyInstance(Ability.class.getClassLoader(), new Class<?>[]{Ability.class},
                    (proxy, method, args) -> { if (method.getName().equals("getPlayer")) return player; throw new AssertionError(method); });
            var simulation = new RollbackSimulation<Integer, Boolean, Effect>() {
                int cancellations;
                public Integer snapshot() { output.captureRollbackState(); return cancellations; }
                public void restore(Integer state) { output.restoreRollbackState(null); cancellations = state; }
                public Boolean predict(UUID ignored, Boolean last) { return false; }
                public void step(long tick, Map<UUID, Boolean> input, RollbackStep<Effect> step) {
                    try (var bound = output.open(step)) {
                        if (input.get(id)) {
                            var event = new AbilityStartEvent(ability); replay.call(event);
                            assertTrue(event.isCancelled()); cancellations++;
                            replay.call(new PlayerStanceChangeEvent(player, "old", "new"));
                        }
                    }
                }
            };
            var engine = new RollbackEngine<>(simulation, Map.of(id, false), new RollbackEngine.Limits(4, 2, 20, 50000000), 0);
            assertTrue(engine.advance().head().effects().isEmpty());
            engine.submit(id, 1, true);
            var revised = engine.reconcile();
            assertEquals(List.of(new ConsoleCommand("test Duelist"), new Board(id, "old", 0, false, false),
                    new Board(id, "new", 0, false, false)), revised.head().effects());
            assertEquals(1, simulation.cancellations);
            assertTrue(liveEffects.isEmpty(), "Source publisher must never be retained by imported listeners");
            assertTrue(engine.advance().head().effects().isEmpty());
            for (int i = 0; i < 4; i++) engine.advance().finalizedEffects().forEach(effect -> liveEffects.add(effect.value()));
            assertEquals(revised.head().effects(), liveEffects, "Only finalized output is published, once");
            engine.advance().finalizedEffects().forEach(effect -> liveEffects.add(effect.value()));
            assertEquals(3, liveEffects.size());
            assertThrows(IllegalStateException.class, () -> replay.call(new AbilityStartEvent(ability)));
        } finally { ConfigManager.defaultConfig = previous; }
    }
}
