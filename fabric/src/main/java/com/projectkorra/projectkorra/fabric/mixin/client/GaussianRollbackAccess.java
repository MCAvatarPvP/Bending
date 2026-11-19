package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.util.math.random.GaussianGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GaussianGenerator.class)
public interface GaussianRollbackAccess {
    @Accessor("hasNextGaussian") void rollback$hasGaussian(boolean present);
    @Accessor("nextNextGaussian") void rollback$gaussianValue(double value);
}
