package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityRollbackControlAccess {
    @Invoker("setFlag") void rollback$setFlag(int flag, boolean value);
    @Mutable @Accessor("random") void rollback$random(net.minecraft.util.math.random.Random random);
}
