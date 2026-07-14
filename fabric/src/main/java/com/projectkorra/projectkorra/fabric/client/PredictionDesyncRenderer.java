package com.projectkorra.projectkorra.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;

/** Renders a short-lived translucent marker over an intentionally deferred owner correction. */
public final class PredictionDesyncRenderer {
    private static final DrawStyle DESYNC_STYLE = DrawStyle.filledAndStroked(
            0xB3FFD54F, 2.0F, 0x38FFD54F);

    private PredictionDesyncRenderer() {
    }

    public static void initialize() {
        WorldRenderEvents.END_EXTRACTION.register(context -> {
            var desyncs = ExactPredictionRuntime.ownedTempDesyncs(context.world());
            if (desyncs.isEmpty()) return;
            MinecraftClient client = MinecraftClient.getInstance();
            try (var ignored = client.worldRenderer.startDrawingGizmos()) {
                for (ExactPredictionRuntime.PredictionDesyncBlock desync : desyncs) {
                    GizmoDrawing.box(new Box(desync.pos()).expand(0.003), DESYNC_STYLE);
                }
            }
        });
    }
}
