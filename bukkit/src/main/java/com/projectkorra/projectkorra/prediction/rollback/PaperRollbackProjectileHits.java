package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

final class PaperRollbackProjectileHits {
    private PaperRollbackProjectileHits() { }

    private static Entity logical(PaperRollbackNativeScene scene, net.minecraft.world.entity.Entity entity) {
        if (!scene.world().ownsEntity(entity)) throw new IllegalStateException("Projectile impact crosses the scene");
        var view = scene.logical().entities().get(entity.getUUID());
        if (view == null) throw new IllegalStateException("Projectile impact has no logical owner");
        return view;
    }

    static boolean continues(net.minecraft.world.entity.projectile.Projectile projectile, HitResult hit, boolean cancelled) {
        return !projectile.isRemoved() && (hit.getType() == HitResult.Type.BLOCK || !cancelled);
    }

    static boolean cancelled(PaperRollbackNativeScene scene, net.minecraft.world.entity.projectile.Projectile projectile, HitResult hit) {
        if (!RollbackDomain.active() || !RollbackClock.active()) throw new IllegalStateException("Private projectile outside replay");
        var event = new ProjectileHitEvent();
        event.setEntity((Projectile) logical(scene, projectile));
        if (hit instanceof EntityHitResult target) event.setHitEntity(logical(scene, target.getEntity()));
        if (hit instanceof BlockHitResult target) {
            var pos = target.getBlockPos();
            event.setHitBlock(scene.logical().getBlockAt(pos.getX(), pos.getY(), pos.getZ()));
        }
        Platform.events().call(event);
        return event.isCancelled();
    }
}
