package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.bukkit.event.entity.EntityRemoveEvent;

/** A match-owned shulker bullet uses native flight and reports its impact to the private event bus. */
final class PaperRollbackShulkerBullet extends ShulkerBullet {
    private final PaperRollbackNativeScene scene;

    PaperRollbackShulkerBullet(PaperRollbackNativeScene scene) {
        super(EntityType.SHULKER_BULLET, scene.world().world());
        this.scene = scene;
    }

    @Override public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult hit) {
        hitCancelled = PaperRollbackProjectileHits.cancelled(scene, this, hit);
        return PaperRollbackProjectileHits.continues(this, hit, hitCancelled)
                ? hitTargetOrDeflectSelf(hit) : ProjectileDeflection.NONE;
    }

    @Override protected void onHitEntity(net.minecraft.world.phys.EntityHitResult hit) { scene.world().projectileImpact(this, hit); }

    @Override protected void onHitBlock(BlockHitResult hit) {
        boolean cancelled = hitCancelled;
        if (!cancelled) scene.ticks().projectile(this, hit);
        hitCancelled = true;
        try { super.onHitBlock(hit); }
        finally { hitCancelled = cancelled; }
    }

    @Override public void remove(RemovalReason reason, EntityRemoveEvent.Cause cause) {
        if (isRemoved()) return;
        if (!scene.world().ownsEntity(this) || valid || inWorld) throw new IllegalStateException("Unowned bullet removal");
        if (cause != null) scene.world().event(new EntityRemoveEvent(getBukkitEntity(), cause));
        PaperRollbackPrivateAccess.retirePrivateActor(this, reason);
    }
}
