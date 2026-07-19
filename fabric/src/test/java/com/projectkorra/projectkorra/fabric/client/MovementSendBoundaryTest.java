package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the packet-order boundary used as Paper's input pose. */
class MovementSendBoundaryTest {
    @Test
    void inputStaysOnRenderThreadWhileMovementUsesTheDeepAcceptedBoundary() throws IOException {
        final String input = read(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerMovementMixin.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerMovementMixin.java");
        final String movement = read(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientConnectionMovementMixin.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientConnectionMovementMixin.java");
        final String client = read(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");

        final int inputMixin = input.indexOf("@Mixin(ClientCommonNetworkHandler.class)");
        final int inputHook = input.indexOf("@Inject(method = \"sendPacket\"", inputMixin);
        final int inputCapture = input.indexOf("PredictionClient.beforeVanillaPacket", inputHook);
        final int connectionMixin = movement.indexOf("@Mixin(value = ClientConnection.class, priority = 500)");
        final int acceptedHook = movement.indexOf("@Inject(method = \"send(", connectionMixin);
        final int finalSend = movement.indexOf("ClientConnection;sendImmediately", acceptedHook);
        final int movementCommit = movement.indexOf("PredictionClient.acceptedMovementPacket", finalSend);
        final int metadataAfterSend = movement.indexOf("shift = At.Shift.AFTER", movementCommit);
        final int metadataFlush = movement.indexOf("PredictionClient.acceptedNativeInputPacket", metadataAfterSend);

        assertTrue(inputMixin >= 0 && inputHook > inputMixin && inputCapture > inputHook,
                "native input must execute synchronously on ClientCommonNetworkHandler's render-thread boundary");
        assertTrue(connectionMixin >= 0 && acceptedHook > connectionMixin,
                "movement acceptance must be below ClientCommonNetworkHandler cancellation hooks");
        assertTrue(finalSend > acceptedHook && movementCommit > finalSend,
                "movement pose must commit only when ClientConnection reaches its final send path");
        assertTrue(metadataAfterSend > movementCommit && metadataFlush > metadataAfterSend,
                "action tags and hit claims must be sent only after the outer vanilla input");
        final int acceptedMovement = client.indexOf("public static void acceptedMovementPacket");
        final int nativeInput = client.indexOf("public static void beforeVanillaPacket", acceptedMovement);
        assertTrue(acceptedMovement >= 0 && nativeInput > acceptedMovement);
        assertFalse(client.substring(acceptedMovement, nativeInput).contains("beforeVanillaPacket("),
                "the deep connection callback must never execute a native ability input");
        final int acceptedMetadata = client.indexOf("private void acceptedNativeInputPacket0");
        final int actionTag = client.indexOf("new PredictionPayloads.ActionTag", acceptedMetadata);
        final int hitClaims = client.indexOf("flushPendingHitClaims()", actionTag);
        assertTrue(acceptedMetadata >= 0 && actionTag > acceptedMetadata && hitClaims > actionTag,
                "the server must correlate the vanilla action before receiving its contact evidence");
    }

    private static String read(final String first, final String second) throws IOException {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        assertTrue(Files.exists(path), "missing source " + first);
        return Files.readString(path);
    }
}
