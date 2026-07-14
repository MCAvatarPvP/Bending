package com.projectkorra.projectkorra.fabric;

import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.earthbending.passive.EarthPassive;
import com.projectkorra.projectkorra.firebending.Illumination;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.WaterBubble;
import com.projectkorra.projectkorra.waterbending.WaterManipulation;
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

        Block toBlock = FabricMC.block(serverWorld, targetPos);
        Block fromBlock = FabricMC.block(serverWorld, targetPos.offset(flowDirection.getOpposite()));

        if (TempBlock.isTempBlock(fromBlock) || TempBlock.isTempBlock(toBlock)) {
            return true;
        }

        if (ElementalAbility.isLava(fromBlock)) {
            return !EarthPassive.canFlowFromTo(fromBlock, toBlock);
        }

        if (ElementalAbility.isWater(fromBlock)) {
            if (WaterBubble.isAir(toBlock)) {
                return true;
            }
            if (!WaterManipulation.canFlowFromTo(fromBlock, toBlock)) {
                return true;
            }
            if (Illumination.isIlluminationTorch(toBlock)) {
                toBlock.setType(Material.AIR);
            }
        }

        return false;
    }
}
