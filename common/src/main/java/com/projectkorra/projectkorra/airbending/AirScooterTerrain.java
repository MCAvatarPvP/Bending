package com.projectkorra.projectkorra.airbending;

/** Pure terrain-profile math used by modern AirScooter movement. */
final class AirScooterTerrain {
    private AirScooterTerrain() {
    }

    static double averageHeight(final double... profile) {
        if (profile == null || profile.length == 0) return 0;
        double total = 0;
        for (double height : profile) total += height;
        return total / profile.length;
    }

    static double verticalVelocity(final double playerY, final double floorY,
                                   final double targetHeight, final double strength) {
        final double delta = targetHeight - (playerY - floorY);
        return Math.max(-1, Math.min(0.5, strength * delta));
    }
}
