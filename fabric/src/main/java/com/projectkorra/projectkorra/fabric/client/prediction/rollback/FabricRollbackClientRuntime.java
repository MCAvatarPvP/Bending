package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Actual Fabric input source and transport over an already imported private combat replica. */
public final class FabricRollbackClientRuntime<S, E> implements FabricRollbackStarts.NativeRuntime {
    private final ClientPlayerEntity player;
    private final RollbackClientRuntime<S, E> runtime;
    private final FabricRollbackInput input;
    private final FabricRollbackClientControls controls;
    private final IntSupplier sprintWindow;
    private final BooleanSupplier autoJump;
    private long samplingTick = -1;

    public FabricRollbackClientRuntime(UUID session, long seed, ClientPlayerEntity player,
            RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline, Consumer<RollbackInputPacket> send,
            RollbackClientRuntime.Output<S, E> output) {
        this(session, seed, player, timeline, send, output, null, null, null);
    }
    public FabricRollbackClientRuntime(UUID session, long seed, ClientPlayerEntity player,
            RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline, Consumer<RollbackInputPacket> send,
            RollbackClientRuntime.Output<S, E> output, FabricRollbackNativePlayerState predicted,
            IntSupplier sprintWindow, BooleanSupplier autoJump) {
        this.player = Objects.requireNonNull(player);
        if (predicted != null && (!predicted.identity().uuid().equals(player.getUuid())
                || predicted.ownedPlayer().getEntityWorld() == player.getEntityWorld())) throw new IllegalArgumentException("Foreign or live private input player");
        controls = predicted == null ? null : new FabricRollbackClientControls(predicted);
        this.sprintWindow = controls == null ? null : Objects.requireNonNull(sprintWindow);
        this.autoJump = controls == null ? null : Objects.requireNonNull(autoJump);
        runtime = new RollbackClientRuntime<>(session, player.getUuid(), seed, timeline, this::sample, send, output);
        var initial = predicted == null ? player : predicted.ownedPlayer();
        input = new FabricRollbackInput(runtime, initial.isSneaking(), initial.getInventory().getSelectedSlot());
    }
    private RollbackClientRuntime.Held sample() {
        var held = input.sample(player.input, player.getYaw(), player.getPitch(), controls == null ? player.isSprinting() : controls.sprinting(samplingTick));
        if (controls != null) {
            Boolean flight = controls.flightRequest(samplingTick);
            if (flight != null) input.flight(flight, player.getYaw(), player.getPitch());
            if (controls.glideRequest(samplingTick)) input.glide(player.getYaw(), player.getPitch());
        }
        return held;
    }
    @Override public boolean ownsNativeTick() { return controls != null; }
    @Override public void nativeTick(long tick) {
        if (controls == null) throw new IllegalStateException("Native ticking has no private control source");
        controls.tick(tick, player.input, sprintWindow.getAsInt(), autoJump.getAsBoolean());
    }
    @Override public void packet(Packet<?> packet, long tick) { input.packet(packet, tick, player.getYaw(), player.getPitch()); }
    @Override public void start(long tick) { simulate(() -> runtime.start(tick)); }
    @Override public void tick(long tick) { samplingTick = tick; simulate(() -> runtime.tick(tick)); }
    @Override public void authority(RollbackAuthorityUpdate update) { simulate(() -> runtime.authority(update)); }
    @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
        try { runtime.stop(failure); }
        finally { if (controls != null) controls.clear(); }
    }
    private void simulate(Runnable action) { if (controls == null) action.run(); else controls.simulate(action); }
}
