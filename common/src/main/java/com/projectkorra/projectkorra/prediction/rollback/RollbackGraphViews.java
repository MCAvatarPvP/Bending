package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import java.util.function.Function;
import java.util.function.Supplier;

/** Portable views of private world objects for outgoing gameplay graph capture. */
public final class RollbackGraphViews implements Supplier<Function<Object, RollbackStateTransfer.Replacement>> {
    @Override public Function<Object, RollbackStateTransfer.Replacement> get() {
        return value -> {
            var block = RollbackBlockStore.portableReference(value);
            return block == null ? null : RollbackStateTransfer.Replacement.fromProjection(block);
        };
    }
}
