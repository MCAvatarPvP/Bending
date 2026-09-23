package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.craftbukkit.CraftStatistic;
import net.minecraft.stats.Stat;
import org.bukkit.plugin.PluginManager;
import net.minecraft.world.damagesource.DamageSource;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.CraftServer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Native event construction/ordering with an explicit private dispatcher. Its
 * listener state must be a session checkpoint root. No global Bukkit installation
 * or live plugin-manager call is made. This binds damage, resurrection, potion,
 * knockback, exhaustion and statistic delivery, not every native event or damage-source import.
 */
final class PaperRollbackNativeEvents {
    private static final class StatisticEntities {
        static final java.util.Map<org.bukkit.NamespacedKey, org.bukkit.entity.EntityType> TYPES = types();
        private static java.util.Map<org.bukkit.NamespacedKey, org.bukkit.entity.EntityType> types() {
            var values = new java.util.HashMap<org.bukkit.NamespacedKey, org.bukkit.entity.EntityType>();
            for (var type : org.bukkit.entity.EntityType.values()) {
                if (type != org.bukkit.entity.EntityType.UNKNOWN) values.put(type.getKey(), type);
            }
            return java.util.Map.copyOf(values);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final Consumer<Event> dispatcher;
    private final Predicate<org.bukkit.entity.Entity> owned;
    private CraftServer server;
    private PluginManager manager;

    PaperRollbackNativeEvents(Consumer<Event> dispatcher, Predicate<org.bukkit.entity.Entity> owned) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "private event dispatcher");
        this.owned = Objects.requireNonNull(owned, "owned Bukkit entity predicate");
    }

