package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackWorldSettings.ArrowLifetime;

/** Owned native arrows retain the world rules captured by Paper at enrollment. */
public interface FabricRollbackArrow {
    void rollback$arrowLifetime(ArrowLifetime lifetime);
}
