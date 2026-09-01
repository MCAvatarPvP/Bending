package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockForegroundProvenanceBoundaryTest {
    @Test
    void foregroundRenderingRequiresProvenLocalLifecycleProvenance() throws IOException {
        final String authority = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String selection = method(authority, "private TempVisual tempVisual(",
                "private void refreshVisual");
        final String refresh = method(authority, "private void refreshVisual",
                "private BlockState closedClientState");
        final String logicalState = method(authority, "private BlockState tempVisualState(",
                "private TempVisual tempVisual(");

        assertTrue(selection.contains(
                        "if (showServerLayers) return physical.map(TempVisual::server)"),
                "the Paper debug view must never enter the local foreground path");
        assertTrue(selection.contains(
                        "if (!hiddenServerLayer && physical.isPresent()) return TempVisual.server(physical.get())")
                        && selection.contains("return TempVisual.server(overlay.get())")
                        && selection.contains("return TempVisual.underlay(viewer.get())"),
                "unowned stack overlays remain above DIRECT while a concealed owner's saved underlay delegates to it");

        assertTrue(selection.contains(
                        "if (local != null) return TempVisual.active(local)"),
                "only a live common-client TempBlock may become an active foreground visual");
        assertTrue(selection.contains("completed.localLayerId > 0L")
                        && selection.contains("? TempVisual.handoff(completed.state)")
                        && selection.contains(": TempVisual.underlay(completed.state)"),
                "a completed restore needs an exact local-layer link to receive handoff provenance");
        assertTrue(selection.contains("if (hiddenServerLayer)")
                        && selection.contains("TempVisual.handoff(closed)"),
                "the exact semantic pair must carry a locally closed layer through its visual handoff");
        assertFalse(selection.contains(".equals("),
                "equal block-state values cannot prove whether a visual came from Paper or local prediction");

        assertTrue(refresh.contains("TempVisualProvenance.ACTIVE_LOCAL")
                        && refresh.contains("visualOverlay.setImmediateTemp("),
                "active local provenance must select the immediate renderer");
        assertTrue(refresh.contains("TempVisualProvenance.LOCAL_HANDOFF")
                        && refresh.contains("visualOverlay.beginTempHandoff("),
                "locally linked closes must select the bounded foreground handoff");
        assertTrue(refresh.contains("TempVisualProvenance.SERVER_UNDERLAY")
                        && refresh.contains("visualOverlay.setTempUnderlay("),
                "concealed Paper ownership must reveal a current DIRECT foreground instead of suppressing it");
        assertTrue(logicalState.contains("TempVisualProvenance.SERVER_UNDERLAY")
                        && logicalState.contains("TempVisualProvenance.LOCAL_HANDOFF")
                        && logicalState.contains("directBlocks.viewerState("),
                "common prediction must see the same lower DIRECT state revealed by render composition");
        assertTrue(refresh.contains(
                        "visualOverlay.set(ClientBlockVisualOverlay.Layer.TEMP"),
                "all remaining server provenance must stay on the normal TEMP terrain layer");
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
