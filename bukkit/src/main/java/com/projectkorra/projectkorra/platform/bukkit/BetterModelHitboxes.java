package com.projectkorra.projectkorra.platform.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/** Optional BetterModel API adapter; keeps the Bukkit plugin usable without BetterModel or Java 25. */
final class BetterModelHitboxes {
    private static final String HITBOX = "kr.toxicity.model.api.nms.HitBox";
    private static final ClassValue<Optional<Access>> ACCESS = new ClassValue<>() {
        @Override protected Optional<Access> computeValue(Class<?> type) {
            Class<?> api = findHitboxInterface(type);
            if (api == null) return Optional.empty();
            try {
                Method source = api.getMethod("source");
                Method controller = api.getMethod("mountController");
                return Optional.of(new Access(source, source.getReturnType().getMethod("uuid"),
                        api.getMethod("uuid"), controller, controller.getReturnType().getMethod("canMount")));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };

    private BetterModelHitboxes() {}

    record Part(LivingEntity owner, Entity geometry) {}

    static boolean isHitbox(Entity entity) {
        return entity != null && ACCESS.get(entity.getClass()).isPresent();
    }

    static Part resolve(Entity entity) {
        Access access = ACCESS.get(entity.getClass()).orElse(null);
        if (access == null || !entity.isValid()) return null;
        try {
            // p_/sp_ mount anchors are not body colliders. Their real riders are queried separately.
            if (Boolean.TRUE.equals(access.canMount.invoke(access.controller.invoke(entity)))) return null;
            Object source = access.source.invoke(entity);
            Object id = access.sourceUuid.invoke(source);
            if (!(id instanceof UUID uuid) || !(Bukkit.getEntity(uuid) instanceof LivingEntity owner)
                    || !owner.isValid() || owner.isDead() || !owner.getWorld().equals(entity.getWorld())) return null;
            Entity geometry = entity;
            if (entity instanceof Interaction) {
                // The companion is a clickable proxy with different bounds. Always collide with
                // its actual body hitbox, so the companion cannot enlarge the model's damage area.
                geometry = entity.getVehicle();
                if (!isHitbox(geometry) || !geometry.isValid() || !owner.getWorld().equals(geometry.getWorld())
                        || !access.hitboxUuid.invoke(entity).equals(
                                ACCESS.get(geometry.getClass()).orElseThrow().hitboxUuid.invoke(geometry))) return null;
            }
            return new Part(owner, geometry);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Class<?> findHitboxInterface(Class<?> type) {
        if (type == null) return null;
        if (HITBOX.equals(type.getName())) return type;
        for (Class<?> parent : type.getInterfaces()) {
            Class<?> found = findHitboxInterface(parent);
            if (found != null) return found;
        }
        return findHitboxInterface(type.getSuperclass());
    }

    private record Access(Method source, Method sourceUuid, Method hitboxUuid,
                          Method controller, Method canMount) {}
}
