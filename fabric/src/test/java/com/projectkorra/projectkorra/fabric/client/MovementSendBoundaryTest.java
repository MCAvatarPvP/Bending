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
        final String paper = read(
                "../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");

        final int inputMixin = input.indexOf("@Mixin(ClientCommonNetworkHandler.class)");
        final int inputHook = input.indexOf("@Inject(method = \"sendPacket\"", inputMixin);
        final int inputCapture = input.indexOf("PredictionClient.beforeVanillaPacket", inputHook);
        final int connectionMixin = movement.indexOf("@Mixin(value = ClientConnection.class, priority = 500)");
        final int acceptedHook = movement.indexOf("@Inject(method = \"send(", connectionMixin);
        final int finalSend = movement.indexOf("ClientConnection;sendImmediately", acceptedHook);
        final int movementCommit = movement.indexOf("PredictionClient.acceptedMovementPacket", finalSend);
        final int actionTagPrepare = movement.indexOf("PredictionClient.prepareAcceptedNativeInputPacket", movementCommit);
        final int metadataAfterSend = movement.indexOf("shift = At.Shift.AFTER", movementCommit);
        final int metadataFlush = movement.indexOf("PredictionClient.acceptedNativeInputPacket", metadataAfterSend);

        assertTrue(inputMixin >= 0 && inputHook > inputMixin && inputCapture > inputHook,
                "native input must execute synchronously on ClientCommonNetworkHandler's render-thread boundary");
        assertTrue(connectionMixin >= 0 && acceptedHook > connectionMixin,
                "movement acceptance must be below ClientCommonNetworkHandler cancellation hooks");
        assertTrue(finalSend > acceptedHook && movementCommit > finalSend,
                "movement pose must commit only when ClientConnection reaches its final send path");
        assertTrue(actionTagPrepare > movementCommit && metadataAfterSend > actionTagPrepare
                        && metadataFlush > metadataAfterSend,
                "the exact action tag must precede the accepted vanilla input while hit claims follow it");
        final int acceptedMovement = client.indexOf("public static void acceptedMovementPacket");
        final int nativeInput = client.indexOf("public static void beforeVanillaPacket", acceptedMovement);
        assertTrue(acceptedMovement >= 0 && nativeInput > acceptedMovement);
        assertFalse(client.substring(acceptedMovement, nativeInput).contains("beforeVanillaPacket("),
                "the deep connection callback must never execute a native ability input");
        final int preparedMetadata = client.indexOf("private void prepareAcceptedNativeInputPacket0");
        final int actionTag = client.indexOf("new PredictionPayloads.ActionTag", preparedMetadata);
        final int acceptedMetadata = client.indexOf("private void acceptedNativeInputPacket0", actionTag);
        final int hitClaims = client.indexOf("flushPendingHitClaims()", acceptedMetadata);
        assertTrue(preparedMetadata >= 0 && actionTag > preparedMetadata
                        && acceptedMetadata > actionTag && hitClaims > acceptedMetadata,
                "Paper must receive the action identity before vanilla input and contact evidence afterward");
        final int processInput = paper.indexOf("private CommonInputHandler.InputResult processInput(");
        final int consumeTag = paper.indexOf("session.actionTags.consume(kind, selectedSlot, abilityName)", processInput);
        final int assignTag = paper.indexOf("action.clientSequence = clientActionSequence", consumeTag);
        final int nativeReceipt = paper.indexOf("PaperPredictionProtocol.nativeAction", assignTag);
        assertTrue(processInput >= 0 && consumeTag > processInput && assignTag > consumeTag
                        && nativeReceipt > assignTag,
                "Paper must echo the exact client identity in its first native-action receipt");
        final int actionTagHandler = paper.indexOf("private void onActionTag");
        final int actionTagHandlerEnd = paper.indexOf("private void onHitClaim", actionTagHandler);
        assertTrue(actionTagHandler >= 0 && actionTagHandlerEnd > actionTagHandler
                        && !paper.substring(actionTagHandler, actionTagHandlerEnd).contains("session.actions"),
                "a pre-input tag must never search backward and attach to an older matching cast");
    }

    private static String read(final String first, final String second) throws IOException {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        assertTrue(Files.exists(path), "missing source " + first);
        return Files.readString(path);
    }
}
