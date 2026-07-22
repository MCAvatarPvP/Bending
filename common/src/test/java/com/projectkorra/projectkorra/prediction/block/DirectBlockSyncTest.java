package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectBlockSyncTest {
    private RecordingListener listener;

    @AfterEach
    void cleanUp() {
        if (listener != null) DirectBlockSync.clear(listener);
    }

    @Test
    void semanticAttemptsIncludeNoOpsButOnlyChangesExpectPackets() {
        listener = new RecordingListener();
        DirectBlockSync.install(listener);
        FakeBlock block = new FakeBlock(Material.STONE);

        DirectBlockSync.beforeWorldChange(block, Material.STONE.createBlockData());
        DirectBlockSync.beforeWorldChange(block, Material.DIRT.createBlockData());

        assertEquals(List.of(false, true), listener.packetExpectations,
                "both common calls advance ordering; only the actual state change has a vanilla echo");
    }

    private static final class RecordingListener implements DirectBlockSync.Listener {
        private final List<Boolean> packetExpectations = new ArrayList<>();

        @Override
        public void beforeChange(CoreAbility ability, Block block, BlockData replacement,
                                 boolean packetExpected) {
            packetExpectations.add(packetExpected);
        }
    }

    private static final class FakeBlock extends Block {
        private final BlockData data;

        private FakeBlock(Material material) {
            this.data = material.createBlockData();
        }

        @Override
        public Material getType() {
            return data.getMaterial();
        }

        @Override
        public BlockData getBlockData() {
            return data.clone();
        }
    }
}
