package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.earthbending.passive.EarthPassive;
import com.projectkorra.projectkorra.firebending.Illumination;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.waterbending.WaterBubble;
import com.projectkorra.projectkorra.waterbending.WaterManipulation;

/** Standard bending rules for a native fluid change in the supplied world. */
public final class BendingFluidPhysics {
    private BendingFluidPhysics() { }

    public static boolean cancelFlow(Block fromBlock, Block toBlock) {
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

    public static boolean cancelLevelChange(Block block) {
        return TempBlock.isTempBlock(block) || TempBlock.isTouchingTempBlock(block);
    }
}
