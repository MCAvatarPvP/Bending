package com.projectkorra.projectkorra.prediction.action;

import java.util.Locale;

/**
 * Loader-independent gameplay seed for one semantic native input.
 *
 * <p>Paper and Fabric maintain separate transport ordinals. Those counters are
 * useful identities after correlation, but they are not a shared random seed:
 * a vanilla packet which does not become a Bukkit event can shift only one
 * counter. The packet-time pose and logical input are what both runtimes
 * actually execute, so they form the deterministic seed instead.</p>
 */
public final class PredictionActionSeed {
    private PredictionActionSeed() {
    }

    public static long from(final String kind, final int selectedSlot, final String ability,
                            final double eyeX, final double eyeY, final double eyeZ,
                            final float yaw, final float pitch) {
        long hash = 0x6A09E667F3BCC909L;
        hash = mix(hash ^ stableString(kind == null ? "" : kind.toUpperCase(Locale.ROOT)));
        hash = mix(hash ^ Integer.toUnsignedLong(selectedSlot));
        hash = mix(hash ^ stableString(ability == null ? "" : ability.toLowerCase(Locale.ROOT)));
        hash = mix(hash ^ canonicalDouble(eyeX));
        hash = mix(hash ^ canonicalDouble(eyeY));
        hash = mix(hash ^ canonicalDouble(eyeZ));
        hash = mix(hash ^ Integer.toUnsignedLong(Float.floatToIntBits(wrapYaw(yaw))));
        hash = mix(hash ^ Integer.toUnsignedLong(Float.floatToIntBits(canonicalFloat(pitch))));
        hash &= Long.MAX_VALUE;
        return hash == 0L ? 1L : hash;
    }

    static float wrapYaw(final float yaw) {
        if (!Float.isFinite(yaw)) return yaw;
        float wrapped = yaw % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return canonicalFloat(wrapped);
    }

    private static float canonicalFloat(final float value) {
        return value == 0.0F ? 0.0F : value;
    }

    private static long canonicalDouble(final double value) {
        return Double.doubleToLongBits(value == 0.0D ? 0.0D : value);
    }

    private static long stableString(final String value) {
        long hash = 0xCBF29CE484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }
}
