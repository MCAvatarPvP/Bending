package com.projectkorra.projectkorra.platform.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
                return Optional.of(new Access(bind(source, Object.class), bind(source.getReturnType().getMethod("uuid"), UUID.class),
                        bind(api.getMethod("uuid"), UUID.class), bind(controller, Object.class), bind(controller.getReturnType().getMethod("canMount"), boolean.class)));
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
            if (access.mountable(entity)) return null;
            UUID uuid = access.ownerId(entity);
            if (uuid == null || !(Bukkit.getEntity(uuid) instanceof LivingEntity owner)
                    || !owner.isValid() || owner.isDead() || !owner.getWorld().equals(entity.getWorld())) return null;
            Entity geometry = entity;
            if (entity instanceof Interaction) {
                // The companion is a clickable proxy with different bounds. Always collide with
                // its actual body hitbox, so the companion cannot enlarge the model's damage area.
                geometry = entity.getVehicle();
                if (!isHitbox(geometry) || !geometry.isValid() || !owner.getWorld().equals(geometry.getWorld())
                        || !access.hitboxId(entity).equals(
                                ACCESS.get(geometry.getClass()).orElseThrow().hitboxId(geometry))) return null;
            }
            return new Part(owner, geometry);
        } catch (Throwable ignored) {
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

    private static MethodHandle bind(Method method, Class<?> result) throws IllegalAccessException {
        return MethodHandles.publicLookup().unreflect(method).asType(MethodType.methodType(result, Object.class));
    }

    private record Access(MethodHandle source, MethodHandle sourceUuid, MethodHandle hitboxUuid,
                          MethodHandle controller, MethodHandle canMount) {
        boolean mountable(Object entity) throws Throwable {
            Object value = controller.invokeExact(entity);
            return (boolean) canMount.invokeExact(value);
        }
        UUID ownerId(Object entity) throws Throwable {
            Object value = source.invokeExact(entity);
            return (UUID) sourceUuid.invokeExact(value);
        }
        UUID hitboxId(Object entity) throws Throwable { return (UUID) hitboxUuid.invokeExact(entity); }
    }
}
