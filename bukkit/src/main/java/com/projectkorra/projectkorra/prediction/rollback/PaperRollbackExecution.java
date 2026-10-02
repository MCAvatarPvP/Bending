package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

/** Paper's owned ServerPlayer tick phases within the shared combat execution order. */
public final class PaperRollbackExecution<E> extends RollbackPlayerExecution<E> {
    public PaperRollbackExecution(Collection<RollbackPlayer> participants, Services<E, ?> services) {
        super(participants, services);
        Object nativeWorld = null;
        for (var player : participants) {
            if (!(player.body().kinematicsSource() instanceof PaperRollbackNativePlayerState state)
                    || !(state.ownedPlayer() instanceof ServerPlayer nativePlayer)
                    || player.state().controlSource() != state || player.state().living().combatSource() != state
                    || player.state().inventory().nativeOwner() != state) {
                throw new IllegalArgumentException("Combat requires an owned native server player");
            }
            if (nativeWorld == null) nativeWorld = nativePlayer.level();
            if (nativePlayer.level() != nativeWorld) throw new IllegalArgumentException("Combat participants must share their private native world");
        }
    }

    @Override protected void movementInput(RollbackPlayer player, RollbackMovementInput input) {
        state(player).movementInput(input);
    }
    @Override protected void tickPlayer(RollbackPlayer player) { state(player).tick(); }
    private static PaperRollbackNativePlayerState state(RollbackPlayer player) {
        return (PaperRollbackNativePlayerState) player.body().kinematicsSource();
    }
}
