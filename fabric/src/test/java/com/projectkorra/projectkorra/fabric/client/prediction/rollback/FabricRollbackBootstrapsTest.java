package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.*;
import net.minecraft.client.world.ClientWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses real native freeze leases and the production receiver without opening a Minecraft client. */
class FabricRollbackBootstrapsTest {
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }

    @Test void unavailableImporterAndOldConnectionsNeverTakeNativeOwnership() throws Exception {
        try (var f = new Fixture(false)) {
            f.endpoint.receive(f.client, new ObjenesisStd().newInstance(ClientPlayNetworkHandler.class), f.offer, 10);
            assertTrue(f.sent.isEmpty()); assertEquals(0, f.suspensions);
            f.endpoint.receive(f.client, f.connection, f.offer, 10);
            assertEquals(1, f.sent.size()); assertInstanceOf(RollbackBootstrapPacket.Cancel.class, f.sent.getFirst());
            assertFalse(f.endpoint.ownsSession()); assertEquals(0, f.suspensions); assertFalse(FabricRollbackNativeTick.tick(f.player));
        }
    }

    @Test void unsuccessfulPrivateImportNeverSendsReadyAndReleasesNativeControls() throws Exception {
        try (var f = new Fixture(true)) {
            f.endpoint.receive(f.client, f.connection, f.offer, 10);
            assertTrue(f.endpoint.ownsSession()); assertTrue(FabricRollbackNativeTick.tick(f.player)); assertEquals(1, f.begins);
            f.endpoint.receive(f.client, f.connection, f.offer, 10); assertEquals(1, f.begins);
            f.endpoint.receive(f.client, f.connection, new RollbackBootstrapPacket.Part(f.offer.session(), f.offer.challenge(), 0, f.data), 11);
            assertEquals(1, f.imports); assertEquals(1, f.stops); assertFalse(f.endpoint.ownsSession());
            assertTrue(f.sent.stream().noneMatch(RollbackBootstrapPacket.Ready.class::isInstance));
            assertFalse(FabricRollbackNativeTick.tick(f.player));
            assertFalse(FabricRollbackNativeActions.playerPacket(f.player, () -> { throw new AssertionError("Controls still frozen"); }));
        }
    }

    @Test void failedPartialCleanupRetainsFreezeUntilRepairAndAllowsReentrantStop() throws Exception {
        try (var f = new Fixture(true)) {
            f.failBegin = true; f.failStop = true;
            assertThrows(IllegalStateException.class, () -> f.endpoint.receive(f.client, f.connection, f.offer, 10));
            assertTrue(f.endpoint.ownsSession()); assertTrue(FabricRollbackNativeTick.tick(f.player)); assertEquals(1, f.stops);
            f.failStop = false; f.reentrantStop = true;
            f.endpoint.finishStop(f.client, f.offer.session());
            assertEquals(2, f.stops); assertFalse(f.endpoint.ownsSession()); assertFalse(FabricRollbackNativeTick.tick(f.player));
            f.endpoint.stop(f.client, RollbackStartPacket.AbortReason.STATE_CHANGED); assertEquals(2, f.stops);
        }
    }

    @Test void disconnectAndTimeoutDiscardUnfinishedSnapshotAndReleasePreparation() throws Exception {
        try (var f = new Fixture(true)) {
            f.endpoint.receive(f.client, f.connection, f.offer, 10);
            f.client.world = new ObjenesisStd().newInstance(ClientWorld.class);
            f.endpoint.tick(f.client, 11);
            assertFalse(f.endpoint.ownsSession()); assertEquals(1, f.stops); assertFalse(FabricRollbackNativeTick.tick(f.player));
        }
        try (var f = new Fixture(true)) {
            f.endpoint.receive(f.client, f.connection, f.offer, 10);
            f.endpoint.tick(f.client, 31);
            assertFalse(f.endpoint.ownsSession()); assertEquals(1, f.stops); assertEquals(0, f.imports);
        }
    }

    @Test void failedRuntimeCleanupBeforeInstallationStillConsumesGameplayAndCannotResumeImport() throws Exception {
        try (var f = new Fixture(true)) {
            f.prepareUnready = true; f.failRuntimeStop = true;
            f.endpoint.receive(f.client, f.connection, f.offer, 10);
            assertThrows(IllegalStateException.class, () -> f.endpoint.finishStop(f.client, f.offer.session()));
            var part = new RollbackBootstrapPacket.Part(f.offer.session(), f.offer.challenge(), 0, f.data);
            assertThrows(IllegalStateException.class, () -> f.endpoint.receive(f.client, f.connection, part, 11));
            assertTrue(f.endpoint.ownsSession()); assertEquals(1, f.runtimeStops); assertEquals(0, f.stops);
            assertTrue(f.endpoint.consumePacket(f.client, f.connection, new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(0)));
            f.endpoint.receive(f.client, f.connection, part, 12); assertEquals(1, f.imports);
            f.failRuntimeStop = false;
            f.endpoint.finishStop(f.client, f.offer.session());
            assertEquals(2, f.runtimeStops); assertEquals(1, f.stops); assertFalse(f.endpoint.ownsSession());
            assertFalse(FabricRollbackNativeTick.tick(f.player));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClientPlayNetworkHandler connection;
        final ClientPlayerEntity player;
        final MinecraftClient client;
        final FabricRollbackBootstraps endpoint;
        final byte[] data;
        final RollbackBootstrapPacket.Offer offer;
        final List<RollbackBootstrapPacket.Message> sent = new ArrayList<>();
        int suspensions, begins, imports, stops, runtimeStops;
        boolean failBegin, failStop, reentrantStop, prepareUnready, failRuntimeStop;
        Fixture(boolean install) throws Exception {
            var allocator = new ObjenesisStd(); connection = allocator.newInstance(ClientPlayNetworkHandler.class);
            var world = allocator.newInstance(ClientWorld.class);
            player = RollbackNativeQueryShell.create(ClientPlayerEntity.class).constant(ClientPlayerEntity::getEntityWorld, world)
                    .constant(ClientPlayerEntity::getUuid, new UUID(0, 7001)).instance();
            client = RollbackNativeQueryShell.create(MinecraftClient.class).constant(MinecraftClient::isOnThread, true)
                    .constant(MinecraftClient::getNetworkHandler, connection).instance();
            client.world = world; client.player = player; client.interactionManager = allocator.newInstance(ClientPlayerInteractionManager.class);
            endpoint = new FabricRollbackBootstraps(new FabricRollbackStarts(), ignored -> suspensions++, new FabricRollbackBootstraps.Transport() {
                @Override public boolean available() { return true; }
                @Override public boolean enabled() { return true; }
                @Override public void send(RollbackBootstrapPacket.Message message) { sent.add(message); }
            });
            try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/duel-bootstrap.base64"))) {
                data = Base64.getMimeDecoder().decode(resource.readAllBytes());
            }
            offer = new RollbackBootstrapPacket.Offer(new UUID(0, 902), new UUID(0, 903), RollbackStartNegotiation.VERSION,
                    "12".repeat(32), RollbackBootstrapData.fingerprint(data), data.length);
            if (install) endpoint.install(client, new FabricRollbackBootstraps.Factory() {
                @Override public String definitions() { return offer.definitions(); }
                @Override public int maximumBytes() { return 65_536; }
                @Override public int timeoutTicks() { return 20; }
                @Override public RollbackTerrainCodec.Limits terrainLimits() { return new RollbackTerrainCodec.Limits(4_096, 32, 1_048_576, 1_048_576, 65_536); }
                @Override public FabricRollbackBootstraps.Preparation create(RollbackBootstrapPacket.Offer offer) {
                    return new FabricRollbackBootstraps.Preparation() {
                        @Override public void begin() { begins++; if (failBegin) throw new IllegalStateException("Partial initialization"); }
                        @Override public FabricRollbackBootstraps.Prepared importState(RollbackBootstrapData data) {
                            imports++; assertEquals(new UUID(0, 901), data.world().world());
                            if (prepareUnready) return new FabricRollbackBootstraps.Prepared(new FabricRollbackStarts.NativeRuntime() {
                                @Override public boolean ownsNativeTick() { return true; }
                                @Override public void nativeTick(long tick) { throw new AssertionError("Unready runtime ticked"); }
                                @Override public void packet(net.minecraft.network.packet.Packet<?> packet, long tick) { throw new AssertionError("Unready runtime received input"); }
                                @Override public void start(long tick) { throw new AssertionError("Unready runtime started"); }
                                @Override public void tick(long tick) { throw new AssertionError("Unready runtime ticked"); }
                                @Override public void authority(RollbackAuthorityUpdate update) { throw new AssertionError("Unready runtime received authority"); }
                                @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
                                    runtimeStops++; if (failRuntimeStop) throw new IllegalStateException("Runtime cleanup needs repair");
                                }
                            }, () -> false, 20);
                            throw new IllegalStateException("Native service import unavailable");
                        }
                        @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
                            stops++; if (failStop) throw new IllegalStateException("Cleanup needs repair");
                            if (reentrantStop) endpoint.stop(client, failure.reason());
                        }
                    };
                }
            });
        }
        @Override public void close() { failStop = false; failRuntimeStop = false; endpoint.stop(client, RollbackStartPacket.AbortReason.STATE_CHANGED); }
    }
}
