package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.world.World;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Initializes only privately allocated rollback worlds for native direct RNG reads. */
@Mixin(World.class)
public interface WorldRollbackRandomAccess {
    @Mutable @Accessor("random") void rollback$random(Random random);
}
