package com.projectkorra.projectkorra.waterbending.util;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.PotionMeta;
import com.projectkorra.projectkorra.platform.mc.potion.PotionData;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterBottleUtilTest {
    @Test
    void constructedPotionStackUsesPotionMetadata() {
        ItemStack bottle = new ItemStack(Material.POTION);
        PotionMeta meta = assertInstanceOf(PotionMeta.class, bottle.getItemMeta());
        meta.setBasePotionData(new PotionData(PotionType.WATER));

        assertTrue(WaterBottleUtil.isWaterBottle(bottle));
    }

    @Test
    void malformedPotionMetadataIsSkippedInsteadOfThrowing() {
        ItemStack malformed = new ItemStack(Material.POTION);
        malformed.setItemMeta(new ItemMeta());

        assertFalse(WaterBottleUtil.isWaterBottle(malformed));
    }

    @Test
    void platformWrappedPotionTypeHasValueEquality() {
        PotionMeta meta = new PotionMeta();
        meta.setBasePotionData(new PotionData(PotionType.valueOf("water")));
        ItemStack bottle = new ItemStack(Material.POTION);
        bottle.setItemMeta(meta);

        assertTrue(WaterBottleUtil.isWaterBottle(bottle));
    }
}
