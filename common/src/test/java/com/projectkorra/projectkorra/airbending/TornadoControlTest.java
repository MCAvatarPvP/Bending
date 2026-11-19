package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKPlayers;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.FlightHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TornadoControlTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 64, 0.5);
    private final List<Tornado> abilities = new ArrayList<>();
    private final List<Long> appliedCooldowns = new ArrayList<>();
    private Map<Class<?>, Object> attributeFields;
    private Object previousAttributes;
    private final List<Player> targets = new ArrayList<>();
    private final List<Entity> pulledTargets = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        player.eyeHeight = 1.6;
        player.sneaking = true;
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
        }
        BendingPlayer.getPlayers().put(player.getUniqueId(), new BendingPlayer(player) {
            @Override public boolean canBendIgnoreBindsCooldowns(CoreAbility ability) { return true; }
            @Override public boolean isOnCooldown(Ability ability) { return false; }
            @Override public void addCooldown(Ability ability) { appliedCooldowns.add(ability.getCooldown()); }
        });
        ConfigManager.getConfig().set("Abilities.Air.Tornado.Sound.Enabled", false);
        ConfigManager.getConfig().set("Abilities.Air.Tornado.PullVelocity", 0.315);
        ConfigManager.getConfig().set("Abilities.Air.Tornado.PullZoneRadius", 9.0);

        // Keep the real remove path available without registering this test ability globally.
        attributeFields = attributes();
        previousAttributes = attributeFields.put(TestTornado.class, new HashMap<>());
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = new PKEventBus() {
            @Override public void call(Object event) {
                if (event instanceof AbilityVelocityAffectEntityEvent velocity) pulledTargets.add(velocity.getAffected());
            }
            @Override public void registerListener(Object listener) { }
            @Override public void unregisterAll(Object listener) { }
        };
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("events")) return events;
                    if (method.getName().equals("players")) return Proxy.newProxyInstance(PKPlayers.class.getClassLoader(),
                            new Class<?>[]{PKPlayers.class}, (p, m, a) -> m.getReturnType() == boolean.class ? false : null);
                    return method.invoke(delegate, args);
                }));
    }

    @AfterEach void cleanup() throws Exception {
        try {
            for (Tornado tornado : abilities) tornado.remove();
        } finally {
            if (previousAttributes == null) attributeFields.remove(TestTornado.class);
            else attributeFields.put(TestTornado.class, previousAttributes);
            BendingPlayer.getPlayers().remove(player.getUniqueId());
            for (Player target : targets) BendingPlayer.getPlayers().remove(target.getUniqueId());
            RegionProtection.clearCache(player);
            world.close();
        }
    }

    @Test void releasingPartialChargeLocksSizeAndAppliesScaledCooldownOnce() throws Exception {
        Tornado tornado = tornado();
        charge(tornado, 3250);
        player.sneaking = false;
        tornado.progress();

        assertEquals(Tornado.AbilityState.TORNADO_STATIONARY, get(tornado, "state"));
        assertEquals(11.0, number(tornado, "tornadoHeight"), 1e-9);
        assertEquals(4.25, number(tornado, "tornadoRadius"), 1e-9);
        assertEquals(5.75, number(tornado, "pullZoneRadius"), 1e-9);
        assertEquals(List.of(10500L), appliedCooldowns);
        assertEquals(1250, number(tornado, "maxPullDuration"));
        Location deployed = tornado.getLocation().clone();

        charge(tornado, 6000);
        tornado.progress();
        tornado.progress();
        assertEquals(0, deployed.distance(tornado.getLocation()), 1e-9, "released tornado remains in place");
        assertEquals(11.0, number(tornado, "tornadoHeight"), 1e-9);
        assertEquals(4.25, number(tornado, "tornadoRadius"), 1e-9);
        assertEquals(10500, tornado.getCooldown());
        assertEquals(List.of(10500L), appliedCooldowns);
    }

    @Test void holdingToMaximumDeploysWithoutReleaseAndThenSteersSlowly() throws Exception {
        Tornado tornado = tornado();
        charge(tornado, 6000);
        tornado.progress();
        assertEquals(Tornado.AbilityState.TORNADO_STATIONARY, get(tornado, "state"));
        assertEquals(18.0, number(tornado, "tornadoHeight"), 1e-9);
        assertEquals(7.0, number(tornado, "tornadoRadius"), 1e-9);
        assertEquals(List.of(18000L), appliedCooldowns);

        Location deployed = tornado.getLocation().clone();
        player.location.setYaw(90);
        tornado.progress();
        assertTrue(tornado.getLocation().getX() < deployed.getX(), "sneak follows the changed aim");
        assertTrue(tornado.getLocation().distance(deployed) <= 0.020000001, "first step obeys control acceleration");
        for (int tick = 0; tick < 12; tick++) {
            Location previous = tornado.getLocation().clone();
            tornado.progress();
            assertTrue(tornado.getLocation().distance(previous) <= 0.160000001, "steering has a separate slow speed cap");
        }
        assertEquals(List.of(18000L), appliedCooldowns);
    }

    @Test void releaseBeforeMinimumCancelsWithoutCooldownOrDisplays() throws Exception {
        Tornado tornado = tornado();
        charge(tornado, 499);
        assertFalse(tornado.launch(), "left click cannot bypass the minimum charge");
        charge(tornado, 499);
        player.sneaking = false;
        tornado.progress();
        assertTrue(tornado.isRemoved());
        assertTrue(appliedCooldowns.isEmpty());
        assertTrue(world.displays.isEmpty());
    }

    @Test void leftClickDuringChargeLocksDirectionAndResneakingCannotRedirectIt() throws Exception {
        Tornado tornado = tornado();
        charge(tornado, 500);
        assertTrue(tornado.launch());
        assertEquals(4.0, number(tornado, "tornadoHeight"), 1e-9);
        assertEquals(List.of(3000L), appliedCooldowns);
        assertFalse(tornado.launch(), "throw is a one-time transition");

        Location launched = tornado.getLocation().clone();
        player.location.setYaw(90);
        player.sneaking = true;
        tornado.progress();
        assertFalse(tornado.isRemoved());
        assertEquals(launched.getX(), tornado.getLocation().getX(), 1e-9);
        assertEquals(launched.getZ() + 0.35, tornado.getLocation().getZ(), 1e-9);
        Vector direction = (Vector) get(tornado, "direction");
        assertEquals(0, direction.getX(), 1e-9);
        assertEquals(0, direction.getY(), 1e-9);
        assertEquals(1, direction.getZ(), 1e-9);
        assertFalse(tornado.tryStartRiding(), "a thrown tornado cannot be turned back into a ride");
        assertEquals(List.of(3000L), appliedCooldowns);
    }

    @Test void slowRideUsesItsOwnSpeedAndDismountCleansDisplaysAndRetainedEntities() throws Exception {
        ConfigManager.getConfig().set("Abilities.Air.Tornado.Speed", 1.5);
        Tornado tornado = tornado();
        charge(tornado, 6000);
        player.sneaking = false;
        tornado.progress();
        assertTrue(tornado.tryStartRiding(), "right click targets the caster's deployed funnel");
        assertFalse(tornado.launch(), "throwing cannot steal the riding controls");
        Location beforeRide = tornado.getLocation().clone();
        player.location.setYaw(90);
        tornado.progress();
        assertEquals(beforeRide.getX() - 0.22, tornado.getLocation().getX(), 1e-9);
        assertEquals(beforeRide.getZ(), tornado.getLocation().getZ(), 1e-9);
        assertEquals(20, world.displays.size());
        assertTrue(world.displays.stream().allMatch(Entity::isValid));
        for (int tick = 0; tick < 3; tick++) tornado.progress();
        assertEquals(20, world.displays.size(), "movement reuses its fixed display pool");

        UUID trapped = UUID.randomUUID();
        for (String name : List.of("lastDamageTimes", "pullStartTimes", "lastRestrictedTimes")) {
            map(tornado, name).put(trapped, 1L);
        }
        map(tornado, "caughtEntities").put(trapped, player);
        set(tornado, "exhaustedPullEntities").add(trapped);
        set(tornado, "pulledEntitiesThisTick").add(trapped);
        player.sneaking = true;
        tornado.progress();

        assertTrue(tornado.isRemoved());
        assertFalse((boolean) get(tornado, "riding"));
        for (String name : List.of("lastDamageTimes", "pullStartTimes", "lastRestrictedTimes", "caughtEntities")) {
            assertTrue(map(tornado, name).isEmpty(), name);
        }
        assertTrue(set(tornado, "exhaustedPullEntities").isEmpty());
        assertTrue(set(tornado, "pulledEntitiesThisTick").isEmpty());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
        assertDoesNotThrow(tornado::remove, "cleanup is idempotent");
    }

    @Test void chargingAnchorAndOrientationStayFixedWhileCasterMovesAndTurns() throws Exception {
        Tornado tornado = tornado();
        Location anchor = tornado.getLocation().clone();
        Vector direction = ((Vector) get(tornado, "direction")).clone();
        player.location.add(8, 0, -2);
        player.location.setYaw(90);
        charge(tornado, 2000);
        tornado.progress();
        assertEquals(0, anchor.distance(tornado.getLocation()), 1e-9);
        Vector afterTurn = (Vector) get(tornado, "direction");
        assertEquals(direction.getX(), afterTurn.getX(), 1e-9);
        assertEquals(direction.getY(), afterTurn.getY(), 1e-9);
        assertEquals(direction.getZ(), afterTurn.getZ(), 1e-9);
        assertFalse(((TestTornado) tornado).particles.isEmpty());
        for (Location particle : ((TestTornado) tornado).particles) {
            assertTrue(Math.abs(particle.getX() - anchor.getX()) < 7);
            assertTrue(Math.abs(particle.getZ() - anchor.getZ()) < 7);
        }
        player.sneaking = false;
        charge(tornado, 2000);
        tornado.progress();
        assertEquals(0, anchor.distance(tornado.getLocation()), 1e-9, "release must deploy at the original anchor");
    }

    @Test void throwingAfterTurningLaunchesFromTheFixedChargeAnchor() throws Exception {
        Tornado tornado = tornado();
        Location anchor = tornado.getLocation().clone();
        player.location.add(8, 0, 0);
        player.location.setYaw(90);
        charge(tornado, 1000);
        assertTrue(tornado.launch());
        assertEquals(0, anchor.distance(tornado.getLocation()), 1e-9);
        tornado.progress();
        assertEquals(anchor.getX() - 0.35, tornado.getLocation().getX(), 1e-9);
        assertEquals(anchor.getZ(), tornado.getLocation().getZ(), 1e-9);
    }

    @Test void firstCapturedOpponentIsPulledForScaledDurationThenConsumesTornado() throws Exception {
        ConfigManager.getConfig().set("Abilities.Air.Tornado.TrappedAbilityCooldown", 0);
        Tornado tornado = tornado();
        charge(tornado, 6000);
        player.sneaking = false;
        tornado.progress();
        tornado.progress();
        assertEquals(20, world.displays.size());
        Location center = tornado.getLocation().clone();
        target(center.clone().add(1, 0, 0));
        target(center.clone().add(-1, 0, 0));
        tornado.progress();
        assertFalse(tornado.isRemoved(), "capture starts the timed pull");
        assertEquals(1, pulledTargets.size(), "only the first opponent is captured");
        Player caught = (Player) get(tornado, "capturedPlayer");
        assertSame(pulledTargets.getFirst(), caught);
        assertEquals(2000, number(tornado, "maxPullDuration"));
        put(tornado, "capturedAt", System.currentTimeMillis());
        tornado.progress();
        assertFalse(tornado.isRemoved());
        assertEquals(2, pulledTargets.size());
        assertSame(caught, pulledTargets.getLast());
        // Remaining active lifetime must not cut short a pull that already started.
        put(tornado, "time", 0L);
        tornado.progress();
        assertFalse(tornado.isRemoved());
        put(tornado, "capturedAt", System.currentTimeMillis() - 2001);
        tornado.progress();
        assertTrue(tornado.isRemoved());
        assertNull(get(tornado, "capturedPlayer"));
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
        assertTrue(map(tornado, "caughtEntities").isEmpty());
        int hits = pulledTargets.size();
        tornado.progress();
        assertEquals(hits, pulledTargets.size());
    }

    @Test void playerOutsidePullZoneDoesNotConsumeTornado() throws Exception {
        Tornado tornado = tornado();
        charge(tornado, 500);
        player.sneaking = false;
        tornado.progress();
        target(tornado.getLocation().clone().add(4, 0, 0));
        tornado.progress();
        assertFalse(tornado.isRemoved());
        assertTrue(pulledTargets.isEmpty());
    }

    @Test void quickChargeUsesMinimumPullAndEscapedVictimEndsTheVortex() throws Exception {
        ConfigManager.getConfig().set("Abilities.Air.Tornado.TrappedAbilityCooldown", 0);
        ConfigManager.getConfig().set("Abilities.Air.Tornado.MinimumPullDuration", 300);
        ConfigManager.getConfig().set("Abilities.Air.Tornado.MaxPullDuration", 2500);
        Tornado tornado = tornado();
        charge(tornado, 500);
        player.sneaking = false;
        tornado.progress();
        assertEquals(300, number(tornado, "maxPullDuration"));
        target(tornado.getLocation().clone().add(1, 0, 0));
        tornado.progress();
        assertFalse(tornado.isRemoved());
        ((AbilityWorld.TestPlayer) targets.getFirst()).location.add(20, 0, 0);
        tornado.progress();
        assertTrue(tornado.isRemoved(), "do not retain or recapture players after the captured target escapes");
        assertNull(get(tornado, "capturedPlayer"));
    }

    private void target(Location location) {
        Player target = world.player(location.getX(), location.getY(), location.getZ());
        targets.add(target);
        BendingPlayer.getPlayers().put(target.getUniqueId(), new BendingPlayer(target));
    }

    private Tornado tornado() throws Exception {
        Tornado tornado = new TestTornado(player);
        abilities.add(tornado);
        var constructor = FlightHandler.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Field field = CoreAbility.class.getDeclaredField("flightHandler");
        field.setAccessible(true);
        field.set(tornado, constructor.newInstance());
        return tornado;
    }

    private static void charge(Tornado tornado, long milliseconds) throws Exception {
        put(tornado, "chargedDuration", milliseconds);
        // The next update initializes the clock, avoiding timing-dependent assertions.
        put(tornado, "lastChargeUpdateTime", 0L);
    }

    private static Object get(Tornado tornado, String name) throws Exception {
        Field field = Tornado.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(tornado);
    }

    private static void put(Tornado tornado, String name, Object value) throws Exception {
        Field field = Tornado.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tornado, value);
    }

    private static double number(Tornado tornado, String name) throws Exception {
        return ((Number) get(tornado, name)).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> map(Tornado tornado, String name) throws Exception {
        return (Map<UUID, Object>) get(tornado, name);
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> set(Tornado tornado, String name) throws Exception {
        return (Set<UUID>) get(tornado, name);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Object> attributes() throws Exception {
        Field field = CoreAbility.class.getDeclaredField("ATTRIBUTE_FIELDS");
        field.setAccessible(true);
        return (Map<Class<?>, Object>) field.get(null);
    }

    private static final class TestTornado extends Tornado {
        private final List<Location> particles = new ArrayList<>();
        private TestTornado(Player player) { super(player); }
        @Override public boolean isEnabled() { return false; }
        @Override public void start() { }
        @Override public void playAirbendingParticles(Location location, int count,
                double xOffset, double yOffset, double zOffset, double speed) { particles.add(location.clone()); }
    }
}
