package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.prediction.block.TempBlockSync;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TempBlockSyncCodecTest {
    @Test
    void genericBlockSnapshotsKeepEveryNativePropertyAcrossCloningAndSync() {
        BlockData stairs = Material.COBBLESTONE_STAIRS.createBlockData();
        stairs.setExactState("minecraft:cobblestone_stairs[facing=east,half=top,shape=inner_left,waterlogged=true]");

        BlockData clone = stairs.clone();

        assertEquals(stairs.getExactState(), clone.getExactState());
        assertEquals(stairs.getExactState(), TempBlockSync.encode(clone));
    }

    @Test
    void snowKeepsItsMaterialLayersAndCloneAcrossTheLifecycleBoundary() {
        Snow snow = assertInstanceOf(Snow.class, Material.SNOW.createBlockData());
        snow.setLayers(6);

        Snow clone = assertInstanceOf(Snow.class, snow.clone());
        assertEquals(Material.SNOW, clone.getMaterial());
        assertEquals(6, clone.getLayers());
        assertEquals("minecraft:snow;layers=6", TempBlockSync.encode(clone));
    }
}
