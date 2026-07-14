package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.prediction.PredictionPayloads;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint; the normal server-only path never loads these classes. */
public final class ProjectKorraClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PredictionPayloads.registerTypes();
        PredictionClient.initialize();
        PredictionDesyncRenderer.initialize();
        FabricAutoUpdater.initialize();
    }
}
