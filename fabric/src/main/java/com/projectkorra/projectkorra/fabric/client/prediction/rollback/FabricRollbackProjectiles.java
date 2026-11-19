package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.HitResult;

/** The private actor tick owns this projectile and both sides of its contact. */
public final class FabricRollbackProjectiles {
    private FabricRollbackProjectiles() { }
    public static boolean active() { return RollbackDomain.active() && RollbackClock.active(); }
    public static boolean hiddenTarget(ProjectileEntity projectile, net.minecraft.entity.Entity target) {
        var world = FabricRollbackGliding.activeWorld();
        if (world == null || !(target instanceof net.minecraft.entity.player.PlayerEntity player)
                || !world.ownsEntity(projectile) || !(projectile.getOwner() instanceof FabricRollbackDamagePlayer shooter)) return false;
        return world.hiddenProjectileTarget(projectile, shooter, player);
    }
    public static boolean cancelled(ProjectileEntity projectile, HitResult hit) {
        var world = FabricRollbackGliding.activeWorld();
        if (world == null) throw new IllegalStateException("Missing world owner for private projectile impact");
        return world.projectileImpact(projectile, hit);
    }
}
