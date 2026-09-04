package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives client-owned predicted falling blocks the same logical block view as
 * predicted abilities without installing that view in the client chunk.
 * Player movement deliberately keeps the authoritative chunk collision view:
 * render-only AIR cannot remove a real block's collision, and a render-only
 * solid TempBlock cannot add collision over real AIR.
 *
 * <p>For a predicted TempFallingBlock, Paper's physical source or previous
 * TempBlock frame can arrive after the falling block has already launched
 * locally. Composing the prediction for that falling-block entity prevents it
 * from colliding with a server block which has logically moved away.</p>
 */
@Mixin(BlockCollisionSpliterator.class)
public abstract class BlockCollisionSpliteratorPredictionMixin {
    @Shadow @Final private ShapeContext context;
    @Shadow @Final private CollisionView world;

    @Redirect(
            method = "computeNext",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/BlockView;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState projectkorra$predictedCollisionState(final BlockView chunk,
                                                             final BlockPos pos) {
        final BlockState authoritativeState = chunk.getBlockState(pos);
        if (!(this.world instanceof ClientWorld clientWorld)
                || !(this.context instanceof EntityShapeContext entityContext)) {
            return authoritativeState;
        }
        final Entity entity = entityContext.getEntity();
        return ExactPredictionRuntime.collisionBlockState(
                clientWorld, entity, pos, authoritativeState);
    }
}
