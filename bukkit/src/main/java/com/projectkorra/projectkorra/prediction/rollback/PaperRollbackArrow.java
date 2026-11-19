package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Bending arrows keep native flight, embedding, and pickup state in the owned scene. */
final class PaperRollbackArrow extends Arrow {
    private final PaperRollbackNativeScene scene;

    PaperRollbackArrow(PaperRollbackNativeScene scene) {
        super(EntityType.ARROW, scene.world().world());
        this.scene = scene;
    }

    @Override public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult hit) {
        hitCancelled = PaperRollbackProjectileHits.cancelled(scene, this, hit);
        return PaperRollbackProjectileHits.continues(this, hit, hitCancelled)
                ? hitTargetOrDeflectSelf(hit) : ProjectileDeflection.NONE;
    }

    @Override public void remove(RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.Cause cause) {
        if (isRemoved()) return;
        if (!scene.world().ownsEntity(this) || valid || inWorld) throw new IllegalStateException("Unowned arrow removal");
        if (cause != null) scene.world().event(new org.bukkit.event.entity.EntityRemoveEvent(getBukkitEntity(), cause));
        PaperRollbackPrivateAccess.retirePrivateActor(this, reason);
    }

    @Override protected void tickDespawn() {
        var lifetime = scene.services().settings().arrowLifetime();
        int limit = switch (pickup) {
            case ALLOWED -> lifetime.groundTicks();
            case CREATIVE_ONLY -> lifetime.creativeTicks();
            case DISALLOWED -> lifetime.unpickableTicks();
        };
        if (++life >= limit) {
            var cause = org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN;
            scene.world().event(new org.bukkit.event.entity.EntityRemoveEvent(getBukkitEntity(), cause));
            PaperRollbackPrivateAccess.retirePrivateActor(this);
        }
    }

    @Override protected void onHitEntity(net.minecraft.world.phys.EntityHitResult hit) { scene.world().projectileImpact(this, hit); }

    @Override protected void onHitBlock(BlockHitResult hit) {
        boolean cancelled = hitCancelled;
        if (!cancelled) scene.ticks().projectile(this, hit);
        // The base arrow still embeds and plays its impact sound. Its block callback has already run privately.
        hitCancelled = true;
        try { super.onHitBlock(hit); }
        finally { hitCancelled = cancelled; }
    }
}
