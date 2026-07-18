package com.jedk1.jedcore.util;

import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.InventoryHolder;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class RegenTempBlock {

    public static Map<Block, RegenBlockData> blocks = new HashMap<>();
    public static Map<Block, TempBlock> temps = new HashMap<>();
    public static Map<Block, BlockState> states = new HashMap<>();

    /**
     * Creates a TempBlock that reverts after a delay.
     *
     * @param block    Block to be updated/reverted.
     * @param material Material to be changed.
     * @param data     Data to be changed.
     * @param delay    Delay until block regens.
     */
    public RegenTempBlock(Block block, Material material, BlockData data, long delay) {
        this(block, material, data, delay, true);
    }

    /**
     * Creates a TempBlock or a State of a block that reverts after a certain time.
     *
     * @param block    Block to be updated/reverted.
     * @param material Material to be changed.
     * @param data     Data to be changed.
     * @param delay    Delay until block regens.
     * @param temp     Use TempBlock or BlockState.
     */
    @SuppressWarnings("deprecation")
    public RegenTempBlock(Block block, Material material, BlockData data, long delay, boolean temp) {
        this(block, material, data, delay, temp, null);
    }

    public RegenTempBlock(Block block, Material material, BlockData data, long delay, boolean temp, RegenCallback callback) {
        if (DensityShift.isPassiveSand(block)) {
            DensityShift.revertSand(block);
        }
        if (block.getState() instanceof InventoryHolder || block.getType() == Material.JUKEBOX) {
            return;
        }
        if (blocks.containsKey(block)) {
            blocks.replace(block, new RegenBlockData(System.currentTimeMillis() + delay, callback));
            if (temp) {
                final BlockState directState = states.remove(block);
                if (directState != null) directState.update(true);
                refreshTempBlock(block, data);
            } else {
                final TempBlock tracked = temps.remove(block);
                if (tracked != null && !tracked.isReverted()) tracked.revertBlock();
                states.putIfAbsent(block, block.getState());
                block.setBlockData(data.clone());
            }
        } else {
            blocks.put(block, new RegenBlockData(System.currentTimeMillis() + delay, callback));
            // RegenTempBlock is a replacement lifecycle, not an overlapping
            // TempBlock layer.  WaterFlow relies on this when it freezes its
            // moving WATER: the water handle is consumed before the timed ICE
            // handle captures the real world state.  Keeping the water buried
            // under the ice makes it reappear after the ice expires and leaves
            // an unowned, never-expiring water trail.
            retireReplacedLayer(block);
            if (temp) {
                createTempBlock(block, data);
            } else {
                states.put(block, block.getState());
                if (material != null) {
                    block.setBlockData(data.clone());
                }
            }
        }
    }

    /**
     * Manages blocks to be reverted.
     */
    public static void manage() {
        Iterator<Map.Entry<Block, RegenBlockData>> iterator = blocks.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Block, RegenBlockData> entry = iterator.next();

            Block b = entry.getKey();
            RegenBlockData blockData = entry.getValue();

            if (System.currentTimeMillis() >= blockData.endTime) {
                TempBlock tb = temps.get(b);
                if (tb != null) {
                    tb.revertBlock();
                    temps.remove(b);
                }

                BlockState bs = states.remove(b);
                if (bs != null) {
                    bs.update(true);
                }

                iterator.remove();

                if (blockData.callback != null) {
                    blockData.callback.onRegen(b);
                }
            }
        }
    }

    /**
     * Reverts an individual block.
     *
     * @param block
     */
    public static void revert(Block block) {
        if (blocks.containsKey(block)) {
            if (temps.containsKey(block)) {
                TempBlock tb = temps.get(block);
                tb.revertBlock();
                temps.remove(block);
            }
            if (states.containsKey(block)) {
                states.get(block).update(true);
                states.remove(block);
            }
            blocks.remove(block);
        }
    }

    /**
     * Reverts all blocks.
     */
    public static void revertAll() {
        for (Block b : blocks.keySet()) {
            if (temps.containsKey(b)) {
                TempBlock tb = temps.get(b);
                tb.revertBlock();
            }
            if (states.containsKey(b)) {
                states.get(b).update(true);
            }
        }
        temps.clear();
        states.clear();
        blocks.clear();
    }

    /**
     * Returns true if the block is a RegenTempBlock.
     *
     * @param block
     * @return
     */
    public static boolean hasBlock(Block block) {
        if (blocks.containsKey(block)) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if the block is stored as a temp block.
     *
     * @param block
     * @return
     */
    public static boolean isTempBlock(Block block) {
        if (temps.containsKey(block)) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if the block is stored as a block state.
     *
     * @param block
     * @return
     */
    public static boolean isBlockState(Block block) {
        if (states.containsKey(block)) {
            return true;
        }
        return false;
    }

    private static void refreshTempBlock(Block block, BlockData data) {
        TempBlock trackedTemp = temps.get(block);

        if (trackedTemp != null && !trackedTemp.isReverted() && TempBlock.isTempBlock(block)) {
            trackedTemp.setType(data.clone());
            return;
        }

        temps.remove(block);
        createTempBlock(block, data);
    }

    private static void createTempBlock(Block block, BlockData data) {
        TempBlock tb = new TempBlock(block, data.clone());
        temps.put(block, tb);
    }

    private static void retireReplacedLayer(final Block block) {
        final TempBlock replaced = TempBlock.get(block);
        if (replaced != null) replaced.revertBlock();
    }

    public interface RegenCallback {
        void onRegen(Block block);
    }

    private static class RegenBlockData {
        long endTime;
        RegenCallback callback;

        public RegenBlockData(long endTime, RegenCallback callback) {
            this.endTime = endTime;
            this.callback = callback;
        }
    }
}
