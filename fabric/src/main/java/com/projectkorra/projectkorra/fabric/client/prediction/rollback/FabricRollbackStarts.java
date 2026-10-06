package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig;
import com.projectkorra.projectkorra.fabric.prediction.protocol.RollbackStartPayloads;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.prediction.rollback.RollbackInputPacket;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartClientEndpoint;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStartServerEndpoint;
import com.projectkorra.projectkorra.prediction.rollback.RollbackAuthorityChunk;
import com.projectkorra.projectkorra.prediction.rollback.RollbackAuthorityUpdate;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Control transport for an already prepared private client simulation. */
public final class FabricRollbackStarts {
    public interface NativeRuntime extends RollbackStartClientEndpoint.Runtime {
        void packet(Packet<?> packet, long tick);
        default boolean ownsNativeTick() { return false; }
        default void nativeTick(long tick) { throw new IllegalStateException("Private native controls are not bound"); }
    }
    private record Binding(UUID session, ClientPlayNetworkHandler connection, ClientWorld world, ClientPlayerEntity player,
                           ClientPlayerInteractionManager manager, RollbackStartClientEndpoint.Runtime runtime) { }
    private Binding binding;
    private FabricRollbackNativeActions nativeActions;
    private FabricRollbackNativeTick nativeTicks;
    private long clientTick;
    private RollbackAuthorityChunk.Assembler authority;
    private int authorityPackets;
    private final RollbackStartClientEndpoint endpoint = new RollbackStartClientEndpoint(this::send);

