package com.projectkorra.projectkorra.fabric;

import net.minecraft.util.math.BlockPos;


/** Per-world callback for a client-side private native world. */
public interface PrivateFluidFlow {
    interface Policy {
        boolean cancelFlow(BlockPos from, BlockPos to);
        boolean cancelLevelChange(BlockPos block);
    }

    void projectKorra$bindFluidFlow(Policy policy);
    Policy projectKorra$fluidFlow();
}
