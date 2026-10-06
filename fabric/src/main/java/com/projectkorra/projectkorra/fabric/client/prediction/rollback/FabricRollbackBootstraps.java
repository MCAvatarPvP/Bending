package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig;
import com.projectkorra.projectkorra.fabric.prediction.protocol.RollbackBootstrapPayloads;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Receives one authenticated snapshot and acknowledges only after real start-runtime installation. */
public final class FabricRollbackBootstraps {
    interface Transport {
        boolean available();
        boolean enabled();
        void send(RollbackBootstrapPacket.Message message);
    }
    public interface Factory {
        String definitions();
        int maximumBytes();
        int timeoutTicks();
        RollbackTerrainCodec.Limits terrainLimits();
        /** Return a cleanup owner without acquiring native resources or changing gameplay. */
        Preparation create(RollbackBootstrapPacket.Offer offer);
    }
    public interface Preparation {
        /** Called after this owner is registered, legacy prediction is stopped and ordinary controls are frozen. */
        void begin();
        /** Must bind all native services and import the complete shared graph before returning. */
        Prepared importState(RollbackBootstrapData data);
        /** Idempotent and covers failed/partial import; runtime teardown happens first. */
        void stop(RollbackStartServerEndpoint.Failure failure);
    }
    public record Prepared(FabricRollbackStarts.NativeRuntime runtime, BooleanSupplier ready, int timeoutTicks) {
        public Prepared {
            Objects.requireNonNull(runtime); Objects.requireNonNull(ready);
            if (!runtime.ownsNativeTick() || timeoutTicks < 1 || timeoutTicks > 1200) throw new IllegalArgumentException("Bootstrap requires a complete native client runtime");
        }
    }
    private static final class Binding {
        final RollbackBootstrapPacket.Offer offer;
        final ClientPlayNetworkHandler connection;
        final ClientWorld world;
        final ClientPlayerEntity player;
        final ClientPlayerInteractionManager manager;
        final Factory factory;
        final Preparation preparation;
        final RollbackBootstrapPacket.Assembler assembler;
        FabricRollbackNativeActions actions;
        FabricRollbackNativeTick ticks;
        Prepared prepared;
        boolean runtimeStopped, preparationStopped, stopping, ending, installed;
        Binding(MinecraftClient client, RollbackBootstrapPacket.Offer offer, Factory factory, Preparation preparation, long tick) {
            this.offer = offer; this.factory = factory; this.preparation = preparation;
            connection = client.getNetworkHandler(); world = client.world; player = client.player; manager = client.interactionManager;
            assembler = new RollbackBootstrapPacket.Assembler(offer, tick, factory.timeoutTicks(), factory.maximumBytes());
        }
    }
    private final FabricRollbackStarts starts;
    private final Consumer<MinecraftClient> suspendLegacy;
    private final Transport transport;
    private Factory factory;
    private Binding binding;
    private int packets;
    private long lastTick;
    public FabricRollbackBootstraps(FabricRollbackStarts starts, Consumer<MinecraftClient> suspendLegacy) {
        this(starts, suspendLegacy, new Transport() {
            @Override public boolean available() { return ClientPlayNetworking.canSend(RollbackBootstrapPayloads.ToServer.ID); }
            @Override public boolean enabled() { return ClientBendingConfig.isEnabled(); }
            @Override public void send(RollbackBootstrapPacket.Message message) { ClientPlayNetworking.send(new RollbackBootstrapPayloads.ToServer(message)); }
        });
    }
    FabricRollbackBootstraps(FabricRollbackStarts starts, Consumer<MinecraftClient> suspendLegacy, Transport transport) {
        this.starts = Objects.requireNonNull(starts); this.suspendLegacy = Objects.requireNonNull(suspendLegacy); this.transport = Objects.requireNonNull(transport);
    }
    public void install(MinecraftClient client, Factory factory) {
        requireThread(client); Objects.requireNonNull(factory);
        if (this.factory != null || binding != null || starts.ownsSession()) throw new IllegalStateException("Bootstrap factory already installed or session active");
        if (factory.definitions() == null || !factory.definitions().matches("[0-9a-f]{64}") || factory.maximumBytes() < 1
                || factory.maximumBytes() > RollbackBootstrapData.MAXIMUM_BYTES || factory.timeoutTicks() < 1 || factory.timeoutTicks() > 1200) throw new IllegalArgumentException("Bootstrap factory limits/definitions");
        Objects.requireNonNull(factory.terrainLimits()); this.factory = factory;
    }
    public void receive(MinecraftClient client, ClientPlayNetworkHandler source, RollbackBootstrapPacket.Message message, long tick) {
        requireThread(client);
        if (source != client.getNetworkHandler()) return;
        if (binding != null && !current(client, binding)) { stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED); return; }
        if (tick != lastTick) { packets = 0; lastTick = tick; }
        if (++packets > 128) { if (binding != null) stop(client, RollbackStartPacket.AbortReason.INCOMPATIBLE); return; }
        if (message instanceof RollbackBootstrapPacket.Offer offer) {
            if (binding != null) {
                if (binding.offer.equals(offer)) return;
                if (binding.offer.session().equals(offer.session()) && binding.offer.challenge().equals(offer.challenge())) stop(client, RollbackStartPacket.AbortReason.INCOMPATIBLE);
                else send(client, source, new RollbackBootstrapPacket.Cancel(offer.session(), offer.challenge(), RollbackStartPacket.AbortReason.STATE_CHANGED));
                return;
            }
            if (factory == null || starts.ownsSession() || offer.startVersion() != RollbackStartNegotiation.VERSION
                    || !offer.definitions().equals(factory.definitions()) || offer.bytes() > factory.maximumBytes()
                    || !transport.enabled() || client.world == null || client.player == null || client.interactionManager == null
                    || client.player.getEntityWorld() != client.world || !transport.available()) {
                send(client, source, new RollbackBootstrapPacket.Cancel(offer.session(), offer.challenge(), RollbackStartPacket.AbortReason.INCOMPATIBLE)); return;
            }
            try {
                Preparation preparation = Objects.requireNonNull(factory.create(offer));
                binding = new Binding(client, offer, factory, preparation, tick);
                suspendLegacy.accept(client);
                freeze(client, binding);
                preparation.begin();
            } catch (RuntimeException | Error problem) { fail(client, offer, source, problem); }
            return;
        }
        Binding captured = binding;
        if (captured == null || !captured.offer.session().equals(message.session()) || !captured.offer.challenge().equals(message.challenge())) return;
        if (message instanceof RollbackBootstrapPacket.Cancel cancelled) { stop(client, cancelled.reason()); return; }
        if (captured.ending || captured.prepared != null || !(message instanceof RollbackBootstrapPacket.Part part)) return;
        try {
            byte[] bytes = captured.assembler.receive(part, tick);
            if (bytes == null) return;
            var data = RollbackBootstrapData.decode(bytes, captured.factory.definitions(), new FabricRollbackTerrainTransfer(), captured.factory.terrainLimits());
            if (!data.session().equals(captured.offer.session()) || !data.challenge().equals(captured.offer.challenge())
                    || !data.sides().containsKey(captured.player.getUuid())) throw new IllegalArgumentException("Bootstrap envelope/local roster mismatch");
            captured.prepared = Objects.requireNonNull(captured.preparation.importState(data));
            if (binding != captured || !current(client, captured) || ExactPredictionRuntime.isReady() || !captured.prepared.ready().getAsBoolean()) throw new IllegalStateException("Private import is not ready");
            releaseFreeze(captured); // Same-thread handoff; ordinary client code cannot run between these operations.
            starts.prepare(client, data.session(), tick, captured.prepared.timeoutTicks(), () -> current(client, captured) && captured.prepared.ready().getAsBoolean(), wrap(captured));
            captured.installed = true;
            send(client, source, new RollbackBootstrapPacket.Ready(data.session(), data.challenge(), captured.offer.fingerprint()));
        } catch (RuntimeException | Error problem) { fail(client, captured.offer, source, problem); }
    }
    private FabricRollbackStarts.NativeRuntime wrap(Binding captured) {
        return new FabricRollbackStarts.NativeRuntime() {
            @Override public boolean ownsNativeTick() { return true; }
            @Override public void nativeTick(long tick) { captured.prepared.runtime().nativeTick(tick); }
            @Override public void packet(Packet<?> packet, long tick) { captured.prepared.runtime().packet(packet, tick); }
            @Override public void start(long tick) { captured.prepared.runtime().start(tick); }
            @Override public void tick(long tick) { captured.prepared.runtime().tick(tick); }
            @Override public void authority(RollbackAuthorityUpdate update) { captured.prepared.runtime().authority(update); }
            @Override public void stop(RollbackStartServerEndpoint.Failure failure) { finish(captured, failure); }
        };
    }
    public void tick(MinecraftClient client, long tick) {
        requireThread(client); lastTick = tick; packets = 0;
        var captured = binding;
        if (captured == null || captured.ending) return;
        if (!current(client, captured) || !transport.enabled() || ExactPredictionRuntime.isReady()) { stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED); return; }
        if (captured.prepared == null) try { captured.assembler.poll(tick); }
        catch (RuntimeException problem) { stop(client, RollbackStartPacket.AbortReason.TIMEOUT); }
    }
    public boolean ownsSession() { return binding != null; }
    public boolean consumePacket(MinecraftClient client, ClientPlayNetworkHandler source, Packet<?> packet) {
        var captured = binding;
        if (captured == null || captured.installed || source != captured.connection || !FabricRollbackInput.gameplay(packet)) return false;
        if (!current(client, captured)) stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED);
        return true;
    }
    public void stop(MinecraftClient client, RollbackStartPacket.AbortReason reason) {
        requireThread(client); var captured = binding;
        if (captured == null || captured.stopping) return;
        captured.stopping = true; captured.ending = true;
        try {
            try { send(client, captured.connection, new RollbackBootstrapPacket.Cancel(captured.offer.session(), captured.offer.challenge(), reason)); }
            catch (RuntimeException ignored) { /* A lost connection cannot prevent local cleanup. */ }
            if (starts.ownsSession()) starts.stop(reason);
            if (binding == captured) {
                if (!starts.ownsSession()) freeze(client, captured);
                finish(captured, new RollbackStartServerEndpoint.Failure(reason, "Client bootstrap stopped", null));
                starts.finishStop(captured.offer.session());
            }
        } finally { captured.stopping = false; }
    }
    private void finish(Binding captured, RollbackStartServerEndpoint.Failure failure) {
        boolean stopping = captured.stopping; captured.stopping = true; captured.ending = true;
        try {
            if (captured.prepared != null && !captured.runtimeStopped) { captured.prepared.runtime().stop(failure); captured.runtimeStopped = true; }
            if (!captured.preparationStopped) { captured.preparation.stop(failure); captured.preparationStopped = true; }
            releaseFreeze(captured);
            if (binding == captured) binding = null;
        } finally { captured.stopping = stopping; }
    }
    /** Retry the retained cleanup after the importer/runtime owner has repaired its failure. */
    public void finishStop(MinecraftClient client, UUID session) {
        requireThread(client);
        if (binding == null || !binding.offer.session().equals(session)) return;
        if (!binding.ending) throw new IllegalStateException("Client bootstrap/session is still active");
        stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED);
    }
    private void freeze(MinecraftClient client, Binding captured) {
        if (!current(client, captured)) return;
        if (captured.actions == null) captured.actions = new FabricRollbackNativeActions(captured.manager, captured.player, captured.world,
                () -> current(client, captured), ignored -> { }, problem -> stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED));
        if (captured.ticks == null) captured.ticks = new FabricRollbackNativeTick(captured.player, captured.world,
                () -> current(client, captured), () -> { }, problem -> stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED));
    }
    private static void releaseFreeze(Binding captured) {
        if (captured.actions != null) captured.actions.close();
        if (captured.ticks != null) captured.ticks.close();
        captured.actions = null; captured.ticks = null;
    }
    private void fail(MinecraftClient client, RollbackBootstrapPacket.Offer offer, ClientPlayNetworkHandler source, Throwable problem) {
        org.slf4j.LoggerFactory.getLogger(FabricRollbackBootstraps.class).warn("Rollback bootstrap {} failed before handoff", offer.session(), problem);
        try {
            if (binding != null) stop(client, RollbackStartPacket.AbortReason.INCOMPATIBLE);
            else send(client, source, new RollbackBootstrapPacket.Cancel(offer.session(), offer.challenge(), RollbackStartPacket.AbortReason.INCOMPATIBLE));
        } catch (RuntimeException | Error cleanup) { if (cleanup != problem) problem.addSuppressed(cleanup); }
        if (binding != null) {
            if (problem instanceof RuntimeException runtime) throw runtime;
            throw (Error) problem;
        }
    }
    private static boolean current(MinecraftClient client, Binding captured) {
        return client.getNetworkHandler() == captured.connection && client.world == captured.world && client.player == captured.player
                && client.interactionManager == captured.manager && captured.player.getEntityWorld() == captured.world;
    }
    private void send(MinecraftClient client, ClientPlayNetworkHandler source, RollbackBootstrapPacket.Message message) {
        if (source == client.getNetworkHandler() && transport.available()) transport.send(message);
    }
    private static void requireThread(MinecraftClient client) { if (!client.isOnThread()) throw new IllegalStateException("Bootstrap requires the client thread"); }
}
