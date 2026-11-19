package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.Objects;
import java.util.function.Function;

/** Run the game's own item, reach, entity and block picking against the replica's latest head. */
public final class FabricRollbackCrosshair {
    private static final ThreadLocal<FabricRollbackCrosshair> PICKING = new ThreadLocal<>();
    private final PlayerEntity player;
    private final float pitch, yaw;
    private final Vec3d eye;
    private final Function<Entity, Entity> model;

    private FabricRollbackCrosshair(PlayerEntity player, float pitch, float yaw, Vec3d eye, Function<Entity, Entity> model) {
        this.player = player; this.pitch = pitch; this.yaw = yaw; this.eye = eye; this.model = model;
    }
    static net.minecraft.client.network.ClientPlayerEntity selector(PlayerEntity body) {
        // The vanilla entry point belongs to the client-only player class. Its three virtual
        // reads must use the private inventory and attributes. The native ray uses the real owned body.
        return com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell.create(net.minecraft.client.network.ClientPlayerEntity.class)
                .query(PlayerEntity::getActiveOrMainHandStack, null, args -> body.getActiveOrMainHandStack())
                .query(PlayerEntity::getBlockInteractionRange, 0D, args -> body.getBlockInteractionRange())
                .query(PlayerEntity::getEntityInteractionRange, 0D, args -> body.getEntityInteractionRange())
                .nativeQuery(client -> client.getCrosshairTarget(0F, null), null, args -> {
                    if (args[1] != body) throw new IllegalArgumentException("Crosshair camera outside private roster");
                }).instance();
    }
    static HitResult pick(net.minecraft.client.network.ClientPlayerEntity selector, FabricRollbackNativePlayerState state,
                          float delta, float pitch, float yaw, Vec3d eye, Function<Entity, Entity> model) {
        return state.use(player -> {
            if (PICKING.get() != null) throw new IllegalStateException("Reentrant crosshair query");
            PICKING.set(new FabricRollbackCrosshair(player, pitch, yaw, eye, model));
            try {
                var hit = selector.getCrosshairTarget(delta, player);
                if (!(hit instanceof EntityHitResult contact)) return hit;
                Entity source = contact.getEntity();
                return new Picked(Objects.requireNonNull(model.apply(source)), contact.getPos(), source);
            } finally { PICKING.remove(); }
        });
    }
    static boolean active(World world) {
        var current = PICKING.get(); return current != null && current.player.getEntityWorld() == world;
    }
    static boolean visible(Entity viewer, Entity entity) {
        var current = PICKING.get(); return current == null || current.player != viewer || current.model.apply(entity) != null;
    }
    public static Vec3d eye(Entity entity) {
        var current = PICKING.get(); return current != null && current.player == entity ? current.eye : null;
    }
    static float pitch(PlayerEntity entity, float ordinary) {
        var current = PICKING.get(); return current != null && current.player == entity ? current.pitch : ordinary;
    }
    static float yaw(PlayerEntity entity, float ordinary) {
        var current = PICKING.get(); return current != null && current.player == entity ? current.yaw : ordinary;
    }

    private static final class Picked extends EntityHitResult {
        private final Entity source;
        private final Vec3d offset;
        private Picked(Entity model, Vec3d point, Entity source) {
            super(model, point); this.source = source; this.offset = point.subtract(source.getEntityPos());
        }
    }
    public static Vec3d offset(EntityHitResult hit, Entity target) {
        return hit instanceof Picked pick && pick.getEntity() == target ? pick.offset : hit.getPos().subtract(target.getEntityPos());
    }
    public static Entity packetTarget(Entity target, HitResult hit) {
        return hit instanceof Picked pick && pick.getEntity() == target ? pick.source : target;
    }
}
