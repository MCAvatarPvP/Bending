package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source boundaries for renderer-independent local DIRECT and TEMP visuals. */
class PredictedBlockForegroundLayerBoundaryTest {
    @Test
    void bothLocalLayersCanUseTheImmediateRenderer() throws IOException {
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String renderer = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionBlockVisualRenderer.java");

        assertTrue(overlay.contains("setImmediateDirect(")
                        && overlay.contains("setImmediateTemp("),
                "EarthBlast/direct earth and TempBlocks both need a per-frame visual path");
        assertTrue(overlay.contains("foregroundDirect")
                        && overlay.contains("foregroundTemp")
                        && overlay.contains("foregroundBlocks("),
                "foreground ownership must remain separated by prediction layer");
        assertTrue(renderer.contains(
                        "ExactPredictionRuntime.foregroundBlocks(context.world())"),
                "the renderer must consume the generalized foreground list directly");
    }

    @Test
    void foregroundReplacementCannotInheritAnOccupiedCellsBlackLightOrMesh()
            throws IOException {
        final String renderer = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionBlockVisualRenderer.java");

        assertTrue(renderer.contains("private static int foregroundLight(")
                        && renderer.contains("if (!world.getBlockState(pos).isOpaqueFullCube()) return local")
                        && renderer.contains("for (Direction direction : LIGHT_SAMPLE_DIRECTIONS)")
                        && renderer.contains(".packedBrightness(world, pos.offset(direction))")
                        && renderer.contains("LightmapTextureManager.pack(blockLight, skyLight)"),
                "foreground blocks must use exposed neighboring light instead of the occupied authoritative cell alone");
        assertTrue(renderer.contains("final float inset = (FOREGROUND_SCALE - 1.0F) * 0.5F")
                        && renderer.contains("matrices.scale(FOREGROUND_SCALE"),
                "every active or handoff foreground block must cover a briefly overlapping terrain mesh");
        assertTrue(renderer.contains("LayeringTransform.VIEW_OFFSET_Z_LAYERING_FORWARD")
                        && renderer.contains("RenderLayers.entitySolidZOffsetForward(")
                        && renderer.contains("context.commandQueue().submitBlockStateModel("),
                "foreground models need a forward depth layer instead of relying on scale at distance");
    }

    @Test
    void ordinaryTempSuppressesDirectButConcealedOwnerUnderlayDelegates()
            throws IOException {
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String selection = method(overlay,
                "private VisibleForeground visibleForeground(",
                "private static boolean supportsForeground");

        assertTrue(selection.indexOf("if (temp.containsKey(key))")
                        < selection.indexOf("foregroundDirect.get(key)"),
                "TEMP must have the same priority in foreground and terrain composition");
        assertTrue(selection.contains("new VisibleForeground(Layer.TEMP, visual)")
                        && selection.contains("if (!tempLayer.delegatesToDirect) return null")
                        && selection.contains("final boolean directIsVisible")
                        && overlay.contains("public void setTempUnderlay("),
                "normal TEMP must occlude DIRECT, but a concealed owner layer must reveal the current DIRECT foreground");
    }

    @Test
    void onlyLocallyProvenDirectViewsEnterTheImmediateRenderer() throws IOException {
        final String authority = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientDirectBlockAuthority.java");
        final String receipt = method(authority, "public void noteReceipt(",
                "public void updateServerViewer");
        final String local = method(authority, "private void updateLocalView(",
                "private void retireConvergedTransactions");

        assertTrue(local.contains("existing != null && existing.authoritative,")
                        && local.contains("DirectVisualProvenance.ACTIVE_LOCAL")
                        && local.contains("effect, revision,"),
                "a common-client direct write must mark its DirectMask as locally predicted");
        assertTrue(local.contains("mask.visualProvenance.immediate()")
                        && local.contains("visualOverlay.setImmediateDirect(")
                        && local.contains(
                        "visualOverlay.set(ClientBlockVisualOverlay.Layer.DIRECT"),
                "local DirectMasks use the foreground renderer while receipt-only masks stay on terrain");
        assertTrue(receipt.contains(
                        "final boolean sameCoordinate = local != null && local.key.equals(serverKey)")
                        && receipt.contains("final boolean retainObserved")
                        && receipt.contains("DirectVisualProvenance.RECEIPT_ONLY"),
                "a receipt with no exact local coordinate must not manufacture foreground provenance");
        final String predict = method(authority, "public void predict(",
                "public BlockState simulatedState");
        assertTrue(predict.indexOf("if (state.equals(before)) return;")
                        < predict.indexOf("updateLocalView("),
                "an equal-state direct write must not claim sticky foreground ownership");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "missing boundary " + start + " -> " + end);
        return source.substring(from, to);
    }
}
