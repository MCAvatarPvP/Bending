package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import java.util.*;

/** Private native event policy and vibration routing for the captured match. */
final class PaperRollbackArenaEvents implements PaperRollbackWorldServices.Events<Void> {
    private final RollbackStepOutput<Object> output;
    private final RollbackWorldSettings.Policy policy;
    private PaperRollbackNativeScene scene;

    PaperRollbackArenaEvents(RollbackStepOutput<Object> output, RollbackWorldSettings.Policy policy) {
        this.output = Objects.requireNonNull(output); this.policy = Objects.requireNonNull(policy);
    }
    void bind(PaperRollbackNativeScene scene) {
        if (this.scene != null) throw new IllegalStateException("Native event policy already has a scene");
        this.scene = Objects.requireNonNull(scene);
    }
    @Override public void event(Event event) {
        if (!policy.pvp() && event instanceof EntityDamageByEntityEvent damage
                && damage.getDamageSource().getCausingEntity() instanceof org.bukkit.entity.Player attacker
                && attacker != damage.getEntity()) damage.setCancelled(true);
    }
    @Override public void output(PaperRollbackCombatAccess.Output event) { output.accept(event); }

    @Override @SuppressWarnings("unchecked") public void gameEvent(Object nativeEvent, Object location, Object context) {
        var event = (Holder<GameEvent>) nativeEvent;
        var position = (Vec3) location;
        var emitter = (GameEvent.Context) context;
        var level = scene.world().world();
        var source = BlockPos.containing(position);
        int radius = event.value().notificationRadius();
        var byDistance = new ArrayList<GameEvent.ListenerInfo>();
        for (var cell : scene.logical().terrain().blockEntityPositions()) {
            if (!withinSections(cell.x(), source.getX(), radius) || !withinSections(cell.y(), source.getY(), radius)
                    || !withinSections(cell.z(), source.getZ(), radius)) continue;
            var pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!(level.getBlockState(pos).getBlock() instanceof EntityBlock type)) continue;
            var tile = level.getBlockEntity(pos);
            if (tile == null) continue;
            var listener = type.getListener(level, tile);
            if (listener == null) continue;
            var target = listener.getListenerSource().getPosition(level);
            if (target.isEmpty() || BlockPos.containing(target.get()).distSqr(source) >
                    (double) listener.getListenerRadius() * listener.getListenerRadius()) continue;
            if (listener.getDeliveryMode() == GameEventListener.DeliveryMode.BY_DISTANCE)
                byDistance.add(new GameEvent.ListenerInfo(event, position, emitter, listener, target.get()));
            else listener.handleGameEvent(level, event, emitter, position);
        }
        Collections.sort(byDistance);
        for (var message : byDistance) message.recipient().handleGameEvent(level, event, emitter, position);
    }
    private static boolean withinSections(int value, int center, int radius) {
        int section = value >> 4;
        return section >= ((center - radius) >> 4) && section <= ((center + radius) >> 4);
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void saved) { }
    @Override public List<?> rollbackReferences() { return List.of(output); }
}
