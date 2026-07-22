package com.projectkorra.projectkorra.util;

import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockLifecycleTest {
    private RecordingListener listener;

    @AfterEach
    void cleanUp() {
        RegenTempBlock.revertAll();
        TempBlock.removeAll();
        if (listener != null) TempBlockSync.clear(listener);
    }

    @Test
    void regenReplacementConsumesMovingWaterBeforeTimedIceExpires() throws ReflectiveOperationException {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock movingWater = new TempBlock(block, Material.WATER);

        final var replacement = RegenTempBlock.class.getDeclaredMethod("retireReplacedLayer", Block.class);
        replacement.setAccessible(true);
        replacement.invoke(null, block);
        TempBlock timedIce = new TempBlock(block, Material.ICE);

        assertTrue(movingWater.isReverted(),
                "freezing a moving-water lifecycle must retire it instead of burying it");
        assertEquals(Material.ICE, block.getType());
        assertEquals(1, TempBlock.getAll(block).size());

        timedIce.revertBlock();

        assertEquals(Material.STONE, block.getType());
        assertFalse(TempBlock.isTempBlock(block),
                "the expired ice must not uncover a never-expiring water layer");
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
    void overlappingCloseRevisionsDescribeTheFinalVisibleStateInEventOrder() {
        FakeBlock block = new FakeBlock(Material.STONE);
        List<TempBlockSync.Change> closes = new ArrayList<>();
        TempBlockSync.Listener closeListener = change -> {
            if (change.operation() == TempBlockSync.Operation.REVERT
                    || change.operation() == TempBlockSync.Operation.DISCARD) {
                closes.add(change);
            }
        };
        TempBlockSync.install(closeListener);
        try {
            TempBlock buried = new TempBlock(block, Material.WATER);
            TempBlock top = new TempBlock(block, Material.ICE);

            top.revertBlock();
            buried.revertBlock();

            assertEquals(2, closes.size());
            assertEquals(Material.WATER, closes.get(0).data().getMaterial());
            assertEquals(Material.STONE, closes.get(1).data().getMaterial());
            assertTrue(closes.get(1).revision() > closes.get(0).revision(),
                    "the final close revision, not creation/layer id, determines the resulting client state");
        } finally {
            TempBlockSync.clear(closeListener);
        }
    }

    @Test
    void legacyCoordinateCleanupRetainsItsAllLayerBehavior() {
        FakeBlock block = new FakeBlock(Material.STONE);
        DummyAbility firstOwner = new DummyAbility("first");
        DummyAbility secondOwner = new DummyAbility("second");
        TempBlock[] layers = new TempBlock[2];
        AbilityExecutionContext.run(firstOwner,
                () -> layers[0] = new TempBlock(block, Material.DIRT));
        AbilityExecutionContext.run(secondOwner,
                () -> layers[1] = new TempBlock(block, Material.ICE));

        AbilityExecutionContext.run(firstOwner,
                () -> TempBlock.revertBlock(block, Material.AIR));

        assertTrue(layers[0].isReverted());
        assertTrue(layers[1].isReverted());
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    void semanticIdentityIsReproducibleAcrossAbilityInstances() {
        DummyAbility paper = new DummyAbility("PhaseChange");
        DummyAbility fabric = new DummyAbility("PhaseChange");
        TempBlock[] paperLayers = new TempBlock[2];
        TempBlock[] fabricLayers = new TempBlock[2];

        AbilityExecutionContext.run(paper, () -> {
            paperLayers[0] = new TempBlock(new FakeBlock(Material.WATER), Material.ICE);
            paperLayers[1] = new TempBlock(new FakeBlock(Material.WATER), Material.ICE);
        });
        AbilityExecutionContext.run(fabric, () -> {
            fabricLayers[0] = new TempBlock(new FakeBlock(Material.WATER), Material.ICE);
            fabricLayers[1] = new TempBlock(new FakeBlock(Material.WATER), Material.ICE);
        });

        for (int index = 0; index < 2; index++) {
            assertEquals(paperLayers[index].getEffectAbility(), fabricLayers[index].getEffectAbility());
            assertEquals(paperLayers[index].getEffectStep(), fabricLayers[index].getEffectStep());
            assertEquals(paperLayers[index].getEffectOrdinal(), fabricLayers[index].getEffectOrdinal());
        }
        assertEquals(1, paperLayers[0].getEffectOrdinal());
        assertEquals(2, paperLayers[1].getEffectOrdinal());

        paperLayers[0].setType(Material.PACKED_ICE);
        assertEquals(1, paperLayers[0].getEffectOrdinal(),
                "updates must retain the CREATE identity used to close the layer");
    }

    @Test
    void exactDiscardNeverRemovesOrRepaintsAnOverlappingLayer() {
        FakeBlock block = new FakeBlock(Material.STONE);
        TempBlock buried = new TempBlock(block, Material.DIRT);
        TempBlock top = new TempBlock(block, Material.ICE);
        AtomicBoolean callback = new AtomicBoolean();
        buried.setRevertTask(() -> callback.set(true));

        buried.discard();

        assertTrue(buried.isReverted());
        assertFalse(top.isReverted());
        assertFalse(callback.get());
        assertEquals(Material.ICE, block.getType());
        assertEquals(top, TempBlock.get(block));
        top.revertBlock();
        assertEquals(Material.STONE, block.getType());
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
    void externalWriteHandoffPublishesBeforeTheReplacementWithoutRollback() {
        FakeBlock block = new FakeBlock(Material.STONE);
        listener = new RecordingListener();
        TempBlockSync.install(listener);
        TempBlock layer = new TempBlock(block, Material.ICE);
        AtomicBoolean callback = new AtomicBoolean();
        layer.setRevertTask(() -> callback.set(true));
        listener.events.clear();

        TempBlock.removeBlockBeforeWrite(block, Material.GOLD_BLOCK.createBlockData());

        assertEquals(Material.ICE, block.getType(),
                "handoff must not restore a captured snapshot before the ordinary write");
        assertFalse(TempBlock.isTempBlock(block));
        assertTrue(callback.get());
        assertEquals(List.of("before:" + layer.getRevision()), listener.events,
                "the semantic close must be flushed before the adapter emits vanilla authority");
        block.externalSet(Material.GOLD_BLOCK);
        assertEquals(Material.GOLD_BLOCK, block.getType());
    }

    @Test
    void externalWriteHandoffCarriesOneBaseUnderEveryOverlappingLayer() {
        FakeBlock block = new FakeBlock(Material.STONE);
        listener = new RecordingListener();
        TempBlockSync.install(listener);
        new TempBlock(block, Material.WATER);
        new TempBlock(block, Material.ICE);
        listener.events.clear();
        listener.underlays.clear();

        TempBlock.removeBlockBeforeWrite(block, Material.AIR.createBlockData());

        assertEquals(List.of(Material.STONE, Material.STONE), listener.underlays,
                "discarding a stack must never expose a newer layer's intermediate creation snapshot");
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
    void activeLayerIdentityIndexTracksHundredsOfLayersThroughEveryClosePath() {
        List<TempBlock> layers = new ArrayList<>();
        for (int index = 0; index < 600; index++) {
            TempBlock layer = new TempBlock(new FakeBlock(Material.STONE), Material.ICE);
            layers.add(layer);
            assertSame(layer, TempBlock.getActiveLayer(layer.getLayerId()));
        }

        for (int index = 0; index < layers.size(); index++) {
            TempBlock layer = layers.get(index);
            if ((index & 1) == 0) layer.revertBlock();
            else layer.discard();
            assertNull(TempBlock.getActiveLayer(layer.getLayerId()));
        }

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
        private final List<Material> underlays = new ArrayList<>();

        @Override
        public void beforeWorldChange(TempBlockSync.Change change) {
            events.add("before:" + change.revision());
            underlays.add(change.underlayData().getMaterial());
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

    private static final class DummyAbility extends CoreAbility {
        private final String name;

        private DummyAbility(String name) {
            this.name = name;
        }

        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return name; }
        @Override public Element getElement() { return Element.EARTH; }
        @Override public Location getLocation() { return null; }
    }
}
