package com.projectkorra.projectkorra.prediction;

/** Shared visibility check for prediction metadata that follows chunk tracking. */
public final class PredictionVisibility {
    private PredictionVisibility() {
    }

    public static boolean tracksBlock(String viewerWorld, String blockWorld,
                                      int viewerBlockX, int viewerBlockZ,
                                      int blockX, int blockZ, int viewDistanceChunks) {
        if (viewerWorld == null || blockWorld == null || !viewerWorld.equals(blockWorld)) return false;
        int radius = Math.max(2, viewDistanceChunks) + 1;
        return Math.abs((viewerBlockX >> 4) - (blockX >> 4)) <= radius
                && Math.abs((viewerBlockZ >> 4) - (blockZ >> 4)) <= radius;
    }
}
