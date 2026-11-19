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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import org.bukkit.craftbukkit.CraftGameEvent;

/** Private native event policy and vibration routing for the captured match. */
final class PaperRollbackArenaEvents implements PaperRollbackWorldServices.Events<Void> {
    private final RollbackStepOutput<Object> output;
    private final RollbackWorldSettings.Policy policy;
    private PaperRollbackNativeScene scene;
    private MethodHandle vibration;

    PaperRollbackArenaEvents(RollbackStepOutput<Object> output, RollbackWorldSettings.Policy policy) {
        this.output = Objects.requireNonNull(output); this.policy = Objects.requireNonNull(policy);
    }
    void bind(PaperRollbackNativeScene scene) {
        if (this.scene != null) throw new IllegalStateException("Native event policy already has a scene");
        this.scene = Objects.requireNonNull(scene);
        var methods = new RollbackNativeMethods();
        scene.world().bindEvents(methods);
        try {
            var receive = VibrationSystem.Listener.class.getMethod("handleGameEvent", ServerLevel.class, Holder.class,
                    GameEvent.Context.class, Vec3.class);
            var adapter = MethodHandles.lookup().findConstructor(CraftGameEvent.class, MethodType.methodType(void.class, Holder.class))
                    .asType(MethodType.methodType(org.bukkit.GameEvent.class, Holder.class));
            methods.replace(CraftGameEvent.class.getMethod("minecraftHolderToBukkit", Holder.class), adapter);
            // Paper raises BlockReceiveGameEvent inside this method. Route its plugin manager to the private scene.
            methods.copy(receive);
            vibration = methods.build().get(receive);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native vibration listener changed", failure); }
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
            else deliver(listener, level, event, emitter, position);
        }
        Collections.sort(byDistance);
        for (var message : byDistance) deliver(message.recipient(), level, event, emitter, position);
    }
    private void deliver(GameEventListener listener, ServerLevel level, Holder<GameEvent> event, GameEvent.Context emitter, Vec3 position) {
        if (!(listener instanceof VibrationSystem.Listener sensor)) {
            listener.handleGameEvent(level, event, emitter, position);
            return;
        }
        try { boolean ignored = (boolean) vibration.invokeExact(sensor, level, event, emitter, position); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Private vibration listener failed", failure); }
    }
    private static boolean withinSections(int value, int center, int radius) {
        int section = value >> 4;
        return section >= ((center - radius) >> 4) && section <= ((center + radius) >> 4);
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void saved) { }
    @Override public List<?> rollbackReferences() { return List.of(output); }
}
