package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackItemPolicy;
import com.projectkorra.projectkorra.prediction.rollback.RollbackItemRules;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ItemEntity.class)
abstract class ItemRulesRollbackMixin implements FabricRollbackItemPolicy {
    @Unique private RollbackItemRules projectkorra$rules;
    @Override public void rollback$itemRules(RollbackItemRules rules) { projectkorra$rules = rules; }

    @ModifyConstant(method = {"tick", "canMerge()Z"}, constant = @Constant(intValue = 6000))
    private int projectkorra$itemDespawn(int ordinary) {
        if (projectkorra$rules == null) return ordinary;
        var item = (ItemEntity)(Object)this;
        return projectkorra$rules.despawn(Registries.ITEM.getId(item.getStack().getItem()).toString());
    }
    @WrapOperation(method = "tryMerge()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Box;expand(DDD)Lnet/minecraft/util/math/Box;"))
    private Box projectkorra$mergeRadius(Box bounds, double x, double y, double z, Operation<Box> original) {
        if (projectkorra$rules == null) return original.call(bounds, x, y, z);
        double radius = projectkorra$rules.mergeRadius();
        return bounds.expand(radius, projectkorra$rules.horizontalOnly() ? 0 : radius - .5, radius);
    }
    @WrapOperation(method = "tryMerge()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ItemEntity;tryMerge(Lnet/minecraft/entity/ItemEntity;)V"))
    private void projectkorra$mergeLineOfSight(ItemEntity self, ItemEntity other, Operation<Void> merge) {
        if (projectkorra$rules != null && projectkorra$rules.blockWalls()) {
            var clip = new RaycastContext(self.getEntityPos(), other.getEntityPos(), RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, self);
            if (self.getEntityWorld().raycast(clip).getType() == HitResult.Type.BLOCK) return;
        }
        merge.call(self, other);
    }
}
