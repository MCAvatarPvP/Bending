package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Installs an authenticated Paper arena as a private native replica on a connected client. */
public final class FabricRollbackDuelFactory implements FabricRollbackBootstraps.Factory {
    private final MinecraftClient client;
    private final Consumer<RollbackInputPacket> send;
    private final RollbackDuelRuntime setup = new RollbackDuelRuntime(getClass().getClassLoader());
    public FabricRollbackDuelFactory(MinecraftClient client, Consumer<RollbackInputPacket> send) {
        this.client = Objects.requireNonNull(client); this.send = Objects.requireNonNull(send);
    }
    @Override public String definitions() { return setup.definitions(); }
    @Override public int maximumBytes() { return RollbackBootstrapData.MAXIMUM_BYTES; }
    @Override public int timeoutTicks() { return RollbackDuelRuntime.TRANSFER.timeoutTicks(); }
    @Override public RollbackTerrainCodec.Limits terrainLimits() { return RollbackDuelRuntime.TERRAIN; }
    @Override public FabricRollbackBootstraps.Preparation create(RollbackBootstrapPacket.Offer offer) {
        return new FabricRollbackBootstraps.Preparation() {
            final net.minecraft.client.network.ClientPlayNetworkHandler connection = Objects.requireNonNull(client.getNetworkHandler());
            final net.minecraft.client.network.ClientPlayerEntity player = Objects.requireNonNull(client.player);
            final net.minecraft.client.world.ClientWorld world = Objects.requireNonNull(client.world);
            FabricRollbackPresentation<RollbackDomain.Checkpoint, Object> presentation;
            boolean began, stopped;
            boolean current() { return !stopped && client.getNetworkHandler() == connection && client.player == player && client.world == world; }
            @Override public void begin() {
                if (began || !current()) throw new IllegalStateException("Client changed before private import");
                began = true;
            }
            @Override public FabricRollbackBootstraps.Prepared importState(RollbackBootstrapData data) {
                if (!began || !current() || !offer.session().equals(data.session()) || !offer.challenge().equals(data.challenge()))
                    throw new IllegalStateException("Private bootstrap no longer owns this connection");
                var duel = FabricRollbackNativeScene.importDuel(data, connection.getRegistryManager(), connection.getEnabledFeatures(),
                        65_536, 16_384, 500_000);
                var execution = new FabricRollbackExecution<>(duel.views().players().values(), new FabricRollbackPhases(duel), duel.output());
                var identity = new RollbackPlatform.Identity("bukkit", FabricLoader.getInstance().getConfigDir().resolve("projectkorra"),
                        Logger.getLogger("ProjectKorra"));
                var match = setup.open(data, duel.round(), duel.views(), identity, new FabricRollbackServerBlocks(),
                        duel.items(), execution, List.of(duel.scene(), duel.actors()), true);
                var repair = FabricRollbackConfirmed.repair(data, match, duel, execution, maximumBytes());
                presentation = FabricRollbackPresentation.prepare(client, data.session(), duel.scene().roster(), duel.actors(), match.combat(),
                        new FabricRollbackParticles(client, duel, match.combat()));
                var runtime = new FabricRollbackClientRuntime<>(data.session(), RollbackDuelRuntime.seed(data.session()), player,
                        match.combat(), send, presentation, duel.scene().roster().players().get(player.getUuid()),
                        () -> client.options.getSprintWindow().getValue(), () -> client.options.getAutoJump().getValue(), repair);
                return new FabricRollbackBootstraps.Prepared(runtime, () -> current() && !match.combat().failed(), RollbackDuelRuntime.CLOCK.timeoutTicks());
            }
            @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
                stopped = true;
                if (presentation != null) presentation.stop(failure);
            }
        };
    }
}
