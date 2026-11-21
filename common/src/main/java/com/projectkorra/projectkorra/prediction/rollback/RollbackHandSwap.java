package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import java.util.Objects;

/** Captured swap-event result. The two items describe the proposed post-swap hands. */
public record RollbackHandSwap(boolean cancelled, RollbackItemData mainHand, RollbackItemData offHand) {
    public RollbackHandSwap { Objects.requireNonNull(mainHand); Objects.requireNonNull(offHand); }
}
