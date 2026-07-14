package com.projectkorra.projectkorra.waterbending.util;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.PotionMeta;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;

/**
 * Loader-neutral checks for bottle-bending inventory items.
 */
public final class WaterBottleUtil {
    private WaterBottleUtil() {
    }

    public static boolean isWaterBottle(ItemStack item) {
        return item != null
                && item.getType() == Material.POTION
                && item.hasItemMeta()
                && item.getItemMeta() instanceof PotionMeta meta
                && meta.getBasePotionData() != null
                && PotionType.WATER.equals(meta.getBasePotionData().getType());
    }
}
