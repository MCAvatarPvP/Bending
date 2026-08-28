package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

public final class AirGliderPhysics {
    private static final double EPSILON = 1.0E-9;

    private AirGliderPhysics() {
    }

    public static StepResult step(final Vector rawVelocity, final Vector rawFacing,
                                  final boolean powered, final boolean forcedDescent,
                                  final boolean stalled, final Settings settings) {
        final Vector velocity = finiteOrZero(rawVelocity);
        final Vector facing = normalizedOr(rawFacing, velocity.lengthSquared() > EPSILON
                ? velocity : new Vector(0, 0, 1));
        final double incomingSpeed = velocity.length();
        double speed = incomingSpeed;
        if (speed < EPSILON) {
            speed = Math.max(0.05, settings.minimumAirspeed() * 0.5);
            velocity.copy(facing).multiply(speed);
        }

        final double authority = Math.toRadians(powered && !forcedDescent
                ? settings.poweredTurnDegrees() : settings.coastTurnDegrees());
        final double angle = velocity.angle(facing);
        final double turn = Math.min(angle, authority);
        final Vector direction = rotateTowards(velocity.clone().normalize(), facing, turn);

        final double turnDegrees = Math.toDegrees(turn);
        final double turnDrag = Math.max(0.0, 1.0 - settings.turnDragPerDegree() * turnDegrees);
        speed *= settings.straightDrag() * turnDrag;

        final double liftProgress = clamp((speed - settings.minimumAirspeed())
                / Math.max(0.01, settings.fullLiftAirspeed() - settings.minimumAirspeed()), 0, 1);
        double lift = settings.maximumCoastLift() * liftProgress;
        if (powered && !forcedDescent) lift += settings.poweredLift() * liftProgress;
        if (stalled) lift *= settings.stallLiftFactor();

        final Vector next = direction.multiply(speed);
        next.setY(next.getY() - settings.gravity() + lift);
        applyHorizontalDrive(next, facing, powered, settings);
        // MaximumVelocity caps acceleration produced by the glider itself. An
        // external impulse (most importantly the caster's ordinary AirBlast)
        // is allowed to exceed that cap and then coast down under drag.
        final double propulsionLimit = Math.max(settings.maximumVelocity(),
                incomingSpeed * Math.max(0.0, settings.straightDrag()));
        limit(next, propulsionLimit);

        final double attackAngle = Math.toDegrees(velocity.angle(facing));
        return new StepResult(next, attackAngle, next.length());
    }

    /**
     * Gives the glider useful forward authority without making pitch irrelevant.
     * Diving adds flat forward acceleration; levelling out converts part of the
     * accumulated downward speed into forward speed and a smaller amount of lift.
     */
    private static void applyHorizontalDrive(final Vector velocity, final Vector facing,
                                             final boolean powered, final Settings settings) {
        Vector horizontalForward = facing.clone().setY(0);
        if (horizontalForward.lengthSquared() <= EPSILON) {
            horizontalForward = velocity.clone().setY(0);
        }
        if (horizontalForward.lengthSquared() <= EPSILON) {
            horizontalForward = new Vector(0, 0, 1);
        }
        horizontalForward.normalize();

        final double diveFactor = clamp(-facing.getY(), 0, 1);
        final double pullOutFactor = clamp((facing.getY() + 0.20) / 0.40, 0, 1);
        final double descentSpeed = Math.max(0, -velocity.getY());
        final double convertedDescent = descentSpeed
                * Math.max(0, settings.descentConversion()) * pullOutFactor;
        double acceleration = Math.max(0, settings.horizontalAcceleration())
                + Math.max(0, settings.diveAcceleration()) * diveFactor
                + convertedDescent;
        if (powered) acceleration *= 1.20;

        velocity.add(horizontalForward.multiply(acceleration));
        velocity.setY(velocity.getY() + convertedDescent * 0.40);
    }

    public static Vector rotateTowards(final Vector rawFrom, final Vector rawTo, final double maxRadians) {
        final Vector from = normalizedOr(rawFrom, new Vector(0, 0, 1));
        final Vector to = normalizedOr(rawTo, from);
        final double angle = from.angle(to);
        if (angle <= maxRadians || angle <= EPSILON) return to;
        Vector axis = from.clone().crossProduct(to);
        if (axis.lengthSquared() <= EPSILON) {
            axis = from.clone().crossProduct(new Vector(0, 1, 0));
            if (axis.lengthSquared() <= EPSILON) axis = from.clone().crossProduct(new Vector(1, 0, 0));
        }
        axis.normalize();
        final double turn = Math.max(0, maxRadians);
        return from.clone().multiply(Math.cos(turn))
                .add(axis.crossProduct(from.clone()).multiply(Math.sin(turn))).normalize();
    }

    private static Vector finiteOrZero(final Vector value) {
        if (value == null || !Double.isFinite(value.getX()) || !Double.isFinite(value.getY())
                || !Double.isFinite(value.getZ())) return new Vector();
        return value.clone();
    }

    private static Vector normalizedOr(final Vector value, final Vector fallback) {
        final Vector result = finiteOrZero(value);
        if (result.lengthSquared() <= EPSILON) return finiteOrZero(fallback).normalize();
        return result.normalize();
    }

    private static void limit(final Vector velocity, final double maximum) {
        if (maximum <= 0 || velocity.lengthSquared() <= maximum * maximum) return;
        velocity.normalize().multiply(maximum);
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Settings(double straightDrag, double gravity, double minimumAirspeed,
                           double fullLiftAirspeed, double maximumCoastLift, double poweredLift,
                           double coastTurnDegrees, double poweredTurnDegrees,
                           double turnDragPerDegree, double stallLiftFactor,
                           double horizontalAcceleration, double diveAcceleration,
                           double descentConversion,
                           double maximumVelocity) {
    }

    public record StepResult(Vector velocity, double attackAngleDegrees, double airspeed) {
    }
}
