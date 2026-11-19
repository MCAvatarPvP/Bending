package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.PrivateFluidFlow;
import net.minecraft.server.world.ServerWorld;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Objects;


@Mixin(ServerWorld.class)
abstract class ServerWorldRollbackFluid implements PrivateFluidFlow {
    @Unique private Policy projectKorra$flow;

    @Override public final void projectKorra$bindFluidFlow(Policy policy) {
        if (projectKorra$flow != null) throw new IllegalStateException("Native fluid policy is already bound");
        projectKorra$flow = Objects.requireNonNull(policy);
    }

    @Override public final Policy projectKorra$fluidFlow() { return projectKorra$flow; }
}
