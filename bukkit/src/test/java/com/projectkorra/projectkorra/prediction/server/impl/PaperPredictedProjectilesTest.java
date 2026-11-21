package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaperPredictedProjectilesTest {
    @Test void deniedRemovedAndSuccessfulImpactsUseTheOriginalSnowballsIdentityOnce() throws Exception {
        UUID ownerId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        var constructor = PaperPredictionServer.class.getDeclaredConstructor(JavaPlugin.class);
        constructor.setAccessible(true);
        var server = constructor.newInstance((JavaPlugin) null);
        var session = new PaperPredictionState.Session(ownerId, sessionId, PaperPredictionState.CAPABILITY_EXACT, 0, 0);
        server.sessions.put(ownerId, session);
        var action = new PaperPredictionState.Action(ownerId, 12, 3, PaperPredictionProtocol.InputKind.LEFT_CLICK, 1,
                "Smokescreen", 0, 0, 0, 0, 0, 7, true);
        session.actions.put(action.sequence, action);
        var messages = new ArrayList<byte[]>();
        var owner = proxy(Player.class, (self, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> ownerId;
            case "getListeningPluginChannels" -> Set.of(PaperPredictionProtocol.PROJECTILE_OWNER, PaperPredictionProtocol.PROJECTILE_IMPACT);
            case "sendPluginMessage" -> {
                if (PaperPredictionProtocol.PROJECTILE_IMPACT.equals(args[1])) messages.add((byte[]) args[2]);
                yield null;
            }
            default -> throw new AssertionError(method);
        });
        var delayed = new ArrayList<Runnable>();
        var scheduler = proxy(BukkitScheduler.class, (self, method, args) -> {
            if (!method.getName().equals("runTask")) throw new AssertionError(method);
            delayed.add((Runnable) args[1]);
            return null;
        });
        var nativeServer = Bukkit.class.getDeclaredField("server");
        nativeServer.setAccessible(true);
        var original = nativeServer.get(null);
        try {
            nativeServer.set(null, proxy(Server.class, (self, method, args) -> switch (method.getName()) {
                case "getPlayer" -> owner;
                case "getScheduler" -> scheduler;
                default -> throw new AssertionError(method);
            }));
            var projectiles = new PaperPredictedProjectiles(server);
            var denied = actor(Snowball.class, EntityType.SNOWBALL, 91);
            var successful = actor(Snowball.class, EntityType.SNOWBALL, 92);
            var removed = actor(Snowball.class, EntityType.SNOWBALL, 93);
            var cloud = actor(AreaEffectCloud.class, EntityType.AREA_EFFECT_CLOUD, 94);
            PaperPredictionUtilities.runWithOwnerAndSequence(ownerId, action.sequence, () -> {
                projectiles.spawned(owner, denied);
                projectiles.spawned(owner, denied);
                projectiles.spawned(owner, successful);
                projectiles.spawned(owner, removed);
            });
            projectiles.finish(denied); // Native event cancelled before the bending hit listener.
            projectiles.impact(successful, () -> {
                projectiles.removed(successful); // Synchronous removal during the native callback.
                projectiles.spawned(owner, cloud);
            });
            projectiles.finish(successful);
            projectiles.removed(removed);
            projectiles.removed(removed);
            assertEquals(2, delayed.size());
            delayed.forEach(Runnable::run);
            projectiles.finish(successful);
            assertEquals(3, messages.size());
            for (var pair : List.of(denied, successful, removed)) {
                int ordinal = pair.getEntityId() - 90;
                assertArrayEquals(PaperPredictionProtocol.projectileImpact(sessionId, 12, ordinal, ownerId,
                        pair.getEntityId(), pair.getUniqueId(), pair == successful), messages.get(ordinal - 1));
            }
        } finally { nativeServer.set(null, original); }
    }

    private static <T extends Entity> T actor(Class<T> type, EntityType kind, int networkId) {
        UUID id = UUID.randomUUID();
        return proxy(type, (self, method, args) -> switch (method.getName()) {
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            case "getUniqueId" -> id;
            case "getEntityId" -> networkId;
            case "getType" -> kind;
            case "getMetadata" -> List.of();
            case "isValid" -> true;
            default -> throw new AssertionError(method);
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
