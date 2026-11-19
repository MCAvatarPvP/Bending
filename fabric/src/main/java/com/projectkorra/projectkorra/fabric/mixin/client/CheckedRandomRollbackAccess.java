package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.GaussianGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(CheckedRandom.class)
public interface CheckedRandomRollbackAccess {
    @Accessor("seed") AtomicLong rollback$bits();
    @Accessor("gaussianGenerator") GaussianGenerator rollback$gaussian();
}
