package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.FlightHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EarthBlastRedirectTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer shooter = world.player(0.5, 64, 12.5);
    private final AbilityWorld.TestPlayer controller = world.player(0.5, 64, 0.5);
    private final List<Runnable> restores = new ArrayList<>();
    private final List<AbilityDamageEntityEvent> hits = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        backup(CoreAbility.class, "ATTRIBUTE_FIELDS");
        backup(CoreAbility.class, "ABILITIES_BY_NAME");
        backup(Manager.class, "MANAGERS");
        backup(BlockSource.class, "playerSources");
        map(CoreAbility.class, "ATTRIBUTE_FIELDS").put(EarthBlast.class, new HashMap<>());
        map(Manager.class, "MANAGERS").put(FlightHandler.class, null);
        @SuppressWarnings("unchecked") Set<String> earth = (Set<String>) field(ElementalAbility.class, "EARTH_BLOCKS").get(null);
        Set<String> previousEarth = new HashSet<>(earth);
        earth.addAll(List.of("STONE", "COBBLESTONE"));
        restores.add(() -> { earth.clear(); earth.addAll(previousEarth); });
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class},
                (p, m, args) -> {
                    if (m.getName().equals("call") && args[0] instanceof AbilityDamageEntityEvent hit) {
                        hits.add(hit);
                        hit.setCancelled(true);
                    }
                    return null;
                });
        PKPlayers players = (PKPlayers) Proxy.newProxyInstance(PKPlayers.class.getClassLoader(), new Class<?>[]{PKPlayers.class},
                (p, m, args) -> m.getReturnType() == boolean.class ? false : List.of());
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (p, m, args) -> switch (m.getName()) {
                    case "events" -> events;
                    case "players" -> players;
                    default -> m.invoke(delegate, args);
                }));
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.Enabled", true);
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.Range", 30);
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.SelectRange", 10);
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.DeflectRange", 3);
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.CollisionRadius", 1.0);
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.Speed", 35);
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.Damage", 3);
        ConfigManager.getConfig().set("Properties.DamageMultiplier", 1);
        ConfigManager.getConfig().set("Properties.Earth.RevertEarthbending", false);
        for (AbilityWorld.TestPlayer player : List.of(shooter, controller)) {
            player.eyeHeight = 1.62;
            BendingPlayer bending = new BendingPlayer(player) {
                @Override public boolean canBend(CoreAbility ability) { return true; }
                @Override public boolean canBendIgnoreBindsCooldowns(CoreAbility ability) { return true; }
                @Override public String getBoundAbilityName() { return "EarthBlast"; }
                @Override public boolean canMetalbend() { return player.equals(shooter); }
                @Override public boolean canSandbend() { return true; }
                @Override public boolean canLavabend() { return false; }
            };
            BendingPlayer.getPlayers().put(player.getUniqueId(), bending);
        }
        shooter.location.setYaw(-90); // Original shooter looks east; controller looks south.
        RegionProtection.clearCache();
    }

    @AfterEach void cleanup() throws Exception {
        for (EarthBlast blast : CoreAbility.getAbilities(EarthBlast.class)) {
            if (blast.getPlayer().equals(shooter) || blast.getPlayer().equals(controller)) blast.remove();
        }
        BendingPlayer.getPlayers().remove(shooter.getUniqueId());
        BendingPlayer.getPlayers().remove(controller.getUniqueId());
        RegionProtection.unloadPlugin("EarthBlastRedirectTest");
        RegionProtection.clearCache();
        Collections.reverse(restores);
        restores.forEach(Runnable::run);
        world.close();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void configurationControlsWhetherPreparingEarthDestroysAnIncomingBlast(boolean fixed) throws Exception {
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.RedirectFix", fixed);
        EarthBlast incoming = launched();
        EarthBlast prepared = prepare(controller, world.getBlockAt(2, 63, 1));
        assertEquals(fixed, prepared.isStarted());
        assertEquals(!fixed, incoming.isRemoved());
        assertEquals(fixed ? Material.COBBLESTONE : Material.AIR, incoming.getSourceBlock().getType());
        if (fixed) {
            assertTrue(incoming.isProgressing());
            assertFalse(prepared.isRemoved());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void redirectUsesTheConfiguredAimAndOwnershipBehavior(boolean fixed) throws Exception {
        ConfigManager.getConfig().set("Abilities.Earth.EarthBlast.RedirectFix", fixed);
        EarthBlast blast = launched();
        Block block = blast.getSourceBlock();
        AbilityActivationManager.beginTracking();
        AbilityActivationManager.TrackingResult tracked;
        try {
            EarthBlast.throwEarth(controller);
        } finally {
            tracked = AbilityActivationManager.finishTrackingResult();
        }
        assertFalse(blast.isRemoved());
        assertSame(block, blast.getSourceBlock());
        assertEquals(Material.COBBLESTONE, block.getType());
        assertSame(fixed ? controller : shooter, blast.getPlayer());
        assertSame(BendingPlayer.getBendingPlayer(fixed ? controller : shooter), blast.getBendingPlayer());
        if (fixed) {
            assertEquals(controller.location.getX(), blast.getDestination().getX(), 0.01);
            assertTrue(blast.getDestination().getZ() > 10);
            assertTrue(tracked.affectedAbilities().contains(blast));
            assertTrue(CoreAbility.getAbilities(controller, EarthBlast.class).contains(blast));
            assertFalse(CoreAbility.getAbilities(shooter, EarthBlast.class).contains(blast));
        } else {
            assertTrue(blast.getDestination().getX() > 25);
            assertEquals(shooter.location.getZ(), blast.getDestination().getZ(), 0.01);
        }
    }

    @Test void defaultRedirectKeepsMovingAndCanHitItsOriginalShooter() throws Exception {
        EarthBlast blast = launched();
        Block previous = blast.getSourceBlock();
        EarthBlast.throwEarth(controller);
        tick(blast);
        assertFalse(blast.isRemoved());
        assertNotSame(previous, blast.getSourceBlock());
        assertEquals(Material.STONE, blast.getSourceBlock().getType());
        assertEquals(Material.AIR, previous.getType());
        for (int i = 0; i < 15 && !blast.isRemoved(); i++) tick(blast);
        assertTrue(blast.isRemoved());
        assertEquals(1, hits.size());
        assertSame(shooter, hits.getFirst().getEntity());
        assertSame(controller, hits.getFirst().getAbility().getPlayer());
    }

    @Test void ownBlastCanBeRedirectedWithoutBeingTargeted() throws Exception {
        EarthBlast blast = launched();
        shooter.location.setYaw(90);
        EarthBlast.throwEarth(shooter);
        assertFalse(blast.isRemoved());
        assertTrue(blast.getDestination().getX() < -25);
        tick(blast);
        assertFalse(blast.isRemoved());
    }

    @Test void redirectUsesTheControllersRegionPermission() throws Exception {
        EarthBlast blast = launched();
        Location destination = blast.getDestination().clone();
        RegionProtection.registerRegionProtection("EarthBlastRedirectTest", (p, location, ability) -> p.equals(controller));
        RegionProtection.clearCache();
        EarthBlast.throwEarth(controller);
        assertEquals(destination, blast.getDestination());
        assertSame(shooter, blast.getPlayer());
        assertFalse(blast.isRemoved());
    }

    @Test void outOfRangeBehindAndOtherWorldBlastsAreNotRedirected() throws Exception {
        EarthBlast blast = launched();
        Location destination = blast.getDestination().clone();
        for (Location position : List.of(new Location(world, 0, 64, -40),
                new Location(world, 0, 64, 20), new Location(world, 8, 64, 0),
                new Location(new World(), 0, 64, 0))) {
            controller.location = position;
            EarthBlast.throwEarth(controller);
            assertSame(shooter, blast.getPlayer());
            assertEquals(destination, blast.getDestination());
            assertFalse(blast.isRemoved());
        }
    }

    @Test void aFocusedMetalBlastCannotBeTakenByANonMetalbender() throws Exception {
        @SuppressWarnings("unchecked") Set<String> metals = (Set<String>) field(ElementalAbility.class, "METAL_BLOCKS").get(null);
        boolean alreadyMetal = !metals.add("IRON_BLOCK");
        try {
            EarthBlast blast = launched();
            blast.setSourcetype(Material.IRON_BLOCK);
            Location destination = blast.getDestination().clone();
            EarthBlast.throwEarth(controller);
            assertSame(shooter, blast.getPlayer());
            assertEquals(destination, blast.getDestination());
            assertFalse(blast.isRemoved());
        } finally {
            if (!alreadyMetal) metals.remove("IRON_BLOCK");
        }
    }

    @Test void aimingAtTheMovingMetalBlockDoesNotStopTheRedirectRayOnItself() throws Exception {
        EarthBlast blast = launched();
        blast.setSourcetype(Material.IRON_BLOCK);
        blast.getSourceBlock().setType(Material.IRON_BLOCK);
        shooter.location = new Location(world, 0.5, 64, 0.5);
        EarthBlast.throwEarth(shooter);
        assertTrue(blast.getDestination().getZ() > 25, "the aim ray must pass through the projectile");
        assertFalse(blast.isRemoved());
    }

    private EarthBlast launched() throws Exception {
        EarthBlast blast = prepare(shooter, world.getBlockAt(0, 65, 6));
        assertTrue(blast.isStarted());
        blast.throwEarth();
        assertTrue(blast.isProgressing());
        return blast;
    }

    private EarthBlast prepare(Player player, Block block) throws Exception {
        block.setType(Material.STONE);
        var source = BlockSource.class.getDeclaredMethod("putSource", Player.class, Block.class, BlockSource.BlockSourceType.class, ClickType.class);
        source.setAccessible(true);
        source.invoke(null, player, block, BlockSource.BlockSourceType.EARTH, ClickType.SHIFT_DOWN);
        return new EarthBlast(player);
    }

    private void tick(EarthBlast blast) {
        blast.setTime(0);
        AbilityExecutionContext.run(blast, blast::progress);
    }
    @SuppressWarnings("unchecked") private Map<Object, Object> map(Class<?> type, String name) throws Exception {
        return (Map<Object, Object>) field(type, name).get(null);
    }
    private void backup(Class<?> type, String name) throws Exception {
        Map<Object, Object> map = map(type, name), copy = new HashMap<>(map);
        restores.add(() -> { map.clear(); map.putAll(copy); });
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
