package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import java.lang.invoke.*;
import java.util.*;

/** Native release ordering with the captured Paper event policy and private item effects. */
public final class FabricRollbackItemRelease implements RollbackStateCell<Void> {
    public interface Items<S> extends RollbackStateCell<S> {
        /** Paper stop-use event: called while the current used stack is still installed. */
        void stopped(LivingEntity player, ItemStack stack, int usedTicks);
        void release(ItemStack stack, World world, LivingEntity player, int remaining);
        void update(LivingEntity player);
    }
    private final Thread thread = Thread.currentThread();
    private final List<FabricRollbackNativePlayerState> players;
    private final Items<?> items;
    private final MethodHandle release;
    public FabricRollbackItemRelease(Collection<FabricRollbackNativePlayerState> players, Items<?> items) {
        this.players = List.copyOf(players); this.items = Objects.requireNonNull(items);
        if (RollbackClock.active() || this.players.isEmpty() || this.players.size() > 128
                || new HashSet<>(this.players).size() != this.players.size()) throw new IllegalArgumentException("Release roster");
        var world = this.players.getFirst().ownedPlayer().getEntityWorld();
        for (var player : this.players) if (player.ownedPlayer().getEntityWorld() != world)
            throw new IllegalArgumentException("Release roster spans worlds");
        try {
            var builder = new RollbackNativeMethods(); var lookup = MethodHandles.lookup();
            builder.replace(ItemStack.class.getDeclaredMethod("onStoppedUsing", World.class, LivingEntity.class, int.class),
                    lookup.findVirtual(FabricRollbackItemRelease.class, "effects", MethodType.methodType(void.class,
                            ItemStack.class, World.class, LivingEntity.class, int.class)).bindTo(this));
            builder.replace(LivingEntity.class.getDeclaredMethod("tickActiveItemStack"),
                    lookup.findVirtual(Items.class, "update", MethodType.methodType(void.class, LivingEntity.class)).bindTo(items));
            var entry = LivingEntity.class.getDeclaredMethod("stopUsingItem");
            builder.copy(entry).copy(LivingEntity.class.getDeclaredMethod("clearActiveItem"));
            release = builder.build().get(entry);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native item release changed", failure); }
    }
    private void effects(ItemStack stack, World world, LivingEntity player, int remaining) {
        items.stopped(player, stack, player.getItemUseTime());
        // Paper evaluates these arguments after its stop-use event returns.
        items.release(player.getActiveItem(), player.getEntityWorld(), player, player.getItemUseTimeLeft());
    }
    public void release(FabricRollbackNativePlayerState player) {
        checkThread();
        if (!RollbackClock.active()) throw new IllegalStateException("Item release requires simulation time");
        if (!players.contains(Objects.requireNonNull(player))) throw new IllegalArgumentException("Foreign release player");
        player.use(nativePlayer -> {
            try { release.invokeExact((LivingEntity) nativePlayer); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Private native release failed", failure); }
            return null;
        });
    }
    @Override public Void captureRollbackState() { checkThread(); return null; }
    @Override public void restoreRollbackState(Void ignored) { checkThread(); }
    @Override public Collection<?> rollbackReferences() {
        checkThread(); var roots = new ArrayList<Object>(players); roots.add(items); return List.copyOf(roots);
    }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Item release crossed threads"); }
}
