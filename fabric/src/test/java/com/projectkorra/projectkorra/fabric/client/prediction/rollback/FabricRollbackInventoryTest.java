package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackInventoryTest {
    @BeforeAll static void bootstrapRegistry() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }
    @Test void layoutRetainsEveryNativeEquipmentSlot() {
        var layout = FabricRollbackInventory.layout();
        assertEquals(PlayerInventory.MAIN_SIZE, layout.storageSize());
        assertEquals(PlayerInventory.getHotbarSize(), layout.hotbarSize());
        assertEquals(PlayerInventory.MAIN_SIZE + PlayerInventory.EQUIPMENT_SLOTS.size(), layout.size());
        assertEquals(EquipmentSlot.FEET, PlayerInventory.EQUIPMENT_SLOTS.get(layout.boots()));
        assertEquals(EquipmentSlot.LEGS, PlayerInventory.EQUIPMENT_SLOTS.get(layout.leggings()));
        assertEquals(EquipmentSlot.CHEST, PlayerInventory.EQUIPMENT_SLOTS.get(layout.chestplate()));
        assertEquals(EquipmentSlot.HEAD, PlayerInventory.EQUIPMENT_SLOTS.get(layout.helmet()));
        assertEquals(EquipmentSlot.OFFHAND, PlayerInventory.EQUIPMENT_SLOTS.get(layout.offhand()));
        assertEquals(43, layout.size());
        assertEquals(EquipmentSlot.BODY, PlayerInventory.EQUIPMENT_SLOTS.get(41));
        assertEquals(EquipmentSlot.SADDLE, PlayerInventory.EQUIPMENT_SLOTS.get(42));
    }
}
