package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.client.prediction.impl.PredictionClientApi;

public final class PredictionClient extends PredictionClientApi {
    private static final PredictionClient INSTANCE = new PredictionClient();

    private PredictionClient() {
        super();
    }

    public static PredictionClient instance() {
        return INSTANCE;
    }
}
