package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import java.util.List;

/** Native item and actor phases surrounding bending input and movement. */
final class FabricRollbackPhases implements RollbackPlayerExecution.Services<Object, Void> {
    private final FabricRollbackNativeScene.Duel duel;
    FabricRollbackPhases(FabricRollbackNativeScene.Duel duel) { this.duel = duel; }
    @Override public void begin(RollbackStep<Object> effects) { duel.scene().services().advanceTick(effects.tick()); }
    @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) {
        if (result.cancelEvent()) return;
        var state = duel.scene().roster().players().get(player.getUniqueId());
        if (player.body().kinematicsSource() != state) throw new IllegalArgumentException("Foreign native input player");
        state.use(body -> {
            var hand = edge.action().hand() == RollbackInputActions.Hand.OFF ? Hand.OFF_HAND : Hand.MAIN_HAND;
            switch (edge.action().kind()) {
                case SNEAK_START, SNEAK_STOP -> { /* The common dispatcher already wrote the private native control. */ }
                case RELEASE_USE_ITEM -> body.stopUsingItem();
                case DROP_ITEM, DROP_ALL_ITEMS -> duel.actors().dropSelected(body, edge.action().kind() == RollbackInputActions.Kind.DROP_ALL_ITEMS);
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                    var target = (RollbackInteractionTarget.Dig) edge.action().target();
                    var pos = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(target.x(), target.y(), target.z());
                    RollbackAbilityGlobals.digging().action(player.getUniqueId(), duel.round().tick(), edge.action().kind(), pos,
                            new FabricRollbackDigBlocks(duel, player, body));
                }
                case RIGHT_CLICK -> {
                    var stack = body.getStackInHand(hand);
                    if (body.isSpectator() || body.getItemCooldownManager().isCoolingDown(stack)) break;
                    if (stack.use(body.getEntityWorld(), body, hand) instanceof ActionResult.Success success && success.getNewHandStack() != null)
                        body.setStackInHand(hand, success.getNewHandStack());
                }
                case ATTACK, INTERACT_ENTITY, RIGHT_CLICK_ENTITY -> {
                    var target = (RollbackInteractionTarget.Actor) edge.action().target();
                    var entity = duel.actors().nativeEntity(duel.scene().logical().entities().get(target.networkId()));
                    if (entity == null || entity.getEntityWorld() != body.getEntityWorld()) break;
                    if (edge.action().kind() == RollbackInputActions.Kind.ATTACK) body.attack(entity);
                    else if (target.hit() == null) body.interact(entity, hand);
                    else {
                        var pos = target.hit();
                        entity.interactAt(body, new net.minecraft.util.math.Vec3d(pos.x(),pos.y(),pos.z()), hand);
                    }
                }
                case RIGHT_CLICK_BLOCK -> {
                    var target = (RollbackInteractionTarget.Block) edge.action().target();
                    var pos = new net.minecraft.util.math.BlockPos(target.x(), target.y(), target.z());
                    var hit = new net.minecraft.util.hit.BlockHitResult(new net.minecraft.util.math.Vec3d((double)pos.getX()+target.hit().x(),
                            (double)pos.getY()+target.hit().y(),(double)pos.getZ()+target.hit().z()), net.minecraft.util.math.Direction.valueOf(target.face().name()), pos, target.inside());
                    var world = body.getEntityWorld();
                    var block = world.getBlockState(pos); var stack = body.getStackInHand(hand);
                    if (!body.shouldCancelInteraction() || body.getMainHandStack().isEmpty() && body.getOffHandStack().isEmpty()) {
                        var action = block.onUseWithItem(stack, world, body, hand, hit);
                        if (action.isAccepted()) break;
                        if (action == ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION && hand == Hand.MAIN_HAND && block.onUse(world, body, hit).isAccepted()) break;
                    }
                    if (!body.isSpectator() && !stack.isEmpty() && !body.getItemCooldownManager().isCoolingDown(stack))
                        stack.useOnBlock(new net.minecraft.item.ItemUsageContext(body, hand, hit));
                }
                default -> throw new IllegalStateException("Unexpected native input remainder " + edge.action().kind());
            }
            return null;
        });
    }
    @Override public void tickWorld(long tick) {
        FabricRollbackGliding.use(duel.scene().roster().world(), () -> {
            var digging = RollbackAbilityGlobals.activeDigging();
            if (digging != null) {
                for (var player : duel.views().players().values()) {
                    var state = duel.scene().roster().players().get(player.getUniqueId());
                    digging.tick(player.getUniqueId(), tick,
                            new FabricRollbackDigBlocks(duel, player, state.ownedPlayer()));
                }
            }
            duel.actors().registry().tick();
            return null;
        });
    }
    @Override public void end() {
        duel.actors().registry().forEachActive(actor -> {
            if (((FabricRollbackActor)actor).owned() instanceof net.minecraft.entity.AreaEffectCloudEntity cloud)
                duel.output().accept(FabricRollbackCloudParticles.Frame.capture(cloud));
        });
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    @Override public List<?> rollbackReferences() { return List.of(duel.scene(), duel.actors()); }
}
