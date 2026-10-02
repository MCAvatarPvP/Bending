package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackInventoryNativeTest {
    @BeforeAll static void bootstrapRegistry() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    @Test void layoutRetainsEveryNativeEquipmentSlot() {
        var layout = PaperRollbackInventory.layout();
        assertEquals(Inventory.INVENTORY_SIZE, layout.storageSize());
        assertEquals(Inventory.SELECTION_SIZE, layout.hotbarSize());
        assertEquals(Inventory.INVENTORY_SIZE + Inventory.EQUIPMENT_SLOT_MAPPING.size(), layout.size());
        assertEquals(EquipmentSlot.FEET, Inventory.EQUIPMENT_SLOT_MAPPING.get(layout.boots()));
        assertEquals(EquipmentSlot.LEGS, Inventory.EQUIPMENT_SLOT_MAPPING.get(layout.leggings()));
        assertEquals(EquipmentSlot.CHEST, Inventory.EQUIPMENT_SLOT_MAPPING.get(layout.chestplate()));
        assertEquals(EquipmentSlot.HEAD, Inventory.EQUIPMENT_SLOT_MAPPING.get(layout.helmet()));
        assertEquals(EquipmentSlot.OFFHAND, Inventory.EQUIPMENT_SLOT_MAPPING.get(layout.offhand()));
        assertEquals(43, layout.size(), "1.21.11 includes body and saddle slots beyond the common API");
        assertEquals(EquipmentSlot.BODY, Inventory.EQUIPMENT_SLOT_MAPPING.get(41));
        assertEquals(EquipmentSlot.SADDLE, Inventory.EQUIPMENT_SLOT_MAPPING.get(42));
    }
}
