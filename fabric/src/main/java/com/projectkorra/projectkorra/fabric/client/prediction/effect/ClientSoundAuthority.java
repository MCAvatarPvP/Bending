package com.projectkorra.projectkorra.fabric.client.prediction.effect;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Consumes the delayed server echo of sounds already played by exact prediction. */
public final class ClientSoundAuthority {
    private static final long RETENTION_TICKS = 24L;
    private static final double MAX_POSITION_DELTA_SQUARED = 16.0 * 16.0;
    private static final float PARAMETER_TOLERANCE = 0.025F;

    private final Deque<PredictedSound> predicted = new ArrayDeque<>();

    public void predict(final String sound, final String category,
                        final double x, final double y, final double z,
                        final float volume, final float pitch, final long tick) {
        if (sound == null || sound.isBlank() || category == null || category.isBlank()
                || !finite(x, y, z) || !Float.isFinite(volume) || !Float.isFinite(pitch)) return;
        this.predicted.addLast(new PredictedSound(sound, category, x, y, z, volume, pitch, tick));
        this.expire(tick);
    }

    /** Returns true only once for the matching authoritative packet. */
    public boolean accept(final String sound, final String category,
                          final double x, final double y, final double z,
                          final float volume, final float pitch, final long tick) {
        this.expire(tick);
        if (sound == null || category == null || !finite(x, y, z)) return false;
        final Iterator<PredictedSound> iterator = this.predicted.iterator();
        while (iterator.hasNext()) {
            final PredictedSound candidate = iterator.next();
            if (!candidate.sound.equals(sound) || !candidate.category.equals(category)
                    || Math.abs(candidate.volume - volume) > PARAMETER_TOLERANCE
                    || Math.abs(candidate.pitch - pitch) > PARAMETER_TOLERANCE) continue;
            final double dx = candidate.x - x;
            final double dy = candidate.y - y;
            final double dz = candidate.z - z;
            if (dx * dx + dy * dy + dz * dz > MAX_POSITION_DELTA_SQUARED) continue;
            iterator.remove();
            return true;
        }
        return false;
    }

    public void expire(final long tick) {
        while (!this.predicted.isEmpty()
                && tick - this.predicted.peekFirst().tick > RETENTION_TICKS) {
            this.predicted.removeFirst();
        }
    }

    public void clear() {
        this.predicted.clear();
    }

    private static boolean finite(final double x, final double y, final double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private record PredictedSound(String sound, String category,
                                  double x, double y, double z,
                                  float volume, float pitch, long tick) {
    }
}
