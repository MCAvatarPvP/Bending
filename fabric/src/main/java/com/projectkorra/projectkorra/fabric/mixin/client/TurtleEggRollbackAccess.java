package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(TurtleEggBlock.class)
public interface TurtleEggRollbackAccess {
    @Invoker("breakEgg") void rollback$breakEgg(World world, BlockPos pos, BlockState state);
}
