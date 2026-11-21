package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only native geometry access; ordinary shape/collision behavior is unchanged. */
@Mixin(VoxelShape.class)
public interface VoxelShapeRollbackAccess {
    @Accessor("voxels") VoxelSet rollback$voxels();
}
