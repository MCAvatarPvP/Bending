package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

/** Native gameplay remaining after the common bending input handler accepts an edge. */
final class PaperRollbackPhases implements RollbackPlayerExecution.Services<Object, Void> {
    private final PaperRollbackNativeScene scene;
    private final PaperRollbackActors actors;
    private RollbackStep<Object> step;
    PaperRollbackPhases(PaperRollbackNativeScene scene, PaperRollbackActors actors) {
        this.scene = scene; this.actors = actors;
    }
    @Override public void begin(RollbackStep<Object> effects) { scene.services().advanceTick(effects.tick()); step = effects; }
    @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) {
        if (result.cancelEvent()) return;
        var state = scene.roster().get(player.getUniqueId());
        if (player.body().kinematicsSource() != state) throw new IllegalArgumentException("Foreign native input player");
        var body = (ServerPlayer) state.ownedPlayer();
        var hand = edge.action().hand() == RollbackInputActions.Hand.OFF ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        switch (edge.action().kind()) {
            case SNEAK_START, SNEAK_STOP -> { }
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                var target = (RollbackInteractionTarget.Dig) edge.action().target();
                var pos = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(target.x(), target.y(), target.z());
                RollbackAbilityGlobals.digging().action(body.getUUID(), step.tick(), edge.action().kind(), pos,
                        new PaperRollbackMining(scene, actors, body, player, step));
            }
            case RELEASE_USE_ITEM -> scene.world().releaseItem(body);
            case RIGHT_CLICK -> scene.world().useItem(body, hand);
            case DROP_ITEM, DROP_ALL_ITEMS -> actors.dropSelected(body,
                    edge.action().kind() == RollbackInputActions.Kind.DROP_ALL_ITEMS);
            case ATTACK, INTERACT_ENTITY, RIGHT_CLICK_ENTITY -> {
                var selected = (RollbackInteractionTarget.Actor) edge.action().target();
                var view = scene.logical().entities().get(selected.networkId());
                if (view == null) throw new IllegalStateException("Target left the private simulation during input");
                var target = actors.nativeEntity(view);
                if (target == body || target.level() != body.level() || target.getId() != selected.networkId()
                        || !scene.world().ownsEntity(target)) throw new IllegalStateException("Private target changed identity");
                if (edge.action().kind() == RollbackInputActions.Kind.ATTACK) scene.world().attack(body, target);
                else scene.world().interact(body, target, hand, selected.hit());
            }
            case RIGHT_CLICK_BLOCK -> {
                var selected = (RollbackInteractionTarget.Block) edge.action().target();
                var pos = new net.minecraft.core.BlockPos(selected.x(), selected.y(), selected.z());
                var offset = selected.hit();
                var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(
                        (double) selected.x() + offset.x(), (double) selected.y() + offset.y(), (double) selected.z() + offset.z()),
                        net.minecraft.core.Direction.valueOf(selected.face().name()), pos, selected.inside());
                scene.world().useBlock(body, hand, hit);
            }
            default -> throw new IllegalArgumentException("Unexpected native input remainder " + edge.action().kind());
        }
    }
    @Override public void tickWorld(long tick) {
        var mining = RollbackAbilityGlobals.digging();
        for (var entry : scene.roster().entrySet()) {
            var player = (RollbackPlayer) scene.logical().entities().get(entry.getKey());
            mining.tick(entry.getKey(), tick, new PaperRollbackMining(scene, actors,
                    (ServerPlayer) entry.getValue().ownedPlayer(), player, step));
        }
        actors.registry().tick();
    }
    @Override public void end() { step = null; }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void state) { }
    @Override public List<?> rollbackReferences() { return List.of(scene, actors); }
}
