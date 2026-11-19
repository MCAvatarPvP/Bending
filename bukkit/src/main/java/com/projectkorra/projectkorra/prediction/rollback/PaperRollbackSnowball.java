package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.bukkit.event.entity.EntityRemoveEvent;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The native projectile keeps vanilla flight and deflection. Impacts use the match's event and entity owners. */
final class PaperRollbackSnowball extends Snowball {
    private final PaperRollbackNativeScene scene;
    private static final MethodHandle BASE_HIT;
    static {
        try {
            BASE_HIT = MethodHandles.privateLookupIn(ThrowableProjectile.class, MethodHandles.lookup())
                    .findSpecial(Projectile.class, "onHit", MethodType.methodType(void.class, HitResult.class), ThrowableProjectile.class);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    PaperRollbackSnowball(PaperRollbackNativeScene scene) {
        super(EntityType.SNOWBALL, scene.world().world());
        this.scene = scene;
    }

    private Entity logical(net.minecraft.world.entity.Entity entity) {
        if (!scene.world().ownsEntity(entity)) throw new IllegalStateException("Projectile impact crosses the scene");
        var view = scene.logical().entities().get(entity.getUUID());
        if (view == null) throw new IllegalStateException("Projectile impact has no logical owner");
        return view;
    }

    @Override public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult hit) {
        if (!RollbackDomain.active() || !RollbackClock.active()) throw new IllegalStateException("Private projectile outside replay");
        var event = new ProjectileHitEvent();
        event.setEntity((com.projectkorra.projectkorra.platform.mc.entity.Projectile) logical(this));
        if (hit instanceof EntityHitResult target) event.setHitEntity(logical(target.getEntity()));
        if (hit instanceof BlockHitResult target) {
            BlockPos pos = target.getBlockPos();
            event.setHitBlock(scene.logical().getBlockAt(pos.getX(), pos.getY(), pos.getZ()));
        }
        Platform.events().call(event);
        hitCancelled = event.isCancelled();
        return hit.getType() == HitResult.Type.BLOCK || !hitCancelled ? hitTargetOrDeflectSelf(hit) : ProjectileDeflection.NONE;
    }

    @Override protected void onHitBlock(BlockHitResult hit) {
        if (!hitCancelled) scene.ticks().projectile(this, hit);
    }

    @Override protected void onHit(HitResult hit) {
        try { BASE_HIT.invokeExact((ThrowableProjectile)this, hit); }
        catch (Throwable failure) { throw new IllegalStateException("Private native projectile impact failed", failure); }
        level().broadcastEntityEvent(this, (byte)3);
        scene.world().event(new EntityRemoveEvent(getBukkitEntity(), EntityRemoveEvent.Cause.HIT));
        PaperRollbackPrivateAccess.retirePrivateActor(this);
    }
}
