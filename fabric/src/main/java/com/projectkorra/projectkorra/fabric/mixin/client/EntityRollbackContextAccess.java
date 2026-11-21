package com.projectkorra.projectkorra.fabric.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.tag.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Set;

/** Direct initialization of the private replica's contact caches; no fluid/world query is rerun. */
@Mixin(Entity.class)
public interface EntityRollbackContextAccess {
    @Accessor("fluidHeight") Object2DoubleMap<TagKey<Fluid>> rollback$fluidHeights();
    @Accessor("submergedFluidTag") Set<TagKey<Fluid>> rollback$eyeFluids();
    @Accessor("pistonMovementDelta") double[] rollback$pistons();
}
