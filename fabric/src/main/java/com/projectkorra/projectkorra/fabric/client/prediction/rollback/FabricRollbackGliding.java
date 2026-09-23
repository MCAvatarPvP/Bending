package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.entity.player.PlayerEntity;
import java.util.function.Function;

/** Native-only execution scope; event policy belongs to the checkpointed private world. */
final class FabricRollbackGliding {
    private static final ThreadLocal<FabricRollbackWorldAccess> ACTIVE = new ThreadLocal<>();
    private FabricRollbackGliding() { }
    static <R> R use(FabricRollbackWorldAccess world, PlayerEntity player, Function<PlayerEntity, R> operation) {
        var previous = ACTIVE.get();
        if (previous != null && previous != world) throw new IllegalStateException("Native glide scope crossed private worlds");
        ACTIVE.set(world);
        try { return operation.apply(player); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    static boolean allowed(PlayerEntity player, boolean gliding) {
        var world = ACTIVE.get();
        if (world == null) throw new IllegalStateException("Native glide event outside private execution");
        return world.glideAllowed(player, gliding);
    }
}
