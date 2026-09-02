package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.client.prediction.impl.ExactPredictionApiLifecycle;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

public final class ExactPredictionRuntime extends ExactPredictionApiLifecycle {
    private static final ExactPredictionRuntime INSTANCE = new ExactPredictionRuntime();

    private ExactPredictionRuntime() {
        super();
    }

    public static ExactPredictionRuntime instance() {
        return INSTANCE;
    }

    public record PredictionDesyncBlock(BlockPos pos, BlockState predicted, BlockState authoritative) {
    }
}
