package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.firebending.combo.ParticleStream;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AirSweepWaterTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 64.5, 0.5);
    private final Vector direction = new Vector(1, 0, 0);

    @BeforeEach void setup() {
        BendingPlayer.getPlayers().put(player.getUniqueId(), new BendingPlayer(player) {
            @Override public boolean canBendIgnoreBindsCooldowns(CoreAbility ability) { return true; }
            @Override public boolean canUseSubElement(SubElement element) { return false; }
            @Override public void addCooldown(Ability ability) { }
        });
        ConfigManager.getConfig().set("Abilities.Air.AirSweep.Speed", 2.0);
        ConfigManager.getConfig().set("Abilities.Air.AirSweep.Radius", 0.1);
    }

    @AfterEach void cleanup() throws Exception {
        RegionProtection.unloadPlugin("AirSweepWaterTest");
        RegionProtection.clearCache(player);
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        world.close();
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"WATER", "BUBBLE_COLUMN", "SEAGRASS", "KELP"})
    void enabledSweepStartsAndMovesWhileSubmerged(Material water) throws Exception {
        for (int x = 0; x <= 3; x++) {
            world.getBlockAt(x, 64, 0).setType(water);
            world.getBlockAt(x, 65, 0).setType(Material.WATER);
        }
        // Air's setting must work even when fire cannot pass through temporary water.
        ConfigManager.getConfig().set("Properties.Fire.CanGoThroughTempWater", false);
        AirSweep sweep = sweep(true);
        ParticleStream stream = stream(sweep);
        stream.setGoThroughWater(true);
        Location origin = stream.getLocation().clone();

        assertFalse(blockedBetween(sweep, origin, origin), "submerged launch point");
        stream.run();
        assertFalse(stream.isCancelled(), "shared stream must honor the opt-in");
        assertEquals(2.5, stream.getLocation().getX(), 1e-9);
        assertFalse(blockedBetween(sweep, origin, stream.getLocation()), "submerged flight path");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledSweepStopsAtWaterAtTheSurfaceAndBelow(boolean submerged) throws Exception {
        world.getBlockAt(0, 64, 0).setType(Material.WATER);
        if (submerged) world.getBlockAt(0, 65, 0).setType(Material.WATER);
        AirSweep sweep = sweep(false);
        assertTrue(blockedBetween(sweep, player.getLocation(), player.getLocation()));
        if (submerged) {
            ParticleStream stream = stream(sweep);
            stream.setGoThroughWater(false);
            stream.run();
            assertTrue(stream.isCancelled());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waterBetweenTickEndpointsHonorsTheSetting(boolean enabled) throws Exception {
        world.getBlockAt(1, 64, 0).setType(Material.WATER);
        AirSweep sweep = sweep(enabled);
        Location origin = player.getLocation();
        assertEquals(!enabled, blockedBetween(sweep, origin, origin.clone().add(2, 0, 0)));
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"STONE", "LAVA"})
    void allowingWaterDoesNotAllowOtherObstacles(Material obstacle) throws Exception {
        world.getBlockAt(1, 64, 0).setType(obstacle);
        Location origin = player.getLocation();
        assertTrue(blockedBetween(sweep(true), origin, origin.clone().add(2, 0, 0)));
    }

    @Test void protectedWaterStillStopsTheSweepBetweenTickEndpoints() throws Exception {
        world.getBlockAt(1, 64, 0).setType(Material.WATER);
        RegionProtection.registerRegionProtection("AirSweepWaterTest",
                (caster, location, ability) -> location.getBlockX() == 1);
        Location origin = player.getLocation();
        assertTrue(blockedBetween(sweep(true), origin, origin.clone().add(2, 0, 0)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyFireStreamsStillStopInOrdinaryDeepWater(boolean allowTempWater) {
        ConfigManager.getConfig().set("Properties.Fire.CanGoThroughTempWater", allowTempWater);
        world.getBlockAt(0, 64, 0).setType(Material.WATER);
        world.getBlockAt(0, 65, 0).setType(Material.WATER);
        ParticleStream stream = stream(fireAbility());
        stream.run();
        assertTrue(stream.isCancelled(), "fire callers do not set GoThroughWater explicitly");
    }

    @Test void legacyFireStreamsRetainTheirWaterSurfaceException() {
        world.getBlockAt(0, 64, 0).setType(Material.WATER);
        ParticleStream stream = stream(fireAbility());
        stream.run();
        assertFalse(stream.isCancelled());
        assertEquals(2.5, stream.getLocation().getX(), 1e-9);
    }

    private AirSweep sweep(boolean enabled) {
        ConfigManager.getConfig().set("Abilities.Air.AirSweep.GoThroughWater", enabled);
        return new AirSweep(player) {
            @Override public boolean isEnabled() { return false; }
            @Override public void start() { } // Drive the collision and scheduler passes directly.
        };
    }

    private FireAbility fireAbility() {
        return new FireAbility(player) {
            @Override public boolean isEnabled() { return false; }
            @Override public void progress() { }
            @Override public String getName() { return "TestFireStream"; }
            @Override public Location getLocation() { return player.getLocation(); }
            @Override public long getCooldown() { return 0; }
            @Override public boolean isSneakAbility() { return false; }
            @Override public boolean isHarmlessAbility() { return false; }
        };
    }

    private ParticleStream stream(CoreAbility owner) {
        ParticleStream stream = new ParticleStream(player, owner, direction, player.getLocation(), 10, 2);
        stream.setParticlesVisible(false);
        stream.setCollides(false);
        return stream;
    }

    private boolean blockedBetween(AirSweep sweep, Location from, Location to) throws Exception {
        var method = AirSweep.class.getDeclaredMethod("isBlockedBetween", Location.class, Location.class, Vector.class);
        method.setAccessible(true);
        return (boolean) method.invoke(sweep, from, to, direction);
    }
}
