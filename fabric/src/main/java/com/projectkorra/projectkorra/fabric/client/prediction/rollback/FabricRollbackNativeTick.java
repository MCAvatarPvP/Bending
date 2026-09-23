package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.World;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The exact prepared local player stops its ordinary tick while its session samples controls and advances the private replica. */
public final class FabricRollbackNativeTick implements AutoCloseable {
    private static FabricRollbackNativeTick active;
    private final Thread thread = Thread.currentThread();
    private final ClientPlayerEntity player;
    private final World world;
    private final BooleanSupplier current;
    private final Runnable sample;
    private final Consumer<Throwable> fail;
    public FabricRollbackNativeTick(ClientPlayerEntity player, World world, BooleanSupplier current, Runnable sample, Consumer<Throwable> fail) {
        this.player = Objects.requireNonNull(player); this.world = Objects.requireNonNull(world); this.current = Objects.requireNonNull(current);
        this.sample = Objects.requireNonNull(sample); this.fail = Objects.requireNonNull(fail);
        if (active != null || player.getEntityWorld() != world || !current.getAsBoolean()) throw new IllegalStateException("Native player tick already owned or client changed");
        active = this;
    }
    public static boolean tick(ClientPlayerEntity player) {
        var owner = active;
        if (owner == null || owner.player != player) return false;
        owner.check();
        try {
            if (!owner.current.getAsBoolean() || player.getEntityWorld() != owner.world) throw new IllegalStateException("Native player tick client changed");
            owner.sample.run();
        } catch (RuntimeException | Error failure) { owner.fail.accept(failure); }
        return true; // Cleanup may release ownership; this invocation must still never execute the vanilla tick.
    }
    @Override public void close() { check(); if (active == this) active = null; }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Native player tick crossed threads"); }
}
