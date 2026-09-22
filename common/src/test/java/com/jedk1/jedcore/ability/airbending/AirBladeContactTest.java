package com.jedk1.jedcore.ability.airbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.event.AbilityEndEvent;
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
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.FlightHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AirBladeContactTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer caster = world.player(0.5, 64, 0.5);
    private final AbilityWorld.TestPlayer target = world.player(0.5, 64, 100.5);
    private final List<Entity> contacts = new ArrayList<>();
    private final List<AbilityDamageEntityEvent> damageEvents = new ArrayList<>();
    private final List<AbilityEndEvent> endEvents = new ArrayList<>();
    private Map<Class<?>, Object> attributes;
    private Object previousAttributes;
    private Map<Class<?>, Object> managers;
    private Map<Class<?>, Object> previousManagers;
    private TestBlade blade;
    private boolean authoritative;
    private final CooldownSync.Listener side = new CooldownSync.Listener() {
        @Override public boolean isAuthoritative() { return authoritative; }
        @Override public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiry) { }
        @Override public void onRemoved(BendingPlayer player, String ability) { }
    };
    private final PredictedContactSync.Listener reporter = (ability, entity) -> {
        assertSame(blade, ability);
        assertFalse(ability.isRemoved(), "report the contact before destroying the predicted blade");
        contacts.add(entity);
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        Field field = CoreAbility.class.getDeclaredField("ATTRIBUTE_FIELDS");
        field.setAccessible(true);
        attributes = (Map<Class<?>, Object>) field.get(null);
        previousAttributes = attributes.put(TestBlade.class, new HashMap<>());
        Field managerField = Manager.class.getDeclaredField("MANAGERS");
        managerField.setAccessible(true);
        managers = (Map<Class<?>, Object>) managerField.get(null);
        previousManagers = new HashMap<>(managers);
        managers.put(FlightHandler.class, null);
        caster.eyeHeight = 1.62;
        BendingPlayer.getPlayers().put(caster.getUniqueId(), new BendingPlayer(caster) {
            @Override public boolean canBend(CoreAbility ability) { return true; }
            @Override public void addCooldown(Ability ability) { }
        });
        ConfigManager.getConfig().set("Properties.DamageMultiplier", 1.0);
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = new PKEventBus() {
            @Override public void call(Object event) {
                if (event instanceof AbilityEndEvent end) endEvents.add(end);
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
                    default -> method.invoke(delegate, args);
                }));
        CooldownSync.install(side);
        PredictedContactSync.install(reporter);
        RegionProtection.clearCache();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (blade != null && !blade.isRemoved()) blade.remove();
        CooldownSync.clear(side);
        PredictedContactSync.clear(reporter);
        BendingPlayer.getPlayers().remove(caster.getUniqueId());
        if (previousAttributes == null) attributes.remove(TestBlade.class);
        else attributes.put(TestBlade.class, previousAttributes);
        managers.clear();
        managers.putAll(previousManagers);
        RegionProtection.clearCache();
        world.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contactStopsBladeAndCleansDisplaysOnBothSides(boolean serverSide) {
        authoritative = serverSide;
        blade = new TestBlade(caster);
        tick();
        assertFalse(blade.isRemoved());
        assertEquals(11, world.displays.size());
        assertTrue(contacts.isEmpty());

        target.location = new Location(world, 0.5, 64, 3.5);
        tick();

        assertTrue(blade.isRemoved(), "entity contact must stop the blade on the client too");
        assertEquals(3.0, blade.getTravelled(), "do not run another movement substep after the hit");
        assertEquals(1, endEvents.size(), "one hit must only remove the blade once");
        assertTrue(world.displays.stream().noneMatch(AbilityWorld.TestDisplay::isValid));
        assertEquals(serverSide ? List.of() : List.of(target), contacts);
        assertEquals(serverSide ? 1 : 0, damageEvents.size(), "only the server attempts health damage");
        if (serverSide) assertSame(target, damageEvents.getFirst().getEntity());
    }

    @Test
    void predictedMissKeepsTravellingUntilRangeThenCleansDisplays() {
        blade = new TestBlade(caster);
        for (int i = 0; i < 20 && !blade.isRemoved(); i++) tick();
        assertTrue(blade.isRemoved());
        assertEquals(30.0, blade.getTravelled());
        assertTrue(contacts.isEmpty());
        assertTrue(damageEvents.isEmpty());
        assertEquals(1, endEvents.size());
        assertFalse(world.displays.isEmpty());
        assertTrue(world.displays.stream().noneMatch(AbilityWorld.TestDisplay::isValid));
    }

    private void tick() { AbilityExecutionContext.run(blade, blade::progress); }

    /** Keep real progress/collision/removal; supply fixed config values for the test world. */
    private static final class TestBlade extends AirBlade {
        private TestBlade(Player player) { super(player); }
        @Override public void setFields() {
            setRange(30);
            setDamage(3);
            setGrowth(1);
            setEntityCollisionRadius(0.5);
        }
        @Override public boolean isEnabled() { return true; }
        @Override public double getCollisionRadius() { return 1; }
    }
}
