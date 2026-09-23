package com.projectkorra.projectkorra.prediction.rollback;

/** Minecraft 1.21.11 client input factors, shared by the owned Paper/Fabric player input phases. Native travel remains unchanged. */
public final class RollbackMovementFactors {
    private RollbackMovementFactors() { }
    public record Axes(float strafe, float forward) {
        public Axes { finite(strafe); finite(forward); }
    }
    /** Multiplication/normalization order matches native Vec2f operations, including zero/underflow behavior. */
    public static Axes apply(float strafe, float forward, float itemUseSpeed, float sneakingSpeed) {
        finite(strafe); finite(forward); finite(itemUseSpeed); finite(sneakingSpeed);
        if (itemUseSpeed < 0 || sneakingSpeed < 0) throw new IllegalArgumentException("Negative movement speed factor");
        if (strafe * strafe + forward * forward == 0) return new Axes(strafe, forward);
        float x = strafe * .98F, z = forward * .98F;
        x *= itemUseSpeed; z *= itemUseSpeed;
        x *= sneakingSpeed; z *= sneakingSpeed;
        float length = (float) Math.sqrt(x * x + z * z);
        if (length <= 0) return new Axes(x, z);
        float inverse = 1F / length;
        float nx = x * inverse, nz = z * inverse;
        float a = Math.abs(nx), b = Math.abs(nz), ratio = b > a ? a / b : b / a;
        float multiplier = (float) Math.sqrt(1F + ratio * ratio);
        float speed = Math.min(length * multiplier, 1F);
        return new Axes(nx * speed, nz * speed);
    }
    private static void finite(float value) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite movement factor"); }
}
