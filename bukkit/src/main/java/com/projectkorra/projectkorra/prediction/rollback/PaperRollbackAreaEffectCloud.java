package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.event.entity.EntityRemoveEvent;

/** Runs Paper's stock cloud application and lifetime against the match's event dispatcher. */
final class PaperRollbackAreaEffectCloud extends AreaEffectCloud {
    private static final VarHandle VICTIMS;
    static {
        try {
            VICTIMS = MethodHandles.privateLookupIn(AreaEffectCloud.class, MethodHandles.lookup())
                    .findVarHandle(AreaEffectCloud.class, "victims", Map.class);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private final PaperRollbackNativeScene scene;

    @SuppressWarnings("unchecked")
    private static Map<Entity, Integer> victims(AreaEffectCloud cloud) {
        return (Map<Entity, Integer>) VICTIMS.get(cloud);
    }

    static Map<UUID, Integer> captureVictims(Entity entity, PaperRollbackWorldAccess world) {
        if (!(entity instanceof AreaEffectCloud cloud)) return Map.of();
        var captured = new TreeMap<UUID, Integer>();
        victims(cloud).forEach((target, age) -> {
            if (world.retiredActor(target)) return;
            if (!(target instanceof LivingEntity) || !world.ownsEntity(target) || captured.put(target.getUUID(), age) != null)
                throw new IllegalArgumentException("Unowned or duplicated private cloud victim");
        });
        return captured;
    }

    /** Stage references after the replacement roster and all active actors have their destination bodies. */
    record Applications(AreaEffectCloud cloud, Map<UUID, Integer> deadlines) {
        static void stage(Entity entity, Map<UUID, Integer> deadlines, Consumer<Applications> pending) {
            if (deadlines.isEmpty()) return;
            if (!(entity instanceof AreaEffectCloud cloud)) throw new IllegalArgumentException("Cloud recipients on another actor type");
            pending.accept(new Applications(cloud, deadlines));
        }

        void bind(Map<UUID, Entity> bodies) {
            var next = new java.util.HashMap<Entity, Integer>();
            deadlines.forEach((id, age) -> {
                var target = bodies.get(id);
                if (!(target instanceof LivingEntity) || target.isRemoved() || !id.equals(target.getUUID()) || target.level() != cloud.level())
                    throw new IllegalArgumentException("Cloud recipient is absent from the restored arena: " + id);
                next.put(target, age);
            });
            var victims = victims(cloud);
            victims.clear();
            victims.putAll(next);
        }
    }

    PaperRollbackAreaEffectCloud(PaperRollbackNativeScene scene) {
        super(EntityType.AREA_EFFECT_CLOUD, scene.world().world());
        this.scene = scene;
    }

    @Override public void tick() { scene.world().tickCloud(this); }

    @Override public void remove(RemovalReason reason, EntityRemoveEvent.Cause cause) {
        if (isRemoved()) return;
        if (!scene.world().ownsEntity(this) || valid || inWorld) throw new IllegalStateException("Foreign cloud removal");
        if (cause != null) scene.world().event(new EntityRemoveEvent(getBukkitEntity(), cause));
        PaperRollbackPrivateAccess.retirePrivateActor(this, reason);
    }
}
