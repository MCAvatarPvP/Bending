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
        return !isRemoved() && (hit.getType() == HitResult.Type.BLOCK || !hitCancelled)
                ? hitTargetOrDeflectSelf(hit) : ProjectileDeflection.NONE;
    }

    @Override protected void onHitBlock(BlockHitResult hit) {
        boolean cancelled = hitCancelled;
        if (!cancelled) scene.ticks().projectile(this, hit);
        // The base arrow still embeds and plays its impact sound. Its block callback has already run privately.
        hitCancelled = true;
        try { super.onHitBlock(hit); }
        finally { hitCancelled = cancelled; }
    }
}
