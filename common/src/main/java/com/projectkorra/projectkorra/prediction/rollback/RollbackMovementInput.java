package com.projectkorra.projectkorra.prediction.rollback;

/**
 * One immutable movement intent, before native input damping and travel. Session
 * authentication/time mapping and sneak/flight/ability edges are separate. Pitch
 * and axes are bounded; no client position or velocity is accepted as an input.
 */
public record RollbackMovementInput(float strafe, float forward, boolean jump, float yaw, float pitch) {
    public RollbackMovementInput {
        if (!Float.isFinite(strafe) || Math.abs(strafe) > 1 || !Float.isFinite(forward) || Math.abs(forward) > 1
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90) {
            throw new IllegalArgumentException("Movement input outside native bounds");
        }
        yaw %= 360F;
        if (yaw >= 180F) yaw -= 360F;
        if (yaw < -180F) yaw += 360F;
    }
}
