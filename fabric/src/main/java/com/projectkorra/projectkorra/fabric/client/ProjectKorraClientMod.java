package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint; the normal server-only path never loads these classes. */
public final class ProjectKorraClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientBendingConfig.initialize();
        PredictionPayloads.registerTypes();
        PredictionClient.initialize();
        PredictionDebugCommands.initialize();
        PredictionBlockVisualRenderer.initialize();
        PredictionDesyncRenderer.initialize();
        FabricAutoUpdater.initialize();
    }
}