    void bind(RollbackNativeMethods methods) {
        if (Thread.currentThread() != thread || RollbackClock.active()) throw new IllegalStateException("Configure native event routing during session bootstrap");
        try {
            var route = MethodHandles.lookup().findVirtual(PaperRollbackNativeEvents.class, "dispatch",
                    MethodType.methodType(boolean.class, Event.class)).bindTo(this);
            methods.replace(Event.class.getMethod("callEvent"), route);
            if (manager == null) {
                manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("callEvent") && method.getParameterCount() == 1 && method.getParameterTypes()[0] == Event.class) {
                            dispatch((Event) arguments[0]); return null;
                        }
                        throw new IllegalStateException("Unsupported private plugin-manager operation: " + method);
                    });
                // CraftServer is final. This uninitialized identity token is never
                // published; only the explicitly replaced manager getter may use it.
                server = new org.objenesis.ObjenesisStd(false).newInstance(CraftServer.class);
            }
            methods.replace(Bukkit.class.getMethod("getPluginManager"), MethodHandles.constant(PluginManager.class, manager));
            methods.replace(CraftServer.class.getMethod("getPluginManager"),
                    MethodHandles.lookup().findVirtual(PaperRollbackNativeEvents.class, "manager",
                            MethodType.methodType(PluginManager.class, CraftServer.class)).bindTo(this));
            methods.replace(org.bukkit.Server.class.getMethod("getPluginManager"),
                    MethodHandles.lookup().findVirtual(PaperRollbackNativeEvents.class, "manager",
                            MethodType.methodType(PluginManager.class, CraftServer.class)).bindTo(this)
                            .asType(MethodType.methodType(PluginManager.class, org.bukkit.Server.class)));
            methods.replace(org.bukkit.entity.Entity.class.getMethod("getServer"),
                    MethodHandles.lookup().findVirtual(PaperRollbackNativeEvents.class, "entityServer",
                            MethodType.methodType(org.bukkit.Server.class, org.bukkit.entity.Entity.class)).bindTo(this));
            methods.replace(org.bukkit.craftbukkit.entity.CraftEntity.class.getMethod("getServer"),
                    MethodHandles.lookup().findVirtual(PaperRollbackNativeEvents.class, "entityServer",
                            MethodType.methodType(org.bukkit.Server.class, org.bukkit.entity.Entity.class)).bindTo(this)
                            .asType(MethodType.methodType(org.bukkit.Server.class, org.bukkit.craftbukkit.entity.CraftEntity.class)));
            methods.replace(CraftStatistic.class.getMethod("getEntityTypeFromStatistic", Stat.class),
                    MethodHandles.lookup().findStatic(PaperRollbackNativeEvents.class, "statisticEntity",
                            MethodType.methodType(org.bukkit.entity.EntityType.class, Stat.class)));
            Set<String> factories = Set.of("callEntityDamageEvent", "callEntityKnockbackEvent", "callPlayerExhaustionEvent",
                    "callEntityPotionEffectChangeEvent", "handleStatisticsIncrease", "callToggleSwimEvent", "callToggleGlideEvent", "callFoodLevelChangeEvent", "callPlayerLevelChangeEvent");
            var found = new java.util.HashSet<String>();
            for (var method : CraftEventFactory.class.getDeclaredMethods()) {
                if (factories.contains(method.getName()) && Modifier.isStatic(method.getModifiers())) {
                    methods.copy(method); found.add(method.getName());
                }
            }
            if (!found.equals(factories)) throw new IllegalStateException("Native event factories are unavailable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native event route unavailable", failure); }
    }

    CraftServer server() { return Objects.requireNonNull(server, "Bind native events before binding the world"); }

    private org.bukkit.Server entityServer(org.bukkit.entity.Entity entity) {
        if (Thread.currentThread() != thread || !owned.test(entity)) throw new IllegalArgumentException("Foreign entity server query");
        return server();
    }

    private static org.bukkit.entity.EntityType statisticEntity(Stat<?> statistic) {
        var type = (net.minecraft.world.entity.EntityType<?>) statistic.getValue();
        var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getResourceKey(type)
                .orElseThrow(() -> new IllegalArgumentException("Foreign statistic entity type"));
        var result = StatisticEntities.TYPES.get(org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(key.identifier()));
        if (result == null) throw new IllegalArgumentException("Native statistic entity has no Bukkit type: " + key);
        return result;
    }

    @SuppressWarnings("WrapperReferenceEquality")
    private PluginManager manager(CraftServer receiver) {
        if (Thread.currentThread() != thread || receiver != server) throw new IllegalArgumentException("Foreign native server event route");
        return manager;
    }

    /** Includes native modifier/cause mapping with a private native damage-source wrapper. */
    void bindDamageSources(RollbackNativeMethods methods, Consumer<Object> validateSource) {
        Objects.requireNonNull(validateSource, "native source ownership validation");
        try {
            var factory = MethodHandles.lookup().findVirtual(SourceFactory.class, "create", MethodType.methodType(Object.class, Object.class))
                    .bindTo(new SourceFactory(validateSource)).asType(MethodType.methodType(CraftDamageSource.class, DamageSource.class));
            methods.construct(CraftDamageSource.class.getConstructor(DamageSource.class), factory);
            for (var method : CraftEventFactory.class.getDeclaredMethods()) {
                if (Set.of("handleEntityDamageEvent", "handleLivingEntityDamageEvent").contains(method.getName())) methods.copy(method);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native damage-source route unavailable", failure); }
    }

    private final class SourceFactory {
        private final Consumer<Object> validate;
        private SourceFactory(Consumer<Object> validate) { this.validate = validate; }
        private Object create(Object source) {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Native damage source crossed threads");
            validate.accept(source);
            return PaperRollbackPrivateAccess.damageSource((DamageSource) source);
        }
    }

    boolean dispatch(Event event) {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native event crossed threads");
        Objects.requireNonNull(event, "event");
        org.bukkit.entity.Entity target = event instanceof EntityEvent entity ? entity.getEntity()
                : event instanceof PlayerEvent player ? player.getPlayer() : null;
        if (event.isAsynchronous() || target == null || !owned.test(target)) {
            throw new IllegalArgumentException("Event does not belong to the private simulation");
        }
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damage) requireOwned(damage.getDamager());
        if (event instanceof org.bukkit.event.entity.EntityKnockbackByEntityEvent knockback) requireOwned(knockback.getSourceEntity());
        if (event instanceof com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent knockback) requireOwned(knockback.getHitBy());
        if (event instanceof org.bukkit.event.entity.EntityDamageEvent damage) {
            requireOwned(damage.getDamageSource().getDirectEntity());
            requireOwned(damage.getDamageSource().getCausingEntity());
        }
        if (target instanceof org.bukkit.entity.Player player) {
            if (event instanceof org.bukkit.event.player.PlayerToggleFlightEvent flight && !flight.isCancelled()) {
                flight.setCancelled(RollbackControlEvents.cancelFlight(player.getUniqueId()));
            }
            if (event instanceof org.bukkit.event.entity.EntityToggleGlideEvent glide && !glide.isCancelled()) {
                glide.setCancelled(RollbackControlEvents.cancelGlide(player.getUniqueId()));
            }
        }
        dispatcher.accept(event);
        return !(event instanceof Cancellable cancellable) || !cancellable.isCancelled();
    }

    private void requireOwned(org.bukkit.entity.Entity entity) {
        if (entity != null && !owned.test(entity)) throw new IllegalArgumentException("Event source does not belong to the private simulation");
    }
}
