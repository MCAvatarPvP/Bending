package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.activation.ActivationContext;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityStartEvent;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKPlayers;
import com.projectkorra.projectkorra.platform.PKScheduler;
import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EarthShellTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 64, 0.5);
    private final Map<Entity, Vector> impulses = new HashMap<>();
    private final Map<Entity, Integer> hitCounts = new HashMap<>();
    private final List<ScheduledBurst> bursts = new ArrayList<>();
    private final List<BendingPlayer> targets = new ArrayList<>();
    private Map<Class<?>, Object> attributes;
    private Map<Class<?>, Object> managers;
    private Object previousAttributes;
    private Object previousFlight;
    private boolean hadFlight;
    private Set<String> earthMaterials;
    private boolean hadStone;
    private boolean holes;
    private boolean canBend = true;
    private boolean cooldown;
    private boolean cancelStart;
    private int cooldowns;
    private BendingPlayer bender;
    private EarthShell descriptor;
    private String bound = "EarthBlast";
    private Map<String, CoreAbility> registry;
    private final Map<String, CoreAbility> previousRegistry = new HashMap<>();
    private ComboManager.ComboAbilityInfo previousCombo;
    private Map<String, ArrayList<ComboManager.AbilityInformation>> histories;
    private ArrayList<ComboManager.AbilityInformation> previousHistory;
    private Object previousShockwaveAttributes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        previousAttributes = attributes.put(EarthShell.class, new HashMap<>());
        previousShockwaveAttributes = attributes.put(Shockwave.class, new HashMap<>());
        managers = (Map<Class<?>, Object>) field(Manager.class, "MANAGERS").get(null);
        hadFlight = managers.containsKey(FlightHandler.class);
        previousFlight = managers.put(FlightHandler.class, null);
        earthMaterials = (Set<String>) field(ElementalAbility.class, "EARTH_BLOCKS").get(null);
        hadStone = !earthMaterials.add("STONE");
        player.sneaking = true;
        player.onGround = true;
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
        }
        bender = new BendingPlayer(player) {
            @Override public boolean canBend(CoreAbility ability) { return canBend && !cooldown; }
            @Override public boolean canBendIgnoreBinds(CoreAbility ability) { return canBend && !cooldown; }
            @Override public boolean canBendIgnoreBindsCooldowns(CoreAbility ability) { return canBend; }
            @Override public boolean canBendIgnoreCooldowns(CoreAbility ability) { return canBend; }
            @Override public boolean canCurrentlyBendWithWeapons() { return true; }
            @Override public CoreAbility getBoundAbility() { return registry.get(bound.toLowerCase()); }
            @Override public String getBoundAbilityName() { return bound; }
            @Override public boolean areSourceHolesOn() { return holes; }
            @Override public void addCooldown(Ability ability) { EarthShellTest.this.cooldowns++; cooldown = true; }
        };
        BendingPlayer.getPlayers().put(player.getUniqueId(), bender);
        final PKEventBus events = new PKEventBus() {
            @Override public void call(Object event) {
                if (event instanceof AbilityDamageEntityEvent) fail("EarthShell must never deal damage");
                if (event instanceof AbilityStartEvent start && cancelStart) start.setCancelled(true);
                if (event instanceof AbilityVelocityAffectEntityEvent velocity) {
                    assertTrue(TempBlock.getActiveLayers().isEmpty(), "restore the dome before pushing entities");
                    impulses.put(velocity.getAffected(), velocity.getVelocity().clone());
                    hitCounts.merge(velocity.getAffected(), 1, Integer::sum);
                }
            }
            @Override public void registerListener(Object listener) { }
            @Override public void unregisterAll(Object listener) { }
        };
        final PKPlayers players = (PKPlayers) Proxy.newProxyInstance(PKPlayers.class.getClassLoader(),
                new Class<?>[]{PKPlayers.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onlinePlayers" -> List.of();
                    case "isExternalSpectator", "isBedrockPlayer" -> false;
                    default -> null;
                });
        final ProjectKorraPlatform delegate = Platform.current();
        final PKScheduler scheduler = (PKScheduler) Proxy.newProxyInstance(PKScheduler.class.getClassLoader(),
                new Class<?>[]{PKScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPrimaryThread")) return true;
                    if (method.getName().equals("runTimer")) {
                        ScheduledBurst burst = new ScheduledBurst((Runnable) args[0]);
                        bursts.add(burst);
                        return burst;
                    }
                    throw new AssertionError(method);
                });
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "events" -> events;
                    case "players" -> players;
                    case "scheduler" -> scheduler;
                    default -> method.invoke(delegate, args);
                }));
        descriptor = new EarthShell(null);
        registry = (Map<String, CoreAbility>) field(CoreAbility.class, "ABILITIES_BY_NAME").get(null);
        for (String name : List.of("Shockwave", "EarthBlast", "RaiseEarth")) {
            previousRegistry.put(name.toLowerCase(), registry.put(name.toLowerCase(), new Component(name)));
        }
        previousRegistry.put("earthshell", registry.put("earthshell", descriptor));
        previousCombo = ComboManager.getComboAbilities().put("EarthShell",
                new ComboManager.ComboAbilityInfo("EarthShell", descriptor.getCombination(), descriptor));
        histories = (Map<String, ArrayList<ComboManager.AbilityInformation>>) field(ComboManager.class, "RECENTLY_USED").get(null);
        previousHistory = histories.remove(player.getName());
        RegionProtection.clearCache();
    }

    @AfterEach
    void cleanup() throws Exception {
        for (EarthShell shell : CoreAbility.getAbilities(player, EarthShell.class)) shell.remove();
        for (Shockwave wave : CoreAbility.getAbilities(player, Shockwave.class)) wave.remove();
        TempBlock.removeAll();
        RegionProtection.unloadPlugin("EarthShellTest");
        RegionProtection.clearCache();
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        for (BendingPlayer target : targets) {
            for (AirBlast blast : CoreAbility.getAbilities(target.getPlayer(), AirBlast.class)) blast.remove();
            for (AirScooter scooter : CoreAbility.getAbilities(target.getPlayer(), AirScooter.class)) scooter.remove();
            BendingPlayer.getPlayers().remove(target.getUUID());
        }
        if (previousAttributes == null) attributes.remove(EarthShell.class);
        else attributes.put(EarthShell.class, previousAttributes);
        if (previousShockwaveAttributes == null) attributes.remove(Shockwave.class);
        else attributes.put(Shockwave.class, previousShockwaveAttributes);
        previousRegistry.forEach((name, ability) -> {
            if (ability == null) registry.remove(name);
            else registry.put(name, ability);
        });
        if (previousCombo == null) ComboManager.getComboAbilities().remove("EarthShell");
        else ComboManager.getComboAbilities().put("EarthShell", previousCombo);
        if (previousHistory == null) histories.remove(player.getName());
        else histories.put(player.getName(), previousHistory);
        if (hadFlight) managers.put(FlightHandler.class, previousFlight);
        else managers.remove(FlightHandler.class);
        if (!hadStone) earthMaterials.remove("STONE");
        world.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void formsClosedDomeAndOnlyExcavatesWithSourceHolesEnabled(boolean enabled) {
        holes = enabled;
        EarthShell shell = form();
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.AIR, world.getBlockAt(0, 65, 0).getType());
        assertEquals(Material.STONE, world.getBlockAt(0, 66, 0).getType());
        for (int y = 64; y <= 65; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || z != 0) assertEquals(Material.STONE, world.getBlockAt(x, y, z).getType());
                }
            }
        }
        assertEquals(enabled ? 21 : 0, groundHoles());
        assertEquals(Material.STONE, world.getBlockAt(0, 63, 0).getType());
        shell.remove();
        assertRestored();
        assertEquals(1, cooldowns);
        shell.remove();
        assertEquals(1, cooldowns);
    }

    @Test void shellExpandsEnoughAtBlockBoundariesToKeepCasterClear() {
        player.location.setX(0.95);
        player.location.setZ(0.95);
        holes = true;
        form();
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                assertEquals(Material.AIR, world.getBlockAt(x, 65, z).getType());
                assertEquals(Material.STONE, world.getBlockAt(x, 66, z).getType());
                assertEquals(Material.STONE, world.getBlockAt(x, 63, z).getType());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 0.5, 1.0, 1.25, 2.0})
    void airborneCastBuildsFromGroundToAboveThePlayerAndAllowsLanding(double height) {
        player.location.setY(64 + height);
        player.onGround = false;
        int roofY = (int) Math.ceil(player.location.getY() + 1.8);
        EarthShell shell = new EarthShell(player);
        assertTrue(shell.isStarted());
        for (int tick = 0; tick < 10; tick++) shell.progress();
        assertFalse(shell.isRemoved());
        for (int y = 64; y < roofY; y++) {
            assertEquals(Material.STONE, world.getBlockAt(1, y, 0).getType(), "walls must start at the ground");
            assertEquals(Material.AIR, world.getBlockAt(0, y, 0).getType(), "keep the caster's landing column clear");
        }
        assertEquals(Material.STONE, world.getBlockAt(0, roofY, 0).getType());
        assertEquals(0, groundHoles());
        player.location.setY(64);
        player.onGround = true;
        shell.progress();
        assertFalse(shell.isRemoved(), "landing inside the taller dome must not cancel it");
        shell.release();
        tickBursts(32);
        assertRestored();
        assertEquals(Material.AIR, world.getBlockAt(0, roofY, 0).getType());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tallerDomeStillRespectsSourceHolesAndRestoresOnExpiry(boolean sourceHoles) throws Exception {
        holes = sourceHoles;
        player.location.setY(66);
        player.onGround = false;
        EarthShell shell = new EarthShell(player);
        assertTrue(shell.isStarted());
        for (int tick = 0; tick < 10; tick++) shell.progress();
        assertEquals(sourceHoles ? 37 : 0, groundHoles());
        field(CoreAbility.class, "startTime").setLong(shell, System.currentTimeMillis() - 9000);
        shell.progress();
        assertTrue(shell.isRemoved());
        assertRestored();
        assertEquals(Material.AIR, world.getBlockAt(0, 68, 0).getType());
    }

    @Test void moreThanTwoBlocksAboveGroundCannotStartTheShell() {
        player.location.setY(66.01);
        player.onGround = false;
        assertFalse(new EarthShell(player).isStarted());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
        assertEquals(0, cooldowns);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ICE", "WATER"})
    void airborneCastCannotSourceThroughANonEarthFloorOrWater(String material) {
        player.location.setY(66);
        player.onGround = false;
        world.getBlockAt(0, 64, 0).setType(Material.valueOf(material));
        assertFalse(new EarthShell(player).isStarted());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
        assertEquals(0, cooldowns);
    }

    @Test void maximumHeightAboveGroundIsConfigurable() {
        ConfigManager.getConfig().set("Abilities.Earth.EarthShell.MaxHeightAboveGround", 0.5);
        player.location.setY(64.51);
        player.onGround = false;
        assertFalse(new EarthShell(player).isStarted());
        player.location.setY(64.5);
        EarthShell shell = new EarthShell(player);
        assertTrue(shell.isStarted());
        shell.remove();
    }

    @Test void releaseRestoresImmediatelyButOnlyTravelingShardsPushTargets() {
        var east = world.player(3.5, 64, 0.5);
        var west = world.player(-2.5, 64, 0.5);
        var far = world.player(14.5, 64, 0.5);
        var above = world.player(0.5, 68, 0.5);
        holes = true;
        bound = "Shockwave";
        ComboManager.addComboAbility(player, ClickType.SHIFT_DOWN);
        assertNull(CoreAbility.getAbility(player, EarthShell.class));
        Shockwave charge = new Shockwave(player, false);
        assertTrue(charge.isStarted());
        bound = "EarthBlast";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        EarthShell shell = CoreAbility.getAbility(player, EarthShell.class);
        assertNotNull(shell);
        assertTrue(charge.isRemoved(), "consume the normal Shockwave charge");
        for (int tick = 0; tick < 6; tick++) shell.progress();
        // The input event can still expose the old crouch state.
        assertTrue(player.isSneaking());
        CommonInputHandler.handleSneak(player, true);
        assertTrue(shell.isRemoved());
        assertRestored();
        assertTrue(impulses.isEmpty(), "release must not hit the entire range instantly");
        tickBursts(32);
        assertEquals(4, impulses.get(east).getX(), 1e-9);
        assertEquals(-4, impulses.get(west).getX(), 1e-9);
        assertEquals(0, impulses.get(east).getY(), 1e-9);
        assertFalse(impulses.containsKey(player));
        assertFalse(impulses.containsKey(far));
        assertFalse(impulses.containsKey(above));
        shell.release();
        shell.progress();
        assertEquals(2, impulses.size());
        assertTrue(hitCounts.values().stream().allMatch(count -> count == 1));
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
        assertEquals(1, cooldowns);
        assertFalse(new EarthShell(player).isStarted());
    }

    @Test void releaseDuringFormationAlsoRestoresAllTerrain() {
        holes = true;
        var target = world.player(3.5, 64, 0.5);
        EarthShell shell = new EarthShell(player);
        player.sneaking = false;
        shell.progress();
        assertTrue(shell.isRemoved());
        assertRestored();
        tickBursts(32);
        assertTrue(impulses.containsKey(target));
    }

    @Test void shardsReachDistantTargetsOnlyAfterTravelAndApplyAirCooldownsOnce() {
        var target = world.player(10.5, 64, 0.5);
        var air = targetBender(target, Element.AIR);
        var betweenShards = world.player(8.5, 64, -3.5);
        var betweenShardsAir = targetBender(betweenShards, Element.AIR);
        EarthShell shell = form();
        shell.release();
        assertFalse(world.displays.isEmpty());
        assertTrue(air.getCooldowns().isEmpty());
        tickBursts(4);
        assertTrue(impulses.isEmpty(), "distant targets must wait for the shards");
        long beforeContact = System.currentTimeMillis();
        tickBursts(28);
        assertEquals(4, impulses.get(target).getX(), 1e-9);
        assertEquals(1, hitCounts.get(target));
        assertEquals(Set.of("AirBlast", "AirScooter"), air.getCooldowns().keySet());
        for (Cooldown cooldown : air.getCooldowns().values()) {
            assertTrue(cooldown.getCooldown() >= beforeContact + 2990);
            assertTrue(cooldown.getCooldown() <= System.currentTimeMillis() + 3000);
        }
        assertTrue(impulses.containsKey(betweenShards), "the wider shard fan should cover this former gap");
        assertTrue(betweenShardsAir.isOnCooldown("AirBlast"));
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
        assertTrue(bursts.stream().allMatch(PKTask::cancelled));
    }

    @Test void cooldownsOnlyAffectAirbendersAndNeverShortenExistingLocks() {
        var airTarget = world.player(3.5, 64, 0.5);
        var earthTarget = world.player(-2.5, 64, 0.5);
        var air = targetBender(airTarget, Element.AIR);
        var earth = targetBender(earthTarget, Element.EARTH);
        long existing = System.currentTimeMillis() + 10000;
        air.getCooldowns().put("AirBlast", new Cooldown(existing, false));
        air.getCooldowns().put("AirScooter", new Cooldown(existing, false));
        form().release();
        tickBursts(32);
        assertTrue(impulses.containsKey(airTarget));
        assertTrue(impulses.containsKey(earthTarget));
        assertTrue(earth.getCooldowns().isEmpty());
        assertEquals(existing, air.getCooldowns().get("AirBlast").getCooldown());
        assertEquals(existing, air.getCooldowns().get("AirScooter").getCooldown());
    }

    @Test void contactsInterruptActiveAirMovesAndPreserveTheirLongerCooldowns() throws Exception {
        Object blastAttributes = attributes.put(AirBlast.class, new HashMap<>());
        Object scooterAttributes = attributes.put(AirScooter.class, new HashMap<>());
        try {
            var constructor = FlightHandler.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            managers.put(FlightHandler.class, constructor.newInstance());
            ConfigManager.getConfig().set("Abilities.Air.AirSurf.StaminaDrainRate", 0);
            ConfigManager.getConfig().set("Abilities.Air.AirScooter.StaminaDrainRate", 0);
            var target = world.player(3.5, 64, 0.5);
            var air = targetBender(target, Element.AIR);
            var blast = new AirBlast(target, target.getEyeLocation(), new Vector(1, 0, 0), 1, null);
            var scooter = new AirScooter(target);
            assertTrue(blast.isStarted());
            assertTrue(scooter.isStarted());
            long existing = System.currentTimeMillis() + 10000;
            air.getCooldowns().put("AirScooter", new Cooldown(existing, false));
            form().release();
            tickBursts(32);
            assertTrue(blast.isRemoved());
            assertTrue(scooter.isRemoved());
            assertTrue(air.isOnCooldown("AirBlast"));
            assertTrue(air.getCooldowns().get("AirScooter").getCooldown() >= existing);
        } finally {
            if (blastAttributes == null) attributes.remove(AirBlast.class);
            else attributes.put(AirBlast.class, blastAttributes);
            if (scooterAttributes == null) attributes.remove(AirScooter.class);
            else attributes.put(AirScooter.class, scooterAttributes);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 5000})
    void airLockDurationIsConfigurable(long duration) {
        ConfigManager.getConfig().set("Abilities.Earth.EarthShell.AirStunDuration", duration);
        var target = world.player(3.5, 64, 0.5);
        var air = targetBender(target, Element.AIR);
        form().release();
        long beforeContact = System.currentTimeMillis();
        tickBursts(32);
        assertTrue(impulses.containsKey(target));
        if (duration == 0) assertTrue(air.getCooldowns().isEmpty());
        else for (String ability : List.of("AirBlast", "AirScooter")) {
            assertTrue(air.getCooldowns().get(ability).getCooldown() >= beforeContact + duration - 10);
            assertTrue(air.getCooldowns().get(ability).getCooldown() <= System.currentTimeMillis() + duration);
        }
    }

    @Test void aTargetCanDodgeBeforeTheShardsArrive() {
        var target = world.player(10.5, 64, 0.5);
        form().release();
        tickBursts(4);
        target.location.setZ(15);
        tickBursts(28);
        assertFalse(impulses.containsKey(target));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.2, 0.85})
    void widerShardHitboxesCatchGlancingContactsButLeaveUntraveledDirectionsUntouched(double hitRadius) {
        var glancing = world.player(3.5, 64, 2.0);
        var awayFromShards = world.player(-2.5, 64, 0.5);
        Set<Entity> contacts = new HashSet<>();
        EarthShellBurst.play(player, player.getLocation(), List.of(new EarthShellBurst.Piece(
                new Location(world, 1.5, 64.5, 0.5), Material.STONE.createBlockData())), 12, hitRadius,
                (target, location) -> contacts.add(target));
        assertTrue(contacts.isEmpty());
        tickBursts(32);
        assertEquals(hitRadius == 0.85, contacts.contains(glancing));
        assertFalse(contacts.contains(awayFromShards), "there must be no circular hit front");
    }

    @Test void removingAllShardsLeavesNoInvisibleAreaHit() {
        var target = world.player(3.5, 64, 0.5);
        form().release();
        world.displays.forEach(Entity::remove);
        tickBursts(32);
        assertFalse(impulses.containsKey(target));
        assertTrue(bursts.stream().allMatch(PKTask::cancelled));
    }

    @Test void enteringTheInteriorAfterTheShardsPassDoesNotCauseALateHit() {
        var target = world.player(20.5, 64, 0.5);
        form().release();
        tickBursts(8);
        target.location.setX(3.5);
        tickBursts(24);
        assertFalse(impulses.containsKey(target));
    }

    @Test void widerShardHitboxesRespectTheConfiguredRange() {
        ConfigManager.getConfig().set("Abilities.Earth.EarthShell.BurstRange", 5);
        var touchingRange = world.player(5.7, 64, 0.5);
        var outsideRange = world.player(-4.9, 64, 0.5);
        form().release();
        tickBursts(32);
        assertTrue(impulses.containsKey(touchingRange), "a body in a shard's path at the range boundary must be hit");
        assertFalse(impulses.containsKey(outsideRange), "do not hit a body completely outside the configured range");
    }

    @Test void changingWorldsCleansUpAllShardsAndTheirTask() {
        form().release();
        tickBursts(2);
        player.location = new Location(new World(), 0.5, 64, 0.5);
        tickBursts(1);
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
        assertTrue(bursts.stream().allMatch(PKTask::cancelled));
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "teleport", "world", "timeout"})
    void cancellationRestoresTerrainWithoutBurst(String reason) throws Exception {
        holes = true;
        world.player(3.5, 64, 0.5);
        EarthShell shell = form();
        switch (reason) {
            case "switch" -> bound = "RaiseEarth";
            case "teleport" -> player.location.setX(20);
            case "world" -> player.location = new Location(new World(), 0.5, 64, 0.5);
            case "timeout" -> field(CoreAbility.class, "startTime").setLong(shell, System.currentTimeMillis() - 9000);
        }
        shell.progress();
        assertTrue(shell.isRemoved());
        assertRestored();
        assertTrue(impulses.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(longs = {4000, 20000})
    void configuredDurationEndsHeldShellAndStartsCooldown(long duration) throws Exception {
        ConfigManager.getConfig().set("Abilities.Earth.EarthShell.Duration", duration);
        holes = true;
        world.player(3.5, 64, 0.5);
        EarthShell shell = form();
        Field startedAt = field(CoreAbility.class, "startTime");
        startedAt.setLong(shell, System.currentTimeMillis() - duration + 2000);
        shell.progress();
        assertFalse(shell.isRemoved(), "keep the dome until its configured limit");
        assertTrue(player.isSneaking());
        assertEquals(0, cooldowns);

        startedAt.setLong(shell, System.currentTimeMillis() - duration);
        shell.progress();
        assertTrue(shell.isRemoved(), "holding sneak must not extend the duration");
        assertRestored();
        assertTrue(impulses.isEmpty());
        assertEquals(1, cooldowns);
        assertFalse(new EarthShell(player).isStarted(), "expiration must prevent immediate recasting");
        shell.release();
        assertTrue(impulses.isEmpty(), "release after expiration must not burst");
        assertEquals(1, cooldowns);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "cooldown", "noEarth", "occupied", "protected", "cancelled"})
    void invalidCastsDoNotChangeTerrainOrSpendCooldown(String reason) {
        switch (reason) {
            case "disabled" -> ConfigManager.getConfig().set("Abilities.Earth.EarthShell.Enabled", false);
            case "cooldown" -> cooldown = true;
            case "noEarth" -> world.getBlockAt(0, 63, 0).setType(Material.AIR);
            case "occupied" -> world.player(1.5, 64, 0.5);
            case "protected" -> RegionProtection.registerRegionProtection("EarthShellTest", (p, loc, ability) -> loc.getBlockX() == 1);
            case "cancelled" -> cancelStart = true;
        }
        EarthShell shell = new EarthShell(player);
        assertFalse(shell.isStarted());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
        assertEquals(0, cooldowns);
    }

    @Test void targetsBehindWallsOrInProtectedRegionsAreNotPushed() {
        var shielded = world.player(3.5, 64, 0.5);
        var protectedTarget = world.player(-3.5, 64, 0.5);
        var shieldedAir = targetBender(shielded, Element.AIR);
        var protectedAir = targetBender(protectedTarget, Element.AIR);
        EarthShell shell = form();
        world.getBlockAt(2, 64, 0).setType(Material.STONE);
        world.getBlockAt(2, 65, 0).setType(Material.STONE);
        RegionProtection.registerRegionProtection("EarthShellTest", (p, loc, ability) -> loc.getBlockX() <= -3);
        RegionProtection.clearCache();
        shell.release();
        tickBursts(32);
        assertFalse(impulses.containsKey(shielded));
        assertFalse(impulses.containsKey(protectedTarget));
        assertTrue(shieldedAir.getCooldowns().isEmpty());
        assertTrue(protectedAir.getCooldowns().isEmpty());
    }

    @Test void configurationChangesTheTwoMoveCombo() {
        ConfigManager.getConfig().set("Abilities.Earth.EarthShell.Combination",
                List.of("RaiseEarth:LEFT_CLICK", "Shockwave:SHIFT_DOWN"));
        var combination = descriptor.getCombination();
        assertEquals(2, combination.size());
        ComboManager.getComboAbilities().put("EarthShell",
                new ComboManager.ComboAbilityInfo("EarthShell", combination, descriptor));
        bound = "Shockwave";
        ComboManager.addComboAbility(player, ClickType.SHIFT_DOWN);
        bound = "EarthBlast";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        assertNull(CoreAbility.getAbility(player, EarthShell.class), "old inputs must no longer create the shell");
        bound = "RaiseEarth";
        ComboManager.addComboAbility(player, ClickType.LEFT_CLICK);
        bound = "Shockwave";
        ComboManager.addComboAbility(player, ClickType.SHIFT_DOWN);
        EarthShell shell = CoreAbility.getAbility(player, EarthShell.class);
        assertNotNull(shell);
        shell.progress();
        assertFalse(shell.isRemoved(), "keep holding on the new final component");
        CommonInputHandler.handleSneak(player, true);
        assertTrue(shell.isRemoved());
    }

    @Test void standaloneSneakCannotActivateEarthShell() {
        bound = "EarthShell";
        AbilityActivationManager.discover(descriptor);
        assertFalse(AbilityActivationManager.dispatchBoundAbility(new ActivationContext(player, ClickType.SHIFT_DOWN)));
        assertNull(CoreAbility.getAbility(player, EarthShell.class));
    }

    @Test void realBendingChecksAndCommonInputDispatchCreateTheCombo() {
        BendingPlayer actual = new BendingPlayer(player) {
            @Override public boolean canCurrentlyBendWithWeapons() { return true; }
            @Override public void addCooldown(Ability ability) { EarthShellTest.this.cooldowns++; }
        };
        actual.getElements().add(Element.EARTH);
        actual.getAbilities().put(1, "Shockwave");
        actual.getAbilities().put(2, "EarthBlast");
        BendingPlayer.getPlayers().put(player.getUniqueId(), actual);
        assertTrue(actual.canBendIgnoreBinds(descriptor), "real player eligibility must permit the combo");
        CommonInputHandler.handleSneak(player, false);
        assertEquals("Shockwave", ComboManager.getRecentlyUsedAbilities(player, 2).getLast().getAbilityName());
        player.getInventory().setHeldItemSlot(1);
        CommonInputHandler.handleSwing(player, new HashSet<>(), new HashSet<>());
        assertNotNull(ComboManager.checkForValidCombo(player), () -> ComboManager.getRecentlyUsedAbilities(player, 2).toString());
        assertNotNull(CoreAbility.getAbility(player, EarthShell.class));
        BendingPlayer.getPlayers().put(player.getUniqueId(), bender);
    }

    @Test void grassAndFlowersDoNotBlockTheDomeAndRestoreAfterward() {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) world.getBlockAt(x, 64, z).setType(Material.SHORT_GRASS);
        }
        world.getBlockAt(1, 64, 0).setType(Material.POPPY);
        EarthShell shell = form();
        shell.remove();
        assertEquals(Material.POPPY, world.getBlockAt(1, 64, 0).getType());
        assertEquals(Material.SHORT_GRASS, world.getBlockAt(-1, 64, 0).getType());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
    }

    @Test void noSourceHolesDoesNotRequireOneExposedSourcePerShellBlock() {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (Math.abs(x) > 1 || Math.abs(z) > 1) world.getBlockAt(x, 63, z).setType(Material.AIR);
            }
        }
        EarthShell shell = form();
        assertEquals(0, groundHoles());
        shell.remove();
        assertEquals(Material.STONE, world.getBlockAt(1, 63, 0).getType());
    }

    @Test void nearbyEarthWallIsKeptAsPartOfTheDome() {
        world.getBlockAt(1, 65, 0).setType(Material.STONE);
        EarthShell shell = form();
        shell.remove();
        assertEquals(Material.STONE, world.getBlockAt(1, 65, 0).getType());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void formsWhilePressedAgainstNonEarthWallsInACorner(boolean sourceHoles) {
        holes = sourceHoles;
        player.location.setX(0.7);
        player.location.setZ(0.7);
        for (int y = 64; y <= 67; y++) {
            for (int side = -3; side <= 3; side++) {
                world.getBlockAt(1, y, side).setType(Material.ICE);
                world.getBlockAt(side, y, 1).setType(Material.ICE);
            }
        }
        EarthShell shell = form();
        assertEquals(Material.STONE, world.getBlockAt(-1, 65, 0).getType());
        assertEquals(Material.STONE, world.getBlockAt(0, 65, -1).getType());
        assertEquals(Material.STONE, world.getBlockAt(0, 66, 0).getType());
        assertTrue(TempBlock.getActiveLayers().stream().filter(block -> block.getBlock().getY() >= 64)
                .allMatch(block -> block.getBlock().getX() < 1 && block.getBlock().getZ() < 1),
                "do not expand the shell through a wall the player is touching");
        if (!sourceHoles) assertEquals(0, groundHoles());
        shell.release();
        tickBursts(32);
        for (int y = 64; y <= 67; y++) {
            for (int side = -3; side <= 3; side++) {
                assertEquals(Material.ICE, world.getBlockAt(1, y, side).getType());
                assertEquals(Material.ICE, world.getBlockAt(side, y, 1).getType());
            }
        }
        assertTrue(TempBlock.getActiveLayers().isEmpty());
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) assertEquals(Material.STONE, world.getBlockAt(x, 63, z).getType());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingTemporaryWallKeepsItsOwnLifecycle(boolean sourceHoles) {
        holes = sourceHoles;
        var wallBlock = world.getBlockAt(1, 65, 0);
        TempBlock wall = new TempBlock(wallBlock, Material.STONE);
        EarthShell shell = form();
        assertEquals(List.of(wall), TempBlock.getAll(wallBlock));
        shell.release();
        tickBursts(32);
        assertFalse(wall.isReverted());
        assertEquals(Material.STONE, wallBlock.getType());
        assertEquals(List.of(wall), TempBlock.getAll(wallBlock));
        wall.revertBlock();
        assertEquals(Material.AIR, wallBlock.getType());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
    }

    private EarthShell form() {
        EarthShell shell = new EarthShell(player);
        assertTrue(shell.isStarted(), "the shell should activate on clear earth");
        for (int tick = 0; tick < 6; tick++) shell.progress();
        assertFalse(shell.isRemoved());
        return shell;
    }

    private void tickBursts(int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            for (ScheduledBurst burst : List.copyOf(bursts)) if (!burst.cancelled) burst.runnable.run();
        }
    }

    private BendingPlayer targetBender(AbilityWorld.TestPlayer target, Element element) {
        BendingPlayer bending = new BendingPlayer(target) {
            @Override public void addCooldown(String ability, long duration, boolean database) {
                getCooldowns().put(ability, new Cooldown(System.currentTimeMillis() + duration, database));
            }
        };
        bending.getElements().add(element);
        BendingPlayer.getPlayers().put(target.getUniqueId(), bending);
        targets.add(bending);
        return bending;
    }

    private static final class ScheduledBurst implements PKTask {
        private final Runnable runnable;
        private boolean cancelled;
        private ScheduledBurst(Runnable runnable) { this.runnable = runnable; }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
        @Override public int legacyId() { return 0; }
    }

    private long groundHoles() {
        return TempBlock.getActiveLayers().stream().filter(block -> block.getBlock().getY() == 63).count();
    }

    private void assertRestored() {
        assertTrue(TempBlock.getActiveLayers().isEmpty());
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) assertEquals(Material.STONE, world.getBlockAt(x, 63, z).getType());
        }
        for (int y = 64; y <= 66; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) assertEquals(Material.AIR, world.getBlockAt(x, y, z).getType());
            }
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Component extends EarthAbility {
        private final String name;
        private Component(String name) { super(null); this.name = name; }
        @Override public String getName() { return name; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return true; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public Location getLocation() { return null; }
    }
}
