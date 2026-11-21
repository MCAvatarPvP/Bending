package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

/** Applies Paper's captured limit only to an owned player inventory during bootstrap. */
public interface FabricRollbackInventoryLimit {
    void rollback$maximumStack(int value);
}
