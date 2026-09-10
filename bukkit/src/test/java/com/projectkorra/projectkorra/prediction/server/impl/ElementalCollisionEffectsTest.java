package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ElementalCollisionEffects;
import com.projectkorra.projectkorra.platform.PKScheduler;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.prediction.authority.AuthoritativeEffects;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ElementalCollisionEffectsTest {
    private final UUID ownerId = UUID.randomUUID();
    private final RecordingWorld world = new RecordingWorld();
    private PaperPredictionServer prediction;
    private Player owner;
    private Field activeField;
    private Field serverField;
    private Field platformField;
    private Object previousPrediction;
    private Object previousServer;
    private Object previousPlatform;
    private CooldownSync.Listener listener;

    @BeforeEach
    void predictOwnerEffects() throws ReflectiveOperationException {
        final var constructor = PaperPredictionServer.class.getDeclaredConstructor(JavaPlugin.class);
        constructor.setAccessible(true);
        prediction = constructor.newInstance((JavaPlugin) null);
        prediction.sessions.put(ownerId, new PaperPredictionState.Session(
                ownerId, UUID.randomUUID(), PaperPredictionState.CAPABILITY_EXACT, 0, 0));
        activeField = PaperPredictionServer.class.getDeclaredField("active");
        activeField.setAccessible(true);
        previousPrediction = activeField.get(null);
        activeField.set(null, prediction);

        owner = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return ownerId;
                    throw new AssertionError(method.toString());
                });
        final Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPlayer") && ownerId.equals(args[0])) return owner;
                    throw new AssertionError(method.toString());
                });
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, server);
        PaperPredictionState.EFFECT_OWNER.set(ownerId);
        PaperPredictionState.EFFECT_PREDICTED.set(true);

        final PKScheduler scheduler = (PKScheduler) Proxy.newProxyInstance(
                PKScheduler.class.getClassLoader(), new Class<?>[]{PKScheduler.class},
                (proxy, method, args) -> {
                    // These tests observe particles and sounds without scheduling display animations.
                    if (method.getName().equals("isPrimaryThread")) return false;
                    throw new AssertionError(method.toString());
                });
        platformField = Platform.class.getDeclaredField("current");
        platformField.setAccessible(true);
        previousPlatform = platformField.get(null);
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(
                ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("scheduler")) return scheduler;
                    throw new AssertionError(method.toString());
                }));
    }

    @AfterEach
    void restoreRuntime() throws IllegalAccessException {
        PaperPredictionState.EFFECT_OWNER.remove();
        PaperPredictionState.EFFECT_PREDICTED.remove();
        CooldownSync.clear(listener);
        if (activeField != null) activeField.set(null, previousPrediction);
        if (serverField != null) serverField.set(null, previousServer);
        if (platformField != null) platformField.set(null, previousPlatform);
    }

    @Test
    void collisionParticlesAndSoundsReachThePredictingOwner() {
        assertSame(owner, PaperPredictionServer.predictedEffectOwner());
        assertSame(owner, PaperPredictionServer.predictedSoundEffectOwner());

        playCollision();

        assertTrue(world.particles > 0);
        assertTrue(world.sounds > 0);
        assertSame(owner, PaperPredictionServer.predictedEffectOwner(),
                "ordinary ability particles must still suppress the predicted echo afterward");
        assertSame(owner, PaperPredictionServer.predictedSoundEffectOwner());
    }

    @Test
    void collisionEffectsStillPlayWithoutClientPrediction() {
        prediction.sessions.clear();
        playCollision();
        assertTrue(world.particles > 0);
        assertTrue(world.sounds > 0);
    }

    @Test
    void failedCollisionPlaybackRestoresPredictionFiltering() {
        world.failParticles = true;
        assertThrows(IllegalStateException.class, this::playCollision);
        assertFalse(AuthoritativeEffects.isBroadcasting());
        assertSame(owner, PaperPredictionServer.predictedEffectOwner());
        assertSame(owner, PaperPredictionServer.predictedSoundEffectOwner());
    }

    @Test
    void predictingRuntimeDoesNotEmitSpeculativeCollisionEffects() {
        listener = new CooldownSync.Listener() {
            @Override public boolean isAuthoritative() { return false; }
            @Override public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiry) { }
            @Override public void onRemoved(BendingPlayer player, String ability) { }
        };
        CooldownSync.install(listener);

        playCollision();

        assertEquals(0, world.particles);
        assertEquals(0, world.sounds);
        assertFalse(AuthoritativeEffects.isBroadcasting());
    }

    private void playCollision() {
        ElementalCollisionEffects.play(new Location(world, 0, 64, 0), Element.WATER, Element.FIRE);
    }

    private static final class RecordingWorld extends World {
        private int particles;
        private int sounds;
        private boolean failParticles;

        @Override
        public <T> void spawnParticle(Particle particle, Location location, int count,
                                      double ox, double oy, double oz, double extra, T data) {
            assertNull(PaperPredictionServer.predictedEffectOwner(),
                    "Paper must broadcast collision particles to every viewer, including the predicting owner");
            if (failParticles) throw new IllegalStateException("particle failure");
            particles += count;
        }

        @Override
        public void spawnParticle(Particle particle, Location location, int count,
                                  double ox, double oy, double oz, double extra) {
            spawnParticle(particle, location, count, ox, oy, oz, extra, null);
        }

        @Override
        public void playSound(Location location, Sound sound, float volume, float pitch) {
            assertNull(PaperPredictionServer.predictedSoundEffectOwner(),
                    "Paper must broadcast collision sounds to the predicting owner too");
            sounds++;
        }
    }
}
