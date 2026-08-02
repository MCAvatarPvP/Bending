package com.jedk1.jedcore.util;

import com.projectkorra.projectkorra.ability.CoreAbility;
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
        this(block, material, data, delay, true, null, null, null);
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
        this(block, material, data, delay, temp, null, null, null);
    }

    public RegenTempBlock(Block block, Material material, BlockData data, long delay, boolean temp, RegenCallback callback) {
        this(block, material, data, delay, temp, callback, null, null);
    }

    /**
     * Replaces one exact ability-owned layer with a timed layer. Other
     * TempBlocks at the coordinate remain in their normal stack order.
     */
    public RegenTempBlock(Block block, Material material, BlockData data, long delay,
                          CoreAbility ability, TempBlock replacedLayer) {
        this(block, material, data, delay, true, null, ability, replacedLayer);
    }

    private RegenTempBlock(Block block, Material material, BlockData data, long delay,
                           boolean temp, RegenCallback callback, CoreAbility ability,
                           TempBlock replacedLayer) {
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
                retireReplacedLayer(replacedLayer);
                refreshTempBlock(block, data, ability);
            } else {
                final TempBlock tracked = temps.remove(block);
                if (tracked != null && !tracked.isReverted()) tracked.revertBlock();
                states.putIfAbsent(block, block.getState());
                block.setBlockData(data.clone());
            }
        } else {
            blocks.put(block, new RegenBlockData(System.currentTimeMillis() + delay, callback));
            // Callers that replace a moving layer must provide its exact
            // handle. Retiring the coordinate's current top here would destroy
            // an unrelated overlapping ability merely because it is on top.
            retireReplacedLayer(replacedLayer);
            if (temp) {
                createTempBlock(block, data, ability);
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

    private static void refreshTempBlock(Block block, BlockData data, CoreAbility ability) {
        TempBlock trackedTemp = temps.get(block);

        if (trackedTemp != null && !trackedTemp.isReverted()
                && TempBlock.isTempBlock(block)
                && (ability == null || trackedTemp.getAbility().orElse(null) == ability)) {
            trackedTemp.setType(data.clone());
            return;
        }

        if (trackedTemp != null && !trackedTemp.isReverted()) trackedTemp.revertBlock();
        temps.remove(block);
        createTempBlock(block, data, ability);
    }

    private static void createTempBlock(Block block, BlockData data, CoreAbility ability) {
        TempBlock tb = new TempBlock(block, data.clone(), ability);
        temps.put(block, tb);
    }

    private static void retireReplacedLayer(final TempBlock replaced) {
        if (replaced != null && !replaced.isReverted()) replaced.revertBlock();
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
