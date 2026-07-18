package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.airbending.AirBlast;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loader-neutral AirBlast parity probe.
 *
 * <p>This is diagnostic evidence, not reconciliation authority. Both runtimes
 * publish the values their shared AirBlast implementation actually used. The
 * client can then identify the first field where its local simulation differs
 * from Paper without moving, replacing, or removing either ability.</p>
 */
public final class AirBlastTraceSync {
    private static final double DOUBLE_EPSILON = 1.0E-9;
    private static final double ANGLE_EPSILON = 1.0E-5;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private AirBlastTraceSync() {
    }

    public static void install(final Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void clear(final Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    public static boolean isEnabled() {
        return !LISTENERS.isEmpty();
    }

    public static void publish(final AirBlast ability, final Trace trace) {
        if (ability == null || trace == null) return;
        for (Listener listener : LISTENERS) {
            try {
                listener.onAirBlastTrace(ability, trace);
            } catch (RuntimeException ignored) {
                // Evidence must never participate in ability behavior. A
                // disconnected endpoint or failed diagnostic packet cannot
                // interrupt the real AirBlast lifecycle.
            }
        }
    }

    /** Returns an empty string for parity, otherwise the first differing field. */
    public static String firstDifference(final Trace local, final Trace paper) {
        if (local == null) return "local-event missing";
        if (paper == null) return "paper-event missing";
        String difference;
        if (local.eventOrdinal() != paper.eventOrdinal()) {
            return values("eventOrdinal", local.eventOrdinal(), paper.eventOrdinal());
        }
        if (local.phase() != paper.phase()) return values("phase", local.phase(), paper.phase());
        if (local.progressTick() != paper.progressTick()) {
            return values("progressTick", local.progressTick(), paper.progressTick());
        }
        if (!(difference = decimal("eyeX", local.eyeX(), paper.eyeX(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("eyeY", local.eyeY(), paper.eyeY(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("eyeZ", local.eyeZ(), paper.eyeZ(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = angle("yaw", local.yaw(), paper.yaw(), ANGLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("pitch", local.pitch(), paper.pitch(), ANGLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("originX", local.originX(), paper.originX(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("originY", local.originY(), paper.originY(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("originZ", local.originZ(), paper.originZ(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("targetX", local.targetX(), paper.targetX(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("targetY", local.targetY(), paper.targetY(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("targetZ", local.targetZ(), paper.targetZ(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("locationX", local.locationX(), paper.locationX(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("locationY", local.locationY(), paper.locationY(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("locationZ", local.locationZ(), paper.locationZ(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("directionX", local.directionX(), paper.directionX(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("directionY", local.directionY(), paper.directionY(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("directionZ", local.directionZ(), paper.directionZ(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("speed", local.speed(), paper.speed(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("range", local.range(), paper.range(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("radius", local.radius(), paper.radius(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("preShootStamina", local.preShootStamina(), paper.preShootStamina(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (!(difference = decimal("shotStamina", local.shotStamina(), paper.shotStamina(), DOUBLE_EPSILON)).isEmpty()) return difference;
        if (local.blockX() != paper.blockX()) return values("blockX", local.blockX(), paper.blockX());
        if (local.blockY() != paper.blockY()) return values("blockY", local.blockY(), paper.blockY());
        if (local.blockZ() != paper.blockZ()) return values("blockZ", local.blockZ(), paper.blockZ());
        if (!local.blockMaterial().equals(paper.blockMaterial())) {
            return values("blockMaterial", local.blockMaterial(), paper.blockMaterial());
        }
        if (local.removed() != paper.removed()) return values("removed", local.removed(), paper.removed());
        return "";
    }

    public static String describe(final Trace trace) {
        if (trace == null) return "<missing>";
        return String.format(Locale.ROOT,
                "event=%d phase=%s tick=%d eye=(%.5f,%.5f,%.5f;%.3f,%.3f) "
                        + "origin=(%.5f,%.5f,%.5f) target=(%.5f,%.5f,%.5f) "
                        + "location=(%.5f,%.5f,%.5f) direction=(%.7f,%.7f,%.7f) "
                        + "speed=%.7f range=%.7f stamina=%.7f/%.7f block=(%d,%d,%d,%s) removed=%s",
                trace.eventOrdinal(), trace.phase(), trace.progressTick(),
                trace.eyeX(), trace.eyeY(), trace.eyeZ(), trace.yaw(), trace.pitch(),
                trace.originX(), trace.originY(), trace.originZ(),
                trace.targetX(), trace.targetY(), trace.targetZ(),
                trace.locationX(), trace.locationY(), trace.locationZ(),
                trace.directionX(), trace.directionY(), trace.directionZ(),
                trace.speed(), trace.range(), trace.preShootStamina(), trace.shotStamina(),
                trace.blockX(), trace.blockY(), trace.blockZ(), trace.blockMaterial(), trace.removed());
    }

    private static String decimal(final String field, final double local, final double paper,
                                  final double epsilon) {
        if (Double.doubleToLongBits(local) == Double.doubleToLongBits(paper)) return "";
        if (Double.isNaN(local) && Double.isNaN(paper)) return "";
        if (Double.isFinite(local) && Double.isFinite(paper) && Math.abs(local - paper) <= epsilon) return "";
        return String.format(Locale.ROOT, "%s local=%.12f paper=%.12f delta=%.12f",
                field, local, paper, local - paper);
    }

    private static String angle(final String field, final double local, final double paper,
                                final double epsilon) {
        if (Double.doubleToLongBits(local) == Double.doubleToLongBits(paper)) return "";
        if (Double.isNaN(local) && Double.isNaN(paper)) return "";
        if (Double.isFinite(local) && Double.isFinite(paper)) {
            final double delta = signedAngleDelta(local, paper);
            if (Math.abs(delta) <= epsilon) return "";
            return String.format(Locale.ROOT, "%s local=%.12f paper=%.12f angularDelta=%.12f",
                    field, local, paper, delta);
        }
        return String.format(Locale.ROOT, "%s local=%.12f paper=%.12f angularDelta=%.12f",
                field, local, paper, local - paper);
    }

    /**
     * Returns {@code local - paper} on the shortest signed arc. Minecraft yaw
     * is intentionally unbounded, so values separated by whole turns describe
     * the same view direction and are parity, not a 360-degree mismatch.
     */
    public static double signedAngleDelta(final double local, final double paper) {
        if (!Double.isFinite(local) || !Double.isFinite(paper)) return local - paper;
        double delta = (local - paper) % 360.0;
        if (delta > 180.0) delta -= 360.0;
        else if (delta <= -180.0) delta += 360.0;
        return delta == -0.0 ? 0.0 : delta;
    }

    private static String values(final String field, final Object local, final Object paper) {
        return field + " local=" + local + " paper=" + paper;
    }

    public enum Phase {
        LAUNCH,
        PROGRESS,
        SELF_CONTACT,
        BLOCKED,
        ADVANCE,
        REMOVE_PLAYER_STATE,
        REMOVE_REGION,
        REMOVE_SOURCE_WORLD,
        REMOVE_CANNOT_BEND,
        REMOVE_SOURCE_RANGE,
        REMOVE_TICK_LIMIT,
        REMOVE_DOOR,
        REMOVE_TRAPDOOR,
        REMOVE_RANGE,
        REMOVE_INVALID_DIRECTION,
        REMOVE_AUTHORITATIVE,
        REMOVE_COLLISION,
        REMOVE_EXTERNAL
    }

    public record Trace(int eventOrdinal, Phase phase, int progressTick,
                        double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                        double originX, double originY, double originZ,
                        double targetX, double targetY, double targetZ,
                        double locationX, double locationY, double locationZ,
                        double directionX, double directionY, double directionZ,
                        double speed, double range, double radius,
                        double preShootStamina, double shotStamina,
                        int blockX, int blockY, int blockZ, String blockMaterial,
                        boolean removed) {
        public Trace {
            phase = phase == null ? Phase.PROGRESS : phase;
            blockMaterial = blockMaterial == null ? "" : blockMaterial;
        }
    }

    public interface Listener {
        void onAirBlastTrace(AirBlast ability, Trace trace);
    }
}
