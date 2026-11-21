package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerExecution;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStepOutput;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;

import java.util.Collection;

/**
 * Owned Fabric player physics inside the same input/bending tick order as Paper.
 * The native adapter currently ticks PlayerEntity bodies; server-player maintenance
 * and imported Paper event/configuration policy still require parity before live use.
 */
public final class FabricRollbackExecution<E> extends RollbackPlayerExecution<E> {
    public FabricRollbackExecution(Collection<RollbackPlayer> participants, Services<E, ?> phases,
            FabricRollbackItemRelease release) {
        this(participants, new com.projectkorra.projectkorra.prediction.rollback.RollbackItemReleaseServices<>(phases, release));
    }
    public FabricRollbackExecution(Collection<RollbackPlayer> participants, FabricRollbackWorldServices world,
            Services<E, ?> phases, FabricRollbackItemRelease release) {
        this(participants, world, new com.projectkorra.projectkorra.prediction.rollback.RollbackItemReleaseServices<>(phases, release));
    }
    public FabricRollbackExecution(Collection<RollbackPlayer> participants, FabricRollbackWorldServices world, Services<E, ?> phases) {
        this(participants, new com.projectkorra.projectkorra.prediction.rollback.RollbackTimedExecutionServices<>(world, phases));
        for (var player : participants) if (player.getWorld().handle() != world.logicalWorld().handle())
            throw new IllegalArgumentException("Execution clock belongs to another private world");
    }
    public FabricRollbackExecution(Collection<RollbackPlayer> participants, Services<E, ?> services) {
        this(participants, services, new RollbackStepOutput<>());
    }
    public FabricRollbackExecution(Collection<RollbackPlayer> participants, Services<E, ?> services, RollbackStepOutput<E> output) {
        super(participants, services, output);
        Object nativeWorld = null;
        for (var player : participants) {
            if (!(player.body().kinematicsSource() instanceof FabricRollbackNativePlayerState state)
                    || !(state.ownedPlayer() instanceof FabricRollbackSimulatedPlayer)
                    || player.state().controlSource() != state || player.state().living().combatSource() != state
                    || player.state().inventory().nativeOwner() != state) {
                throw new IllegalArgumentException("Combat requires one owned native player for movement, controls and combat");
            }
            var world = state.ownedPlayer().getEntityWorld();
            if (nativeWorld == null) nativeWorld = world;
            if (world != nativeWorld) throw new IllegalArgumentException("Combat participants must share their private native world");
        }
    }

    @Override protected boolean validTarget(RollbackPlayer player, com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerInput.Edge edge) {
        if (edge.action().target() == null) return true;
        var nativePlayer = state(player).ownedPlayer();
        var range = com.projectkorra.projectkorra.prediction.rollback.RollbackInteractionTarget.targetsBlock(edge.action().target())
                ? nativePlayer.getBlockInteractionRange() : nativePlayer.getEntityInteractionRange();
        return com.projectkorra.projectkorra.prediction.rollback.RollbackInteractionTarget.allowed(player, edge, range, candidate -> {
            var entity = ((net.minecraft.server.world.ServerWorld) nativePlayer.getEntityWorld()).getEntityAnyDimension(candidate.getUniqueId());
            return entity != null && entity.canHit() && !entity.isSpectator();
        });
    }
    @Override protected void movementInput(RollbackPlayer player, RollbackMovementInput input) {
        state(player).movementInput(input);
    }
    @Override protected boolean selectSlot(RollbackPlayer player, int slot, boolean cancelled) { return state(player).selectSlot(slot, cancelled); }
    @Override protected void swapHands(RollbackPlayer player) { state(player).swapHands(); }
    @Override protected void swing(RollbackPlayer player, boolean offHand) { state(player).swing(offHand); }
    @Override protected void tickPlayer(RollbackPlayer player) { state(player).tick(); }
    private static FabricRollbackNativePlayerState state(RollbackPlayer player) {
        return (FabricRollbackNativePlayerState) player.body().kinematicsSource();
    }
}
