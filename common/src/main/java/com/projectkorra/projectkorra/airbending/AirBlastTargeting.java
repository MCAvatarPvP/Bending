package com.projectkorra.projectkorra.airbending;

final class AirBlastTargeting {
    private AirBlastTargeting() {
    }

    static double targetDistance(final double blockDistance, final double range) {
        return blockDistance > range ? range - 0.1 : blockDistance;
    }
}
