package com.projectkorra.projectkorra.fabric.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Small client-only switches for inspecting prediction ownership in game. */
final class PredictionDebugCommands {
    private static boolean initialized;

    private PredictionDebugCommands() {
    }

    static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("pkprediction")
                        .then(literal("status")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Text.literal(
                                            "ProjectKorra prediction: " + PredictionClient.diagnosticStatus()));
                                    return 1;
                                }))
                        .then(literal("removals")
                                .executes(context -> {
                                    ExactPredictionRuntime.abilityRemovalReport().forEach(line ->
                                            context.getSource().sendFeedback(Text.literal(line)));
                                    return 1;
                                }))
                        .then(literal("tempblocks")
                                .executes(context -> {
                                    ExactPredictionRuntime.tempBlockReport().forEach(line ->
                                            context.getSource().sendFeedback(Text.literal(line)));
                                    return 1;
                                }))
                        .then(literal("world")
                                .executes(context -> {
                                    PredictionClient.worldTransitionReport().forEach(line ->
                                            context.getSource().sendFeedback(Text.literal(line)));
                                    return 1;
                                }))
                        .then(literal("servertempblocks")
                                .executes(context -> {
                                    final boolean visible = ExactPredictionRuntime.toggleServerTempBlockDebug();
                                    context.getSource().sendFeedback(Text.literal(
                                            "Server TempBlocks are now " + (visible ? "visible" : "hidden")));
                                    return 1;
                                }))));
    }
}
