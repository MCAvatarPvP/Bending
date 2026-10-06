package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Native release ordering with explicitly owned item-effect and active-use callbacks.
 * The session must retain the event dispatcher's mutable listener state as a checkpoint
 * root, as with every PaperRollbackNativeEvents binding. Item callbacks are roots here.
 * This adapter does not supply item implementations or install an execution service.
 */
public final class PaperRollbackItemRelease implements RollbackStateCell<Void> {
    public interface Items<S> extends RollbackStateCell<S> {
        void release(ItemStack stack, Level world, LivingEntity player, int remaining);
        void update(LivingEntity player);
    }
    private final Thread thread = Thread.currentThread();
    private final Items<?> items;
    private final PaperRollbackNativeEvents events;
    private final MethodHandle release;
    PaperRollbackItemRelease(PaperRollbackNativeEvents events, Items<?> items) {
        this.items = Objects.requireNonNull(items); this.events = Objects.requireNonNull(events);
        if (RollbackClock.active()) throw new IllegalStateException("Construct release services before replay");
        try {
            var builder = new RollbackNativeMethods(); Objects.requireNonNull(events).bind(builder);
            var lookup = MethodHandles.lookup();
            builder.replace(ItemStack.class.getDeclaredMethod("releaseUsing", Level.class, LivingEntity.class, int.class),
                    lookup.findVirtual(Items.class, "release", MethodType.methodType(void.class,
                            ItemStack.class, Level.class, LivingEntity.class, int.class)).bindTo(items));
            builder.replace(LivingEntity.class.getDeclaredMethod("updatingUsingItem"),
                    lookup.findVirtual(Items.class, "update", MethodType.methodType(void.class, LivingEntity.class)).bindTo(items));
            var entry = LivingEntity.class.getDeclaredMethod("releaseUsingItem");
            builder.copy(entry).copy(LivingEntity.class.getDeclaredMethod("stopUsingItem"));
            release = builder.build().get(entry);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native item release changed", failure); }
    }
    public void release(PaperRollbackNativePlayerState player) {
        checkThread();
        if (!RollbackClock.active()) throw new IllegalStateException("Item release requires simulation time");
        Objects.requireNonNull(player).use(nativePlayer -> {
            events.requireOwned(((LivingEntity) nativePlayer).getBukkitEntity());
            try { release.invokeExact((LivingEntity) nativePlayer); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Private native release failed", failure); }
            return null;
        });
    }
    @Override public Void captureRollbackState() { checkThread(); return null; }
    @Override public void restoreRollbackState(Void ignored) { checkThread(); }
    @Override public Collection<?> rollbackReferences() { checkThread(); return List.of(items); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Item release crossed threads"); }
}
