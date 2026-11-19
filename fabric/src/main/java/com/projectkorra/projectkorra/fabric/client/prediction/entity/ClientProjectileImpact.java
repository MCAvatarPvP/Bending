package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.util.hit.HitResult;
import java.util.function.BooleanSupplier;

/** Dispatches each completed local impact before applying the native server-side snowball removal. */
public final class ClientProjectileImpact {
    private ClientProjectileImpact() { }

    public static boolean deliver(ProjectileEntity projectile, HitResult.Type hit, BooleanSupplier dispatch) {
        if (projectile.isRemoved()) return true;
        boolean cancelled = dispatch.getAsBoolean();
        if (projectile instanceof SnowballEntity && (!cancelled || hit == HitResult.Type.BLOCK)) {
            // SnowballEntity.onCollision() discards only on the server. In ClientWorld an owned
            // prediction would otherwise hit the same surface and recreate its cloud every tick.
            projectile.discard();
        }
        return cancelled || projectile.isRemoved();
    }
}
