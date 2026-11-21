package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackItemRules;

/** Per-actor Paper item policy, installed only on an item owned by an imported arena. */
public interface FabricRollbackItemPolicy {
    void rollback$itemRules(RollbackItemRules rules);
}