    /** The bootstrap owner must suspend legacy prediction before transferring ownership. */
    public void prepare(MinecraftClient client, UUID session, long tick, int timeoutTicks,
                        BooleanSupplier ready, RollbackStartClientEndpoint.Runtime runtime) {
        Objects.requireNonNull(ready); Objects.requireNonNull(runtime);
        if (!client.isOnThread() || binding != null || endpoint.ownsSession() || ExactPredictionRuntime.isReady()
                || client.getNetworkHandler() == null || client.world == null || client.player == null || client.interactionManager == null
                || client.player.getEntityWorld() != client.world || !ClientBendingConfig.isEnabled()
                || !ClientPlayNetworking.canSend(RollbackStartPayloads.ToServer.ID)
                || !ClientPlayNetworking.canSend(PredictionPayloads.RollbackInput.ID)) {
            throw new IllegalStateException("Private rollback bootstrap cannot take ownership of this client");
        }
        Binding captured = new Binding(session, client.getNetworkHandler(), client.world, client.player, client.interactionManager, runtime);
        binding = captured;
        try {
            clientTick = tick;
            nativeActions = new FabricRollbackNativeActions(captured.manager(), captured.player(), captured.world(), () -> current(client, captured),
                    packet -> consumePacket(client, captured.connection(), packet, clientTick), this::nativeFailure);
            if (runtime instanceof NativeRuntime nativeRuntime && nativeRuntime.ownsNativeTick()) {
                nativeTicks = new FabricRollbackNativeTick(captured.player(), captured.world(), () -> current(client, captured),
                        () -> { if (endpoint.acceptsAuthority()) nativeRuntime.nativeTick(Math.incrementExact(clientTick)); }, this::nativeFailure);
            }
            authorityPackets = 0;
            authority = new RollbackAuthorityChunk.Assembler(session, tick, 40);
            endpoint.prepare(session, tick, timeoutTicks, () -> current(client, captured)
                    && ClientBendingConfig.isEnabled() && !ExactPredictionRuntime.isReady() && ready.getAsBoolean(),
                    new RollbackStartClientEndpoint.Runtime() {
                        @Override public void start(long clientTick) { runtime.start(clientTick); }
                        @Override public void tick(long clientTick) { runtime.tick(clientTick); }
                        @Override public void authority(RollbackAuthorityUpdate update) { runtime.authority(update); }
                        @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
                            runtime.stop(failure);
                            release(captured);
                        }
                    });
        } catch (RuntimeException | Error failure) {
            if (!endpoint.ownsSession()) release(captured);
            throw failure;
        }
    }

    public void receive(MinecraftClient client, ClientPlayNetworkHandler source,
                        RollbackStartPacket.Message message, long tick) {
        if (source != client.getNetworkHandler()) return;
        if (binding != null && !current(client, binding)) endpoint.stop(RollbackStartPacket.AbortReason.DISCONNECTED);
        if (ClientPlayNetworking.canSend(RollbackStartPayloads.ToServer.ID)) endpoint.receive(message, tick);
    }

    public void receiveAuthority(MinecraftClient client, ClientPlayNetworkHandler source, RollbackAuthorityChunk chunk, long tick) {
        if (source != client.getNetworkHandler() || binding == null || !endpoint.acceptsAuthority()) return;
        if (!current(client, binding)) { endpoint.stop(RollbackStartPacket.AbortReason.DISCONNECTED); return; }
        try {
            if (++authorityPackets > 256) throw new IllegalStateException("Authority chunk work budget exceeded");
            var update = authority.receive(chunk, tick);
            if (update != null) endpoint.authority(update);
        } catch (RuntimeException | Error failure) { stopAfterFailure(failure); }
    }

    /** Called at the native render-thread send boundary, before legacy prediction or vanilla writes. */
    public boolean consumePacket(MinecraftClient client, ClientPlayNetworkHandler source, Packet<?> packet, long tick) {
        Binding captured = binding;
        if (captured == null || source != captured.connection() || !FabricRollbackInput.gameplay(packet)) return false;
        if (!current(client, captured)) { endpoint.stop(RollbackStartPacket.AbortReason.DISCONNECTED); return true; }
        if (!endpoint.acceptsAuthority()) return true;
        try {
            if (!(captured.runtime() instanceof NativeRuntime nativeRuntime)) throw new IllegalStateException("Private runtime has no native input owner");
            nativeRuntime.packet(packet, tick);
        } catch (RuntimeException | Error failure) {
            endpoint.stop(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Client input capture failed", failure));
        }
        return true;
    }

    public void sendInput(RollbackInputPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (binding == null || !binding.session().equals(packet.session()) || !endpoint.acceptsAuthority()
                || !current(client, binding) || !ClientPlayNetworking.canSend(PredictionPayloads.RollbackInput.ID)) {
            throw new IllegalStateException("Rollback input connection is no longer available");
        }
        ClientPlayNetworking.send(new PredictionPayloads.RollbackInput(packet));
    }

    public void tick(long tick) {
        clientTick = tick; authorityPackets = 0;
        try { if (endpoint.acceptsAuthority()) authority.poll(tick); endpoint.tick(tick); }
        catch (RuntimeException | Error failure) { stopAfterFailure(failure); }
    }
    private void stopAfterFailure(Throwable failure) {
        try { endpoint.stop(RollbackStartPacket.AbortReason.STATE_CHANGED); }
        catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
    }
    public boolean ownsSession() { return endpoint.ownsSession(); }
    public void stop(RollbackStartPacket.AbortReason reason) { endpoint.stop(reason); }

    /** Call only after repairing a failed private-runtime teardown. */
    public void finishStop(UUID session) {
        endpoint.finishStop(session);
        if (!endpoint.ownsSession()) release(binding);
    }

    private void nativeFailure(Throwable failure) {
        endpoint.stop(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Client native interaction failed", failure));
        if (endpoint.ownsSession() && !endpoint.acceptsAuthority()) {
            // A previous teardown failure retained the lease. Do not conceal it if another boundary encounters it.
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Native interaction cleanup incomplete", failure);
        }
    }
    private void release(Binding captured) {
        if (binding != captured) return;
        if (nativeActions != null) nativeActions.close();
        if (nativeTicks != null) nativeTicks.close();
        nativeActions = null; nativeTicks = null; binding = null; authority = null;
    }

    private void send(RollbackStartPacket.Message message) {
        MinecraftClient client = MinecraftClient.getInstance();
        // A cancellation from the old session must never be sent on a newly joined connection.
        if (client.getNetworkHandler() == null || binding != null && client.getNetworkHandler() != binding.connection()
                || !ClientPlayNetworking.canSend(RollbackStartPayloads.ToServer.ID)) {
            throw new IllegalStateException("Rollback control connection is no longer available");
        }
        ClientPlayNetworking.send(new RollbackStartPayloads.ToServer(message));
    }

    private static boolean current(MinecraftClient client, Binding captured) {
        return client.getNetworkHandler() == captured.connection() && client.world == captured.world()
                && client.player == captured.player() && captured.player().getEntityWorld() == captured.world()
                && client.interactionManager == captured.manager();
    }
}
