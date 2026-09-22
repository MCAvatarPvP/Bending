package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKPlayers;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.FlightHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FireShotsContactTest {
    @TempDir Path directory;
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer caster = world.player(0.5, 64, 0.5);
    private final AbilityWorld.TestPlayer target = world.player(0.5, 64, 2.5);
    private final List<Entity> contacts = new ArrayList<>();
    private final List<AbilityDamageEntityEvent> damageEvents = new ArrayList<>();
    private Map<Class<?>, Object> attributes;
    private Object previousAttributes;
    private Map<Class<?>, Object> managers;
    private Map<Class<?>, Object> previousManagers;
    private com.jedk1.jedcore.configuration.Config previousJedCore;
    private Field timerAbility;
    private Object previousTimerAbility;
    private TestShots ability;
    private boolean authoritative;
    private final CooldownSync.Listener side = new CooldownSync.Listener() {
        @Override public boolean isAuthoritative() { return authoritative; }
        @Override public void onAdded(CoreAbility source, BendingPlayer player, String name, long expiry) { }
        @Override public void onRemoved(BendingPlayer player, String name) { }
    };
    private final PredictedContactSync.Listener reporter = (source, entity) -> {
        assertSame(ability, source);
        assertFalse(source.isRemoved());
        contacts.add(entity);
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        Field field = CoreAbility.class.getDeclaredField("ATTRIBUTE_FIELDS");
        field.setAccessible(true);
        attributes = (Map<Class<?>, Object>) field.get(null);
        previousAttributes = attributes.put(TestShots.class, new HashMap<>());
        Field managerField = Manager.class.getDeclaredField("MANAGERS");
        managerField.setAccessible(true);
        managers = (Map<Class<?>, Object>) managerField.get(null);
        previousManagers = new HashMap<>(managers);
        managers.put(FlightHandler.class, null);
        timerAbility = FireDamageTimer.class.getDeclaredField("ability");
        timerAbility.setAccessible(true);
        previousTimerAbility = timerAbility.get(null);
        caster.eyeHeight = 1.62;
        BendingPlayer.getPlayers().put(caster.getUniqueId(), new BendingPlayer(caster) {
            @Override public boolean canBend(CoreAbility source) { return true; }
            @Override public boolean canBendIgnoreCooldowns(CoreAbility source) { return true; }
            @Override public void addCooldown(Ability source) { }
        });
        ConfigManager.getConfig().set("Properties.DamageMultiplier", 1.0);
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = new PKEventBus() {
            @Override public void call(Object event) {
                if (event instanceof AbilityDamageEntityEvent damage) {
                    damageEvents.add(damage);
                    damage.setCancelled(true);
                }
            }
            @Override public void registerListener(Object listener) { }
            @Override public void unregisterAll(Object listener) { }
        };
        PKPlayers players = (PKPlayers) Proxy.newProxyInstance(PKPlayers.class.getClassLoader(),
                new Class<?>[]{PKPlayers.class}, (proxy, method, args) ->
                        method.getReturnType() == boolean.class ? false : List.of());
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "events" -> events;
                    case "players" -> players;
                    case "dataFolder" -> directory;
                    default -> method.invoke(delegate, args);
                }));
        previousJedCore = JedCoreConfig.config;
        JedCoreConfig.config = new com.jedk1.jedcore.configuration.Config(directory.resolve("jedcore.yml").toFile());
        JedCoreConfig.config.set("Abilities.Fire.FireShots.ForwardSpeed", 1.0);
        JedCoreConfig.config.set("Abilities.Fire.FireShots.SideSpeed", 0.0);
        CooldownSync.install(side);
        PredictedContactSync.install(reporter);
        ability = new TestShots(caster);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (ability != null && !ability.isRemoved()) ability.remove();
        CooldownSync.clear(side);
        PredictedContactSync.clear(reporter);
        FireDamageTimer.getInstances().remove(target);
        timerAbility.set(null, previousTimerAbility);
        BendingPlayer.getPlayers().remove(caster.getUniqueId());
        JedCoreConfig.config = previousJedCore;
        if (previousAttributes == null) attributes.remove(TestShots.class);
        else attributes.put(TestShots.class, previousAttributes);
        managers.clear();
        managers.putAll(previousManagers);
        world.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hitStopsOnlyTheShotWithDamageAndBurningKeptOnServer(boolean serverSide) {
        authoritative = serverSide;
        FireShots.FireShot shot = launch(0.5);
        tick();
        assertTrue(ability.getShots().isEmpty(), "client contact must retire the projectile too");
        assertEquals(2.5, shot.getLocation().getZ(), "do not advance past the first hit substep");
        assertFalse(ability.isRemoved(), "a hit must leave the unfired shot available");
        assertEquals(1, ability.getAmount());
        assertEquals(serverSide ? List.of() : List.of(target), contacts);
        assertEquals(serverSide ? 1 : 0, damageEvents.size());
        assertEquals(serverSide, FireDamageTimer.getInstances().containsKey(target));
        if (serverSide) assertSame(target, damageEvents.getFirst().getEntity());
    }

    @Test
    void onePredictedHitDoesNotStopAnotherFlyingShot() {
        FireShots.FireShot hit = launch(0.5);
        FireShots.FireShot flying = launch(10.5);
        tick();
        assertEquals(List.of(flying), ability.getShots());
        assertFalse(ability.isRemoved());
        assertEquals(2.5, hit.getLocation().getZ());
        assertEquals(3.5, flying.getLocation().getZ());
        assertEquals(List.of(target), contacts);

        target.location = new Location(world, 10.5, 64, 4.5);
        tick();
        assertTrue(ability.getShots().isEmpty());
        assertTrue(ability.isRemoved(), "finish after the last fired shot stops");
        assertEquals(List.of(target, target), contacts);
        assertTrue(damageEvents.isEmpty());
        assertFalse(FireDamageTimer.getInstances().containsKey(target));
    }

    @Test
    void missedShotsContinueToTheirNormalRange() {
        target.location.setZ(100.5);
        launch(0.5);
        launch(10.5);
        tick();
        assertEquals(2, ability.getShots().size());
        for (int i = 0; i < 20 && !ability.isRemoved(); i++) tick();
        assertTrue(ability.isRemoved());
        assertTrue(ability.getShots().isEmpty());
        assertTrue(contacts.isEmpty());
        assertTrue(damageEvents.isEmpty());
        assertFalse(FireDamageTimer.getInstances().containsKey(target));
    }

    private FireShots.FireShot launch(double x) {
        ability.fireShot();
        FireShots.FireShot shot = ability.getShots().getLast();
        shot.setLocation(new Location(world, x, 65.2, 1.5));
        return shot;
    }

    private void tick() { AbilityExecutionContext.run(ability, ability::progress); }

    private static final class TestShots extends FireShots {
        private TestShots(Player player) { super(player); }
        @Override public void setFields() {
            setStartAmount(2);
            setRange(20);
            setDamage(2);
            setFireticks(1000);
            setCollisionRadius(0.5);
        }
        @Override public boolean isEnabled() { return true; }
    }
}
