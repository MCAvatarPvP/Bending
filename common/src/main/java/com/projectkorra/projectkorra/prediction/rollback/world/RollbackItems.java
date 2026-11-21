package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;

/**
 * Native item operations for a logical inventory. Copies must be detached,
 * checkpointable ItemStack views which retain every native component. The ordinary
 * common ItemStack.clone() is not suitable: it shares metadata and loses native data.
 * Metadata views returned by these items must also preserve and checkpoint native data.
 */
public interface RollbackItems {
    ItemStack copy(ItemStack source);
    ItemStack emptyStack();
    boolean isEmpty(ItemStack item);
    /** Native item and component equality, ignoring count. */
    boolean similar(ItemStack first, ItemStack second);
    /** Includes a stack's native max_stack_size component. */
    int maximumStack(ItemStack item);
}
