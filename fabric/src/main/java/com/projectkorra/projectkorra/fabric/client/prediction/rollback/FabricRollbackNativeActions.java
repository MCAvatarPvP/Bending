package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.world.World;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Session lease for native actions which mutate client state before their ordinary packet send. */
public final class FabricRollbackNativeActions implements AutoCloseable {
    private static FabricRollbackNativeActions active;
    private final Thread thread = Thread.currentThread();
    private final ClientPlayerInteractionManager manager;
    private final PlayerEntity player;
    private final World world;
    private final BooleanSupplier current;
    private final Consumer<Packet<?>> input;
    private final Consumer<Throwable> fail;
    private boolean closed;

    public FabricRollbackNativeActions(ClientPlayerInteractionManager manager, PlayerEntity player, World world,
            BooleanSupplier current, Consumer<Packet<?>> input, Consumer<Throwable> fail) {
        this.manager = Objects.requireNonNull(manager); this.player = Objects.requireNonNull(player); this.world = Objects.requireNonNull(world);
        this.current = Objects.requireNonNull(current); this.input = Objects.requireNonNull(input); this.fail = Objects.requireNonNull(fail);
        if (active != null || player.getEntityWorld() != world || !current.getAsBoolean()) throw new IllegalStateException("Native actions already owned or client changed");
        active = this;
    }
    /** A null player is reserved for methods on the captured interaction manager that implicitly act on the local player. */
    public static boolean packet(ClientPlayerInteractionManager manager, PlayerEntity player, Supplier<Packet<?>> packet) {
        var owner = match(manager, player); return owner != null && owner.capture(packet);
    }
    public static boolean playerPacket(PlayerEntity player, Supplier<Packet<?>> packet) {
        var owner = active; return owner != null && owner.player == player && owner.capture(packet);
    }
    public static boolean unsupported(ClientPlayerInteractionManager manager, PlayerEntity player, String action) {
        var owner = match(manager, player);
        if (owner == null) return false;
        owner.check(); owner.fail.accept(new UnsupportedOperationException("Private native interaction is not bound for " + action));
        return true;
    }
    private static FabricRollbackNativeActions match(ClientPlayerInteractionManager manager, PlayerEntity player) {
        var owner = active;
        return owner != null && owner.manager == manager && (player == null || owner.player == player) ? owner : null;
    }
    private boolean capture(Supplier<Packet<?>> packet) {
        check();
        try {
            if (!current.getAsBoolean() || player.getEntityWorld() != world) throw new IllegalStateException("Native action client changed");
            // Native interact methods normally synchronize the slot first. Preserve that ordering even though their bodies are bypassed.
            input.accept(new UpdateSelectedSlotC2SPacket(player.getInventory().getSelectedSlot()));
            if (!closed) input.accept(Objects.requireNonNull(packet.get()));
        } catch (RuntimeException | Error failure) { fail.accept(failure); }
        // Even if capture/cleanup ended the session, this invocation must not resume its ordinary mutation.
        return true;
    }
    @Override public void close() { check(); closed = true; if (active == this) active = null; }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Native input crossed threads"); }
}
