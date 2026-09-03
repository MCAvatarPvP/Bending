package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirGliderItemTest {
    @Test
    void classicGliderIsAHexStyledTaggedStick() {
        final ItemStack item = AirGliderItem.create(null);
        final ItemMeta meta = item.getItemMeta();

        assertEquals(Material.STICK, item.getType());
        assertEquals(AirGliderItem.CUSTOM_MODEL_DATA, meta.getCustomModelData());
        assertEquals("true", meta.getCustomData(AirGliderItem.ITEM_TAG));
        assertEquals("classic", meta.getCustomData(AirGliderItem.COLOR_TAG));
        assertTrue(meta.getDisplayName().contains(ChatColor.COLOR_CHAR + "x"));
        assertEquals(4, meta.getLore().size());
        assertTrue(ChatColor.stripColor(meta.getLore().get(0)).contains("AirGlider ability to be bound"));
        assertTrue(ChatColor.stripColor(meta.getLore().get(1)).contains("either hand"));
        assertTrue(meta.getLore().stream().allMatch(line -> line.contains(ChatColor.COLOR_CHAR + "x")));
    }
}
