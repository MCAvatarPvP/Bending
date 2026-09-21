package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AirBlastStaminaTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 64.25, 0.5);
    private final List<Vector> impulses = new ArrayList<>();
    private BendingPlayer bender;
    private boolean onCooldown;
    private int cooldownsAdded;

    @BeforeEach void setup() {
        bender = new BendingPlayer(player) {
            @Override public boolean isOnCooldown(Ability ability) { return onCooldown; }
            @Override public void addCooldown(Ability ability) { cooldownsAdded++; }
        };
        BendingPlayer.getPlayers().put(player.getUniqueId(), bender);
        var config = ConfigManager.getConfig();
        config.set("Abilities.Air.AirBlast.Speed", 25.0);
        config.set("Abilities.Air.AirBlast.Range", 20.0);
        config.set("Abilities.Air.AirBlast.Push.Self", 2.5);
        config.set("Abilities.Air.AirBlast.Push.Entities", 3.5);
        config.set("Abilities.Air.AirBlast.DecayMinimum", 0.2);
        config.set("Abilities.Air.AirBlast.DecayAmount", 0.2);
        config.set("Abilities.Air.AirBlast.SlidingConsumesStamina", true);
        config.set("Abilities.Air.AirBlast.StaminaSliding", false);
        config.set("Abilities.Air.AirBlast.SlidingActivationDelay", 0L);

        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = new PKEventBus() {
            @Override public void call(Object event) {
                if (event instanceof AbilityVelocityAffectEntityEvent velocity) {
                    impulses.add(velocity.getVelocity().clone());
                }
            }
            @Override public void registerListener(Object listener) { }
            @Override public void unregisterAll(Object listener) { }
        };
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("events")) return events;
                    return method.invoke(delegate, args);
                }));
    }

    @AfterEach void cleanup() throws Exception {
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        world.close();
    }

    @Test void missingOptionPreservesStaminaRequirementAndScaling() {
        bender.setAirBlastDecay(0.2);
        assertFalse(AirBlast.hasSufficientStamina(bender));
        AirBlast blocked = blast(true);
        blocked.shoot();
        assertFalse(blocked.isProgressing());
        assertEquals(0, cooldownsAdded);

        bender.setAirBlastDecay(0.8);
        AirBlast allowed = blast(true);
        allowed.shoot();
        assertTrue(allowed.isProgressing());
        assertEquals(15.0, allowed.getSpeed(), 1e-9);
        assertEquals(12.0, allowed.getRange(), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledStaminaAllowsDepletedShotsAtFullSpeedAndRange(boolean sourced) {
        ConfigManager.getConfig().set("Abilities.Air.AirBlast.StaminaEnabled", false);
        bender.setAirBlastDecay(0.0);
        assertTrue(AirBlast.hasSufficientStamina(bender));
        assertFalse(AirBlast.hasSufficientStamina(null));

        AirBlast blast = blast(sourced);
        blast.shoot();

        assertTrue(blast.isProgressing());
        assertEquals(25.0, blast.getSpeed(), 1e-9);
        assertEquals(20.0, blast.getRange(), 1e-9);
        assertEquals(0.0, bender.getAirBlastDecay());
        assertEquals(1, cooldownsAdded, "ordinary ability cooldown still applies");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledStaminaPreservesRepeatedSelfPushesWithoutDrainOrRegenDelay(boolean sliding) throws Exception {
        ConfigManager.getConfig().set("Abilities.Air.AirBlast.StaminaEnabled", false);
        bender.setAirBlastDecay(0.1);
        if (sliding) world.getBlockAt(0, 63, 0).setType(Material.STONE);
        AirBlast blast = blast(true);
        blast.shoot();

        affectSelf(blast);
        affectSelf(blast);

        assertEquals(0.1, bender.getAirBlastDecay(), 1e-9);
        assertEquals(0L, bender.getLastAirBlastTime());
        assertEquals(2.5, blast.getPushFactor(), 1e-9);
        assertEquals(2, impulses.size());
        double expected = sliding ? 0.75 : 1.25;
        assertEquals(expected, impulses.get(0).getZ(), 1e-9);
        assertEquals(expected, impulses.get(1).getZ(), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enabledStaminaStillDrainsOncePerSelfBlastAndDelaysRegen(boolean sliding) throws Exception {
        ConfigManager.getConfig().set("Abilities.Air.AirBlast.StaminaEnabled", true);
        bender.setAirBlastDecay(1.0);
        if (sliding) world.getBlockAt(0, 63, 0).setType(Material.STONE);
        AirBlast blast = blast(true);
        blast.shoot();

        affectSelf(blast);
        affectSelf(blast);

        double remaining = sliding ? 0.9 : 0.8;
        assertEquals(remaining, bender.getAirBlastDecay(), 1e-9);
        assertTrue(bender.getLastAirBlastTime() > 0L);
        assertEquals(2.5 * remaining, blast.getPushFactor(), 1e-9);
    }

    @Test void disablingStaminaDoesNotBypassCooldownOrUnderwaterRestriction() {
        ConfigManager.getConfig().set("Abilities.Air.AirBlast.StaminaEnabled", false);
        onCooldown = true;
        AirBlast blocked = blast(true);
        blocked.shoot();
        assertFalse(blocked.isProgressing());

        onCooldown = false;
        world.getBlockAt(0, 64, 0).setType(Material.WATER);
        AirBlast underwater = blast(true);
        underwater.shoot();
        assertFalse(underwater.isProgressing());
        assertEquals(0, cooldownsAdded);
    }

    private AirBlast blast(boolean sourced) {
        AirBlast blast = new AirBlast(player) {
            @Override public boolean isEnabled() { return false; }
            @Override public void start() { }
            @Override public void selectOrigin() { setOrigin(player.getEyeLocation().subtract(0, 0, 2)); }
        };
        blast.setFromOtherOrigin(sourced);
        if (!sourced) blast.setOrigin(player.getEyeLocation());
        return blast;
    }

    private void affectSelf(AirBlast blast) throws Exception {
        var affect = AirBlast.class.getDeclaredMethod("affect", Entity.class, Location.class);
        affect.setAccessible(true);
        affect.invoke(blast, player, blast.getLocation());
    }
}
