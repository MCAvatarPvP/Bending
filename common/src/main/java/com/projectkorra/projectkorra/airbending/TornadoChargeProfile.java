package com.projectkorra.projectkorra.airbending;

/** Charge-to-size tuning, independent of wall clocks and rendering. */
final class TornadoChargeProfile {
    private final long minimumCharge, maximumCharge, minimumCooldown, maximumCooldown;
    private final double minimumHeight, maximumHeight, minimumRadius, maximumRadius;
    private final double minimumPullRadius, maximumPullRadius;

    TornadoChargeProfile(long minimumCharge, long maximumCharge, long minimumCooldown, long maximumCooldown,
                         double minimumHeight, double maximumHeight, double minimumRadius, double maximumRadius,
                         double minimumPullRadius, double maximumPullRadius) {
        this.minimumCharge = Math.max(0, minimumCharge);
        this.maximumCharge = Math.max(this.minimumCharge, maximumCharge);
        this.maximumCooldown = Math.max(0, maximumCooldown);
        // Attribute modifiers can make the maximum cooldown zero (Avatar State).
        this.minimumCooldown = Math.min(this.maximumCooldown, Math.max(0, minimumCooldown));
        this.maximumHeight = dimension(maximumHeight, 0.5, 64);
        this.minimumHeight = Math.min(this.maximumHeight, dimension(minimumHeight, 0.5, 64));
        this.maximumRadius = dimension(maximumRadius, 0.25, 32);
        this.minimumRadius = Math.min(this.maximumRadius, dimension(minimumRadius, 0.25, 32));
        this.maximumPullRadius = dimension(maximumPullRadius, 0.25, 40);
        this.minimumPullRadius = Math.min(this.maximumPullRadius, dimension(minimumPullRadius, 0.25, 40));
    }

    boolean ready(long held) { return held >= minimumCharge; }
    boolean complete(long held) { return held >= maximumCharge; }

    Size sample(long held) {
        double progress = held >= maximumCharge ? 1 : held <= minimumCharge ? 0
                : (double) (held - minimumCharge) / (maximumCharge - minimumCharge);
        return new Size(lerp(minimumHeight, maximumHeight, progress),
                lerp(minimumRadius, maximumRadius, progress),
                lerp(minimumPullRadius, maximumPullRadius, progress),
                minimumCooldown + Math.min(maximumCooldown - minimumCooldown,
                        Math.round((maximumCooldown - minimumCooldown) * progress)), progress);
    }

    long pullDuration(long held, long minimum, long maximum) {
        // The consume-on-capture lifecycle always has a finite positive duration.
        long max = maximum > 0 ? maximum : 2000;
        long min = Math.min(max, Math.max(1, minimum));
        long delta = max - min;
        return min + Math.min(delta, Math.round(delta * sample(held).progress()));
    }

    private static double dimension(double value, double fallback, double maximum) {
        return Double.isFinite(value) ? Math.max(fallback, Math.min(maximum, value)) : fallback;
    }

    private static double lerp(double min, double max, double progress) { return min + (max - min) * progress; }
    record Size(double height, double radius, double pullRadius, long cooldown, double progress) { }
}
