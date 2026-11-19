package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.EntityList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Owned entity membership consulted by the vanilla passenger tick. */
@Mixin(ServerWorld.class)
public interface ServerWorldRollbackEntities {
    @Mutable @Accessor("entityList") void rollback$entities(EntityList entities);
}
