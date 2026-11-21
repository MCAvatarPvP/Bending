package com.projectkorra.projectkorra.fabric;

import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;

/** Ports Bukkit block physics events that Fabric does not expose directly. */
public final class FabricPhysicsBridge {
    private FabricPhysicsBridge() {
    }

    public static boolean shouldCancelFluidFlow(WorldAccess world, BlockPos targetPos, Direction flowDirection) {
        if (!(world instanceof ServerWorld serverWorld) || targetPos == null || flowDirection == null) {
            return false;
        }

        if (world instanceof PrivateFluidFlow privateWorld && privateWorld.projectKorra$fluidFlow() != null) {
            return privateWorld.projectKorra$fluidFlow().cancelFlow(targetPos.offset(flowDirection.getOpposite()), targetPos);
        }
        Block toBlock = FabricMC.block(serverWorld, targetPos);
        Block fromBlock = FabricMC.block(serverWorld, targetPos.offset(flowDirection.getOpposite()));

        return com.projectkorra.projectkorra.util.BendingFluidPhysics.cancelFlow(fromBlock, toBlock);
    }
}
