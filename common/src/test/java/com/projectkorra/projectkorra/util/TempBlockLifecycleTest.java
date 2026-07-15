package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockLifecycleTest {
    private RecordingListener listener;

    @AfterEach
    void cleanUp() {
        TempBlock.removeAll();
        if (listener != null) TempBlockSync.clear(listener);
    }

    @Test
    void stackedLayersRestoreOnlyFromTheirOwnOrderedLifecycle() {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock first = new TempBlock(block, Material.DIRT);
        TempBlock second = new TempBlock(block, Material.ICE);

        assertEquals(Material.ICE, block.getType());
        assertEquals(second, TempBlock.get(block));
        assertEquals(2, TempBlock.getAll(block).size());

        first.revertBlock();
        assertEquals(Material.ICE, block.getType(), "removing a buried layer must not write the world");
        assertEquals(second, TempBlock.get(block));

        second.revertBlock();
        assertEquals(Material.STONE, block.getType());
        assertFalse(TempBlock.isTempBlock(block));
    }

    @Test
    void leakedWorldStateCannotInvalidateALiveClientStack() {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock first = new TempBlock(block, Material.DIRT);

        // Simulates a physical server packet leaking into ClientWorld. The
        // registry must not treat this transport side effect as block authority.
        block.externalSet(Material.AIR);
        TempBlock second = new TempBlock(block, Material.ICE);
        assertEquals(2, TempBlock.getAll(block).size());

        second.revertBlock();
        assertEquals(Material.DIRT, block.getType());
        assertEquals(first, TempBlock.get(block));
        first.revertBlock();
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    void externalAuthorityDiscardNeverWritesARollback() {
        FakeBlock block = new FakeBlock(Material.STONE);
        new TempBlock(block, Material.ICE);
        block.externalSet(Material.GOLD_BLOCK);

        TempBlock.removeBlock(block);

        assertEquals(Material.GOLD_BLOCK, block.getType());
        assertFalse(TempBlock.isTempBlock(block));
    }

    @Test
    void callbackFreeDiscardStillClosesThePublishedLayer() {
        FakeBlock block = new FakeBlock(Material.STONE);
        listener = new RecordingListener();
        TempBlockSync.install(listener);
        TempBlock layer = new TempBlock(block, Material.ICE);
        AtomicBoolean callback = new AtomicBoolean();
        layer.setRevertTask(() -> callback.set(true));
        block.externalSet(Material.GOLD_BLOCK);
        listener.events.clear();

        TempBlock.discardBlock(block);

        assertEquals(Material.GOLD_BLOCK, block.getType());
        assertFalse(callback.get());
        assertTrue(layer.isReverted());
        assertEquals(List.of("after:" + layer.getRevision()), listener.events);
    }

    @Test
    void runtimeShutdownDiscardDoesNotWriteOrInvokeAbilityCallbacks() {
        FakeBlock firstBlock = new FakeBlock(Material.STONE);
        FakeBlock secondBlock = new FakeBlock(Material.DIRT);
        TempBlock first = new TempBlock(firstBlock, Material.ICE);
        new TempBlock(secondBlock, Material.WATER);
        AtomicBoolean callback = new AtomicBoolean();
        first.setRevertTask(() -> callback.set(true));
        firstBlock.externalSet(Material.GOLD_BLOCK);
        secondBlock.externalSet(Material.DIAMOND_BLOCK);

        TempBlock.discardAll();

        assertEquals(Material.GOLD_BLOCK, firstBlock.getType());
        assertEquals(Material.DIAMOND_BLOCK, secondBlock.getType());
        assertFalse(callback.get());
        assertTrue(first.isReverted());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
    }

    @Test
    void revertingABuriedLayerPublishesLifecycleOnlyAndNeverWritesTheTop() {
        FakeBlock block = new FakeBlock(Material.STONE);
        listener = new RecordingListener();
        TempBlockSync.install(listener);
        TempBlock buried = new TempBlock(block, Material.DIRT);
        TempBlock top = new TempBlock(block, Material.ICE);
        listener.events.clear();

        buried.revertBlock();

        assertEquals(Material.ICE, block.getType());
        assertEquals(top, TempBlock.get(block));
        assertEquals(List.of("after:" + buried.getRevision()), listener.events);
    }

    @Test
    void hideMetadataPrecedesThePhysicalWriteAtOneRevision() {
        FakeBlock block = new FakeBlock(Material.STONE);
        listener = new RecordingListener();
        TempBlockSync.install(listener);

        TempBlock layer = new TempBlock(block, Material.ICE);

        assertTrue(layer.getRevision() > 0L);
        assertEquals(List.of("before:" + layer.getRevision(), "write:" + layer.getRevision(),
                "after:" + layer.getRevision()), listener.events);
        layer.revertBlock();
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    void aRevertedLayerCannotBeResurrectedBySetType() {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock layer = new TempBlock(block, Material.DIRT);
        layer.revertBlock();

        layer.setType(Material.ICE);

        assertTrue(layer.isReverted());
        assertEquals(Material.STONE, block.getType());
        assertFalse(TempBlock.isTempBlock(block));
    }

    @Test
    void failedCreateCannotLeaveAPhantomRegistryLayer() {
        FakeBlock block = new FakeBlock(Material.STONE);
        block.failNextWrite();

        assertThrows(IllegalStateException.class, () -> new TempBlock(block, Material.ICE));

        assertEquals(Material.STONE, block.getType());
        assertFalse(TempBlock.isTempBlock(block));
        assertTrue(TempBlock.getActiveLayers().isEmpty());
    }

    @Test
    void failedTypeUpdateRestoresThePreviousLayerTransaction() {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock layer = new TempBlock(block, Material.DIRT);
        block.failNextWrite();

        assertThrows(IllegalStateException.class, () -> layer.setType(Material.ICE));

        assertEquals(Material.DIRT, block.getType());
        assertEquals(Material.DIRT, layer.getBlockData().getMaterial());
        assertEquals(layer, TempBlock.get(block));
    }

    @Test
    void transientStateRestoreFailureUsesTheScopedFallbackAndStillCloses() {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock layer = new TempBlock(block, Material.ICE);
        block.failNextWrite();

        layer.revertBlock();

        assertEquals(Material.STONE, block.getType());
        assertTrue(layer.isReverted());
        assertFalse(TempBlock.isTempBlock(block));
    }

    private final class RecordingListener implements TempBlockSync.Listener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void beforeWorldChange(TempBlockSync.Change change) {
            events.add("before:" + change.revision());
        }

        @Override
        public void onChange(TempBlockSync.Change change) {
            events.add("after:" + change.revision());
        }
    }

    private final class FakeBlock extends Block {
        private final FakeWorld world = new FakeWorld();
        private BlockData data;
        private boolean failNextWrite;

        private FakeBlock(Material material) {
            this.data = material.createBlockData();
        }

        private void externalSet(Material material) {
            this.data = material.createBlockData();
        }

        private void failNextWrite() {
            this.failNextWrite = true;
        }

        @Override
        public Material getType() {
            return data.getMaterial();
        }

        @Override
        public BlockData getBlockData() {
            return data.clone();
        }

        @Override
        public void setBlockData(BlockData data, boolean physics) {
            TempBlockSync.WorldMutation mutation = TempBlockSync.currentWorldMutation();
            assertNotNull(mutation, "TempBlock writes must be explicitly scoped");
            if (listener != null) listener.events.add("write:" + mutation.revision());
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException("simulated write failure");
            }
            this.data = data.clone();
        }

        @Override
        public void setBlockData(BlockData data) {
            setBlockData(data, false);
        }

        @Override
        public BlockState getState() {
            return new FakeState(this, data.clone());
        }

        @Override
        public World getWorld() {
            return world;
        }

        @Override
        public Location getLocation() {
            return new Location(world, 4, 64, 8);
        }

        @Override
        public int getX() {
            return 4;
        }

        @Override
        public int getY() {
            return 64;
        }

        @Override
        public int getZ() {
            return 8;
        }

        @Override
        public Block getRelative(BlockFace face) {
            return this;
        }
    }

    private static final class FakeState extends BlockState {
        private final FakeBlock block;
        private final BlockData data;

        private FakeState(FakeBlock block, BlockData data) {
            this.block = block;
            this.data = data;
        }

        @Override
        public Material getType() {
            return data.getMaterial();
        }

        @Override
        public BlockData getBlockData() {
            return data.clone();
        }

        @Override
        public Block getBlock() {
            return block;
        }

        @Override
        public boolean update(boolean force, boolean physics) {
            block.setBlockData(data.clone(), physics);
            return true;
        }
    }

    private static final class FakeWorld extends World {
        @Override
        public String getName() {
            return "temp-block-test";
        }
    }
}
